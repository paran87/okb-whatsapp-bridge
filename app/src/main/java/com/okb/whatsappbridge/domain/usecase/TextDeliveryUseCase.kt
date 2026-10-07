package com.okb.whatsappbridge.domain.usecase

import com.okb.whatsappbridge.automation.MessagePart
import com.okb.whatsappbridge.automation.SendOutcome
import com.okb.whatsappbridge.automation.SendProgress
import com.okb.whatsappbridge.automation.TextSendRequest
import com.okb.whatsappbridge.data.remote.api.ApiResult
import com.okb.whatsappbridge.data.remote.api.BackendConfig
import com.okb.whatsappbridge.data.remote.api.BridgeApi
import com.okb.whatsappbridge.data.remote.dto.TextDeliveryJob
import com.okb.whatsappbridge.data.remote.dto.TextResultRequest
import com.okb.whatsappbridge.domain.model.TextDeliveryStatus.FAILED
import com.okb.whatsappbridge.domain.model.TextDeliveryStatus.SCHEDULED
import com.okb.whatsappbridge.domain.model.TextDeliveryStatus.SENDING
import com.okb.whatsappbridge.domain.model.TextDeliveryStatus.SENT
import com.okb.whatsappbridge.domain.model.TextReportDelivery
import com.okb.whatsappbridge.domain.repository.TextDeliveryRepository
import com.okb.whatsappbridge.util.logging.BridgeLogger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant

/** Sends a TEXT report into WhatsApp on this phone (accessibility automation; see AndroidAutomaticTextSender). */
interface AutomaticTextSender {
    /** Why automatic sending cannot run right now (e.g. the accessibility service is off), or null when it can. */
    fun unavailableReason(): String?

    suspend fun send(request: TextSendRequest, progress: SendProgress): SendOutcome
}

data class TextDeliveryRunResult(val sent: Int = 0, val failed: Int = 0)

/**
 * Automatic consolidated TEXT reports: the backend hands this phone TEXT jobs; each is sent into the
 * DESTINATION group with no operator action and the outcome is reported back.
 *
 *   job → local queue (Room; unique delivery id and dedupe key) → claim (backend compare-and-set: one attempt
 *   at a time) → WhatsApp automation → SENT | retry later (SCHEDULED) | FAILED → result reported (kept and
 *   retried while offline)
 *
 * Duplicate protection, in layers: the backend creates one TEXT delivery per period and group; the phone keeps
 * one row per delivery and per dedupe key and never sends a delivery it has recorded as SENT; a claim is
 * refused while another attempt holds it; and before anything is typed the sender looks for the report's
 * reference in the chat, so an attempt interrupted after pressing Send is confirmed, not repeated.
 */
class TextDeliveryUseCase(
    private val api: BridgeApi,
    private val deliveries: TextDeliveryRepository,
    private val sender: AutomaticTextSender,
    private val logger: BridgeLogger,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** Results that could not be reported (offline) are reported first. */
    suspend fun flushPendingResults(config: BackendConfig) {
        for (d in deliveries.withPendingResult()) report(config, d)
    }

    /** Sends the backend's TEXT jobs, one at a time (process-wide). */
    suspend fun process(config: BackendConfig, jobs: List<TextDeliveryJob>, sourceGroupName: String?): TextDeliveryRunResult =
        LOCK.withLock {
            var sent = 0
            var failed = 0
            for (job in jobs) {
                when (processOne(config, job, sourceGroupName)) {
                    true -> sent++
                    false -> failed++
                    null -> Unit
                }
            }
            housekeeping()
            TextDeliveryRunResult(sent, failed)
        }

    /** true = sent now, false = attempt failed, null = nothing attempted. */
    private suspend fun processOne(config: BackendConfig, job: TextDeliveryJob, sourceGroupName: String?): Boolean? {
        if (job.parts.isEmpty() || job.destinationGroup.isBlank()) {
            logger.warn(TAG, "Text report ${short(job.id)} has no text or destination; skipped")
            return null
        }
        var local = deliveries.get(job.id) ?: run {
            val sameReport = deliveries.getByDedupeKey(job.dedupeKey)
            if (sameReport != null) {
                // Never twice for one period and group, even if the backend offered a second delivery.
                logger.warn(TAG, "Text report ${short(job.id)} duplicates ${short(sameReport.id)} (${sameReport.status}); not sent")
                return null
            }
            val created = fromJob(job, clock())
            if (!deliveries.insert(created)) return null
            logger.info(TAG, "Text report received: ${describe(created)} → \"${created.destinationGroup}\"")
            created
        }
        // A Retry from the Command Center restarts the attempt count: the operator checked the group and wants it
        // sent again, so earlier unconfirmed presses no longer block sending (confirmed parts still never repeat).
        if (job.attempts < local.attempt && local.pressedRefs.isNotEmpty()) {
            logger.info(TAG, "Text report ${short(job.id)} retried from the Command Center: earlier unconfirmed sends cleared")
            local = save(local.copy(pressedRefs = emptySet(), attempt = job.attempts))
        }
        if (local.status == SENT) {
            // Already in the chat (recorded here): make sure the backend knows, never send again.
            if (local.pendingResult == null) report(config, local.copy(pendingResult = RESULT_SENT))
            return null
        }

        val claim = when (val r = api.claimTextDelivery(config, job.id)) {
            is ApiResult.Success -> r.value
            is ApiResult.HttpError -> {
                logger.warn(TAG, "Text report ${short(job.id)} could not be claimed: ${r.message}")
                if (r.httpCode == 403 || r.httpCode == 404) save(local.copy(status = FAILED, lastError = r.message))
                return null
            }
            is ApiResult.NetworkError -> {
                logger.warn(TAG, "Text report ${short(job.id)}: backend unreachable, retried on the next check")
                return null
            }
            is ApiResult.ConfigurationError -> return null
        }
        if (!claim.claimed) {
            when (claim.reason) {
                "already_sent" -> save(local.copy(status = SENT, sentAt = local.sentAt ?: clock(), lastError = null))
                "failed" -> save(local.copy(status = FAILED))
            }
            logger.info(TAG, "Text report ${short(job.id)} not sent now: ${claim.reason ?: "not claimed"}")
            return null
        }

        val attempt = claim.delivery?.attempts?.takeIf { it > 0 } ?: (job.attempts + 1)
        local = save(local.copy(status = SENDING, attempt = attempt, lastAttemptAt = clock(), lastError = null))
        logger.info(TAG, "Sending text report ${short(job.id)} automatically to \"${local.destinationGroup}\" (attempt $attempt)")

        val progress = object : SendProgress {
            override suspend fun beforePressSend(ref: String) {
                local = save(local.copy(pressedRefs = local.pressedRefs + ref))
            }

            override suspend fun partConfirmed(ref: String, verification: String) {
                local = save(local.copy(sentRefs = local.sentRefs + ref, verification = verification))
            }

            override suspend fun sendNotRegistered(ref: String) {
                local = save(local.copy(pressedRefs = local.pressedRefs - ref))
            }
        }
        val outcome = sender.unavailableReason()?.let { SendOutcome.Failed(it, retryable = true) }
            ?: runCatching {
                sender.send(
                    TextSendRequest(
                        deliveryId = local.id,
                        destinationGroup = local.destinationGroup,
                        sourceGroup = sourceGroupName?.takeIf { it.isNotBlank() },
                        parts = local.parts,
                        alreadySentRefs = local.sentRefs,
                        pressedRefs = local.pressedRefs,
                    ),
                    progress,
                )
            }.getOrElse { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                SendOutcome.Failed("Automatic sending stopped: ${e.javaClass.simpleName}", retryable = true)
            }

        val now = clock()
        return when (outcome) {
            is SendOutcome.Sent -> {
                local = save(local.copy(status = SENT, sentAt = now, verification = outcome.verification, lastError = null, pendingResult = RESULT_SENT))
                logger.info(TAG, "Text report ${short(job.id)} SENT to \"${local.destinationGroup}\": ${outcome.verification}")
                report(config, local)
                true
            }
            is SendOutcome.Failed -> {
                local = save(
                    local.copy(
                        status = if (outcome.retryable) SCHEDULED else FAILED,
                        lastError = outcome.reason,
                        pendingResult = RESULT_FAILED,
                        pendingRetryable = outcome.retryable,
                    ),
                )
                logger.warn(TAG, "Text report ${short(job.id)} not sent (attempt $attempt): ${outcome.reason}")
                report(config, local)
                false
            }
        }
    }

    /** Reports the stored outcome; while offline it stays pending and is retried on the next check. */
    private suspend fun report(config: BackendConfig, delivery: TextReportDelivery) {
        val state = delivery.pendingResult ?: return
        val request = TextResultRequest(
            state = state,
            attempt = delivery.attempt,
            error = delivery.lastError.takeIf { state == RESULT_FAILED },
            retryable = delivery.pendingRetryable,
            verification = delivery.verification.takeIf { state == RESULT_SENT },
            sentAt = delivery.sentAt?.takeIf { state == RESULT_SENT }?.let { Instant.ofEpochMilli(it).toString() },
        )
        val r = api.reportTextDeliveryResult(config, delivery.id, request)
        // A 4xx means the backend refused it for good (unknown delivery, another phone): stop retrying.
        val rejected = r is ApiResult.HttpError && r.httpCode in 400..499
        if (rejected) logger.warn(TAG, "Result for text report ${short(delivery.id)} rejected: ${(r as ApiResult.HttpError).message}")
        if (r is ApiResult.Success || rejected) {
            clearPending(delivery.id)
        } else if (deliveries.get(delivery.id)?.pendingResult != state) {
            save(delivery)
        }
    }

    private suspend fun clearPending(id: String) {
        deliveries.get(id)?.let { if (it.pendingResult != null) deliveries.update(it.copy(pendingResult = null)) }
    }

    private suspend fun save(delivery: TextReportDelivery): TextReportDelivery =
        delivery.copy(updatedAt = clock()).also { deliveries.update(it) }

    private suspend fun housekeeping() {
        deliveries.deleteFinishedBefore(clock() - ROW_RETENTION_MILLIS)
    }

    private fun fromJob(job: TextDeliveryJob, now: Long) = TextReportDelivery(
        id = job.id,
        reportId = job.reportId,
        kind = job.kind ?: "scheduled",
        dedupeKey = job.dedupeKey,
        destinationGroup = job.destinationGroup.trim(),
        sourceGroup = job.sourceGroup,
        parts = job.parts.map { MessagePart(it.text, it.ref) },
        periodStart = job.periodStart,
        periodEnd = job.periodEnd,
        reportCount = job.reportCount,
        status = SCHEDULED,
        attempt = job.attempts,
        createdAt = now,
        updatedAt = now,
    )

    private fun describe(d: TextReportDelivery) =
        "${if (d.isTest) "TEST " else ""}${d.reportCount ?: "?"} report(s), ref ${d.parts.firstOrNull()?.ref ?: short(d.id)}"

    private fun short(id: String) = id.take(8)

    companion object {
        private const val TAG = "TextReport"
        const val RESULT_SENT = "sent"
        const val RESULT_FAILED = "failed"
        private const val ROW_RETENTION_MILLIS = 30L * 24 * 60 * 60 * 1000

        /** One automatic send at a time in this process (alarm worker and periodic worker may overlap). */
        private val LOCK = Mutex()
    }
}

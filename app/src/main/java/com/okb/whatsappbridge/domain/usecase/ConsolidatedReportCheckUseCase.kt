package com.okb.whatsappbridge.domain.usecase

import com.okb.whatsappbridge.automation.PdfSendProgress
import com.okb.whatsappbridge.automation.PdfSendRequest
import com.okb.whatsappbridge.automation.SendOutcome
import com.okb.whatsappbridge.data.remote.api.ApiResult
import com.okb.whatsappbridge.data.remote.api.BackendConfig
import com.okb.whatsappbridge.data.remote.api.BridgeApi
import com.okb.whatsappbridge.data.remote.dto.ConsolidatedDelivery
import com.okb.whatsappbridge.data.remote.dto.ConsolidatedRunDueRequest
import com.okb.whatsappbridge.data.remote.dto.TextDeliveryJob
import com.okb.whatsappbridge.domain.model.ConsolidatedDeliveryStatus
import com.okb.whatsappbridge.domain.model.ConsolidatedDeliveryStatus.DOWNLOADING
import com.okb.whatsappbridge.domain.model.ConsolidatedDeliveryStatus.FAILED
import com.okb.whatsappbridge.domain.model.ConsolidatedDeliveryStatus.OPENED_IN_WHATSAPP
import com.okb.whatsappbridge.domain.model.ConsolidatedDeliveryStatus.READY_FOR_WHATSAPP
import com.okb.whatsappbridge.domain.model.ConsolidatedDeliveryStatus.READY_TO_SEND
import com.okb.whatsappbridge.domain.model.ConsolidatedDeliveryStatus.SENT
import com.okb.whatsappbridge.domain.model.ConsolidatedReportDelivery
import com.okb.whatsappbridge.domain.repository.ConsolidatedDeliveryRepository
import com.okb.whatsappbridge.domain.repository.DeviceIdentityRepository
import com.okb.whatsappbridge.domain.repository.SettingsRepository
import com.okb.whatsappbridge.util.logging.BridgeLogger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/** Shows the operator a "report ready" notification that opens the one-tap WhatsApp share. */
fun interface ConsolidatedReportSink {
    /**
     * The PDF needs the operator: automatic sending is not possible or failed ([ConsolidatedReportDelivery.errorMessage]
     * says why). @return true when the notification was posted (notifications allowed).
     */
    fun reportReady(delivery: ConsolidatedReportDelivery, pdf: File): Boolean

    /** The PDF was sent automatically: an earlier "PDF Ready" notification is no longer needed. */
    fun reportSent(delivery: ConsolidatedReportDelivery) {}
}

/** Sends a consolidated PDF into WhatsApp on this phone (accessibility automation; see AndroidAutomaticTextSender). */
interface AutomaticPdfSender {
    /** Why automatic sending cannot run right now (e.g. the accessibility service is off), or null when it can. */
    fun unavailableReason(): String?

    suspend fun send(request: PdfSendRequest, progress: PdfSendProgress): SendOutcome
}

data class ConsolidatedCheckResult(
    val newlyReady: Int = 0,
    val warning: String? = null,
    val error: String? = null,
    val textSent: Int = 0,
    val textFailed: Int = 0,
    val pdfSent: Int = 0,
)

/** Wakes the phone for the next consolidated-report check (exact alarm on Android; see AlarmReportWakeScheduler). */
interface ReportWakeScheduler {
    /** After a successful check: wake just after the next scheduled cut-off or TEXT retry (null = none known). */
    fun scheduleNext(nextCutoffAtMillis: Long?, nextRetryAtMillis: Long?)

    /** After a failed check: try again soon (bounded), so a report due now does not wait for the 15-minute check. */
    fun scheduleRetry()
}

/** A verified local PDF ready to hand to WhatsApp's share screen. */
data class ShareTarget(val delivery: ConsolidatedReportDelivery, val file: File)

/**
 * Consolidated WhatsApp reports on the phone. Each backend check returns two independent kinds of work:
 *
 *  - TEXT reports: sent AUTOMATICALLY to the destination group by [textDelivery] (no operator action). They are
 *    handled first because they are time-critical (e.g. the 12:00 AM report while the operator sleeps).
 *  - PDF reports: also sent AUTOMATICALLY when [pdfSender] can run (WhatsApp's "Send to" screen driven by the
 *    accessibility service). This class brings the PDF to the phone and keeps a local delivery queue (Room) so
 *    nothing is downloaded, offered or sent twice and nothing is lost while offline:
 *
 *   READY_TO_SEND → DOWNLOADING → READY_FOR_WHATSAPP → SENT (sent automatically, or confirmed by the operator)
 *                                        ↓     ↑ not sent                 FAILED (download failed)
 *                                   OPENED_IN_WHATSAPP (manual "Send as PDF")
 *
 *    A PDF that cannot be sent automatically (service off, WhatsApp changed, [MAX_AUTO_ATTEMPTS] failures, or an
 *    unconfirmed earlier Send) gets the "PDF Ready" notification: the operator taps "Send as PDF", selects the
 *    DESTINATION group in WhatsApp's share screen and presses Send, then confirms in the app. Opening the share
 *    screen is never treated as "sent".
 *
 * Runs from the 15-minute periodic check and from an exact alarm set just after each scheduled cut-off
 * ([wakeScheduler]); the backend decides whether a report is due.
 */
class ConsolidatedReportCheckUseCase(
    private val settings: SettingsRepository,
    private val identity: DeviceIdentityRepository,
    private val api: BridgeApi,
    private val deliveries: ConsolidatedDeliveryRepository,
    private val directory: File,
    private val sink: ConsolidatedReportSink,
    private val logger: BridgeLogger,
    private val clock: () -> Long = System::currentTimeMillis,
    private val textDelivery: TextDeliveryUseCase? = null,
    private val wakeScheduler: ReportWakeScheduler? = null,
    private val pdfSender: AutomaticPdfSender? = null,
) {

    /** One check at a time: the cut-off alarm, the 15-minute worker and "Check now" may overlap. */
    suspend operator fun invoke(): ConsolidatedCheckResult = CHECK_LOCK.withLock { check() }

    private suspend fun check(): ConsolidatedCheckResult {
        val current = settings.current()
        if (!current.backendConfigured) return ConsolidatedCheckResult(error = "Backend URL not configured")
        val config = config(current.backendUrl)

        flushPendingAcks(config)
        textDelivery?.flushPendingResults(config)

        val request = ConsolidatedRunDueRequest(
            sourceGroupName = current.sourceGroupName.ifBlank { null },
            destinationGroupName = current.destinationGroupName.ifBlank { null },
        )
        val response = when (val r = api.consolidatedRunDue(config, request)) {
            is ApiResult.Success -> r.value
            is ApiResult.HttpError -> {
                // 404/503: an older backend, or consolidated storage not set up there yet.
                if (r.httpCode != 404 && r.httpCode != 503) logger.warn(TAG, "Consolidated report check failed: ${r.message}")
                if (r.httpCode >= 500) wakeScheduler?.scheduleRetry()
                return ConsolidatedCheckResult(error = r.message)
            }
            is ApiResult.NetworkError -> {
                logger.warn(TAG, "Consolidated report check failed (offline?): ${r.message}")
                wakeScheduler?.scheduleRetry()
                return ConsolidatedCheckResult(error = "Backend unreachable")
            }
            is ApiResult.ConfigurationError -> return ConsolidatedCheckResult(error = r.message)
        }
        response.warning?.let { logger.warn(TAG, "Backend: $it") }

        // TEXT first: automatic and time-critical. The text report of an entry sent as PDF goes right after its
        // PDF (the PDF with its caption, then the text), also when the PDF itself needs the operator.
        val pdfReports = response.deliveries.map { it.id }.toSet()
        val (afterPdf, textFirst) = response.textDeliveries.partition { it.reportId in pdfReports }
        val text = sendTexts(config, textFirst, current.sourceGroupName)
        // Cards of reports not offered now (cancelled, expired, waiting for a retry) show the backend's state.
        textDelivery?.syncWithBackend(config, response.textDeliveries.map { it.id }.toSet())

        var newlyReady = 0
        for (remote in response.deliveries) {
            if (process(config, remote)) newlyReady++
        }
        val pdfSent = autoSendPdfs(config, current.destinationGroupName, current.sourceGroupName)
        val textAfterPdf = sendTexts(config, afterPdf, current.sourceGroupName)
        housekeeping()
        val retryAt = listOfNotNull(epochMillis(response.nextRetryAt), nextAutoRetryAt()).minOrNull()
        wakeScheduler?.scheduleNext(epochMillis(response.nextCutoffAt), retryAt)
        return ConsolidatedCheckResult(
            newlyReady, response.warning,
            textSent = text.sent + textAfterPdf.sent, textFailed = text.failed + textAfterPdf.failed, pdfSent = pdfSent,
        )
    }

    private suspend fun sendTexts(config: BackendConfig, jobs: List<TextDeliveryJob>, sourceGroup: String): TextDeliveryRunResult =
        if (textDelivery != null && jobs.isNotEmpty()) textDelivery.process(config, jobs, sourceGroup) else TextDeliveryRunResult()

    // ---- automatic PDF sending ------------------------------------------------------------------------------

    /** Sends the PDFs waiting on this phone, oldest first. Returns how many were sent. */
    private suspend fun autoSendPdfs(config: BackendConfig, settingsDestination: String, sourceGroup: String): Int {
        val sender = pdfSender ?: return 0
        var sent = 0
        for (delivery in deliveries.withStatus(READY_FOR_WHATSAPP)) {
            if (delivery.autoAttempts >= MAX_AUTO_ATTEMPTS) continue
            if (autoSend(config, sender, delivery, settingsDestination, sourceGroup)) sent++
        }
        return sent
    }

    private suspend fun autoSend(
        config: BackendConfig,
        sender: AutomaticPdfSender,
        waiting: ConsolidatedReportDelivery,
        settingsDestination: String,
        sourceGroup: String,
    ): Boolean {
        var delivery = waiting
        val file = pdfFile(directory, delivery.id, delivery.fileName) ?: return false
        if (!isPdf(file)) delivery = download(config, delivery, file)?.also { deliveries.save(it) } ?: return false
        sender.unavailableReason()?.let { reason ->
            // Not counted as an attempt: it is tried again at every check, and the operator is told once.
            val message = "Not sent automatically: $reason"
            if (delivery.errorMessage != message) {
                delivery = save(delivery.copy(errorMessage = message))
                notifyOperator(delivery, file)
                ack(config, delivery.id, STATE_NOTIFIED, message)
            }
            return false
        }
        val destination = delivery.destinationGroup?.takeIf { it.isNotBlank() } ?: settingsDestination.trim()
        val attempt = delivery.autoAttempts + 1
        var local = save(delivery.copy(autoAttempts = attempt))
        logger.info(TAG, "Sending PDF automatically: ${local.fileName} → \"$destination\" (attempt $attempt)")
        val progress = object : PdfSendProgress {
            override suspend fun beforePressSend() {
                if (!local.autoPressed) local = save(local.copy(autoPressed = true))
            }

            override suspend fun sendNotRegistered() {
                local = save(local.copy(autoPressed = false))
            }
        }
        val request = PdfSendRequest(
            deliveryId = local.id,
            destinationGroup = destination,
            sourceGroup = sourceGroup.takeIf { it.isNotBlank() },
            file = file,
            fileName = local.fileName,
            caption = local.caption,
            pressedEarlier = delivery.autoPressed,
        )
        val outcome = runCatching { sender.send(request, progress) }.getOrElse { e ->
            if (e is kotlinx.coroutines.CancellationException) throw e
            SendOutcome.Failed("Automatic sending stopped: ${e.javaClass.simpleName}")
        }
        return when (outcome) {
            is SendOutcome.Sent -> {
                local = save(local.copy(status = SENT, sentAt = clock(), sentAutomatically = true, errorMessage = null))
                logger.info(TAG, "PDF SENT automatically to \"$destination\": ${outcome.verification}")
                sink.reportSent(local)
                ack(config, local.id, STATE_SENT)
                true
            }
            is SendOutcome.Failed -> {
                val last = !outcome.retryable || attempt >= MAX_AUTO_ATTEMPTS
                val message = "Not sent automatically: ${outcome.reason}" + if (last) "" else " (tried again in a few minutes)"
                local = save(local.copy(errorMessage = message, autoAttempts = if (outcome.retryable) attempt else MAX_AUTO_ATTEMPTS))
                logger.warn(TAG, "PDF ${local.fileName} not sent automatically (attempt $attempt): ${outcome.reason}")
                // The operator can send it by hand at once; a later automatic attempt cancels the notification.
                notifyOperator(local, file)
                ack(config, local.id, STATE_NOTIFIED, message)
                false
            }
        }
    }

    /**
     * When the next automatic PDF attempt is due (after a failed one), or null. The report watcher and the alarm
     * use it so a retry does not wait for the 15-minute check.
     */
    suspend fun nextAutoRetryAt(): Long? = deliveries.withStatus(READY_FOR_WHATSAPP)
        .filter { it.autoAttempts in 1 until MAX_AUTO_ATTEMPTS }
        .minOfOrNull { it.updatedAt + AUTO_RETRY_MILLIS }

    private fun notifyOperator(delivery: ConsolidatedReportDelivery, file: File) {
        if (sink.reportReady(delivery, file)) {
            logger.info(TAG, "PDF ready to send by hand: ${delivery.fileName} → ${delivery.destinationGroup ?: "destination group not set"}")
        } else {
            logger.warn(TAG, "PDF ready but notifications are blocked; open the app to send ${delivery.fileName}")
        }
    }

    private suspend fun save(delivery: ConsolidatedReportDelivery): ConsolidatedReportDelivery =
        delivery.copy(updatedAt = clock()).also { deliveries.save(it) }

    private fun epochMillis(iso: String?): Long? = iso?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() }

    /** One pending delivery from the backend. Returns true when it became ready on this phone just now. */
    private suspend fun process(config: BackendConfig, remote: ConsolidatedDelivery): Boolean {
        val now = clock()
        val file = pdfFile(directory, remote.id, remote.fileName)
        val existing = deliveries.get(remote.id)
        var local = when {
            existing == null -> {
                logger.info(TAG, "Pending report detected: ${remote.fileName}")
                fromRemote(remote, now)
            }
            // The backend offers it again although it was opened/sent/failed here: a Resend from the Command Center.
            existing.status == OPENED_IN_WHATSAPP || existing.status == SENT || existing.status == FAILED -> {
                logger.info(TAG, "Report queued again by the Command Center: ${remote.fileName}")
                fromRemote(remote, existing.createdAt).copy(updatedAt = now)
            }
            else -> existing.copy(destinationGroup = remote.destinationGroup ?: existing.destinationGroup)
        }

        if (file == null) {
            deliveries.save(local.copy(status = FAILED, errorMessage = "Unsafe report file name", updatedAt = now))
            ack(config, local.id, STATE_FAILED, "Unsafe report file name")
            return false
        }
        // Already downloaded and verified (duplicate sync, or the "notified" acknowledgement was lost): no new
        // download and no second notification; just report the state again.
        if (local.status == READY_FOR_WHATSAPP && isPdf(file)) {
            deliveries.save(local)
            ack(config, local.id, STATE_NOTIFIED, local.errorMessage)
            return false
        }

        local = if (isPdf(file)) {
            // A verified copy is still on the phone (e.g. a Resend): offer it again without downloading.
            local.copy(status = READY_FOR_WHATSAPP, errorMessage = null, updatedAt = clock())
        } else {
            download(config, local, file) ?: return false
        }
        val unavailable = pdfSender?.unavailableReason()
        if (pdfSender != null && unavailable == null) {
            // Sent automatically right after this loop; the operator is only notified if that fails.
            deliveries.save(local)
            logger.info(TAG, "PDF ready: ${local.fileName}; sending it automatically")
        } else {
            if (unavailable != null) local = local.copy(errorMessage = "Not sent automatically: $unavailable")
            deliveries.save(local)
            notifyOperator(local, file)
        }
        ack(config, local.id, STATE_NOTIFIED, local.errorMessage)
        return true
    }

    /** Downloads and verifies the PDF. Returns the READY_FOR_WHATSAPP delivery, or null after recording the failure. */
    private suspend fun download(config: BackendConfig, delivery: ConsolidatedReportDelivery, file: File): ConsolidatedReportDelivery? {
        deliveries.save(delivery.copy(status = DOWNLOADING, updatedAt = clock()))
        val result = api.downloadConsolidatedPdf(config, delivery.pdfPath, file)
        val problem: String? = when (result) {
            is ApiResult.Success -> if (isPdf(file)) null else "Downloaded file is not a PDF"
            is ApiResult.HttpError -> result.message
            is ApiResult.NetworkError -> "Download interrupted: ${result.message}"
            is ApiResult.ConfigurationError -> result.message
        }
        val now = clock()
        if (problem == null) {
            logger.info(TAG, "PDF downloaded and verified: ${delivery.fileName} (${file.length()} bytes)")
            return delivery.copy(status = READY_FOR_WHATSAPP, errorMessage = null, downloadedAt = now, updatedAt = now)
        }
        file.delete()
        val attempts = delivery.downloadAttempts + 1
        // 404/410: the report no longer exists on the backend; anything else is retried on later checks.
        val permanent = result is ApiResult.HttpError && (result.httpCode == 404 || result.httpCode == 410)
        val failed = permanent || attempts >= MAX_DOWNLOAD_ATTEMPTS
        deliveries.save(
            delivery.copy(status = if (failed) FAILED else READY_TO_SEND, errorMessage = problem, downloadAttempts = attempts, updatedAt = now),
        )
        logger.warn(TAG, "PDF download failed (attempt $attempts): ${delivery.fileName}: $problem")
        if (failed) ack(config, delivery.id, STATE_FAILED, problem)
        return null
    }

    /**
     * Operator pressed "Send as PDF": returns the verified local PDF, downloading it again first when it is
     * missing (e.g. the app's storage was cleared). Null with an explanation when it cannot be prepared.
     */
    suspend fun prepareShare(id: String): Pair<ShareTarget?, String?> {
        val delivery = deliveries.get(id) ?: return null to "This report is no longer on the phone."
        val file = pdfFile(directory, delivery.id, delivery.fileName) ?: return null to "Unsafe report file name."
        if (isPdf(file)) return ShareTarget(delivery, file) to null
        val current = settings.current()
        if (!current.backendConfigured) return null to "Backend URL not configured."
        val ready = download(config(current.backendUrl), delivery, file)
            ?: return null to (deliveries.get(id)?.errorMessage ?: "The PDF could not be downloaded.")
        deliveries.save(ready)
        return ShareTarget(ready, file) to null
    }

    /**
     * WhatsApp's share screen was opened with the PDF. This is NOT proof that it was sent. The operator handles
     * this report from now on: it is not sent automatically any more (no duplicates).
     */
    suspend fun markOpened(id: String) = transition(id, OPENED_IN_WHATSAPP, STATE_OPENED) {
        it.copy(openedAt = clock(), errorMessage = null, autoAttempts = MAX_AUTO_ATTEMPTS)
    }

    /** The operator confirmed in the app that the report was sent to the destination group. */
    suspend fun confirmSent(id: String) = transition(id, SENT, STATE_SENT) { it.copy(sentAt = clock(), errorMessage = null) }

    /** The operator cancelled or did not send it: it stays ready to send. */
    suspend fun markNotSent(id: String) = transition(id, READY_FOR_WHATSAPP, STATE_NOT_SENT) { it.copy(openedAt = null) }

    /**
     * Dashboard "Remove": the card and its PDF are deleted from this phone, and the backend is told it will not
     * be sent from here (PDF "failed: Removed on the bridge phone", so it is not offered again; a Resend from
     * the Command Center brings it back). Works for any status, also when the report was deleted there.
     */
    suspend fun remove(id: String) {
        val delivery = deliveries.get(id) ?: return
        pdfFile(directory, delivery.id, delivery.fileName)?.parentFile?.deleteRecursively()
        deliveries.delete(id)
        logger.info(TAG, "Removed from the phone: ${delivery.fileName}")
        if (delivery.status == SENT) return // already final on the backend
        val current = settings.current()
        if (current.backendConfigured) {
            api.acknowledgeConsolidatedDelivery(config(current.backendUrl), id, STATE_FAILED, REMOVED_ON_PHONE)
        }
    }

    /** The share screen could not be opened (e.g. no app can share PDFs). The report stays ready; nothing is reported as sent. */
    suspend fun recordShareProblem(id: String, problem: String) {
        val delivery = deliveries.get(id) ?: return
        deliveries.save(delivery.copy(errorMessage = problem, updatedAt = clock()))
        logger.warn(TAG, "Share failed for ${delivery.fileName}: $problem")
    }

    private suspend fun transition(
        id: String,
        status: ConsolidatedDeliveryStatus,
        ackState: String,
        change: (ConsolidatedReportDelivery) -> ConsolidatedReportDelivery,
    ) {
        val delivery = deliveries.get(id) ?: return
        deliveries.save(change(delivery).copy(status = status, updatedAt = clock()))
        logger.info(TAG, "Delivery status changed: ${delivery.fileName} → ${status.name}")
        val current = settings.current()
        if (current.backendConfigured) ack(config(current.backendUrl), id, ackState)
        else deliveries.get(id)?.let { deliveries.save(it.copy(pendingAck = ackState)) }
    }

    /** Reports a delivery state to the backend; while offline it is kept and retried on the next check. */
    private suspend fun ack(config: BackendConfig, id: String, state: String, error: String? = null) {
        val r = api.acknowledgeConsolidatedDelivery(config, id, state, error)
        // 403/404: the report is gone from the backend (deleted in the Command Center) or belongs to another
        // phone; retrying would never succeed, so the acknowledgement is dropped.
        val ok = r is ApiResult.Success || (r is ApiResult.HttpError && (r.httpCode == 403 || r.httpCode == 404))
        val delivery = deliveries.get(id) ?: return
        if (ok) {
            if (delivery.pendingAck != null) deliveries.save(delivery.copy(pendingAck = null, pendingAckError = null))
        } else if (delivery.pendingAck != state || delivery.pendingAckError != error) {
            deliveries.save(delivery.copy(pendingAck = state, pendingAckError = error))
        }
    }

    private suspend fun flushPendingAcks(config: BackendConfig) {
        for (d in deliveries.withPendingAck()) ack(config, d.id, d.pendingAck ?: continue, d.pendingAckError)
    }

    /** Deletes local PDFs of finished deliveries after a week and their rows after 30 days. */
    private suspend fun housekeeping() {
        val now = clock()
        if (directory.exists()) {
            directory.walkBottomUp().forEach { f ->
                if (f.isFile && f.lastModified() < now - FILE_RETENTION_MILLIS) f.delete()
                else if (f.isDirectory && f != directory && f.list().isNullOrEmpty()) f.delete()
            }
        }
        deliveries.deleteFinishedBefore(now - ROW_RETENTION_MILLIS)
    }

    private fun fromRemote(remote: ConsolidatedDelivery, createdAt: Long) = ConsolidatedReportDelivery(
        id = remote.id,
        kind = remote.kind ?: "scheduled",
        fileName = remote.fileName,
        caption = remote.caption,
        sourceGroup = remote.sourceGroup,
        destinationGroup = remote.destinationGroup,
        periodStart = remote.periodStart,
        periodEnd = remote.periodEnd,
        reportCount = remote.reportCount,
        pdfPath = remote.pdfPath,
        status = READY_TO_SEND,
        createdAt = createdAt,
        updatedAt = clock(),
    )

    private fun config(baseUrl: String) = BackendConfig(baseUrl, identity.deviceId(), identity.deviceToken())

    companion object {
        private val CHECK_LOCK = Mutex()
        private const val TAG = "Delivery"
        const val STATE_NOTIFIED = "notified"
        const val STATE_OPENED = "opened"
        const val STATE_SENT = "sent"
        const val STATE_NOT_SENT = "not_sent"
        const val STATE_FAILED = "failed"
        const val REMOVED_ON_PHONE = "Removed on the bridge phone"
        const val MAX_DOWNLOAD_ATTEMPTS = 5
        /** Automatic PDF attempts before it is left to the operator. */
        const val MAX_AUTO_ATTEMPTS = 3
        const val AUTO_RETRY_MILLIS = 2L * 60 * 1000
        private const val FILE_RETENTION_MILLIS = 7L * 24 * 60 * 60 * 1000
        private const val ROW_RETENTION_MILLIS = 30L * 24 * 60 * 60 * 1000
        private val SAFE_NAME = Regex("^[A-Za-z0-9_.-]{1,120}\\.pdf$")
        private val SAFE_ID = Regex("^[0-9a-fA-F-]{8,64}$")

        /** Local copy of a delivery's PDF in the app sandbox (validated: no path tricks). */
        fun pdfFile(directory: File, id: String, fileName: String): File? {
            if (!SAFE_NAME.matches(fileName) || !SAFE_ID.matches(id)) return null
            return File(File(directory, id), fileName)
        }

        /** A complete PDF starts with "%PDF-" and contains an end-of-file marker near its end. */
        fun isPdf(file: File): Boolean {
            if (!file.isFile || file.length() < 16) return false
            return runCatching {
                file.inputStream().use { input ->
                    val head = ByteArray(5)
                    if (input.read(head) != 5 || String(head, Charsets.US_ASCII) != "%PDF-") return false
                }
                val tailSize = minOf(file.length(), 1024L).toInt()
                java.io.RandomAccessFile(file, "r").use { raf ->
                    raf.seek(file.length() - tailSize)
                    val tail = ByteArray(tailSize)
                    raf.readFully(tail)
                    String(tail, Charsets.ISO_8859_1).contains("%%EOF")
                }
            }.getOrDefault(false)
        }
    }
}

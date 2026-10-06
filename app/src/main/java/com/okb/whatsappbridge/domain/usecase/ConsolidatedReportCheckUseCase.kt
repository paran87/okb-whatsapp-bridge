package com.okb.whatsappbridge.domain.usecase

import com.okb.whatsappbridge.data.remote.api.ApiResult
import com.okb.whatsappbridge.data.remote.api.BackendConfig
import com.okb.whatsappbridge.data.remote.api.BridgeApi
import com.okb.whatsappbridge.data.remote.dto.ConsolidatedDelivery
import com.okb.whatsappbridge.data.remote.dto.ConsolidatedRunDueRequest
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
import java.io.File

/** Shows the operator a "report ready" notification that opens the one-tap WhatsApp share. */
fun interface ConsolidatedReportSink {
    /** @return true when the notification was posted (notifications allowed). */
    fun reportReady(delivery: ConsolidatedReportDelivery, pdf: File): Boolean
}

data class ConsolidatedCheckResult(val newlyReady: Int = 0, val warning: String? = null, val error: String? = null)

/** A verified local PDF ready to hand to WhatsApp's share screen. */
data class ShareTarget(val delivery: ConsolidatedReportDelivery, val file: File)

/**
 * Consolidated WhatsApp reports on the phone: the backend generates the PDF, this class brings it to the
 * phone and keeps a local delivery queue (Room) so nothing is downloaded or offered twice and nothing is lost
 * while offline.
 *
 *   READY_TO_SEND → DOWNLOADING → READY_FOR_WHATSAPP → OPENED_IN_WHATSAPP → SENT (operator confirms)
 *                                        ↑__________ not sent __________|        FAILED (download failed)
 *
 * Runs from the existing 15-minute periodic check (WorkManager); the backend decides whether a report is due.
 * Nothing is sent to WhatsApp automatically: the operator opens WhatsApp's share screen, selects the
 * DESTINATION group and presses Send, then confirms in the app. Opening the share screen is never treated
 * as "sent".
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
) {

    suspend operator fun invoke(): ConsolidatedCheckResult {
        val current = settings.current()
        if (!current.backendConfigured) return ConsolidatedCheckResult(error = "Backend URL not configured")
        val config = config(current.backendUrl)

        flushPendingAcks(config)

        val request = ConsolidatedRunDueRequest(
            sourceGroupName = current.sourceGroupName.ifBlank { null },
            destinationGroupName = current.destinationGroupName.ifBlank { null },
        )
        val response = when (val r = api.consolidatedRunDue(config, request)) {
            is ApiResult.Success -> r.value
            is ApiResult.HttpError -> {
                // 404/503: an older backend, or consolidated storage not set up there yet.
                if (r.httpCode != 404 && r.httpCode != 503) logger.warn(TAG, "Consolidated report check failed: ${r.message}")
                return ConsolidatedCheckResult(error = r.message)
            }
            is ApiResult.NetworkError -> {
                logger.warn(TAG, "Consolidated report check failed (offline?): ${r.message}")
                return ConsolidatedCheckResult(error = "Backend unreachable")
            }
            is ApiResult.ConfigurationError -> return ConsolidatedCheckResult(error = r.message)
        }
        response.warning?.let { logger.warn(TAG, "Backend: $it") }

        var newlyReady = 0
        for (remote in response.deliveries) {
            if (process(config, remote)) newlyReady++
        }
        housekeeping()
        return ConsolidatedCheckResult(newlyReady, response.warning)
    }

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
            ack(config, local.id, STATE_NOTIFIED)
            return false
        }

        local = if (isPdf(file)) {
            // A verified copy is still on the phone (e.g. a Resend): offer it again without downloading.
            local.copy(status = READY_FOR_WHATSAPP, errorMessage = null, updatedAt = clock())
        } else {
            download(config, local, file) ?: return false
        }
        deliveries.save(local)
        if (sink.reportReady(local, file)) {
            logger.info(TAG, "PDF ready to send: ${local.fileName} → ${local.destinationGroup ?: "destination group not set"}")
        } else {
            logger.warn(TAG, "PDF ready but notifications are blocked; open the app to send ${local.fileName}")
        }
        ack(config, local.id, STATE_NOTIFIED)
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
     * Operator pressed "Send to WhatsApp": returns the verified local PDF, downloading it again first when it is
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

    /** WhatsApp's share screen was opened with the PDF. This is NOT proof that it was sent. */
    suspend fun markOpened(id: String) = transition(id, OPENED_IN_WHATSAPP, STATE_OPENED) { it.copy(openedAt = clock(), errorMessage = null) }

    /** The operator confirmed in the app that the report was sent to the destination group. */
    suspend fun confirmSent(id: String) = transition(id, SENT, STATE_SENT) { it.copy(sentAt = clock(), errorMessage = null) }

    /** The operator cancelled or did not send it: it stays ready to send. */
    suspend fun markNotSent(id: String) = transition(id, READY_FOR_WHATSAPP, STATE_NOT_SENT) { it.copy(openedAt = null) }

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
        val ok = api.acknowledgeConsolidatedDelivery(config, id, state, error) is ApiResult.Success
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
        private const val TAG = "Delivery"
        const val STATE_NOTIFIED = "notified"
        const val STATE_OPENED = "opened"
        const val STATE_SENT = "sent"
        const val STATE_NOT_SENT = "not_sent"
        const val STATE_FAILED = "failed"
        const val MAX_DOWNLOAD_ATTEMPTS = 5
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

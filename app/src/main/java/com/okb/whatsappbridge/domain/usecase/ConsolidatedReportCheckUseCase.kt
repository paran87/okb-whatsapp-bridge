package com.okb.whatsappbridge.domain.usecase

import com.okb.whatsappbridge.data.remote.api.ApiResult
import com.okb.whatsappbridge.data.remote.api.BackendConfig
import com.okb.whatsappbridge.data.remote.api.BridgeApi
import com.okb.whatsappbridge.data.remote.dto.ConsolidatedDelivery
import com.okb.whatsappbridge.domain.repository.DeviceIdentityRepository
import com.okb.whatsappbridge.domain.repository.SettingsRepository
import com.okb.whatsappbridge.util.logging.BridgeLogger
import java.io.File

/** Shows the operator a "report ready" notification that opens the one-tap WhatsApp share. */
fun interface ConsolidatedReportSink {
    /** @return true when the notification was posted (notifications allowed). */
    fun reportReady(delivery: ConsolidatedDelivery, pdf: File): Boolean
}

/**
 * Consolidated WhatsApp reports, run from the existing 15-minute periodic check.
 *
 * The phone only asks; the backend decides whether a consolidated report is due, generates the PDF and
 * returns the deliveries waiting for this phone. For each one the PDF is downloaded into the app sandbox
 * and a notification is shown. Nothing is sent to WhatsApp automatically: tapping the notification opens
 * WhatsApp's share screen with the PDF and caption, and the operator chooses the group and presses Send.
 */
class ConsolidatedReportCheckUseCase(
    private val settings: SettingsRepository,
    private val identity: DeviceIdentityRepository,
    private val api: BridgeApi,
    private val directory: File,
    private val sink: ConsolidatedReportSink,
    private val logger: BridgeLogger,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    suspend operator fun invoke(): Int {
        val current = settings.current()
        if (!current.backendConfigured) return 0
        val config = config(current.backendUrl)
        val response = when (val r = api.consolidatedRunDue(config)) {
            is ApiResult.Success -> r.value
            is ApiResult.HttpError -> {
                // 503 = consolidated storage not set up on the backend yet; nothing to do on the phone.
                if (r.httpCode != 404 && r.httpCode != 503) logger.warn(TAG, "Consolidated report check failed: ${r.message}")
                return 0
            }
            is ApiResult.NetworkError -> { logger.warn(TAG, "Consolidated report check failed: ${r.message}"); return 0 }
            is ApiResult.ConfigurationError -> return 0
        }

        var notified = 0
        for (delivery in response.deliveries) {
            val file = pdfFile(directory, delivery) ?: continue
            if (!file.exists()) {
                when (val d = api.downloadConsolidatedPdf(config, delivery.pdfPath, file)) {
                    is ApiResult.Success -> Unit
                    is ApiResult.HttpError -> { logger.warn(TAG, "PDF ${delivery.fileName} not downloaded: ${d.message}"); continue }
                    is ApiResult.NetworkError -> { logger.warn(TAG, "PDF ${delivery.fileName} not downloaded: ${d.message}"); continue }
                    is ApiResult.ConfigurationError -> continue
                }
            }
            if (!sink.reportReady(delivery, file)) {
                logger.warn(TAG, "Consolidated report ${delivery.fileName} is ready but notifications are disabled")
                continue
            }
            notified++
            logger.info(TAG, "Consolidated report ready to send: ${delivery.fileName}")
            api.acknowledgeConsolidatedDelivery(config, delivery.id, STATE_NOTIFIED)
        }
        pruneOldFiles()
        return notified
    }

    /** Called when the operator opened the report in WhatsApp from the notification. */
    suspend fun markShared(id: String) {
        val current = settings.current()
        if (!current.backendConfigured) return
        when (val r = api.acknowledgeConsolidatedDelivery(config(current.backendUrl), id, STATE_SHARED)) {
            is ApiResult.Success -> logger.info(TAG, "Consolidated report opened in WhatsApp")
            is ApiResult.HttpError -> logger.warn(TAG, "Could not record the WhatsApp hand-off: ${r.message}")
            is ApiResult.NetworkError -> logger.warn(TAG, "Could not record the WhatsApp hand-off: ${r.message}")
            is ApiResult.ConfigurationError -> Unit
        }
    }

    private fun pruneOldFiles() {
        val cutoff = clock() - RETENTION_MILLIS
        if (!directory.exists()) return
        directory.walkBottomUp().forEach { f ->
            if (f.isFile && f.lastModified() < cutoff) f.delete()
            else if (f.isDirectory && f != directory && f.list().isNullOrEmpty()) f.delete()
        }
    }

    private fun config(baseUrl: String) = BackendConfig(baseUrl, identity.deviceId(), identity.deviceToken())

    companion object {
        private const val TAG = "Consolidated"
        const val STATE_NOTIFIED = "notified"
        const val STATE_SHARED = "shared"
        private const val RETENTION_MILLIS = 7L * 24 * 60 * 60 * 1000
        private val SAFE_NAME = Regex("^[A-Za-z0-9_.-]{1,120}\\.pdf$")

        /** Local copy of a delivery's PDF, named as the backend named it (validated: no path tricks). */
        fun pdfFile(directory: File, delivery: ConsolidatedDelivery): File? =
            pdfFile(directory, delivery.id, delivery.fileName)

        fun pdfFile(directory: File, id: String, fileName: String): File? {
            if (!SAFE_NAME.matches(fileName) || !Regex("^[0-9a-fA-F-]{8,64}$").matches(id)) return null
            return File(File(directory, id), fileName)
        }
    }
}

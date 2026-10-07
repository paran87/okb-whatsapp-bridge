package com.okb.whatsappbridge.data.remote.api

import com.okb.whatsappbridge.data.remote.dto.DeviceRegistrationRequest
import com.okb.whatsappbridge.data.remote.dto.DeviceRegistrationResponse
import com.okb.whatsappbridge.data.remote.dto.HealthResponse
import com.okb.whatsappbridge.data.remote.dto.MessageUploadRequest
import com.okb.whatsappbridge.data.remote.dto.MessageUploadResponse
import com.okb.whatsappbridge.data.remote.dto.MediaIntentRequest
import com.okb.whatsappbridge.data.remote.dto.MediaIntentResponse
import com.okb.whatsappbridge.data.remote.dto.MediaCompleteRequest
import com.okb.whatsappbridge.data.remote.dto.MediaCompleteResponse
import com.okb.whatsappbridge.data.remote.dto.ConsolidatedDeliveryAckResponse
import com.okb.whatsappbridge.data.remote.dto.ConsolidatedRunDueRequest
import com.okb.whatsappbridge.data.remote.dto.ConsolidatedRunDueResponse
import com.okb.whatsappbridge.data.remote.dto.TextClaimResponse
import com.okb.whatsappbridge.data.remote.dto.TextDeliveryJob
import com.okb.whatsappbridge.data.remote.dto.TextResultRequest
import com.okb.whatsappbridge.data.remote.dto.TextResultResponse
import java.io.File

/** Credentials and endpoint used for a call. The token is never logged. */
data class BackendConfig(
    val baseUrl: String,
    val deviceId: String,
    val token: String?,
)

/**
 * OKB backend contract:
 *  - `GET  /api/v1/health`
 *  - `POST /api/v1/devices/register`
 *  - `POST /api/v1/messages`
 *  - `POST /api/v1/media/intent`    (Phase 2)
 *  - `POST /api/v1/media/complete`  (Phase 2)
 *  - `POST /api/v1/consolidated-reports/run-due`        (consolidated reports: 15-minute check)
 *  - `GET  /api/v1/consolidated-reports/{id}/pdf`       (consolidated reports: the PDF)
 *  - `POST /api/v1/consolidated-reports/{id}/delivery`  (consolidated reports, PDF: notified / opened / sent / not_sent / failed)
 *  - `POST /api/v1/consolidated-reports/text-deliveries/{id}/claim`   (TEXT: start one automatic attempt)
 *  - `POST /api/v1/consolidated-reports/text-deliveries/{id}/result`  (TEXT: sent / failed)
 */
interface BridgeApi {
    suspend fun health(config: BackendConfig): ApiResult<HealthResponse>
    suspend fun registerDevice(config: BackendConfig, request: DeviceRegistrationRequest): ApiResult<DeviceRegistrationResponse>
    suspend fun uploadMessage(config: BackendConfig, request: MessageUploadRequest): ApiResult<MessageUploadResponse>
    suspend fun mediaIntent(config: BackendConfig, request: MediaIntentRequest): ApiResult<MediaIntentResponse>
    suspend fun mediaComplete(config: BackendConfig, request: MediaCompleteRequest): ApiResult<MediaCompleteResponse>

    // Consolidated reports. Defaults keep other implementations (test fakes) source-compatible.
    suspend fun consolidatedRunDue(
        config: BackendConfig,
        request: ConsolidatedRunDueRequest = ConsolidatedRunDueRequest(),
    ): ApiResult<ConsolidatedRunDueResponse> =
        ApiResult.ConfigurationError("Consolidated reports are not supported")

    /** Downloads the PDF at [pdfPath] into [target]; returns the number of bytes written. */
    suspend fun downloadConsolidatedPdf(config: BackendConfig, pdfPath: String, target: File): ApiResult<Long> =
        ApiResult.ConfigurationError("Consolidated reports are not supported")

    suspend fun acknowledgeConsolidatedDelivery(
        config: BackendConfig,
        id: String,
        state: String,
        error: String? = null,
    ): ApiResult<ConsolidatedDeliveryAckResponse> =
        ApiResult.ConfigurationError("Consolidated reports are not supported")

    /** Current state of one TEXT delivery (read-only; refreshes the Dashboard card). */
    suspend fun getTextDelivery(config: BackendConfig, id: String): ApiResult<TextDeliveryJob> =
        ApiResult.ConfigurationError("Automatic text reports are not supported")

    suspend fun claimTextDelivery(config: BackendConfig, id: String): ApiResult<TextClaimResponse> =
        ApiResult.ConfigurationError("Automatic text reports are not supported")

    suspend fun reportTextDeliveryResult(config: BackendConfig, id: String, result: TextResultRequest): ApiResult<TextResultResponse> =
        ApiResult.ConfigurationError("Automatic text reports are not supported")
}

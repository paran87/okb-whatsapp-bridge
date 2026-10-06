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
import com.okb.whatsappbridge.data.remote.dto.ConsolidatedRunDueResponse
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
 *  - `POST /api/v1/consolidated-reports/{id}/delivery`  (consolidated reports: notified / shared)
 */
interface BridgeApi {
    suspend fun health(config: BackendConfig): ApiResult<HealthResponse>
    suspend fun registerDevice(config: BackendConfig, request: DeviceRegistrationRequest): ApiResult<DeviceRegistrationResponse>
    suspend fun uploadMessage(config: BackendConfig, request: MessageUploadRequest): ApiResult<MessageUploadResponse>
    suspend fun mediaIntent(config: BackendConfig, request: MediaIntentRequest): ApiResult<MediaIntentResponse>
    suspend fun mediaComplete(config: BackendConfig, request: MediaCompleteRequest): ApiResult<MediaCompleteResponse>

    // Consolidated reports. Defaults keep other implementations (test fakes) source-compatible.
    suspend fun consolidatedRunDue(config: BackendConfig): ApiResult<ConsolidatedRunDueResponse> =
        ApiResult.ConfigurationError("Consolidated reports are not supported")

    /** Downloads the PDF at [pdfPath] into [target]; returns the number of bytes written. */
    suspend fun downloadConsolidatedPdf(config: BackendConfig, pdfPath: String, target: File): ApiResult<Long> =
        ApiResult.ConfigurationError("Consolidated reports are not supported")

    suspend fun acknowledgeConsolidatedDelivery(config: BackendConfig, id: String, state: String): ApiResult<ConsolidatedDeliveryAckResponse> =
        ApiResult.ConfigurationError("Consolidated reports are not supported")
}

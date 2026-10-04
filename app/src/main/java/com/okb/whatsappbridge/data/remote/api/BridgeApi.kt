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
 */
interface BridgeApi {
    suspend fun health(config: BackendConfig): ApiResult<HealthResponse>
    suspend fun registerDevice(config: BackendConfig, request: DeviceRegistrationRequest): ApiResult<DeviceRegistrationResponse>
    suspend fun uploadMessage(config: BackendConfig, request: MessageUploadRequest): ApiResult<MessageUploadResponse>
    suspend fun mediaIntent(config: BackendConfig, request: MediaIntentRequest): ApiResult<MediaIntentResponse>
    suspend fun mediaComplete(config: BackendConfig, request: MediaCompleteRequest): ApiResult<MediaCompleteResponse>
}

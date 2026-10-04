package com.okb.whatsappbridge.data.remote.api

import com.okb.whatsappbridge.data.remote.dto.DeviceRegistrationRequest
import com.okb.whatsappbridge.data.remote.dto.DeviceRegistrationResponse
import com.okb.whatsappbridge.data.remote.dto.HealthResponse
import com.okb.whatsappbridge.data.remote.dto.MessageUploadRequest
import com.okb.whatsappbridge.data.remote.dto.MessageUploadResponse

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
 */
interface BridgeApi {
    suspend fun health(config: BackendConfig): ApiResult<HealthResponse>
    suspend fun registerDevice(config: BackendConfig, request: DeviceRegistrationRequest): ApiResult<DeviceRegistrationResponse>
    suspend fun uploadMessage(config: BackendConfig, request: MessageUploadRequest): ApiResult<MessageUploadResponse>
}

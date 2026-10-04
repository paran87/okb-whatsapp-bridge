package com.okb.whatsappbridge.fakes

import com.okb.whatsappbridge.data.remote.api.ApiResult
import com.okb.whatsappbridge.data.remote.api.BackendConfig
import com.okb.whatsappbridge.data.remote.api.BridgeApi
import com.okb.whatsappbridge.data.remote.dto.DeviceRegistrationRequest
import com.okb.whatsappbridge.data.remote.dto.DeviceRegistrationResponse
import com.okb.whatsappbridge.data.remote.dto.HealthResponse
import com.okb.whatsappbridge.data.remote.dto.MessageUploadRequest
import com.okb.whatsappbridge.data.remote.dto.MessageUploadResponse
import com.okb.whatsappbridge.data.remote.dto.MediaIntentRequest
import com.okb.whatsappbridge.data.remote.dto.MediaIntentResponse
import com.okb.whatsappbridge.data.remote.dto.MediaCompleteRequest
import com.okb.whatsappbridge.data.remote.dto.MediaCompleteResponse

/** Scriptable API: each upload consumes the next scripted result (default: success). */
class FakeBridgeApi : BridgeApi {
    val uploadResults = ArrayDeque<ApiResult<MessageUploadResponse>>()
    val uploaded = mutableListOf<MessageUploadRequest>()
    var healthResult: ApiResult<HealthResponse> = ApiResult.Success(HealthResponse("ok"), 200)
    var registerResult: ApiResult<DeviceRegistrationResponse> = ApiResult.Success(DeviceRegistrationResponse(registered = true), 201)
    var healthCalls = 0

    override suspend fun health(config: BackendConfig): ApiResult<HealthResponse> {
        healthCalls++
        return healthResult
    }

    override suspend fun registerDevice(config: BackendConfig, request: DeviceRegistrationRequest) = registerResult

    override suspend fun uploadMessage(config: BackendConfig, request: MessageUploadRequest): ApiResult<MessageUploadResponse> {
        val result = uploadResults.removeFirstOrNull()
            ?: ApiResult.Success(MessageUploadResponse(id = "srv-${uploaded.size + 1}"), 201)
        if (result is ApiResult.Success) uploaded += request
        return result
    }

    // ---- media ----
    val intentResults = ArrayDeque<ApiResult<MediaIntentResponse>>()
    val completeResults = ArrayDeque<ApiResult<MediaCompleteResponse>>()
    val intents = mutableListOf<MediaIntentRequest>()
    val completes = mutableListOf<MediaCompleteRequest>()

    override suspend fun mediaIntent(config: BackendConfig, request: MediaIntentRequest): ApiResult<MediaIntentResponse> {
        intents += request
        return intentResults.removeFirstOrNull()
            ?: ApiResult.Success(
                MediaIntentResponse(
                    status = "upload",
                    objectKey = "whatsapp/${request.deviceId}/2026/10/04/${request.sha256}.jpg",
                    uploadUrl = "https://sink.test/put/${request.sha256}",
                    method = "PUT",
                    headers = mapOf("Content-Type" to (request.mimeType ?: "application/octet-stream")),
                ),
                200,
            )
    }

    override suspend fun mediaComplete(config: BackendConfig, request: MediaCompleteRequest): ApiResult<MediaCompleteResponse> {
        completes += request
        return completeResults.removeFirstOrNull()
            ?: ApiResult.Success(MediaCompleteResponse(id = "msrv-${completes.size}", objectKey = request.objectKey, etag = request.etag, status = "stored"), 201)
    }
}

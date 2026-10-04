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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class OkHttpBridgeApi(
    private val client: OkHttpClient = defaultClient(),
    private val userAgent: String = "OKB-WhatsApp-Bridge",
) : BridgeApi {

    override suspend fun health(config: BackendConfig): ApiResult<HealthResponse> =
        execute(config, "api/v1/health", body = null, HealthResponse.serializer(), HealthResponse())

    override suspend fun registerDevice(
        config: BackendConfig,
        request: DeviceRegistrationRequest,
    ): ApiResult<DeviceRegistrationResponse> = execute(
        config,
        "api/v1/devices/register",
        body = json.encodeToString(DeviceRegistrationRequest.serializer(), request),
        DeviceRegistrationResponse.serializer(),
        DeviceRegistrationResponse(),
    )

    override suspend fun uploadMessage(
        config: BackendConfig,
        request: MessageUploadRequest,
    ): ApiResult<MessageUploadResponse> = execute(
        config,
        "api/v1/messages",
        body = json.encodeToString(MessageUploadRequest.serializer(), request),
        MessageUploadResponse.serializer(),
        MessageUploadResponse(),
        idempotencyKey = request.fingerprint,
    )

    override suspend fun mediaIntent(
        config: BackendConfig,
        request: MediaIntentRequest,
    ): ApiResult<MediaIntentResponse> = execute(
        config,
        "api/v1/media/intent",
        body = json.encodeToString(MediaIntentRequest.serializer(), request),
        MediaIntentResponse.serializer(),
        MediaIntentResponse(),
        idempotencyKey = request.sha256,
    )

    override suspend fun mediaComplete(
        config: BackendConfig,
        request: MediaCompleteRequest,
    ): ApiResult<MediaCompleteResponse> = execute(
        config,
        "api/v1/media/complete",
        body = json.encodeToString(MediaCompleteRequest.serializer(), request),
        MediaCompleteResponse.serializer(),
        MediaCompleteResponse(),
        idempotencyKey = request.sha256,
    )

    private suspend fun <T> execute(
        config: BackendConfig,
        path: String,
        body: String?,
        serializer: KSerializer<T>,
        emptyValue: T,
        idempotencyKey: String? = null,
    ): ApiResult<T> {
        val url = resolve(config.baseUrl, path)
            ?: return ApiResult.ConfigurationError("Invalid backend URL")
        val builder = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", userAgent)
            .header("X-OKB-Device-Id", config.deviceId)
        config.token?.takeIf { it.isNotBlank() }?.let { builder.header("Authorization", "Bearer $it") }
        idempotencyKey?.let { builder.header("Idempotency-Key", it) }
        if (body != null) builder.post(body.toRequestBody(JSON_MEDIA_TYPE)) else builder.get()

        return withContext(Dispatchers.IO) {
            try {
                client.newCall(builder.build()).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (response.isSuccessful) {
                        // A 2xx means the backend accepted the request; a body we cannot parse
                        // must not turn a confirmed receipt into a retry.
                        val value = if (text.isBlank()) emptyValue else runCatching {
                            json.decodeFromString(serializer, text)
                        }.getOrDefault(emptyValue)
                        ApiResult.Success(value, response.code)
                    } else {
                        ApiResult.HttpError(response.code, describeHttpError(response.code, text))
                    }
                }
            } catch (e: IOException) {
                ApiResult.NetworkError(e.javaClass.simpleName + (e.message?.let { ": $it" } ?: ""))
            } catch (e: IllegalArgumentException) {
                ApiResult.ConfigurationError(e.message ?: "Invalid request")
            }
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = true
        }

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        /** Joins the configured base URL (which may contain a path prefix) with an API path. */
        fun resolve(baseUrl: String, path: String): HttpUrl? {
            val base = baseUrl.trim().trimEnd('/').toHttpUrlOrNull() ?: return null
            return base.newBuilder().addPathSegments(path).build()
        }

        /** Short, log-safe description; backend error bodies are truncated and never echo headers. */
        private fun describeHttpError(code: Int, body: String): String {
            val detail = runCatching {
                json.parseToJsonElementOrNull(body)
            }.getOrNull()
            val snippet = (detail ?: body).take(160).replace('\n', ' ')
            return if (snippet.isBlank()) "HTTP $code" else "HTTP $code: $snippet"
        }

        private fun Json.parseToJsonElementOrNull(body: String): String? {
            val element = runCatching { parseToJsonElement(body) }.getOrNull() ?: return null
            val obj = element as? kotlinx.serialization.json.JsonObject ?: return null
            val message = obj["error"] ?: obj["message"] ?: return null
            return (message as? kotlinx.serialization.json.JsonPrimitive)?.content
        }
    }
}

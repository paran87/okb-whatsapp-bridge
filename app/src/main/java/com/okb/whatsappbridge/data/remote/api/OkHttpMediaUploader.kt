package com.okb.whatsappbridge.data.remote.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * OkHttp PUT uploader. `File.asRequestBody` streams the file from disk in chunks, so a multi-hundred-MB
 * video is never loaded into memory. Timeouts are generous and there is no overall call timeout, so a
 * large transfer on a slow link is not cut off mid-stream.
 */
class OkHttpMediaUploader(
    private val client: OkHttpClient = defaultClient(),
) : MediaUploader {

    override suspend fun put(url: String, headers: Map<String, String>, file: File): ApiResult<String?> {
        val httpUrl = url.toHttpUrlOrNull() ?: return ApiResult.ConfigurationError("Invalid upload URL")
        if (!file.exists()) return ApiResult.ConfigurationError("Local media file is missing")
        val contentType = headers.entries.firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
            ?.value?.toMediaTypeOrNull()
        val builder = Request.Builder().url(httpUrl).put(file.asRequestBody(contentType))
        headers.forEach { (k, v) -> if (!k.equals("Content-Type", ignoreCase = true)) builder.header(k, v) }

        return withContext(Dispatchers.IO) {
            try {
                client.newCall(builder.build()).execute().use { response ->
                    if (response.isSuccessful) {
                        ApiResult.Success(response.header("ETag")?.trim('"'), response.code)
                    } else {
                        val body = response.body?.string().orEmpty().take(160).replace('\n', ' ')
                        ApiResult.HttpError(response.code, if (body.isBlank()) "HTTP ${response.code}" else "HTTP ${response.code}: $body")
                    }
                }
            } catch (e: IOException) {
                ApiResult.NetworkError(e.javaClass.simpleName + (e.message?.let { ": $it" } ?: ""))
            }
        }
    }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(0, TimeUnit.SECONDS) // no write timeout: large uploads on slow links
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}

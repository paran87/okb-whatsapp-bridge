package com.okb.whatsappbridge.fakes

import com.okb.whatsappbridge.data.remote.api.ApiResult
import com.okb.whatsappbridge.data.remote.api.MediaUploader
import com.okb.whatsappbridge.media.MediaContentAccess
import com.okb.whatsappbridge.media.MediaUriMetadata
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

/** Scriptable content access: maps a URI to bytes, or to an exception to throw on open. */
class FakeMediaContentAccess(
    var supported: Boolean = true,
) : MediaContentAccess {
    val bytesByUri = mutableMapOf<String, ByteArray>()
    val metadataByUri = mutableMapOf<String, MediaUriMetadata>()
    val throwOnOpen = mutableMapOf<String, Throwable>()

    override fun isMediaAcquisitionSupported(): Boolean = supported

    override fun queryMetadata(uri: String): MediaUriMetadata? = metadataByUri[uri]
        ?: bytesByUri[uri]?.let { MediaUriMetadata(null, "image/jpeg", it.size.toLong()) }

    override fun openStream(uri: String): InputStream {
        throwOnOpen[uri]?.let { throw it }
        val bytes = bytesByUri[uri] ?: throw IOException("no bytes for $uri")
        return ByteArrayInputStream(bytes)
    }
}

/** Scriptable PUT uploader. */
class FakeMediaUploader : MediaUploader {
    val results = ArrayDeque<ApiResult<String?>>()
    val puts = mutableListOf<String>()
    override suspend fun put(url: String, headers: Map<String, String>, file: File): ApiResult<String?> {
        puts += url
        return results.removeFirstOrNull() ?: ApiResult.Success("etag-${puts.size}", 200)
    }
}

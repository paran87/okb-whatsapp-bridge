package com.okb.whatsappbridge.data.remote.api

import java.io.File

/** Streams a local file to an upload URL (presigned R2 PUT, or a dev local-sink PUT). */
interface MediaUploader {
    /**
     * Uploads [file] with HTTP PUT to [url], applying [headers] (e.g. Content-Type). The file is
     * streamed from disk, never loaded into memory. On success returns the object's ETag (may be null).
     */
    suspend fun put(url: String, headers: Map<String, String>, file: File): ApiResult<String?>
}

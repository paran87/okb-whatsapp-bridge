package com.okb.whatsappbridge.util.media

import com.okb.whatsappbridge.domain.model.MediaType

/** Maps MIME types and media kinds to a safe file extension for object keys and local files. */
object MediaMimeTypes {

    private val BY_MIME = mapOf(
        "image/jpeg" to "jpg", "image/jpg" to "jpg", "image/png" to "png", "image/webp" to "webp",
        "image/gif" to "gif", "image/heic" to "heic", "image/heif" to "heif",
        "video/mp4" to "mp4", "video/3gpp" to "3gp", "video/webm" to "webm", "video/quicktime" to "mov",
        "audio/ogg" to "ogg", "audio/opus" to "opus", "audio/mpeg" to "mp3", "audio/amr" to "amr",
        "audio/mp4" to "m4a", "audio/aac" to "aac",
        "application/pdf" to "pdf", "text/vcard" to "vcf", "text/plain" to "txt",
        "application/zip" to "zip",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to "docx",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" to "xlsx",
        "application/msword" to "doc", "application/vnd.ms-excel" to "xls",
    )

    private val BY_TYPE = mapOf(
        MediaType.IMAGE to "jpg", MediaType.VIDEO to "mp4", MediaType.AUDIO to "ogg",
        MediaType.STICKER to "webp", MediaType.DOCUMENT to "bin",
    )

    /** Best-effort, lowercase, no dot. Falls back by media type, then "bin". */
    fun extension(mimeType: String?, mediaType: MediaType, originalFileName: String?): String {
        mimeType?.lowercase()?.substringBefore(';')?.trim()?.let { BY_MIME[it] }?.let { return it }
        originalFileName?.substringAfterLast('.', "")?.lowercase()
            ?.takeIf { it.isNotEmpty() && it.length <= 5 && it.all(Char::isLetterOrDigit) }
            ?.let { return it }
        return BY_TYPE[mediaType] ?: "bin"
    }

    fun normalizeMime(mimeType: String?): String? =
        mimeType?.substringBefore(';')?.trim()?.lowercase()?.takeIf { it.isNotBlank() && it.contains('/') }
}

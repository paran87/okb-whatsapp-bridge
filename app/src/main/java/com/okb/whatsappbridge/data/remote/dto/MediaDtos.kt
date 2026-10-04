package com.okb.whatsappbridge.data.remote.dto

import kotlinx.serialization.Serializable

/**
 * `POST /api/v1/media/intent` — asks the backend where to put a media object.
 *
 * The device sends only metadata and the content hash; the backend returns either a short-lived,
 * key- and content-type-scoped upload URL (presigned R2, or a local-sink URL in dev) or, when the
 * content already exists, a duplicate response so no bytes are transferred.
 */
@Serializable
data class MediaIntentRequest(
    val deviceId: String,
    val sha256: String,
    val mediaType: String,
    val mimeType: String?,
    val fileSizeBytes: Long,
    val originalFileName: String?,
    val groupName: String?,
    val capturedAt: String,
    val messageFingerprint: String?,
)

@Serializable
data class MediaIntentResponse(
    /** "upload" → transfer bytes to uploadUrl; "duplicate" → already stored, skip transfer. */
    val status: String? = null,
    val objectKey: String? = null,
    val uploadUrl: String? = null,
    val method: String? = null,
    val headers: Map<String, String>? = null,
    val expiresAt: String? = null,
    val remoteRef: String? = null,
) {
    val isDuplicate: Boolean get() = status == "duplicate"
}

/** `POST /api/v1/media/complete` — confirms the object was uploaded and records its metadata. */
@Serializable
data class MediaCompleteRequest(
    val deviceId: String,
    val sha256: String,
    val objectKey: String,
    val etag: String?,
    val fileSizeBytes: Long,
    val mediaType: String,
    val mimeType: String?,
    val originalFileName: String?,
    val groupName: String?,
    val senderName: String?,
    val messageFingerprint: String?,
    val capturedAt: String,
)

@Serializable
data class MediaCompleteResponse(
    val id: String? = null,
    val objectKey: String? = null,
    val etag: String? = null,
    val remoteRef: String? = null,
    val status: String? = null,
)

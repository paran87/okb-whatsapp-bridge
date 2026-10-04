package com.okb.whatsappbridge.domain.model

/** Kind of content carried by a WhatsApp message, as far as it can be inferred from its notification. */
enum class MediaType {
    TEXT, IMAGE, VIDEO, AUDIO, DOCUMENT, LOCATION, STICKER, UNKNOWN;

    companion object {
        fun fromStorage(value: String?): MediaType = entries.firstOrNull { it.name == value } ?: UNKNOWN
    }
}

/**
 * Availability of the original media file.
 *
 * Phase 1 never extracts media: a media notification is stored with [UNAVAILABLE]
 * and the text of the notification (e.g. the caption) is still saved and uploaded.
 */
enum class MediaStatus {
    /** Plain text message – there is no media. */
    NONE,

    /** Media was detected but the original file is not legitimately accessible to the bridge. */
    UNAVAILABLE;

    companion object {
        fun fromStorage(value: String?): MediaStatus = entries.firstOrNull { it.name == value } ?: UNAVAILABLE
    }
}

/** Lifecycle of a captured message in the local upload queue. */
enum class UploadStatus {
    /** Stored locally, waiting for the first upload attempt. */
    PENDING_UPLOAD,

    /** An upload attempt is in progress. */
    UPLOADING,

    /** A transient error occurred (offline, timeout, 5xx); will be retried automatically. */
    RETRYING,

    /** A non-transient error occurred (e.g. 4xx, auth); retried by periodic reconciliation or manually. */
    FAILED,

    /** The backend confirmed receipt. Only now is the server the source of truth. */
    UPLOADED;

    companion object {
        fun fromStorage(value: String?): UploadStatus = entries.firstOrNull { it.name == value } ?: PENDING_UPLOAD

        /** Statuses that still need to reach the backend. */
        val NOT_UPLOADED: List<UploadStatus> = listOf(PENDING_UPLOAD, UPLOADING, RETRYING, FAILED)
    }
}

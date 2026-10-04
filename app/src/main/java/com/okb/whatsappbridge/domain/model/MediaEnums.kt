package com.okb.whatsappbridge.domain.model

/**
 * Whether the bridge has obtained the original media file for a detected attachment.
 *
 * The bridge only ever obtains media the Android OS legitimately hands it (a content URI granted on
 * the notification). When no such file is available the record rests at [UNAVAILABLE] with a clear,
 * operator-readable reason; the message and its caption are still captured and uploaded.
 */
enum class MediaAcquisitionStatus {
    /** A media attachment was detected from the notification but acquisition has not started. */
    DETECTED,

    /** The bridge is copying the legitimately-provided media file into app-controlled storage. */
    ACQUIRING,

    /** A local copy exists, is hashed and sized, and is queued for upload. */
    AVAILABLE,

    /** No legitimate media file was provided by Android (the expected, common outcome). Terminal. */
    UNAVAILABLE,

    /** Acquisition was attempted but failed unexpectedly (e.g. I/O error while copying). Terminal. */
    FAILED;

    companion object {
        fun fromStorage(value: String?): MediaAcquisitionStatus =
            entries.firstOrNull { it.name == value } ?: DETECTED
    }
}

/** Lifecycle of an acquired media file in the persistent media upload queue. */
enum class MediaUploadStatus {
    /** No local file to upload (acquisition not AVAILABLE), or upload not yet started. */
    PENDING,

    /** An upload attempt (intent → PUT → complete) is in progress. */
    UPLOADING,

    /** Confirmed stored remotely (R2 object key recorded). The server is now the source of truth. */
    UPLOADED,

    /** A transient error occurred (offline, timeout, 5xx); retried with backoff. */
    RETRYING,

    /** A non-transient error occurred (e.g. 4xx); retried by reconciliation or manually. */
    FAILED;

    companion object {
        fun fromStorage(value: String?): MediaUploadStatus = entries.firstOrNull { it.name == value } ?: PENDING
    }
}

package com.okb.whatsappbridge.domain.model

/** A captured WhatsApp group message as presented to the UI. */
data class BridgeMessage(
    val id: String,
    val serverId: String?,
    val groupName: String?,
    val senderName: String?,
    val messageText: String?,
    val timestamp: Long,
    val mediaType: MediaType,
    val mediaStatus: MediaStatus,
    val uploadStatus: UploadStatus,
    val packageName: String,
    val createdAt: Long,
    val uploadedAt: Long?,
    val lastError: String?,
    val attemptCount: Int,
    /** Linked media attachment summary, when the message carried a media indicator. */
    val media: MediaSummary? = null,
)

/** Compact media state shown next to a message in the list. */
data class MediaSummary(
    val mediaType: MediaType,
    val mimeType: String?,
    val fileSizeBytes: Long?,
    val acquisitionStatus: MediaAcquisitionStatus,
    val uploadStatus: MediaUploadStatus,
) {
    val hasLocalFile: Boolean get() = acquisitionStatus == MediaAcquisitionStatus.AVAILABLE
}

/** A WhatsApp group known to the bridge. Only [authorized] groups are captured. */
data class MonitoredGroup(
    val id: Long,
    val name: String,
    val authorized: Boolean,
    val discoveredAutomatically: Boolean,
    val createdAt: Long,
    val lastSeenAt: Long?,
)

/** Counts used by the dashboard and sync screens. All values come from the database. */
data class QueueCounts(
    val pending: Int = 0,
    val uploading: Int = 0,
    val retrying: Int = 0,
    val failed: Int = 0,
    val uploaded: Int = 0,
) {
    val notUploaded: Int get() = pending + uploading + retrying + failed
}

/** Diagnostic entry recorded by the bridge. Never contains message content, tokens or secrets. */
data class BridgeLogEntry(
    val id: Long,
    val timestamp: Long,
    val level: String,
    val tag: String,
    val message: String,
)

/** A media attachment as presented to the UI / detail screen. Never exposes a WhatsApp-owned path. */
data class MediaAttachment(
    val id: String,
    val messageId: String,
    val groupName: String?,
    val senderName: String?,
    val mediaType: MediaType,
    val mimeType: String?,
    val originalFileName: String?,
    val fileSizeBytes: Long?,
    val sha256: String?,
    val acquisitionStatus: MediaAcquisitionStatus,
    val uploadStatus: MediaUploadStatus,
    val statusDetail: String?,
    val uploadAttempts: Int,
    val lastError: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val uploadedAt: Long?,
    val r2ObjectKey: String?,
    val r2ETag: String?,
    /** True only when an accessible local file exists (gate for showing a real preview). */
    val hasLocalFile: Boolean,
)

/** Media queue/acquisition counts for dashboard and diagnostics. All from the database. */
data class MediaCounts(
    val detected: Int = 0,
    val acquiring: Int = 0,
    val available: Int = 0,
    val unavailable: Int = 0,
    val acquisitionFailed: Int = 0,
    val pendingUpload: Int = 0,
    val uploading: Int = 0,
    val retrying: Int = 0,
    val uploadFailed: Int = 0,
    val uploaded: Int = 0,
) {
    val pendingUploadTotal: Int get() = pendingUpload + uploading + retrying + uploadFailed
}

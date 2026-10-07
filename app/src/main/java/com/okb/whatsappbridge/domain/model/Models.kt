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
    /** When the operator moved it to the Recycle Bin; null when it is not deleted. */
    val deletedAt: Long? = null,
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

/**
 * Where a consolidated report PDF is on this phone. The bridge never sends to WhatsApp by itself: it opens
 * WhatsApp's share screen and the operator selects the destination group and presses Send.
 */
enum class ConsolidatedDeliveryStatus {
    /** Known from the backend; the PDF is not on the phone yet (also after a failed download attempt). */
    READY_TO_SEND,
    DOWNLOADING,
    /** PDF downloaded and verified; waiting for the operator to share it. */
    READY_FOR_WHATSAPP,
    /** The operator opened WhatsApp's share screen. Not proof of sending: the operator confirms below. */
    OPENED_IN_WHATSAPP,
    /** The operator confirmed in the app that the report was sent in WhatsApp. */
    SENT,
    FAILED,
    ;

    companion object {
        fun of(name: String?): ConsolidatedDeliveryStatus = entries.firstOrNull { it.name == name } ?: READY_TO_SEND
    }
}

data class ConsolidatedReportDelivery(
    val id: String,
    val kind: String,
    val fileName: String,
    val caption: String,
    val sourceGroup: String?,
    val destinationGroup: String?,
    val periodStart: String?,
    val periodEnd: String?,
    val reportCount: Int?,
    val pdfPath: String,
    val status: ConsolidatedDeliveryStatus,
    val errorMessage: String? = null,
    val downloadAttempts: Int = 0,
    val pendingAck: String? = null,
    val pendingAckError: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val downloadedAt: Long? = null,
    val openedAt: Long? = null,
    val sentAt: Long? = null,
) {
    val isTest: Boolean get() = kind == "test"
}

/** Dashboard counts: pending = not yet on the phone; ready = waiting for the operator (incl. opened). */
data class ConsolidatedDeliveryCounts(val pending: Int = 0, val ready: Int = 0, val sent: Int = 0, val failed: Int = 0)

/**
 * Automatic consolidated TEXT report on this phone (no operator action):
 * SCHEDULED (waiting / retrying) → SENDING (WhatsApp is being driven) → SENT (in the destination chat) | FAILED.
 */
enum class TextDeliveryStatus {
    SCHEDULED,
    SENDING,
    SENT,
    FAILED,
    ;

    companion object {
        fun of(name: String?): TextDeliveryStatus = entries.firstOrNull { it.name == name } ?: SCHEDULED
    }
}

data class TextReportDelivery(
    val id: String,
    val reportId: String,
    val kind: String,
    val dedupeKey: String,
    val destinationGroup: String,
    val sourceGroup: String?,
    val parts: List<com.okb.whatsappbridge.automation.MessagePart>,
    val periodStart: String?,
    val periodEnd: String?,
    val reportCount: Int?,
    val status: TextDeliveryStatus,
    val attempt: Int = 0,
    val lastError: String? = null,
    val verification: String? = null,
    val sentRefs: Set<String> = emptySet(),
    val pressedRefs: Set<String> = emptySet(),
    val pendingResult: String? = null,
    val pendingRetryable: Boolean = true,
    val createdAt: Long,
    val updatedAt: Long,
    val lastAttemptAt: Long? = null,
    val sentAt: Long? = null,
) {
    val isTest: Boolean get() = kind == "test"
    val isCancelled: Boolean get() = status == TextDeliveryStatus.FAILED && lastError == CANCELLED

    companion object {
        /** The backend's reason for a delivery cancelled in the Command Center. */
        const val CANCELLED = "Cancelled from the Command Center"
    }
}

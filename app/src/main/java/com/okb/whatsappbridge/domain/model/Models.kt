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
)

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

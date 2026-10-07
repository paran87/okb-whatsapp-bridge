package com.okb.whatsappbridge.domain.repository

import com.okb.whatsappbridge.domain.model.BridgeLogEntry
import com.okb.whatsappbridge.domain.model.BridgeMessage
import com.okb.whatsappbridge.domain.model.BridgeSettings
import com.okb.whatsappbridge.domain.model.ConsolidatedDeliveryCounts
import com.okb.whatsappbridge.domain.model.ConsolidatedReportDelivery
import com.okb.whatsappbridge.domain.model.MediaStatus
import com.okb.whatsappbridge.domain.model.MediaType
import com.okb.whatsappbridge.domain.model.MonitoredGroup
import com.okb.whatsappbridge.domain.model.QueueCounts
import com.okb.whatsappbridge.domain.model.TextReportDelivery
import com.okb.whatsappbridge.domain.model.UploadStatus
import com.okb.whatsappbridge.domain.usecase.SyncTrigger
import kotlinx.coroutines.flow.Flow

/** A message about to be persisted by the capture pipeline. */
data class NewCapturedMessage(
    val groupName: String?,
    val senderName: String?,
    val messageText: String?,
    val timestamp: Long,
    val mediaType: MediaType,
    val mediaStatus: MediaStatus,
    val fingerprint: String,
    val packageName: String,
    val notificationKey: String?,
    val capturedAt: Long,
)

enum class SaveResult { INSERTED, DUPLICATE }

/** Result of persisting a captured message, carrying the new row id when inserted. */
data class SaveOutcome(val result: SaveResult, val messageId: String?)

/** A queued message selected for upload. */
data class UploadCandidate(
    val id: String,
    val deviceId: String,
    val groupName: String?,
    val senderName: String?,
    val messageText: String?,
    val timestamp: Long,
    val mediaType: MediaType,
    val mediaStatus: MediaStatus,
    val fingerprint: String,
    val packageName: String,
    val createdAt: Long,
    val attemptCount: Int,
)

interface MessageRepository {
    suspend fun saveCaptured(message: NewCapturedMessage): SaveOutcome
    fun observeRecent(status: UploadStatus?, limit: Int): Flow<List<BridgeMessage>>
    fun observeById(id: String): Flow<BridgeMessage?>
    fun observeQueueCounts(): Flow<QueueCounts>
    fun observeCapturedSince(since: Long): Flow<Int>
    fun observeLatestMessageTimestamp(): Flow<Long?>

    suspend fun nextUploadBatch(excludedIds: Set<String>, includeFailed: Boolean, limit: Int): List<UploadCandidate>
    suspend fun countUploadable(includeFailed: Boolean): Int
    suspend fun markUploading(id: String)
    suspend fun markUploaded(id: String, serverId: String?, at: Long)
    suspend fun markRetrying(id: String, error: String, httpCode: Int?, at: Long)
    suspend fun markFailed(id: String, error: String, httpCode: Int?, at: Long)
    suspend fun recoverInterruptedUploads(): Int
    suspend fun resetFailedToPending(): Int
    suspend fun repairQueue(now: Long)
    suspend fun deleteUploadedBefore(cutoff: Long): Int

    // ---- Recycle Bin (local only: nothing is deleted on the backend) ----
    /** Soft-deletes; returns how many messages moved. Deleted messages are hidden and not uploaded. */
    suspend fun moveToRecycleBin(ids: Collection<String>, at: Long): Int
    suspend fun restoreFromRecycleBin(ids: Collection<String>): Int
    fun observeRecycleBin(limit: Int): Flow<List<BridgeMessage>>
    fun observeRecycleBinCount(): Flow<Int>
    /** Ids currently in the bin that were deleted before [cutoff] (use Long.MAX_VALUE for all). */
    suspend fun recycleBinIdsDeletedBefore(cutoff: Long): List<String>
    /** Deletes bin messages forever (content removed; a fingerprint-only tombstone remains briefly). */
    suspend fun purgeFromRecycleBin(ids: Collection<String>, at: Long): Int
    suspend fun deleteTombstonesBefore(cutoff: Long): Int

    /** Executes a trivial query; false when the database cannot be opened or queried. */
    suspend fun isDatabaseHealthy(): Boolean

    companion object {
        /** Automatic reconciliation stops retrying a FAILED message after this many attempts. */
        const val MAX_AUTOMATIC_FAILED_ATTEMPTS = 20
    }
}

interface GroupRepository {
    fun observeGroups(): Flow<List<MonitoredGroup>>
    suspend fun authorizedGroupNames(): List<String>
    /** Adds an authorized group (or authorizes an existing one). Returns false for blank names. */
    suspend fun addAuthorizedGroup(name: String, at: Long): Boolean
    suspend fun setAuthorized(id: Long, authorized: Boolean)
    suspend fun delete(id: Long)
    /** Records that a group was seen; unknown groups are stored as *not* authorized. */
    suspend fun recordSeen(name: String, at: Long)
}

interface SettingsRepository {
    val settings: Flow<BridgeSettings>
    suspend fun current(): BridgeSettings
    suspend fun setMonitoringEnabled(enabled: Boolean)
    suspend fun setSyncPaused(paused: Boolean)
    suspend fun setBackendUrl(url: String)
    suspend fun setDeviceName(name: String)
    suspend fun setDeviceRegisteredAt(at: Long?)
    suspend fun recordNotificationReceived(at: Long)
    suspend fun recordProcessed(at: Long)
    suspend fun recordUploadSuccess(at: Long)
    suspend fun recordUploadFailure(at: Long, error: String)
    suspend fun recordListenerConnected(at: Long)
    suspend fun recordListenerDisconnected(at: Long)
    suspend fun recordBackendCheck(at: Long, ok: Boolean, message: String)
    suspend fun recordHealthCheck(at: Long)
    suspend fun recordBoot(at: Long)
    suspend fun setCaptureMedia(enabled: Boolean)
    suspend fun setDeleteLocalAfterUpload(enabled: Boolean)
    suspend fun recordMediaCapture(at: Long)
    suspend fun recordMediaUploadSuccess(at: Long)
    suspend fun recordMediaFailure(at: Long, error: String)
    suspend fun getSourceGroupName(): String = current().sourceGroupName
    suspend fun setSourceGroupName(name: String)
    suspend fun getDestinationGroupName(): String = current().destinationGroupName
    suspend fun setDestinationGroupName(name: String)
}

/** Device identity and credentials. Backed by Android Keystore encryption. */
interface DeviceIdentityRepository {
    /** Stable unique id such as `OKB-ANDROID-A82F19`, generated on first use. */
    fun deviceId(): String
    fun deviceToken(): String?
    fun setDeviceToken(token: String?)
    fun hasDeviceToken(): Boolean = !deviceToken().isNullOrEmpty()
}

interface LogRepository {
    fun observeRecent(limit: Int): Flow<List<BridgeLogEntry>>
    suspend fun clear()
}

/** Abstraction over WorkManager so the capture pipeline can be tested without it. */
interface UploadScheduler {
    /** Queues a (network-constrained) one-time message upload. */
    fun requestUpload(trigger: SyncTrigger = SyncTrigger.IMMEDIATE)
    /** Queues a (network-constrained) one-time media upload. */
    fun requestMediaUpload(trigger: SyncTrigger = SyncTrigger.IMMEDIATE)
    /** Ensures the 15-minute reconciliation / health-check work is scheduled. */
    fun ensurePeriodicReconciliation()
}

/** Local queue of consolidated report deliveries (Room). */
interface ConsolidatedDeliveryRepository {
    suspend fun get(id: String): ConsolidatedReportDelivery?
    suspend fun save(delivery: ConsolidatedReportDelivery)
    fun observeRecent(limit: Int = 20): Flow<List<ConsolidatedReportDelivery>>
    fun observeCounts(): Flow<ConsolidatedDeliveryCounts>
    suspend fun withPendingAck(): List<ConsolidatedReportDelivery>
    suspend fun delete(id: String)
    suspend fun deleteFinishedBefore(before: Long): Int
}

/** Local queue of automatic consolidated TEXT reports. */
interface TextDeliveryRepository {
    suspend fun get(id: String): TextReportDelivery?
    suspend fun getByDedupeKey(key: String): TextReportDelivery?
    /** False when the dedupe key is already used by another delivery (nothing is stored). */
    suspend fun insert(delivery: TextReportDelivery): Boolean
    suspend fun update(delivery: TextReportDelivery)
    fun observeRecent(limit: Int = 10): Flow<List<TextReportDelivery>>
    suspend fun withPendingResult(): List<TextReportDelivery>
    /** Scheduled or sending. */
    suspend fun open(): List<TextReportDelivery>
    /** Removes a SENT or FAILED row; false when it is still being sent (or unknown). */
    suspend fun deleteFinished(id: String): Boolean
    suspend fun deleteFinishedBefore(before: Long): Int
}

package com.okb.whatsappbridge.domain.repository

import com.okb.whatsappbridge.domain.model.BridgeLogEntry
import com.okb.whatsappbridge.domain.model.BridgeMessage
import com.okb.whatsappbridge.domain.model.BridgeSettings
import com.okb.whatsappbridge.domain.model.MediaStatus
import com.okb.whatsappbridge.domain.model.MediaType
import com.okb.whatsappbridge.domain.model.MonitoredGroup
import com.okb.whatsappbridge.domain.model.QueueCounts
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
    suspend fun saveCaptured(message: NewCapturedMessage): SaveResult
    fun observeRecent(status: UploadStatus?, limit: Int): Flow<List<BridgeMessage>>
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
    /** Queues a (network-constrained) one-time upload. */
    fun requestUpload(trigger: SyncTrigger = SyncTrigger.IMMEDIATE)
    /** Ensures the 15-minute reconciliation / health-check work is scheduled. */
    fun ensurePeriodicReconciliation()
}

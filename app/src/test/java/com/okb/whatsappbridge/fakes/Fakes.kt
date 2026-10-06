package com.okb.whatsappbridge.fakes

import com.okb.whatsappbridge.domain.model.BridgeMessage
import com.okb.whatsappbridge.domain.model.BridgeSettings
import com.okb.whatsappbridge.domain.model.MonitoredGroup
import com.okb.whatsappbridge.domain.model.MonitoringState
import com.okb.whatsappbridge.domain.model.QueueCounts
import com.okb.whatsappbridge.domain.model.SystemStatus
import com.okb.whatsappbridge.domain.model.SystemStatusProvider
import com.okb.whatsappbridge.domain.model.UploadStatus
import com.okb.whatsappbridge.domain.repository.GroupRepository
import com.okb.whatsappbridge.domain.repository.MessageRepository
import com.okb.whatsappbridge.domain.repository.NewCapturedMessage
import com.okb.whatsappbridge.domain.repository.SaveOutcome
import com.okb.whatsappbridge.domain.repository.SaveResult
import com.okb.whatsappbridge.domain.repository.SettingsRepository
import com.okb.whatsappbridge.domain.repository.UploadCandidate
import com.okb.whatsappbridge.domain.repository.UploadScheduler
import com.okb.whatsappbridge.domain.usecase.HealthAlertSink
import com.okb.whatsappbridge.domain.usecase.SyncTrigger
import com.okb.whatsappbridge.util.logging.BridgeLogger
import com.okb.whatsappbridge.whatsapp.GroupAllowlist
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow

class RecordingLogger : BridgeLogger {
    val entries = mutableListOf<String>()
    override fun info(tag: String, message: String) { entries += "I/$tag: $message" }
    override fun warn(tag: String, message: String) { entries += "W/$tag: $message" }
    override fun error(tag: String, message: String, throwable: Throwable?) { entries += "E/$tag: $message" }
}

class FakeScheduler : UploadScheduler {
    val requests = mutableListOf<SyncTrigger>()
    val mediaRequests = mutableListOf<SyncTrigger>()
    var periodicEnsured = 0
    override fun requestUpload(trigger: SyncTrigger) { requests += trigger }
    override fun requestMediaUpload(trigger: SyncTrigger) { mediaRequests += trigger }
    override fun ensurePeriodicReconciliation() { periodicEnsured++ }
}

class FakeSettingsRepository(initial: BridgeSettings = BridgeSettings()) : SettingsRepository {
    val state = MutableStateFlow(initial)
    override val settings: Flow<BridgeSettings> = state
    override suspend fun current(): BridgeSettings = state.value
    private fun update(f: (BridgeSettings) -> BridgeSettings) { state.value = f(state.value) }
    override suspend fun setMonitoringEnabled(enabled: Boolean) = update { it.copy(monitoringEnabled = enabled) }
    override suspend fun setSyncPaused(paused: Boolean) = update { it.copy(syncPaused = paused) }
    override suspend fun setBackendUrl(url: String) = update { it.copy(backendUrl = url) }
    override suspend fun setDeviceName(name: String) = update { it.copy(deviceName = name) }
    override suspend fun setDeviceRegisteredAt(at: Long?) = update { it.copy(deviceRegisteredAt = at) }
    override suspend fun recordNotificationReceived(at: Long) = update { it.copy(lastNotificationAt = at) }
    override suspend fun recordProcessed(at: Long) = update { it.copy(lastProcessedAt = at) }
    override suspend fun recordUploadSuccess(at: Long) = update { it.copy(lastUploadSuccessAt = at) }
    override suspend fun recordUploadFailure(at: Long, error: String) = update { it.copy(lastUploadFailureAt = at, lastUploadError = error) }
    override suspend fun recordListenerConnected(at: Long) = update { it.copy(lastListenerConnectedAt = at) }
    override suspend fun recordListenerDisconnected(at: Long) = update { it.copy(lastListenerDisconnectedAt = at) }
    override suspend fun recordBackendCheck(at: Long, ok: Boolean, message: String) =
        update { it.copy(lastBackendCheckAt = at, lastBackendCheckOk = ok, lastBackendCheckMessage = message) }
    override suspend fun recordHealthCheck(at: Long) = update { it.copy(lastHealthCheckAt = at) }
    override suspend fun recordBoot(at: Long) = update { it.copy(lastBootAt = at) }
    override suspend fun setCaptureMedia(enabled: Boolean) = update { it.copy(captureMedia = enabled) }
    override suspend fun setDeleteLocalAfterUpload(enabled: Boolean) = update { it.copy(deleteLocalAfterUpload = enabled) }
    override suspend fun recordMediaCapture(at: Long) = update { it.copy(lastMediaCaptureAt = at) }
    override suspend fun recordMediaUploadSuccess(at: Long) = update { it.copy(lastMediaUploadAt = at) }
    override suspend fun recordMediaFailure(at: Long, error: String) = update { it.copy(lastMediaError = error) }
    override suspend fun setSourceGroupName(name: String) = update { it.copy(sourceGroupName = name.trim()) }
    override suspend fun setDestinationGroupName(name: String) = update { it.copy(destinationGroupName = name.trim()) }
}

class FakeGroupRepository(vararg authorized: String) : GroupRepository {
    val groups = mutableListOf<MonitoredGroup>()
    private var nextId = 1L

    init {
        authorized.forEach { groups += MonitoredGroup(nextId++, it, true, false, 0, null) }
    }

    override fun observeGroups(): Flow<List<MonitoredGroup>> = MutableStateFlow(groups.toList())
    override suspend fun authorizedGroupNames(): List<String> = groups.filter { it.authorized }.map { it.name }
    override suspend fun addAuthorizedGroup(name: String, at: Long): Boolean {
        groups += MonitoredGroup(nextId++, name, true, false, at, null)
        return true
    }
    override suspend fun setAuthorized(id: Long, authorized: Boolean) {
        val i = groups.indexOfFirst { it.id == id }
        if (i >= 0) groups[i] = groups[i].copy(authorized = authorized)
    }
    override suspend fun delete(id: Long) { groups.removeAll { it.id == id } }
    override suspend fun recordSeen(name: String, at: Long) {
        val i = groups.indexOfFirst { GroupAllowlist.normalize(it.name) == GroupAllowlist.normalize(name) }
        if (i >= 0) groups[i] = groups[i].copy(lastSeenAt = at)
        else groups += MonitoredGroup(nextId++, name, false, true, at, at)
    }
}

/** Minimal in-memory message store used by pipeline tests that do not need Room. */
class FakeMessageRepository : MessageRepository {
    val saved = mutableListOf<NewCapturedMessage>()
    private var seq = 0
    override suspend fun saveCaptured(message: NewCapturedMessage): SaveOutcome {
        if (saved.any { it.fingerprint == message.fingerprint }) return SaveOutcome(SaveResult.DUPLICATE, null)
        saved += message
        return SaveOutcome(SaveResult.INSERTED, "msg-${++seq}")
    }
    var uploadable = 0
    var repaired = 0
    override fun observeRecent(status: UploadStatus?, limit: Int): Flow<List<BridgeMessage>> = emptyFlow()
    override fun observeById(id: String): Flow<BridgeMessage?> = emptyFlow()
    override fun observeQueueCounts(): Flow<QueueCounts> = emptyFlow()
    override fun observeCapturedSince(since: Long): Flow<Int> = emptyFlow()
    override fun observeLatestMessageTimestamp(): Flow<Long?> = emptyFlow()
    override suspend fun nextUploadBatch(excludedIds: Set<String>, includeFailed: Boolean, limit: Int): List<UploadCandidate> = emptyList()
    override suspend fun countUploadable(includeFailed: Boolean): Int = uploadable
    override suspend fun markUploading(id: String) = Unit
    override suspend fun markUploaded(id: String, serverId: String?, at: Long) = Unit
    override suspend fun markRetrying(id: String, error: String, httpCode: Int?, at: Long) = Unit
    override suspend fun markFailed(id: String, error: String, httpCode: Int?, at: Long) = Unit
    override suspend fun recoverInterruptedUploads(): Int = 0
    override suspend fun resetFailedToPending(): Int = 0
    override suspend fun repairQueue(now: Long) { repaired++ }
    override suspend fun deleteUploadedBefore(cutoff: Long): Int = 0
    // Recycle Bin behaviour is tested against the real Room repository (RecycleBinTest).
    override suspend fun moveToRecycleBin(ids: Collection<String>, at: Long): Int = 0
    override suspend fun restoreFromRecycleBin(ids: Collection<String>): Int = 0
    override fun observeRecycleBin(limit: Int): Flow<List<BridgeMessage>> = emptyFlow()
    override fun observeRecycleBinCount(): Flow<Int> = emptyFlow()
    override suspend fun recycleBinIdsDeletedBefore(cutoff: Long): List<String> = emptyList()
    override suspend fun purgeFromRecycleBin(ids: Collection<String>, at: Long): Int = 0
    override suspend fun deleteTombstonesBefore(cutoff: Long): Int = 0
    override suspend fun isDatabaseHealthy(): Boolean = true
}

class FakeSystemStatus(
    var accessGranted: Boolean = true,
    var connected: Boolean = true,
) : SystemStatusProvider {
    var rebindRequests = 0
    override fun isNotificationAccessGranted() = accessGranted
    override fun isListenerConnected() = connected
    override fun requestListenerRebind(): Boolean { rebindRequests++; return true }
    override fun snapshot() = SystemStatus(
        notificationAccessGranted = accessGranted,
        listenerConnected = connected,
        installedWhatsAppPackages = listOf("com.whatsapp"),
        ignoringBatteryOptimizations = true,
        backgroundRestricted = false,
        standbyBucket = "Active",
        powerSaveMode = false,
        appNotificationsEnabled = true,
        networkAvailable = true,
        sdkInt = 34,
        manufacturer = "Test",
        model = "Phone",
    )
}

class FakeAlerts : HealthAlertSink {
    val raised = mutableListOf<MonitoringState>()
    var cleared = 0
    override fun monitoringMayBeInactive(state: MonitoringState) { raised += state }
    override fun clear() { cleared++ }
}

class FakeConsolidatedDeliveryRepository : com.okb.whatsappbridge.domain.repository.ConsolidatedDeliveryRepository {
    val rows = linkedMapOf<String, com.okb.whatsappbridge.domain.model.ConsolidatedReportDelivery>()
    private val flow = MutableStateFlow<List<com.okb.whatsappbridge.domain.model.ConsolidatedReportDelivery>>(emptyList())
    override suspend fun get(id: String) = rows[id]
    override suspend fun save(delivery: com.okb.whatsappbridge.domain.model.ConsolidatedReportDelivery) {
        rows[delivery.id] = delivery
        flow.value = rows.values.toList()
    }
    override fun observeRecent(limit: Int) = flow
    override fun observeCounts() = MutableStateFlow(com.okb.whatsappbridge.domain.model.ConsolidatedDeliveryCounts())
    override suspend fun withPendingAck() = rows.values.filter { it.pendingAck != null }
    override suspend fun deleteFinishedBefore(before: Long) = 0
}

class FakeTextDeliveryRepository : com.okb.whatsappbridge.domain.repository.TextDeliveryRepository {
    val rows = linkedMapOf<String, com.okb.whatsappbridge.domain.model.TextReportDelivery>()
    private val flow = MutableStateFlow<List<com.okb.whatsappbridge.domain.model.TextReportDelivery>>(emptyList())
    override suspend fun get(id: String) = rows[id]
    override suspend fun getByDedupeKey(key: String) = rows.values.firstOrNull { it.dedupeKey == key }
    override suspend fun insert(delivery: com.okb.whatsappbridge.domain.model.TextReportDelivery): Boolean {
        if (rows.containsKey(delivery.id) || rows.values.any { it.dedupeKey == delivery.dedupeKey }) return false
        rows[delivery.id] = delivery
        flow.value = rows.values.toList()
        return true
    }
    override suspend fun update(delivery: com.okb.whatsappbridge.domain.model.TextReportDelivery) {
        rows[delivery.id] = delivery
        flow.value = rows.values.toList()
    }
    override fun observeRecent(limit: Int) = flow
    override suspend fun withPendingResult() = rows.values.filter { it.pendingResult != null }
    override suspend fun deleteFinishedBefore(before: Long) = 0
}

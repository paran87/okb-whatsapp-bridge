package com.okb.whatsappbridge.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.okb.whatsappbridge.AppContainer
import com.okb.whatsappbridge.automation.AutomationReadinessProbe
import com.okb.whatsappbridge.domain.model.AutomationReadiness
import com.okb.whatsappbridge.domain.model.BridgeSettings
import com.okb.whatsappbridge.domain.model.ConsolidatedDeliveryCounts
import com.okb.whatsappbridge.domain.model.ConsolidatedReportDelivery
import com.okb.whatsappbridge.domain.model.MonitoringState
import com.okb.whatsappbridge.domain.model.MediaCounts
import com.okb.whatsappbridge.domain.model.QueueCounts
import com.okb.whatsappbridge.domain.model.SystemStatus
import com.okb.whatsappbridge.domain.model.TextReportDelivery
import com.okb.whatsappbridge.domain.usecase.ListenerRecoveryOutcome
import com.okb.whatsappbridge.domain.usecase.SyncTrigger
import com.okb.whatsappbridge.service.ListenerConnectionState
import com.okb.whatsappbridge.ui.components.Formatters
import com.okb.whatsappbridge.util.security.Redactor
import com.okb.whatsappbridge.worker.ReconciliationState
import com.okb.whatsappbridge.worker.SyncWorkerState
import com.okb.whatsappbridge.whatsapp.ReportGroups
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Values read on demand (Keystore, PackageManager, PowerManager…) rather than observed. */
data class PolledState(
    val system: SystemStatus? = null,
    val databaseHealthy: Boolean? = null,
    val deviceId: String = "",
    val hasToken: Boolean = false,
    val tokenPreview: String = "Not set",
    val dayStart: Long = Formatters.startOfToday(),
    val mediaStorageUsedBytes: Long = 0,
    val mediaLargestQueuedBytes: Long = 0,
    val mediaUsableSpaceBytes: Long? = null,
    val mediaAcquisitionSupported: Boolean = true,
    val automation: AutomationReadiness = AutomationReadiness(),
)

data class StatusUiState(
    val settings: BridgeSettings = BridgeSettings(),
    val polled: PolledState = PolledState(),
    val listenerConnected: Boolean = false,
    val counts: QueueCounts = QueueCounts(),
    val capturedToday: Int = 0,
    val latestMessageAt: Long? = null,
    val uploadWorker: SyncWorkerState = SyncWorkerState.IDLE,
    val reconciliation: ReconciliationState = ReconciliationState(false, null),
    val authorizedGroups: Int = 0,
    val mediaCounts: MediaCounts = MediaCounts(),
    val mediaCapturedToday: Int = 0,
    val lastMediaCaptureAt: Long? = null,
    val lastMediaUploadAt: Long? = null,
    val mediaUploadWorker: SyncWorkerState = SyncWorkerState.IDLE,
    val deliveries: List<ConsolidatedReportDelivery> = emptyList(),
    val deliveryCounts: ConsolidatedDeliveryCounts = ConsolidatedDeliveryCounts(),
    val textDeliveries: List<TextReportDelivery> = emptyList(),
    val loaded: Boolean = false,
) {
    val system: SystemStatus? get() = polled.system
    val monitoringState: MonitoringState
        get() = MonitoringState.from(settings.monitoringEnabled, system?.notificationAccessGranted == true, listenerConnected)
}

/**
 * Activity-scoped state shared by Dashboard, Sync, Settings and Diagnostics.
 * Every value comes from Room, WorkManager or Android system services – nothing is simulated.
 */
private data class Derived(val latest: Long?, val worker: SyncWorkerState, val reconcile: ReconciliationState, val groups: Int)
private data class MediaFlowState(
    val counts: MediaCounts, val today: Int, val lastCapture: Long?, val lastUpload: Long?, val worker: SyncWorkerState,
)

@OptIn(ExperimentalCoroutinesApi::class)
class StatusViewModel(private val container: AppContainer) : ViewModel() {

    private val polled = MutableStateFlow(PolledState())
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val events: SharedFlow<String> = _events

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    private val todayCount = polled.map { it.dayStart }
        .flatMapLatest { container.messageRepository.observeCapturedSince(it) }

    private val base = combine(
        container.settingsRepository.settings,
        polled,
        ListenerConnectionState.connected,
        container.messageRepository.observeQueueCounts(),
        todayCount,
    ) { settings, polledState, connected, counts, today ->
        StatusUiState(
            settings = settings,
            polled = polledState,
            listenerConnected = connected,
            counts = counts,
            capturedToday = today,
            loaded = true,
        )
    }

    private val derived = combine(
        container.messageRepository.observeLatestMessageTimestamp(),
        container.uploadScheduler.observeUploadState(),
        container.uploadScheduler.observeReconciliation(),
        container.groupRepository.observeGroups().map { groups -> groups.count { it.authorized } },
    ) { latest, worker, reconcile, groups -> Derived(latest, worker, reconcile, groups) }

    private val mediaToday = polled.map { it.dayStart }
        .flatMapLatest { container.mediaRepository.observeCapturedSince(it) }

    private val mediaFlow = combine(
        container.mediaRepository.observeMediaCounts(),
        mediaToday,
        container.mediaRepository.observeLastCaptureAt(),
        container.mediaRepository.observeLastUploadAt(),
        container.uploadScheduler.observeMediaUploadState(),
    ) { counts, today, lastCapture, lastUpload, worker -> MediaFlowState(counts, today, lastCapture, lastUpload, worker) }

    private val deliveryFlow = combine(
        container.consolidatedDeliveries.observeRecent(),
        container.consolidatedDeliveries.observeCounts(),
        container.textDeliveries.observeRecent(),
    ) { list, counts, text -> Triple(list, counts, text) }

    val state: StateFlow<StatusUiState> = combine(base, derived, mediaFlow, deliveryFlow) { s, d, m, deliveries ->
        s.copy(
            latestMessageAt = d.latest,
            uploadWorker = d.worker,
            reconciliation = d.reconcile,
            authorizedGroups = d.groups,
            mediaCounts = m.counts,
            mediaCapturedToday = m.today,
            lastMediaCaptureAt = m.lastCapture,
            lastMediaUploadAt = m.lastUpload,
            mediaUploadWorker = m.worker,
            deliveries = deliveries.first,
            deliveryCounts = deliveries.second,
            textDeliveries = deliveries.third,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatusUiState())

    init {
        refresh()
    }

    /** Re-reads system state; called on every resume (e.g. returning from Android Settings). */
    fun refresh() {
        viewModelScope.launch {
            polled.value = withContext(Dispatchers.IO) {
                val identity = container.identity
                val token = runCatching { identity.deviceToken() }.getOrNull()
                PolledState(
                    system = container.systemStatus.snapshot(),
                    databaseHealthy = container.messageRepository.isDatabaseHealthy(),
                    deviceId = runCatching { identity.deviceId() }.getOrDefault("UNAVAILABLE"),
                    hasToken = !token.isNullOrEmpty(),
                    tokenPreview = Redactor.mask(token),
                    dayStart = Formatters.startOfToday(),
                    mediaStorageUsedBytes = runCatching { container.mediaRepository.storageUsedBytes() }.getOrDefault(0),
                    mediaLargestQueuedBytes = runCatching { container.mediaRepository.largestQueuedBytes() }.getOrDefault(0),
                    mediaUsableSpaceBytes = runCatching { container.mediaRepository.usableSpaceBytes() }.getOrNull(),
                    mediaAcquisitionSupported = runCatching { container.mediaContentAccess.isMediaAcquisitionSupported() }.getOrDefault(true),
                    automation = runCatching { AutomationReadinessProbe.read(container.appContext) }.getOrDefault(AutomationReadiness()),
                )
            }
        }
    }

    fun setMonitoringEnabled(enabled: Boolean) {
        viewModelScope.launch {
            container.settingsRepository.setMonitoringEnabled(enabled)
            container.logger.info("Operator", if (enabled) "Background monitoring enabled" else "Background monitoring paused")
            container.ensureMonitoring(if (enabled) "monitoring enabled" else "monitoring paused")
            refresh()
        }
    }

    /** Shows a one-off message in the app's snackbar. */
    fun notify(message: String) {
        _events.tryEmit(message)
    }

    /** Operator pressed "Reconnect now". */
    fun reconnectListener() {
        viewModelScope.launch {
            val outcome = container.listenerRecovery("operator request")
            _events.emit(
                when (outcome) {
                    ListenerRecoveryOutcome.NO_ACCESS -> "Notification Access is not granted – enable it in Android Settings"
                    ListenerRecoveryOutcome.ALREADY_CONNECTED, ListenerRecoveryOutcome.RECONNECTED -> "Notification listener connected"
                    ListenerRecoveryOutcome.STILL_DISCONNECTED ->
                        "Android has not reconnected yet. Switch OKB Bridge off and on in Notification Access."
                },
            )
            refresh()
        }
    }

    fun setSyncPaused(paused: Boolean) {
        viewModelScope.launch {
            container.settingsRepository.setSyncPaused(paused)
            container.logger.info("Operator", if (paused) "Synchronization paused" else "Synchronization resumed")
            if (!paused) {
                container.uploadScheduler.requestUpload(SyncTrigger.MANUAL)
                container.uploadScheduler.requestMediaUpload(SyncTrigger.MANUAL)
            }
        }
    }

    fun syncNow() {
        container.uploadScheduler.requestUpload(SyncTrigger.MANUAL)
        _events.tryEmit("Sync requested – it runs as soon as a network connection is available")
    }

    fun retryFailed() {
        viewModelScope.launch {
            val count = container.messageRepository.resetFailedToPending()
            container.uploadScheduler.requestUpload(SyncTrigger.MANUAL)
            _events.emit(if (count == 0) "No failed messages to retry" else "$count failed message(s) queued for retry")
        }
    }

    fun syncMediaNow() {
        container.uploadScheduler.requestMediaUpload(SyncTrigger.MANUAL)
        _events.tryEmit("Media sync requested – it runs as soon as a network connection is available")
    }

    fun retryFailedMedia() {
        viewModelScope.launch {
            val count = container.mediaRepository.resetFailedToPending(System.currentTimeMillis())
            container.uploadScheduler.requestMediaUpload(SyncTrigger.MANUAL)
            _events.emit(if (count == 0) "No failed media to retry" else "$count failed media file(s) queued for retry")
        }
    }

    fun setCaptureMedia(enabled: Boolean) {
        viewModelScope.launch {
            container.settingsRepository.setCaptureMedia(enabled)
            container.logger.info("Operator", if (enabled) "Media capture enabled" else "Media capture disabled")
        }
    }

    fun setDeleteLocalAfterUpload(enabled: Boolean) {
        viewModelScope.launch { container.settingsRepository.setDeleteLocalAfterUpload(enabled) }
    }

    fun testBackend() = runBusy {
        val result = container.backend.checkHealth()
        _events.emit(if (result.ok) "Backend: ${result.message}" else "Backend check failed: ${result.message}")
    }

    fun registerDevice() = runBusy {
        val result = container.backend.registerDevice()
        _events.emit(result.message)
    }

    fun runHealthCheck() = runBusy {
        val report = container.healthCheck(checkBackend = true)
        _events.emit("Health check complete: monitoring ${report.monitoringState.name.lowercase().replace('_', ' ')}")
        refresh()
    }

    /**
     * Saves backend configuration. [token] = null keeps the stored token; an empty string removes it.
     * Previously FAILED messages are re-queued because the failure may have been caused by the old config.
     */
    fun saveBackendConfig(url: String, token: String?) = runBusy {
        val trimmed = url.trim()
        if (trimmed.isNotEmpty()) {
            val parsed = trimmed.toHttpUrlOrNull()
            if (parsed == null) {
                _events.emit("Invalid URL. Use e.g. https://okb.example.org")
                return@runBusy
            }
        }
        container.settingsRepository.setBackendUrl(trimmed.trimEnd('/'))
        if (token != null) withContext(Dispatchers.IO) { container.identity.setDeviceToken(token) }
        container.logger.info("Operator", "Backend configuration updated (token ${if (token == null) "unchanged" else "updated"})")
        container.messageRepository.resetFailedToPending()
        refresh()
        if (trimmed.isEmpty()) {
            _events.emit("Backend URL cleared")
            return@runBusy
        }
        container.uploadScheduler.requestUpload(SyncTrigger.MANUAL)
        container.uploadScheduler.requestMediaUpload(SyncTrigger.MANUAL)
        val health = container.backend.checkHealth()
        _events.emit(if (health.ok) "Saved. Backend ${health.message}" else "Saved, but backend check failed: ${health.message}")
    }

    /** Saves the two WhatsApp report groups (stored separately) and tells the backend when it is configured. */
    fun saveReportGroups(source: String, destination: String) = runBusy {
        ReportGroups.validate(source, destination)?.let {
            _events.emit(it)
            return@runBusy
        }
        container.settingsRepository.setSourceGroupName(source)
        container.settingsRepository.setDestinationGroupName(destination)
        container.logger.info(
            "Groups",
            "Source group ${if (source.isBlank()) "cleared (Groups allowlist used)" else "configured: \"${source.trim()}\""}; " +
                "destination group ${if (destination.isBlank()) "cleared" else "configured: \"${destination.trim()}\""}",
        )
        val current = container.settingsRepository.current()
        if (current.backendConfigured) {
            val result = container.backend.registerDevice()
            _events.emit(if (result.ok) "Groups saved and sent to the backend" else "Groups saved on the phone; backend not updated: ${result.message}")
        } else {
            _events.emit("Groups saved")
        }
    }

    /** "Check now": asks the backend for consolidated reports waiting for this phone (TEXT is sent right away). */
    fun checkReportsNow() = runBusy {
        val result = container.consolidatedReports()
        _events.emit(
            when {
                result.error != null -> "Report check failed: ${result.error}"
                result.textSent > 0 || result.textFailed > 0 ->
                    "Text reports: ${result.textSent} sent, ${result.textFailed} not sent" +
                        if (result.newlyReady > 0) " · ${result.newlyReady} PDF(s) ready" else ""
                result.warning != null -> result.warning
                result.newlyReady > 0 -> "${result.newlyReady} consolidated PDF(s) ready to send"
                else -> "No new consolidated reports"
            },
        )
        refresh()
    }

    /** Dashboard "Remove" on a finished automatic text report card (the phone's copy only). */
    fun removeTextReport(id: String) {
        viewModelScope.launch {
            _events.emit(if (container.textDelivery.remove(id)) "Removed" else "Still being sent; it cannot be removed yet")
        }
    }

    fun confirmReportSent(id: String) {
        viewModelScope.launch {
            container.consolidatedReports.confirmSent(id)
            _events.emit("Marked as sent")
        }
    }

    fun markReportNotSent(id: String) {
        viewModelScope.launch {
            container.consolidatedReports.markNotSent(id)
            _events.emit("Kept as ready to send")
        }
    }

    fun saveDeviceName(name: String) {
        viewModelScope.launch {
            container.settingsRepository.setDeviceName(name)
            _events.emit("Device name saved")
        }
    }

    private fun runBusy(block: suspend () -> Unit) {
        viewModelScope.launch {
            _busy.value = true
            try {
                block()
            } finally {
                _busy.value = false
            }
        }
    }
}

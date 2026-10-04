package com.okb.whatsappbridge.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.okb.whatsappbridge.AppContainer
import com.okb.whatsappbridge.domain.model.BridgeSettings
import com.okb.whatsappbridge.domain.model.MonitoringState
import com.okb.whatsappbridge.domain.model.QueueCounts
import com.okb.whatsappbridge.domain.model.SystemStatus
import com.okb.whatsappbridge.domain.usecase.SyncTrigger
import com.okb.whatsappbridge.service.ListenerConnectionState
import com.okb.whatsappbridge.ui.components.Formatters
import com.okb.whatsappbridge.util.security.Redactor
import com.okb.whatsappbridge.worker.ReconciliationState
import com.okb.whatsappbridge.worker.SyncWorkerState
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

    val state: StateFlow<StatusUiState> = combine(
        base,
        container.messageRepository.observeLatestMessageTimestamp(),
        container.uploadScheduler.observeUploadState(),
        container.uploadScheduler.observeReconciliation(),
        container.groupRepository.observeGroups().map { groups -> groups.count { it.authorized } },
    ) { s, latest, worker, reconcile, groups ->
        s.copy(latestMessageAt = latest, uploadWorker = worker, reconciliation = reconcile, authorizedGroups = groups)
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
                )
            }
        }
    }

    fun setMonitoringEnabled(enabled: Boolean) {
        viewModelScope.launch {
            container.settingsRepository.setMonitoringEnabled(enabled)
            container.logger.info("Operator", if (enabled) "Background monitoring enabled" else "Background monitoring paused")
            if (enabled && container.systemStatus.isNotificationAccessGranted() && !container.systemStatus.isListenerConnected()) {
                container.systemStatus.requestListenerRebind()
            }
            refresh()
        }
    }

    fun setSyncPaused(paused: Boolean) {
        viewModelScope.launch {
            container.settingsRepository.setSyncPaused(paused)
            container.logger.info("Operator", if (paused) "Synchronization paused" else "Synchronization resumed")
            if (!paused) container.uploadScheduler.requestUpload(SyncTrigger.MANUAL)
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
        val health = container.backend.checkHealth()
        _events.emit(if (health.ok) "Saved. Backend ${health.message}" else "Saved, but backend check failed: ${health.message}")
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

package com.okb.whatsappbridge.domain.usecase

import com.okb.whatsappbridge.domain.model.MonitoringState
import com.okb.whatsappbridge.domain.model.SystemStatusProvider
import com.okb.whatsappbridge.domain.repository.MediaRepository
import com.okb.whatsappbridge.domain.repository.MessageRepository
import com.okb.whatsappbridge.domain.repository.SettingsRepository
import com.okb.whatsappbridge.domain.repository.UploadScheduler
import com.okb.whatsappbridge.util.logging.BridgeLogger

/** Receives alerts raised by the health check (e.g. a system notification for the operator). */
interface HealthAlertSink {
    fun monitoringMayBeInactive(state: MonitoringState)
    fun clear()
}

data class HealthCheckReport(
    val monitoringState: MonitoringState,
    val rebindRequested: Boolean,
    val uploadable: Int,
    val uploadScheduled: Boolean,
    val mediaUploadable: Int,
    val mediaUploadScheduled: Boolean,
    val backend: BackendCheckResult?,
)

/**
 * Periodic (15 min) watchdog. It only *verifies* state and uses supported APIs:
 * it never restarts itself in a loop and never works around Android restrictions.
 *
 *  - Notification listener: if access is granted but Android has not bound the listener, ask for a
 *    rebind; if access was revoked, alert the operator.
 *  - Upload queue: repair, then schedule an upload if anything (including FAILED) is pending.
 *  - Backend connectivity: record a health check.
 *  - Housekeeping: prune uploaded messages older than the retention period.
 */
class HealthCheckUseCase(
    private val settings: SettingsRepository,
    private val messages: MessageRepository,
    private val system: SystemStatusProvider,
    private val scheduler: UploadScheduler,
    private val backend: BackendUseCases,
    private val alerts: HealthAlertSink,
    private val logger: BridgeLogger,
    private val media: MediaRepository? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val retentionMillis: Long = DEFAULT_RETENTION_MILLIS,
) {

    suspend operator fun invoke(checkBackend: Boolean = true): HealthCheckReport {
        val now = clock()
        settings.recordHealthCheck(now)
        val current = settings.current()

        val accessGranted = system.isNotificationAccessGranted()
        val state = MonitoringState.from(current.monitoringEnabled, accessGranted, system.isListenerConnected())
        var rebindRequested = false
        when (state) {
            MonitoringState.LISTENER_DISCONNECTED -> {
                rebindRequested = system.requestListenerRebind()
                logger.warn(TAG, "Listener not connected although access is granted; rebind requested=$rebindRequested")
                alerts.monitoringMayBeInactive(state)
            }
            MonitoringState.NO_ACCESS -> {
                logger.warn(TAG, "Notification Access is not granted; background monitoring is inactive")
                alerts.monitoringMayBeInactive(state)
            }
            MonitoringState.ACTIVE, MonitoringState.PAUSED -> alerts.clear()
        }

        messages.repairQueue(now)
        val uploadable = messages.countUploadable(includeFailed = true)
        val schedule = uploadable > 0 && !current.syncPaused && current.backendConfigured
        if (schedule) scheduler.requestUpload(SyncTrigger.RECONCILE)

        // Media queue: repair and schedule, mirroring the message queue.
        var mediaUploadable = 0
        var mediaSchedule = false
        media?.let { m ->
            m.repairQueue(now)
            mediaUploadable = m.countUploadable(includeFailed = true)
            mediaSchedule = mediaUploadable > 0 && !current.syncPaused && current.backendConfigured
            if (mediaSchedule) scheduler.requestMediaUpload(SyncTrigger.RECONCILE)
        }

        val backendResult = if (checkBackend && current.backendConfigured) backend.checkHealth() else null
        val removed = messages.deleteUploadedBefore(now - retentionMillis)
        if (removed > 0) logger.info(TAG, "Retention: removed $removed uploaded messages older than 30 days")
        // Local media cleanup: immediately after upload when the operator opted in, else after retention.
        media?.let { m ->
            val cutoff = if (current.deleteLocalAfterUpload) now else now - retentionMillis
            val cleaned = m.cleanupUploadedLocalFiles(cutoff, now)
            if (cleaned > 0) logger.info(TAG, "Media cleanup: removed $cleaned local file(s) already uploaded")
        }

        return HealthCheckReport(state, rebindRequested, uploadable, schedule, mediaUploadable, mediaSchedule, backendResult)
    }

    companion object {
        private const val TAG = "Health"
        const val DEFAULT_RETENTION_MILLIS = 30L * 24 * 60 * 60 * 1000
    }
}

package com.okb.whatsappbridge

import android.app.Application
import android.os.Build
import java.io.File
import androidx.work.WorkManager
import com.okb.whatsappbridge.data.local.database.BridgeDatabase
import com.okb.whatsappbridge.data.remote.api.OkHttpBridgeApi
import com.okb.whatsappbridge.data.remote.api.OkHttpMediaUploader
import com.okb.whatsappbridge.data.repository.RoomGroupRepository
import com.okb.whatsappbridge.data.repository.RoomLogRepository
import com.okb.whatsappbridge.data.repository.RoomMediaRepository
import com.okb.whatsappbridge.data.repository.RoomMessageRepository
import com.okb.whatsappbridge.data.repository.RoomSettingsRepository
import com.okb.whatsappbridge.data.repository.SecureDeviceIdentityRepository
import com.okb.whatsappbridge.domain.usecase.AcquireMediaUseCase
import com.okb.whatsappbridge.domain.usecase.BackendUseCases
import com.okb.whatsappbridge.domain.usecase.ConsolidatedReportCheckUseCase
import com.okb.whatsappbridge.domain.usecase.DeviceInfo
import com.okb.whatsappbridge.domain.usecase.HealthCheckUseCase
import com.okb.whatsappbridge.domain.usecase.ListenerRecoveryUseCase
import com.okb.whatsappbridge.domain.usecase.ProcessNotificationUseCase
import com.okb.whatsappbridge.domain.usecase.RecycleBinUseCase
import com.okb.whatsappbridge.domain.usecase.SyncMediaUseCase
import com.okb.whatsappbridge.domain.usecase.SyncMessagesUseCase
import com.okb.whatsappbridge.service.AndroidConsolidatedReportNotifier
import com.okb.whatsappbridge.service.AndroidHealthAlertNotifier
import com.okb.whatsappbridge.service.MonitoringForegroundService
import com.okb.whatsappbridge.media.AndroidMediaContentAccess
import com.okb.whatsappbridge.service.NotificationProcessor
import com.okb.whatsappbridge.util.logging.BridgeLogger
import com.okb.whatsappbridge.util.logging.RoomBridgeLogger
import com.okb.whatsappbridge.util.security.KeystoreSecretStore
import com.okb.whatsappbridge.util.media.FileSystemMediaFileStore
import com.okb.whatsappbridge.util.system.AndroidSystemStatusProvider
import com.okb.whatsappbridge.worker.WorkManagerUploadScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Manual dependency container, owned by the Application so it is available to the listener service,
 * workers and boot receiver without any Activity.
 */
class AppContainer(private val app: Application) {

    /** Process-wide scope for short, bounded background tasks (never a keep-alive loop). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: BridgeDatabase by lazy { BridgeDatabase.create(app) }
    val identity by lazy { SecureDeviceIdentityRepository(KeystoreSecretStore(app)) }
    val logger: BridgeLogger by lazy { RoomBridgeLogger(database.eventLogDao(), appScope) }

    val settingsRepository by lazy { RoomSettingsRepository(database.settingsDao()) }
    val groupRepository by lazy { RoomGroupRepository(database.groupDao()) }
    val messageRepository by lazy { RoomMessageRepository(database.messageDao(), database.settingsDao(), identity) }
    val logRepository by lazy { RoomLogRepository(database.eventLogDao()) }

    // Phase 2 (media). Local copies live in the app sandbox (filesDir/media), never WhatsApp storage.
    val mediaFileStore by lazy { FileSystemMediaFileStore(File(app.filesDir, "media")) }
    val mediaRepository by lazy { RoomMediaRepository(database.mediaDao(), mediaFileStore) }
    val mediaContentAccess by lazy { AndroidMediaContentAccess(app) }
    val mediaUploader by lazy { OkHttpMediaUploader() }

    val api by lazy {
        OkHttpBridgeApi(userAgent = "OKB-WhatsApp-Bridge/${BuildConfig.VERSION_NAME} (Android ${Build.VERSION.RELEASE})")
    }

    val workManager: WorkManager by lazy { WorkManager.getInstance(app) }
    val uploadScheduler by lazy { WorkManagerUploadScheduler(workManager, appScope, logger) }
    val systemStatus by lazy { AndroidSystemStatusProvider(app) }
    val healthAlerts by lazy { AndroidHealthAlertNotifier(app) }

    val deviceInfo = DeviceInfo(
        appVersion = BuildConfig.VERSION_NAME,
        osVersion = "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
        manufacturer = Build.MANUFACTURER.orEmpty(),
        model = Build.MODEL.orEmpty(),
    )

    val acquireMedia by lazy {
        AcquireMediaUseCase(mediaRepository, settingsRepository, mediaContentAccess, mediaFileStore, uploadScheduler, logger)
    }
    val processNotification by lazy {
        ProcessNotificationUseCase(
            settingsRepository, groupRepository, messageRepository, uploadScheduler,
            media = mediaRepository, acquireMedia = acquireMedia, deviceId = { identity.deviceId() },
        )
    }
    val notificationProcessor by lazy { NotificationProcessor(appScope, processNotification, logger) }
    val syncMessages by lazy { SyncMessagesUseCase(settingsRepository, messageRepository, identity, api, logger) }
    val syncMedia by lazy { SyncMediaUseCase(settingsRepository, mediaRepository, identity, api, mediaUploader, logger) }
    val backend by lazy { BackendUseCases(settingsRepository, identity, api, deviceInfo, logger) }
    val recycleBin by lazy { RecycleBinUseCase(messageRepository, mediaRepository, uploadScheduler, logger) }
    val listenerRecovery by lazy { ListenerRecoveryUseCase(systemStatus, logger) }
    // Consolidated WhatsApp reports: PDFs downloaded for the one-tap share live in the app sandbox.
    val consolidatedReportDirectory: File by lazy { File(app.filesDir, "consolidated") }
    val consolidatedReports by lazy {
        ConsolidatedReportCheckUseCase(
            settingsRepository, identity, api, consolidatedReportDirectory, AndroidConsolidatedReportNotifier(app), logger,
        )
    }
    val healthCheck by lazy {
        HealthCheckUseCase(
            settingsRepository, messageRepository, systemStatus, uploadScheduler, backend, healthAlerts, logger,
            media = mediaRepository, recycleBin = recycleBin,
        )
    }

    /**
     * Brings background monitoring back after the process was (re)started: keeps the process alive
     * with the monitoring service while monitoring is ON, and reconnects the notification listener.
     * Safe to call often; every step is idempotent.
     */
    fun ensureMonitoring(reason: String) {
        appScope.launch {
            val enabled = runCatching { settingsRepository.current().monitoringEnabled }.getOrDefault(false)
            if (enabled) {
                if (!MonitoringForegroundService.start(app)) logger.info("Monitor", "Monitoring service not started ($reason); Android does not allow it right now")
            } else {
                MonitoringForegroundService.stop(app)
            }
            runCatching { listenerRecovery(reason) }
        }
    }
}

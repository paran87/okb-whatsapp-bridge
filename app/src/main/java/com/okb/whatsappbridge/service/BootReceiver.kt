package com.okb.whatsappbridge.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.okb.whatsappbridge.OkbBridgeApplication
import com.okb.whatsappbridge.domain.usecase.SyncTrigger
import kotlinx.coroutines.launch

/**
 * Recovery after device restart and after the app is updated.
 *
 * Android itself re-binds the notification listener (if access is still granted) and restores
 * WorkManager jobs; this receiver only records the event, makes sure the periodic health check
 * exists and flushes anything left in the upload queue. It never grants Notification Access.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val container = (context.applicationContext as OkbBridgeApplication).container
        val pending = goAsync()
        container.appScope.launch {
            try {
                val reason = if (intent.action == Intent.ACTION_BOOT_COMPLETED) "device boot" else "app update"
                container.settingsRepository.recordBoot(System.currentTimeMillis())
                container.uploadScheduler.ensurePeriodicReconciliation()
                if (container.messageRepository.countUploadable(includeFailed = false) > 0) {
                    container.uploadScheduler.requestUpload(SyncTrigger.IMMEDIATE)
                }
                if (container.mediaRepository.countUploadable(includeFailed = false) > 0) {
                    container.uploadScheduler.requestMediaUpload(SyncTrigger.IMMEDIATE)
                }
                val access = container.systemStatus.isNotificationAccessGranted()
                // Boot and app-update broadcasts may start the monitoring service; also reconnect the listener.
                container.ensureMonitoring(reason)
                container.logger.info("Boot", "Recovered after $reason; notification access granted=$access")
            } finally {
                pending.finish()
            }
        }
    }
}

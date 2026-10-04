package com.okb.whatsappbridge.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.okb.whatsappbridge.OkbBridgeApplication
import com.okb.whatsappbridge.source.SourcePlatform
import kotlinx.coroutines.launch

/**
 * Primary background monitoring mechanism, for every supported messaging app (WhatsApp and Viber).
 * The class name is kept for compatibility: Android remembers the granted Notification Access per
 * component name, so renaming it would silently revoke access on every installed phone.
 *
 * Android binds this service on its own whenever the user has granted Notification Access – after boot,
 * after an app update, and regardless of whether the UI is open, the screen is off or the phone is
 * locked. There is no polling loop, timer or foreground service: the bridge only does work when
 * Android delivers a notification callback.
 */
class WhatsAppNotificationListenerService : NotificationListenerService() {

    private val container get() = (application as OkbBridgeApplication).container

    override fun onListenerConnected() {
        super.onListenerConnected()
        ListenerConnectionState.set(true)
        val c = container
        c.logger.info(TAG, "Notification listener connected")
        c.healthAlerts.clear()
        c.appScope.launch { c.settingsRepository.recordListenerConnected(System.currentTimeMillis()) }

        // Catch up on WhatsApp/Viber notifications still shown in the shade that were posted while the
        // listener was not bound (e.g. right after a reboot). Duplicates are rejected by fingerprint.
        val active = runCatching { activeNotifications }.getOrNull().orEmpty()
        active.filter { SourcePlatform.isSupported(it.packageName) }.forEach(::handle)
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        ListenerConnectionState.set(false)
        val c = container
        c.logger.warn(TAG, "Notification listener disconnected by Android")
        c.appScope.launch { c.settingsRepository.recordListenerDisconnected(System.currentTimeMillis()) }
        // A single, supported rebind request (not a loop). If access was revoked this is a no-op.
        if (c.systemStatus.isNotificationAccessGranted()) c.systemStatus.requestListenerRebind()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null || !SourcePlatform.isSupported(sbn.packageName)) return
        handle(sbn)
    }

    override fun onDestroy() {
        ListenerConnectionState.set(false)
        super.onDestroy()
    }

    private fun handle(sbn: StatusBarNotification) {
        val snapshot = try {
            NotificationSnapshotExtractor.extract(sbn)
        } catch (e: RuntimeException) {
            container.logger.error(TAG, "Unreadable ${SourcePlatform.displayNameFor(sbn.packageName)} notification layout", e)
            return
        }
        container.notificationProcessor.submit(snapshot)
    }

    private companion object {
        const val TAG = "Listener"
    }
}

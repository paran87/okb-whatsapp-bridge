package com.okb.whatsappbridge.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.okb.whatsappbridge.MainActivity
import com.okb.whatsappbridge.OkbBridgeApplication
import com.okb.whatsappbridge.R
import kotlinx.coroutines.launch

/**
 * Keeps the bridge process alive while Background Monitoring is ON, so that closing the app (swiping it
 * away from Recents) does not kill the notification listener — on many phones, Xiaomi/Redmi/POCO
 * especially, a process without a foreground service is killed as soon as its task is removed.
 *
 * It does no work of its own (no polling, no wake lock): it only holds a low-priority ongoing
 * notification and triggers listener recovery when (re)started. Capture still happens in
 * [WhatsAppNotificationListenerService]. It stops when monitoring is turned OFF.
 *
 * It cannot survive "Force stop": Android deliberately stops every component of a force-stopped app
 * until the user opens it again. When the app is opened, it reconnects automatically.
 */
class MonitoringForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForeground must happen promptly on every start, before any suspend work.
        ensureChannel(this)
        val notification = buildNotification(this)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: RuntimeException) {
            // e.g. ForegroundServiceStartNotAllowedException; the listener still works while the process lives.
            container().logger.warn(TAG, "Could not enter foreground: ${e.javaClass.simpleName}")
            stopSelf()
            return START_NOT_STICKY
        }
        val c = container()
        c.appScope.launch {
            if (!c.settingsRepository.current().monitoringEnabled) {
                stopSelf()
                return@launch
            }
            c.listenerRecovery("monitoring service started")
        }
        // Restarted by Android if the process is killed while monitoring is on.
        return START_STICKY
    }

    private fun container() = (application as OkbBridgeApplication).container

    companion object {
        private const val TAG = "Monitor"
        private const val CHANNEL_ID = "okb_monitoring_status"
        private const val NOTIFICATION_ID = 2001

        /**
         * Starts (or refreshes) the service. Android 12+ only allows this while the app is in the
         * foreground or in exempt situations (boot, app update); elsewhere it fails quietly and the
         * next app start or boot starts it.
         */
        fun start(context: Context): Boolean = try {
            ContextCompat.startForegroundService(context, Intent(context, MonitoringForegroundService::class.java))
            true
        } catch (_: RuntimeException) {
            false
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MonitoringForegroundService::class.java))
        }

        private fun ensureChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.monitoring_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                    description = context.getString(R.string.monitoring_channel_description)
                    setShowBadge(false)
                },
            )
        }

        private fun buildNotification(context: Context) = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_bridge)
            .setContentTitle("OKB Bridge is monitoring")
            .setContentText("Capturing authorized WhatsApp and Viber groups in the background.")
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    1,
                    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }
}

package com.okb.whatsappbridge.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.okb.whatsappbridge.MainActivity
import com.okb.whatsappbridge.R
import com.okb.whatsappbridge.domain.model.MonitoringState
import com.okb.whatsappbridge.domain.usecase.HealthAlertSink

/** Tells the operator – even with the UI closed – that background monitoring may be inactive. */
class AndroidHealthAlertNotifier(private val context: Context) : HealthAlertSink {

    override fun monitoringMayBeInactive(state: MonitoringState) {
        if (!canPost()) return
        ensureChannel()
        val text = when (state) {
            MonitoringState.NO_ACCESS -> "Notification Access is disabled. Open OKB Bridge to restore WhatsApp monitoring."
            else -> "Android has not reconnected the WhatsApp listener. Open OKB Bridge to check Notification Access."
        }
        val intent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_bridge)
            .setContentTitle("⚠ Background monitoring may be inactive")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(intent)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Permission revoked between the check and the call; nothing else to do.
        }
    }

    override fun clear() {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun canPost(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    private fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.alert_channel_name), NotificationManager.IMPORTANCE_HIGH)
                .apply { description = context.getString(R.string.alert_channel_description) },
        )
    }

    private companion object {
        const val CHANNEL_ID = "bridge_health"
        const val NOTIFICATION_ID = 1001
    }
}

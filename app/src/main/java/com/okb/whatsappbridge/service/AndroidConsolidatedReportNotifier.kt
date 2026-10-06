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
import com.okb.whatsappbridge.R
import com.okb.whatsappbridge.data.remote.dto.ConsolidatedDelivery
import com.okb.whatsappbridge.domain.usecase.ConsolidatedReportSink
import com.okb.whatsappbridge.ui.consolidated.ShareReportActivity
import java.io.File

/** "Consolidated report ready" notification. Tapping it opens the one-tap WhatsApp share (ShareReportActivity). */
class AndroidConsolidatedReportNotifier(private val context: Context) : ConsolidatedReportSink {

    override fun reportReady(delivery: ConsolidatedDelivery, pdf: File): Boolean {
        if (!canPost()) return false
        ensureChannel()
        val group = delivery.destinationGroup?.takeIf { it.isNotBlank() } ?: "the OKB Command Center group"
        val title = if (delivery.isTest) "📄 TEST consolidated report ready to send" else "📄 Consolidated flood report ready to send"
        val text = "Tap to open WhatsApp with the PDF attached, choose “$group” and press Send."
        val period = delivery.caption.lineSequence()
            .dropWhile { !it.startsWith("Reporting Period") }.drop(1).takeWhile { it.isNotBlank() }.joinToString(" ")
        val intent = PendingIntent.getActivity(
            context,
            delivery.id.hashCode(),
            ShareReportActivity.intent(context, delivery).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_bridge)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(listOf(period, text).filter { it.isNotBlank() }.joinToString("\n")))
            .setContentIntent(intent)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        return try {
            NotificationManagerCompat.from(context).notify(notificationId(delivery.id), notification)
            true
        } catch (_: SecurityException) {
            false
        }
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
            NotificationChannel(CHANNEL_ID, context.getString(R.string.report_channel_name), NotificationManager.IMPORTANCE_HIGH)
                .apply { description = context.getString(R.string.report_channel_description) },
        )
    }

    companion object {
        private const val CHANNEL_ID = "consolidated_reports"
        private const val NOTIFICATION_BASE = 2000

        /** Stable per report, distinct from the health alert (1001). */
        fun notificationId(deliveryId: String): Int = NOTIFICATION_BASE + (deliveryId.hashCode() and 0x0FFFFFFF)

        fun cancel(context: Context, deliveryId: String) {
            NotificationManagerCompat.from(context).cancel(notificationId(deliveryId))
        }
    }
}

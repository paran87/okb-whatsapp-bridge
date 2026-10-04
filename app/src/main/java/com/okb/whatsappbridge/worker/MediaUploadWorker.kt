package com.okb.whatsappbridge.worker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.okb.whatsappbridge.R
import com.okb.whatsappbridge.domain.usecase.SyncMediaUseCase
import com.okb.whatsappbridge.domain.usecase.SyncOutcome
import com.okb.whatsappbridge.domain.usecase.SyncTrigger
import com.okb.whatsappbridge.util.logging.BridgeLogger
import com.okb.whatsappbridge.worker.MessageUploadWorker.Companion.toWorkResult
import kotlin.coroutines.cancellation.CancellationException

/**
 * Uploads queued media files. Like the message worker it is network-constrained with exponential
 * backoff and never returns failure (files stay queued until confirmed). Media uploads can be large
 * and long-running, so the worker promotes itself to a foreground data-sync service while running —
 * the supported WorkManager pattern — degrading gracefully if the promotion is not permitted.
 */
class MediaUploadWorker(
    context: Context,
    params: WorkerParameters,
    private val sync: SyncMediaUseCase,
    private val logger: BridgeLogger,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val trigger = inputData.getString(MessageUploadWorker.KEY_TRIGGER)
            ?.let { runCatching { SyncTrigger.valueOf(it) }.getOrNull() }
            ?: SyncTrigger.IMMEDIATE
        // Best-effort foreground promotion so a long video upload is not killed. Not fatal if denied.
        runCatching { setForeground(getForegroundInfo()) }
        return try {
            sync(trigger).toWorkResult()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(TAG, "Media upload run crashed; will retry (attempt ${runAttemptCount + 1})", e)
            Result.retry()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        ensureChannel()
        val notification: Notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_bridge)
            .setContentTitle("OKB WhatsApp Bridge")
            .setContentText("Uploading media…")
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureChannel() {
        // minSdk 26 (O) guarantees notification channels exist.
        val manager = applicationContext.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Media upload", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while the bridge is uploading captured media."
            },
        )
    }

    companion object {
        private const val TAG = "MediaUploadWorker"
        const val CHANNEL_ID = "media_upload"
        const val NOTIFICATION_ID = 1002
    }
}

package com.okb.whatsappbridge.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.okb.whatsappbridge.R
import com.okb.whatsappbridge.domain.usecase.ConsolidatedReportCheckUseCase
import com.okb.whatsappbridge.util.logging.BridgeLogger
import kotlin.coroutines.cancellation.CancellationException

/**
 * One consolidated-report check right now (from the cut-off alarm): the backend generates the due report, the
 * TEXT report is sent automatically and the PDF is brought to the phone. Expedited, so it runs promptly even in
 * Doze; the 15-minute periodic check remains the safety net.
 */
class ConsolidatedReportWorker(
    context: Context,
    params: WorkerParameters,
    private val check: ConsolidatedReportCheckUseCase,
    private val logger: BridgeLogger,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val result = check()
            logger.info(
                TAG,
                "Report check (${inputData.getString(KEY_REASON) ?: "now"}): " +
                    (result.error?.let { "failed: $it" } ?: "text sent ${result.textSent}, text failed ${result.textFailed}, PDFs ready ${result.newlyReady}"),
            )
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(TAG, "Report check failed", e)
            Result.success()
        }
    }

    /** Needed for expedited work on Android 11 and older (shown only while the check runs). */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        if (manager?.getNotificationChannel(CHANNEL_ID) == null) {
            manager?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, applicationContext.getString(R.string.report_channel_name), NotificationManager.IMPORTANCE_LOW),
            )
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_bridge)
            .setContentTitle("Checking consolidated reports")
            .setOngoing(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val TAG = "TextReport"
        private const val KEY_REASON = "reason"
        private const val CHANNEL_ID = "consolidated_report_check"
        private const val NOTIFICATION_ID = 4101
        const val WORK_NAME = "okb-consolidated-report-now"

        fun runNow(workManager: WorkManager, reason: String) {
            val request = OneTimeWorkRequestBuilder<ConsolidatedReportWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setInputData(workDataOf(KEY_REASON to reason))
                .build()
            // KEEP: a check already queued or running covers this request.
            workManager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}

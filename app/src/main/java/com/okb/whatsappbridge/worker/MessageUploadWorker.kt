package com.okb.whatsappbridge.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.okb.whatsappbridge.domain.usecase.SyncMessagesUseCase
import com.okb.whatsappbridge.domain.usecase.SyncOutcome
import com.okb.whatsappbridge.domain.usecase.SyncTrigger
import com.okb.whatsappbridge.util.logging.BridgeLogger
import kotlin.coroutines.cancellation.CancellationException

/**
 * Uploads queued messages. Scheduled by WorkManager with a CONNECTED network constraint and
 * exponential backoff, so it survives app/UI closure, process death and reboots.
 *
 * It never returns [Result.failure]: messages stay in the persistent queue until the backend confirms
 * them, and a failed chain would also cancel any upload work appended behind it.
 */
class MessageUploadWorker(
    context: Context,
    params: WorkerParameters,
    private val sync: SyncMessagesUseCase,
    private val logger: BridgeLogger,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val trigger = inputData.getString(KEY_TRIGGER)
            ?.let { runCatching { SyncTrigger.valueOf(it) }.getOrNull() }
            ?: SyncTrigger.IMMEDIATE
        return try {
            sync(trigger).toWorkResult()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(TAG, "Upload run crashed; will retry (attempt ${runAttemptCount + 1})", e)
            Result.retry()
        }
    }

    companion object {
        const val KEY_TRIGGER = "trigger"
        private const val TAG = "UploadWorker"

        fun SyncOutcome.toWorkResult(): Result = when (this) {
            SyncOutcome.Paused, SyncOutcome.NotConfigured -> Result.success()
            is SyncOutcome.Completed -> if (retryNeeded) Result.retry() else Result.success()
        }
    }
}

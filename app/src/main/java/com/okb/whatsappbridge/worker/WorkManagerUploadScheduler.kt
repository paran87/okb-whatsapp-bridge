package com.okb.whatsappbridge.worker

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.okb.whatsappbridge.domain.repository.UploadScheduler
import com.okb.whatsappbridge.domain.usecase.SyncTrigger
import com.okb.whatsappbridge.util.logging.BridgeLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

/** Observable state of the upload worker, derived from WorkManager's own records. */
enum class SyncWorkerState { RUNNING, WAITING, BACKING_OFF, IDLE }

data class ReconciliationState(val scheduled: Boolean, val nextRunAt: Long?)

/**
 * Upload scheduling policy (one unique work chain, [UPLOAD_WORK_NAME]):
 *  - nothing queued → enqueue a one-time upload;
 *  - an upload is waiting (for network or in backoff) → a new message does not add work, while
 *    reconciliation/manual sync replaces it so a long backoff cannot delay recovery by hours;
 *  - an upload is running → append one follow-up run so messages stored meanwhile are not missed.
 */
class WorkManagerUploadScheduler(
    private val workManager: WorkManager,
    private val scope: CoroutineScope,
    private val logger: BridgeLogger,
) : UploadScheduler {

    private val mutex = Mutex()

    override fun requestUpload(trigger: SyncTrigger) = schedule(Kind.MESSAGE, trigger)

    override fun requestMediaUpload(trigger: SyncTrigger) = schedule(Kind.MEDIA, trigger)

    private enum class Kind { MESSAGE, MEDIA }

    private fun schedule(kind: Kind, trigger: SyncTrigger) {
        scope.launch(Dispatchers.IO) {
            mutex.withLock {
                try {
                    enqueue(kind, trigger)
                } catch (e: Exception) {
                    logger.error(TAG, "Could not schedule ${kind.name.lowercase()} upload work", e)
                }
            }
        }
    }

    private fun enqueue(kind: Kind, trigger: SyncTrigger) {
        val workName = if (kind == Kind.MESSAGE) UPLOAD_WORK_NAME else MEDIA_UPLOAD_WORK_NAME
        val infos = workManager.getWorkInfosForUniqueWork(workName).get().orEmpty()
        val running = infos.any { it.state == WorkInfo.State.RUNNING }
        val waiting = infos.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }
        val policy = when {
            running && waiting -> return
            running -> ExistingWorkPolicy.APPEND_OR_REPLACE
            waiting && trigger == SyncTrigger.IMMEDIATE -> return
            else -> ExistingWorkPolicy.REPLACE
        }
        val builder = if (kind == Kind.MESSAGE) {
            OneTimeWorkRequestBuilder<MessageUploadWorker>()
        } else {
            OneTimeWorkRequestBuilder<MediaUploadWorker>()
        }
        val request = builder
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .setInputData(workDataOf(MessageUploadWorker.KEY_TRIGGER to trigger.name))
            .addTag(if (kind == Kind.MESSAGE) UPLOAD_TAG else MEDIA_UPLOAD_TAG)
            .build()
        workManager.enqueueUniqueWork(workName, policy, request)
    }

    override fun ensurePeriodicReconciliation() {
        val request = PeriodicWorkRequestBuilder<ReconciliationWorker>(RECONCILE_INTERVAL_MINUTES, TimeUnit.MINUTES)
            .addTag(RECONCILE_TAG)
            .build()
        workManager.enqueueUniquePeriodicWork(RECONCILE_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun observeUploadState(): Flow<SyncWorkerState> =
        workManager.getWorkInfosForUniqueWorkFlow(UPLOAD_WORK_NAME).map { infos ->
            when {
                infos.any { it.state == WorkInfo.State.RUNNING } -> SyncWorkerState.RUNNING
                infos.any { it.state == WorkInfo.State.ENQUEUED && it.runAttemptCount > 0 } -> SyncWorkerState.BACKING_OFF
                infos.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED } -> SyncWorkerState.WAITING
                else -> SyncWorkerState.IDLE
            }
        }

    fun observeMediaUploadState(): Flow<SyncWorkerState> =
        workManager.getWorkInfosForUniqueWorkFlow(MEDIA_UPLOAD_WORK_NAME).map { infos ->
            when {
                infos.any { it.state == WorkInfo.State.RUNNING } -> SyncWorkerState.RUNNING
                infos.any { it.state == WorkInfo.State.ENQUEUED && it.runAttemptCount > 0 } -> SyncWorkerState.BACKING_OFF
                infos.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED } -> SyncWorkerState.WAITING
                else -> SyncWorkerState.IDLE
            }
        }

    fun observeReconciliation(): Flow<ReconciliationState> =
        workManager.getWorkInfosForUniqueWorkFlow(RECONCILE_WORK_NAME).map { infos ->
            val active = infos.firstOrNull { !it.state.isFinished }
            ReconciliationState(
                scheduled = active != null,
                nextRunAt = active?.nextScheduleTimeMillis?.takeIf { it > 0 && it != Long.MAX_VALUE },
            )
        }

    companion object {
        private const val TAG = "Scheduler"
        const val UPLOAD_WORK_NAME = "okb-message-upload"
        const val MEDIA_UPLOAD_WORK_NAME = "okb-media-upload"
        const val RECONCILE_WORK_NAME = "okb-reconciliation"
        const val UPLOAD_TAG = "okb-upload"
        const val MEDIA_UPLOAD_TAG = "okb-media-upload"
        const val RECONCILE_TAG = "okb-reconcile"
        const val BACKOFF_SECONDS = 30L
        const val RECONCILE_INTERVAL_MINUTES = 15L
    }
}

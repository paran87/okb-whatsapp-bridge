package com.okb.whatsappbridge.worker

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.okb.whatsappbridge.domain.usecase.ConsolidatedReportCheckUseCase
import com.okb.whatsappbridge.domain.usecase.HealthCheckUseCase
import com.okb.whatsappbridge.domain.usecase.SyncMediaUseCase
import com.okb.whatsappbridge.domain.usecase.SyncMessagesUseCase
import com.okb.whatsappbridge.util.logging.BridgeLogger

/** Supplies workers with their dependencies (lazily, so WorkManager can start before they are needed). */
class BridgeWorkerFactory(
    private val sync: () -> SyncMessagesUseCase,
    private val mediaSync: () -> SyncMediaUseCase,
    private val healthCheck: () -> HealthCheckUseCase,
    private val logger: () -> BridgeLogger,
    private val consolidatedCheck: () -> ConsolidatedReportCheckUseCase? = { null },
) : WorkerFactory() {

    override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? =
        when (workerClassName) {
            MessageUploadWorker::class.java.name -> MessageUploadWorker(appContext, workerParameters, sync(), logger())
            MediaUploadWorker::class.java.name -> MediaUploadWorker(appContext, workerParameters, mediaSync(), logger())
            ReconciliationWorker::class.java.name -> ReconciliationWorker(appContext, workerParameters, healthCheck(), logger(), consolidatedCheck())
            else -> null
        }
}

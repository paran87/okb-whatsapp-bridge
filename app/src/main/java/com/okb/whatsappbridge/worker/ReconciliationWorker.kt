package com.okb.whatsappbridge.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.okb.whatsappbridge.domain.usecase.HealthCheckUseCase
import com.okb.whatsappbridge.util.logging.BridgeLogger
import kotlin.coroutines.cancellation.CancellationException

/** 15-minute periodic health check and queue reconciliation (the minimum interval Android allows). */
class ReconciliationWorker(
    context: Context,
    params: WorkerParameters,
    private val healthCheck: HealthCheckUseCase,
    private val logger: BridgeLogger,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = try {
        healthCheck()
        Result.success()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.error("Reconcile", "Health check failed", e)
        // Periodic work keeps its schedule either way.
        Result.success()
    }
}

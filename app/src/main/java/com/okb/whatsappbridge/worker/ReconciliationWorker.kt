package com.okb.whatsappbridge.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.okb.whatsappbridge.domain.usecase.ConsolidatedReportCheckUseCase
import com.okb.whatsappbridge.domain.usecase.HealthCheckUseCase
import com.okb.whatsappbridge.util.logging.BridgeLogger
import kotlin.coroutines.cancellation.CancellationException

/**
 * 15-minute periodic health check and queue reconciliation (the minimum interval Android allows), followed
 * by the consolidated-report check: the backend decides whether a consolidated WhatsApp report is due.
 */
class ReconciliationWorker(
    context: Context,
    params: WorkerParameters,
    private val healthCheck: HealthCheckUseCase,
    private val logger: BridgeLogger,
    private val consolidatedCheck: ConsolidatedReportCheckUseCase? = null,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        try {
            healthCheck()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Reconcile", "Health check failed", e)
        }
        try {
            consolidatedCheck?.invoke()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Reconcile", "Consolidated report check failed", e)
        }
        // Periodic work keeps its schedule either way.
        return Result.success()
    }
}

package com.okb.whatsappbridge

import android.app.Application
import androidx.work.Configuration
import com.okb.whatsappbridge.worker.BridgeWorkerFactory

class OkbBridgeApplication : Application(), Configuration.Provider {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Idempotent (KEEP): makes sure the 15-minute health check exists after install/update.
        container.uploadScheduler.ensurePeriodicReconciliation()
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(
                BridgeWorkerFactory(
                    sync = { container.syncMessages },
                    mediaSync = { container.syncMedia },
                    healthCheck = { container.healthCheck },
                    logger = { container.logger },
                ),
            )
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()
}

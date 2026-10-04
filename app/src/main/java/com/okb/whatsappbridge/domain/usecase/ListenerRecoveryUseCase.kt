package com.okb.whatsappbridge.domain.usecase

import com.okb.whatsappbridge.domain.model.SystemStatusProvider
import com.okb.whatsappbridge.util.logging.BridgeLogger
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class ListenerRecoveryOutcome {
    /** Notification Access is not granted: only the operator can fix that. */
    NO_ACCESS,
    ALREADY_CONNECTED,
    RECONNECTED,
    STILL_DISCONNECTED,
}

/**
 * Reconnects the notification listener after the process was killed or force-stopped.
 *
 * When Android kills the bridge (swiped away on aggressive OEM builds, force-stopped, low memory), it
 * unbinds the listener and does not always bind it again by itself — the app then sits at
 * "Waiting for Android" although Notification Access is still granted. This runs whenever the process
 * starts or the app is opened, and uses only public APIs on the app's own component:
 *
 *  1. ask Android to rebind the listener (`requestRebind`);
 *  2. if it is still not connected after a short wait, re-enable the listener component, which makes
 *     Android's notification service bind it again (rate-limited, never a loop).
 *
 * It never grants Notification Access: without access it does nothing.
 */
class ListenerRecoveryUseCase(
    private val system: SystemStatusProvider,
    private val logger: BridgeLogger,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val waitMillis: Long = 4_000,
    private val resetCooldownMillis: Long = 60_000,
) {
    private val lock = Mutex()
    private var lastResetAt: Long? = null

    suspend operator fun invoke(reason: String): ListenerRecoveryOutcome = lock.withLock {
        if (!system.isNotificationAccessGranted()) return@withLock ListenerRecoveryOutcome.NO_ACCESS
        if (system.isListenerConnected()) return@withLock ListenerRecoveryOutcome.ALREADY_CONNECTED

        val requested = system.requestListenerRebind()
        logger.info(TAG, "Listener not connected ($reason); rebind requested=$requested")
        sleep(waitMillis)
        if (system.isListenerConnected()) return@withLock reconnected("rebind")

        val now = clock()
        val last = lastResetAt
        if (last != null && now - last < resetCooldownMillis) return@withLock ListenerRecoveryOutcome.STILL_DISCONNECTED
        lastResetAt = now
        val reset = system.resetListenerComponent()
        logger.warn(TAG, "Listener still not connected; re-enabled listener component=$reset")
        sleep(waitMillis)
        if (system.isListenerConnected()) return@withLock reconnected("component reset")

        logger.warn(TAG, "Listener still not connected after recovery ($reason)")
        ListenerRecoveryOutcome.STILL_DISCONNECTED
    }

    private fun reconnected(how: String): ListenerRecoveryOutcome {
        logger.info(TAG, "Listener reconnected via $how")
        return ListenerRecoveryOutcome.RECONNECTED
    }

    private companion object {
        const val TAG = "Listener"
    }
}

package com.okb.whatsappbridge.service

import com.okb.whatsappbridge.domain.usecase.ProcessNotificationUseCase
import com.okb.whatsappbridge.domain.usecase.ProcessingOutcome
import com.okb.whatsappbridge.util.logging.BridgeLogger
import com.okb.whatsappbridge.whatsapp.NotificationSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Runs the capture pipeline for one notification at a time, off the main thread.
 *
 * Each submission is a short, bounded unit of work triggered by an Android callback; nothing here
 * keeps the process alive. The application-wide scope is used so that an in-flight database write
 * is not cancelled if Android unbinds the listener at the same moment.
 */
class NotificationProcessor(
    private val scope: CoroutineScope,
    private val useCase: ProcessNotificationUseCase,
    private val logger: BridgeLogger,
) {
    private val mutex = Mutex()

    fun submit(snapshot: NotificationSnapshot) {
        scope.launch(Dispatchers.IO) {
            mutex.withLock {
                try {
                    val outcome = useCase(snapshot)
                    if (outcome is ProcessingOutcome.Captured && outcome.inserted > 0) {
                        logger.info(TAG, "Captured ${outcome.inserted} new message(s) from \"${outcome.groupName}\"")
                    }
                } catch (e: Exception) {
                    logger.error(TAG, "Failed to process WhatsApp notification", e)
                }
            }
        }
    }

    private companion object {
        const val TAG = "Capture"
    }
}

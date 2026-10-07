package com.okb.whatsappbridge.service

import com.okb.whatsappbridge.domain.usecase.ProcessNotificationUseCase
import com.okb.whatsappbridge.domain.usecase.ProcessingOutcome
import com.okb.whatsappbridge.whatsapp.IgnoreReason
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
                    when (val outcome = useCase(snapshot)) {
                        is ProcessingOutcome.Captured -> if (outcome.inserted > 0) {
                            val media = if (outcome.mediaDetected > 0) " (${outcome.mediaDetected} media)" else ""
                            logger.info(TAG, "Source group matched: captured ${outcome.inserted} new message(s) from \"${outcome.groupName}\"$media")
                        }
                        // Log why a WhatsApp/Viber notification was NOT captured (chat name and reason, never the
                        // message text), so a group that is never captured can be diagnosed from View Logs.
                        is ProcessingOutcome.Ignored -> when (outcome.reason) {
                            IgnoreReason.UNSUPPORTED_APP, IgnoreReason.ONGOING -> Unit
                            IgnoreReason.DESTINATION_GROUP ->
                                logger.info(TAG, "Ignored notification from the destination group (not a field report)")
                            else -> logger.info(TAG, "Not captured (${outcome.reason.name}): ${describe(snapshot)}")
                        }
                    }
                } catch (e: Exception) {
                    logger.error(TAG, "Failed to process notification", e)
                }
            }
        }
    }

    private companion object {
        const val TAG = "Capture"
    }

    private fun describe(s: NotificationSnapshot): String {
        val chat = s.conversationTitle?.takeIf { it.isNotBlank() } ?: s.title?.takeIf { it.isNotBlank() } ?: "?"
        val group = when (s.isGroupConversation) { true -> "group"; false -> "not marked as group"; null -> "group flag missing" }
        return "chat \"$chat\" ($group, ${s.messages.size} message(s), category ${s.category ?: "none"}, channel ${s.channelId ?: "none"})"
    }
}

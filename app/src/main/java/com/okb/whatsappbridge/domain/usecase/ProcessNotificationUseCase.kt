package com.okb.whatsappbridge.domain.usecase

import com.okb.whatsappbridge.domain.repository.GroupRepository
import com.okb.whatsappbridge.domain.repository.MessageRepository
import com.okb.whatsappbridge.domain.repository.NewCapturedMessage
import com.okb.whatsappbridge.domain.repository.SaveResult
import com.okb.whatsappbridge.domain.repository.SettingsRepository
import com.okb.whatsappbridge.domain.repository.UploadScheduler
import com.okb.whatsappbridge.util.fingerprint.MessageFingerprint
import com.okb.whatsappbridge.whatsapp.ConversationType
import com.okb.whatsappbridge.whatsapp.FilterDecision
import com.okb.whatsappbridge.whatsapp.GroupAllowlist
import com.okb.whatsappbridge.whatsapp.IgnoreReason
import com.okb.whatsappbridge.whatsapp.NotificationSnapshot
import com.okb.whatsappbridge.whatsapp.WhatsAppNotificationFilter
import com.okb.whatsappbridge.whatsapp.WhatsAppNotificationParser

sealed interface ProcessingOutcome {
    data class Ignored(val reason: IgnoreReason) : ProcessingOutcome
    data class Captured(val groupName: String, val inserted: Int, val duplicates: Int) : ProcessingOutcome
}

/**
 * The capture pipeline executed for every posted notification, entirely in the background:
 *
 * monitoring enabled? → WhatsApp package? → system notification? → parse → authorized group?
 * → fingerprint → save to Room (with queue entry) → schedule WorkManager upload.
 *
 * The network is never touched here: messages are persisted first so nothing is lost if the
 * backend or connectivity is unavailable.
 */
class ProcessNotificationUseCase(
    private val settings: SettingsRepository,
    private val groups: GroupRepository,
    private val messages: MessageRepository,
    private val scheduler: UploadScheduler,
    private val filter: WhatsAppNotificationFilter = WhatsAppNotificationFilter(),
    private val parser: WhatsAppNotificationParser = WhatsAppNotificationParser(),
    private val clock: () -> Long = System::currentTimeMillis,
) {

    suspend operator fun invoke(snapshot: NotificationSnapshot): ProcessingOutcome {
        if (!filter.isWhatsAppPackage(snapshot.packageName)) return ProcessingOutcome.Ignored(IgnoreReason.NOT_WHATSAPP)

        val now = clock()
        settings.recordNotificationReceived(now)
        if (!settings.current().monitoringEnabled) return ProcessingOutcome.Ignored(IgnoreReason.MONITORING_PAUSED)

        val decision = filter.evaluate(snapshot)
        if (decision is FilterDecision.Ignore) return ProcessingOutcome.Ignored(decision.reason)

        val parsed = parser.parse(snapshot)
        if (parsed.conversationType == ConversationType.PRIVATE) return ProcessingOutcome.Ignored(IgnoreReason.NOT_A_GROUP)

        val authorizedGroup = GroupAllowlist.match(parsed.groupNameCandidates, groups.authorizedGroupNames())
        if (authorizedGroup == null) {
            // Remember the group name (only the name) so the operator can authorize it in Groups.
            if (parsed.conversationType == ConversationType.GROUP) parsed.groupName?.let { groups.recordSeen(it, now) }
            return ProcessingOutcome.Ignored(IgnoreReason.GROUP_NOT_AUTHORIZED)
        }

        var inserted = 0
        var duplicates = 0
        for (message in parsed.messages) {
            if (filter.isIgnoredMessageText(message.text)) continue
            val fingerprint = MessageFingerprint.compute(
                groupName = authorizedGroup,
                senderName = message.senderName,
                messageText = message.text,
                timestamp = message.timestamp,
            )
            val result = messages.saveCaptured(
                NewCapturedMessage(
                    groupName = authorizedGroup,
                    senderName = message.senderName,
                    messageText = message.text,
                    timestamp = message.timestamp,
                    mediaType = message.mediaType,
                    mediaStatus = message.mediaStatus,
                    fingerprint = fingerprint,
                    packageName = parsed.packageName,
                    notificationKey = parsed.notificationKey,
                    capturedAt = now,
                ),
            )
            if (result == SaveResult.INSERTED) inserted++ else duplicates++
        }

        groups.recordSeen(authorizedGroup, now)
        if (inserted > 0) {
            settings.recordProcessed(now)
            scheduler.requestUpload()
        }
        return ProcessingOutcome.Captured(authorizedGroup, inserted, duplicates)
    }
}

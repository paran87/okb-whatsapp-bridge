package com.okb.whatsappbridge.domain.usecase

import com.okb.whatsappbridge.domain.model.MediaType
import com.okb.whatsappbridge.domain.repository.GroupRepository
import com.okb.whatsappbridge.domain.repository.MediaRepository
import com.okb.whatsappbridge.domain.repository.MessageRepository
import com.okb.whatsappbridge.domain.repository.NewCapturedMessage
import com.okb.whatsappbridge.domain.repository.NewMediaAttachment
import com.okb.whatsappbridge.domain.repository.SaveResult
import com.okb.whatsappbridge.domain.repository.SettingsRepository
import com.okb.whatsappbridge.domain.repository.UploadScheduler
import com.okb.whatsappbridge.source.SourcePlatform
import com.okb.whatsappbridge.util.fingerprint.MessageFingerprint
import com.okb.whatsappbridge.whatsapp.ConversationType
import com.okb.whatsappbridge.whatsapp.FilterDecision
import com.okb.whatsappbridge.whatsapp.GroupAllowlist
import com.okb.whatsappbridge.whatsapp.IgnoreReason
import com.okb.whatsappbridge.whatsapp.NotificationSnapshot
import com.okb.whatsappbridge.whatsapp.ParsedMessage
import com.okb.whatsappbridge.whatsapp.WhatsAppNotificationFilter
import com.okb.whatsappbridge.whatsapp.WhatsAppNotificationParser

sealed interface ProcessingOutcome {
    data class Ignored(val reason: IgnoreReason) : ProcessingOutcome
    data class Captured(
        val groupName: String,
        val inserted: Int,
        val duplicates: Int,
        val mediaDetected: Int = 0,
    ) : ProcessingOutcome
}

/**
 * The capture pipeline executed for every posted notification, entirely in the background:
 *
 * monitoring enabled? → supported app (WhatsApp / Viber)? → system notification? → parse → authorized group?
 * → fingerprint → save message to Room → (Phase 2) create+acquire linked media → schedule uploads.
 *
 * The pipeline is the same for every [SourcePlatform]; the platform only selects its ignore rules and
 * scopes the fingerprint, and it is preserved through the stored package name.
 *
 * The network is never touched here: messages (and any acquired media) are persisted first so nothing
 * is lost if the backend or connectivity is unavailable. Media acquisition never blocks or fails text
 * capture; when no legitimate media file is available the media row rests at UNAVAILABLE.
 */
class ProcessNotificationUseCase(
    private val settings: SettingsRepository,
    private val groups: GroupRepository,
    private val messages: MessageRepository,
    private val scheduler: UploadScheduler,
    private val media: MediaRepository? = null,
    private val acquireMedia: AcquireMediaUseCase? = null,
    private val deviceId: () -> String = { "" },
    private val filter: WhatsAppNotificationFilter = WhatsAppNotificationFilter(),
    private val parser: WhatsAppNotificationParser = WhatsAppNotificationParser(),
    private val clock: () -> Long = System::currentTimeMillis,
) {

    suspend operator fun invoke(snapshot: NotificationSnapshot): ProcessingOutcome {
        val platform = SourcePlatform.fromPackage(snapshot.packageName)
            ?: return ProcessingOutcome.Ignored(IgnoreReason.UNSUPPORTED_APP)

        val now = clock()
        settings.recordNotificationReceived(now)
        val current = settings.current()
        if (!current.monitoringEnabled) return ProcessingOutcome.Ignored(IgnoreReason.MONITORING_PAUSED)

        val decision = filter.evaluate(snapshot)
        if (decision is FilterDecision.Ignore) return ProcessingOutcome.Ignored(decision.reason)

        val parsed = parser.parse(snapshot)
        if (parsed.conversationType == ConversationType.PRIVATE) return ProcessingOutcome.Ignored(IgnoreReason.NOT_A_GROUP)

        val authorizedGroup = GroupAllowlist.match(parsed.groupNameCandidates, groups.authorizedGroupNames())
        if (authorizedGroup == null) {
            if (parsed.conversationType == ConversationType.GROUP) parsed.groupName?.let { groups.recordSeen(it, now) }
            return ProcessingOutcome.Ignored(IgnoreReason.GROUP_NOT_AUTHORIZED)
        }

        var inserted = 0
        var duplicates = 0
        var mediaDetected = 0
        for (message in parsed.messages) {
            if (filter.isIgnoredMessageText(message.text, platform)) continue
            val fingerprint = MessageFingerprint.compute(
                groupName = authorizedGroup,
                senderName = message.senderName,
                messageText = message.text,
                timestamp = message.timestamp,
                platformScope = platform.fingerprintScope,
            )
            val outcome = messages.saveCaptured(
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
            if (outcome.result == SaveResult.INSERTED) {
                inserted++
                if (outcome.messageId != null && isMediaMessage(message) && current.captureMedia) {
                    if (handleMedia(outcome.messageId, authorizedGroup, message, now)) mediaDetected++
                }
            } else {
                duplicates++
            }
        }

        groups.recordSeen(authorizedGroup, now)
        if (inserted > 0) {
            settings.recordProcessed(now)
            scheduler.requestUpload()
        }
        return ProcessingOutcome.Captured(authorizedGroup, inserted, duplicates, mediaDetected)
    }

    private fun isMediaMessage(message: ParsedMessage): Boolean =
        message.mediaType != MediaType.TEXT && message.mediaType != MediaType.LOCATION

    /** Creates the media row and attempts acquisition. Returns true if a media row was created. */
    private suspend fun handleMedia(messageId: String, group: String, message: ParsedMessage, now: Long): Boolean {
        val mediaRepo = media ?: return false
        val mediaId = mediaRepo.createDetected(
            NewMediaAttachment(
                messageId = messageId,
                deviceId = deviceId(),
                groupName = group,
                senderName = message.senderName,
                mediaType = message.mediaType,
                mimeType = message.dataMimeType,
                originalFileName = null,
                createdAt = now,
            ),
        ) ?: return false
        acquireMedia?.invoke(
            MediaAcquisitionRequest(
                mediaId = mediaId,
                mediaType = message.mediaType,
                dataUri = message.dataUri,
                dataMimeType = message.dataMimeType,
            ),
        )
        return true
    }
}

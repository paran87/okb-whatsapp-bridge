package com.okb.whatsappbridge.whatsapp

import com.okb.whatsappbridge.domain.model.MediaStatus
import com.okb.whatsappbridge.domain.model.MediaType

enum class ConversationType { GROUP, PRIVATE, UNKNOWN }

enum class TimestampSource { MESSAGE, NOTIFICATION_WHEN, POST_TIME }

data class ParsedMessage(
    val senderName: String?,
    val text: String?,
    val timestamp: Long,
    val timestampSource: TimestampSource,
    val mediaType: MediaType,
    val mediaStatus: MediaStatus,
)

data class ParsedNotification(
    val packageName: String,
    val notificationKey: String,
    val conversationType: ConversationType,
    /** Best guess of the group name, or null when it could not be determined. */
    val groupName: String?,
    /** All plausible group names, most likely first; matched against the allowlist. */
    val groupNameCandidates: List<String>,
    val messages: List<ParsedMessage>,
)

/**
 * Defensive parser for WhatsApp notifications.
 *
 * Supported layouts (WhatsApp has used all of them, and may change again):
 *  1. `MessagingStyle` with `conversationTitle` = group name and one entry per message with sender + time
 *     (current layout; the title may carry a " (3 messages)" counter).
 *  2. Plain notification with title "Group: Sender" and text = message.
 *  3. Plain notification with title "Sender @ Group" and text = message.
 *  4. Plain notification with title = group name and text "Sender: message".
 *
 * The chat JID (shortcut id / tag) is used when available to tell groups (`@g.us`) from private chats.
 * Nothing here assumes any of these formats is permanent; unknown layouts degrade to [ConversationType.UNKNOWN]
 * and are only captured if a candidate name exactly matches an authorized group.
 */
class WhatsAppNotificationParser(
    private val mediaDetector: MediaTypeDetector = MediaTypeDetector(),
) {

    fun parse(snapshot: NotificationSnapshot): ParsedNotification {
        val type = detectConversationType(snapshot)
        val candidates = groupNameCandidates(snapshot, type)
        val messages = if (snapshot.messages.any { !it.text.isNullOrBlank() }) {
            parseMessagingStyle(snapshot, type)
        } else {
            parseFallback(snapshot, type)
        }
        return ParsedNotification(
            packageName = snapshot.packageName,
            notificationKey = snapshot.key,
            conversationType = type,
            groupName = candidates.firstOrNull(),
            groupNameCandidates = candidates,
            messages = messages,
        )
    }

    internal fun detectConversationType(s: NotificationSnapshot): ConversationType {
        val jids = listOfNotNull(s.shortcutId, s.tag).map { it.trim().lowercase() }
        if (jids.any { it.endsWith(GROUP_JID_SUFFIX) }) return ConversationType.GROUP
        if (jids.any { jid -> PRIVATE_JID_SUFFIXES.any { jid.endsWith(it) } }) return ConversationType.PRIVATE
        when (s.isGroupConversation) {
            true -> return ConversationType.GROUP
            false -> if (s.messages.isNotEmpty()) return ConversationType.PRIVATE
            null -> Unit
        }
        if (!s.conversationTitle.isNullOrBlank()) return ConversationType.GROUP
        val title = s.title.orEmpty()
        if (AT_PATTERN.matches(title)) return ConversationType.GROUP
        return ConversationType.UNKNOWN
    }

    internal fun groupNameCandidates(s: NotificationSnapshot, type: ConversationType): List<String> {
        if (type == ConversationType.PRIVATE) return emptyList()
        val result = LinkedHashSet<String>()
        fun add(name: String?) {
            val clean = name?.trim()
            if (!clean.isNullOrEmpty()) result += clean
        }
        s.conversationTitle?.let { ct ->
            add(stripMessageCounter(ct))
            add(ct)
        }
        val title = s.title?.trim().orEmpty()
        if (title.isNotEmpty()) {
            AT_PATTERN.matchEntire(title)?.let { add(stripMessageCounter(it.groupValues[2])) }
            // "Group: Sender" – the group name may itself contain ':' so try every split point.
            var idx = title.indexOf(": ")
            while (idx > 0) {
                add(stripMessageCounter(title.substring(0, idx)))
                idx = title.indexOf(": ", idx + 2)
            }
            add(stripMessageCounter(title))
            add(title)
        }
        return result.toList()
    }

    private fun parseMessagingStyle(s: NotificationSnapshot, type: ConversationType): List<ParsedMessage> {
        val lastIndex = s.messages.lastIndex
        return s.messages.mapIndexedNotNull { index, m ->
            val text = m.text?.trim()
            if (text.isNullOrEmpty()) return@mapIndexedNotNull null
            // MessagingStyle convention: a null sender is the device owner ("You").
            val sender = m.sender?.trim()?.takeIf { it.isNotEmpty() }
                ?: s.selfDisplayName?.trim()?.takeIf { it.isNotEmpty() }
            val body: String = text
            val (timestamp, source) = when {
                m.timestamp > 0 -> m.timestamp to TimestampSource.MESSAGE
                s.whenTime > 0 -> s.whenTime to TimestampSource.NOTIFICATION_WHEN
                else -> s.postTime to TimestampSource.POST_TIME
            }
            // A preview picture belongs to the most recent message only.
            val media = mediaDetector.detect(body, hasPicture = s.hasPicture && index == lastIndex)
            ParsedMessage(sender, body, timestamp, source, media, mediaDetector.statusFor(media))
        }
    }

    private fun parseFallback(s: NotificationSnapshot, type: ConversationType): List<ParsedMessage> {
        val rawText = listOf(s.bigText, s.text, s.textLines.lastOrNull())
            .firstOrNull { !it.isNullOrBlank() }?.trim()
        if (rawText == null && !s.hasPicture) return emptyList()

        var sender: String? = null
        var body: String? = rawText
        val title = s.title?.trim().orEmpty()
        AT_PATTERN.matchEntire(title)?.let { sender = it.groupValues[1].trim() }
        // "Group: Sender"
        if (sender == null && title.contains(": ")) sender = title.substringAfterLast(": ").trim().ifEmpty { null }
        // Title is only the group name: some builds put the sender in the text ("Juan: message").
        if (sender == null && type != ConversationType.PRIVATE && rawText != null) {
            SENDER_PREFIX.matchEntire(rawText)?.let {
                sender = it.groupValues[1].trim()
                body = it.groupValues[2].trim()
            }
        }

        val (timestamp, source) = if (s.whenTime > 0) {
            s.whenTime to TimestampSource.NOTIFICATION_WHEN
        } else {
            s.postTime to TimestampSource.POST_TIME
        }
        val media = mediaDetector.detect(body, s.hasPicture)
        return listOf(ParsedMessage(sender, body, timestamp, source, media, mediaDetector.statusFor(media)))
    }

    companion object {
        private const val GROUP_JID_SUFFIX = "@g.us"
        private val PRIVATE_JID_SUFFIXES = listOf("@s.whatsapp.net", "@c.us", "@lid", "@broadcast", "@newsletter")

        /** "Sender @ Group" */
        private val AT_PATTERN = Regex("^(.+?) @ (.+)$")

        /** "Sender: message" – sender limited in length to avoid splitting ordinary sentences. */
        private val SENDER_PREFIX = Regex("^([^:\\n]{1,60}): (.+)$", RegexOption.DOT_MATCHES_ALL)

        /** Trailing counters such as " (3 messages)" or " (12 mensajes)". */
        private val MESSAGE_COUNTER = Regex("\\s*\\(\\d+\\s+[^\\d()]+\\)\\s*$")

        fun stripMessageCounter(name: String): String = name.replace(MESSAGE_COUNTER, "").trim()
    }
}

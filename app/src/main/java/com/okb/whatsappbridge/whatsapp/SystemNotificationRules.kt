package com.okb.whatsappbridge.whatsapp

/**
 * Expandable ignore list for WhatsApp notifications that are not group messages
 * (status/service notifications, call notifications, summaries, placeholders…).
 *
 * The defaults are deliberately conservative: they only match well-known system texts, so a real
 * report is never dropped because it happens to contain a common word. Additional rules can be
 * supplied with [plus] without code changes elsewhere.
 */
data class SystemNotificationRules(
    /** `Notification.category` values that never carry group messages. */
    val ignoredCategories: Set<String>,
    /** Patterns matched against the whole notification title. */
    val ignoredTitlePatterns: List<Regex>,
    /**
     * Patterns matched against the whole notification text. Only applied to notifications that carry
     * no MessagingStyle messages, because WhatsApp may put e.g. "3 new messages" next to real messages.
     */
    val ignoredTextPatterns: List<Regex>,
    /** Patterns matched against each individual message text (placeholders such as deleted messages). */
    val ignoredMessagePatterns: List<Regex>,
) {
    fun isIgnoredCategory(category: String?): Boolean = category != null && category in ignoredCategories

    fun isIgnoredTitle(title: String?): Boolean =
        title != null && ignoredTitlePatterns.any { it.matches(title.trim()) }

    fun isIgnoredText(text: String?): Boolean =
        text != null && ignoredTextPatterns.any { it.matches(text.trim()) }

    fun isIgnoredMessage(text: String?): Boolean =
        text != null && ignoredMessagePatterns.any { it.matches(text.trim()) }

    operator fun plus(other: SystemNotificationRules) = SystemNotificationRules(
        ignoredCategories = ignoredCategories + other.ignoredCategories,
        ignoredTitlePatterns = ignoredTitlePatterns + other.ignoredTitlePatterns,
        ignoredTextPatterns = ignoredTextPatterns + other.ignoredTextPatterns,
        ignoredMessagePatterns = ignoredMessagePatterns + other.ignoredMessagePatterns,
    )

    companion object {
        private fun rx(pattern: String) = Regex(pattern, setOf(RegexOption.IGNORE_CASE))

        val DEFAULT = SystemNotificationRules(
            ignoredCategories = setOf(
                "call", "missed_call", "progress", "service", "sys", "status", "transport",
                "err", "alarm", "navigation", "stopwatch", "location_sharing",
            ),
            ignoredTitlePatterns = listOf(
                rx("whatsapp( business)?"),
                rx("whatsapp web"),
                rx("(missed|incoming|ongoing) (voice|video|group) call.*"),
                rx("(chat )?backup (in progress|completed|failed|paused).*"),
            ),
            ignoredTextPatterns = listOf(
                rx("whatsapp web is (currently )?active.*"),
                rx("whatsapp (business )?is running.*"),
                rx("checking for (new )?messages.*"),
                rx("you may have new messages.*"),
                rx("backup (completed|in progress|failed|paused).*"),
                rx("(backing up|restoring|preparing) (messages|media|backup|chats).*"),
                rx("(missed|incoming|ongoing) (voice|video|group )?(voice |video )?call.*"),
                rx("calling.*"),
                rx("ringing.*"),
                rx("\\d+ (new )?messages? from \\d+ chats?"),
                rx("\\d+ new messages?"),
                rx("a new device was linked.*"),
            ),
            ignoredMessagePatterns = listOf(
                rx("this message was deleted\\.?"),
                rx("you deleted this message\\.?"),
                rx("waiting for this message.*"),
            ),
        )
    }
}

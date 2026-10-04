package com.okb.whatsappbridge.viber

import com.okb.whatsappbridge.whatsapp.SystemNotificationRules

/** Viber application package. Any other package is not Viber. */
object ViberPackages {
    const val VIBER = "com.viber.voip"

    val ALL: Set<String> = setOf(VIBER)
}

/**
 * Ignore list for Viber notifications that are not group messages (calls, service/sync notifications,
 * summaries, placeholders).
 *
 * Viber's notification layout is not a public API. Like the WhatsApp rules, these patterns are kept
 * conservative and only match well-known service texts, so a real report is never dropped because it
 * contains a common word. Group messages themselves are read through the standard Android
 * `MessagingStyle` extras, exactly as for WhatsApp; nothing Viber-private is accessed.
 */
object ViberNotificationRules {

    private fun rx(pattern: String) = Regex(pattern, setOf(RegexOption.IGNORE_CASE))

    val DEFAULT = SystemNotificationRules(
        ignoredCategories = SystemNotificationRules.DEFAULT.ignoredCategories,
        ignoredTitlePatterns = listOf(
            rx("viber"),
            rx("viber out"),
            rx("rakuten viber"),
            rx("(missed|incoming|ongoing) (viber )?(voice |video |group )?(voice |video )?call.*"),
            rx("(chat )?backup (in progress|completed|failed|paused).*"),
        ),
        ignoredTextPatterns = listOf(
            rx("(missed|incoming|ongoing) (viber )?(voice |video |group )?(voice |video )?call.*"),
            rx("(calling|ringing|connecting)(\\.\\.\\.|…)?"),
            rx("\\d+ (new|unread) messages?( in \\d+ (chats?|conversations?))?"),
            rx("you have \\d+ (new|unread) messages?.*"),
            rx("(backing up|restoring|syncing|preparing) (messages|media|backup|chats|history).*"),
            rx("backup (completed|in progress|failed|paused).*"),
            rx("viber is (running|active|syncing).*"),
        ),
        ignoredMessagePatterns = listOf(
            rx("this message (was|has been) deleted\\.?"),
            rx("message deleted\\.?"),
            rx("you deleted this message\\.?"),
        ),
    )
}

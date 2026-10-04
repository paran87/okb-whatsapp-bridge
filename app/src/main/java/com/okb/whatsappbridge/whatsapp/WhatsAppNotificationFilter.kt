package com.okb.whatsappbridge.whatsapp

import com.okb.whatsappbridge.source.SourcePlatform
import com.okb.whatsappbridge.viber.ViberNotificationRules

/** Why a notification (or message) was not captured. Used for diagnostics only. */
enum class IgnoreReason {
    /** Not from a supported messaging app (WhatsApp, WhatsApp Business or Viber). */
    UNSUPPORTED_APP,
    MONITORING_PAUSED,
    GROUP_SUMMARY,
    ONGOING,
    SYSTEM_CATEGORY,
    SYSTEM_NOTIFICATION,
    NO_CONTENT,
    NOT_A_GROUP,
    GROUP_NOT_AUTHORIZED,
}

sealed interface FilterDecision {
    data object Accept : FilterDecision
    data class Ignore(val reason: IgnoreReason) : FilterDecision
}

/**
 * Decides whether a notification may contain a group message from a supported messaging platform
 * ([SourcePlatform]: WhatsApp or Viber). The checks are the same for every platform; only the
 * service-notification ignore list is platform-specific.
 *
 * Group authorization is a separate step ([GroupAllowlist]) because it needs the parsed group name.
 */
class WhatsAppNotificationFilter(
    private val rules: SystemNotificationRules = SystemNotificationRules.DEFAULT,
    private val viberRules: SystemNotificationRules = ViberNotificationRules.DEFAULT,
) {

    fun isWhatsAppPackage(packageName: String?): Boolean = WhatsAppPackages.isWhatsApp(packageName)

    fun isSupportedPackage(packageName: String?): Boolean = SourcePlatform.isSupported(packageName)

    private fun rulesFor(platform: SourcePlatform): SystemNotificationRules = when (platform) {
        SourcePlatform.WHATSAPP -> rules
        SourcePlatform.VIBER -> viberRules
    }

    fun evaluate(snapshot: NotificationSnapshot): FilterDecision {
        val platform = SourcePlatform.fromPackage(snapshot.packageName)
            ?: return FilterDecision.Ignore(IgnoreReason.UNSUPPORTED_APP)
        val rules = rulesFor(platform)
        // The per-chat notifications carry the content; the bundle summary only repeats it.
        if (snapshot.isGroupSummary) return FilterDecision.Ignore(IgnoreReason.GROUP_SUMMARY)
        if (snapshot.isOngoing) return FilterDecision.Ignore(IgnoreReason.ONGOING)
        if (rules.isIgnoredCategory(snapshot.category)) return FilterDecision.Ignore(IgnoreReason.SYSTEM_CATEGORY)

        val hasMessages = snapshot.messages.any {
            !it.text.isNullOrBlank() || !it.dataMimeType.isNullOrBlank() || !it.dataUri.isNullOrBlank()
        }
        if (!hasMessages) {
            if (rules.isIgnoredTitle(snapshot.title) && snapshot.conversationTitle.isNullOrBlank()) {
                return FilterDecision.Ignore(IgnoreReason.SYSTEM_NOTIFICATION)
            }
            if (rules.isIgnoredText(snapshot.text) || rules.isIgnoredText(snapshot.bigText)) {
                return FilterDecision.Ignore(IgnoreReason.SYSTEM_NOTIFICATION)
            }
            val hasText = !snapshot.text.isNullOrBlank() || !snapshot.bigText.isNullOrBlank() ||
                snapshot.textLines.any { it.isNotBlank() } || snapshot.hasPicture
            if (!hasText) return FilterDecision.Ignore(IgnoreReason.NO_CONTENT)
        }
        return FilterDecision.Accept
    }

    /** True for placeholder messages such as "This message was deleted". */
    fun isIgnoredMessageText(text: String?, platform: SourcePlatform = SourcePlatform.WHATSAPP): Boolean =
        rulesFor(platform).isIgnoredMessage(text)
}

/** Persistent allowlist matching. Only explicitly authorized groups are ever captured. */
object GroupAllowlist {

    /** Canonical form used for comparison and for the unique index: trimmed, collapsed, case-folded. */
    fun normalize(name: String): String =
        java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFC)
            .trim()
            .replace(WHITESPACE, " ")
            .lowercase()

    /**
     * Returns the authorized group name matching one of the [candidates] parsed from a notification,
     * or `null` when none is authorized.
     */
    fun match(candidates: List<String>, authorizedNames: Collection<String>): String? {
        if (candidates.isEmpty() || authorizedNames.isEmpty()) return null
        val byNormalized = authorizedNames.associateBy { normalize(it) }
        for (candidate in candidates) {
            byNormalized[normalize(candidate)]?.let { return it }
        }
        return null
    }

    private val WHITESPACE = Regex("\\s+")
}

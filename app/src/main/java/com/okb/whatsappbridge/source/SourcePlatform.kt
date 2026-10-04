package com.okb.whatsappbridge.source

import com.okb.whatsappbridge.viber.ViberNotificationRules
import com.okb.whatsappbridge.viber.ViberPackages
import com.okb.whatsappbridge.whatsapp.SystemNotificationRules
import com.okb.whatsappbridge.whatsapp.WhatsAppPackages

/**
 * Messaging platforms the bridge captures from. Each platform contributes only what genuinely differs:
 * its packages, its service-notification ignore list and how it appears on the wire. Filtering,
 * parsing, storage, upload and media handling are one shared pipeline for every platform.
 *
 * Adding a platform means adding an entry here (plus its ignore rules); nothing downstream changes.
 */
enum class SourcePlatform(
    /** Value sent to the backend as `platform`. */
    val wireName: String,
    val displayName: String,
    val packages: Set<String>,
    val systemRules: SystemNotificationRules,
) {
    WHATSAPP("whatsapp", "WhatsApp", WhatsAppPackages.ALL, SystemNotificationRules.DEFAULT),
    VIBER("viber", "Viber", ViberPackages.ALL, ViberNotificationRules.DEFAULT),
    ;

    /**
     * Extra input to the message fingerprint. WhatsApp has none, so Phase 1/2 fingerprints (and therefore
     * duplicate detection of already-stored messages) are unchanged. Other platforms are scoped so that
     * identical text in a WhatsApp and a Viber group is never treated as the same message.
     */
    val fingerprintScope: String? get() = if (this == WHATSAPP) null else wireName

    companion object {
        val ALL_PACKAGES: Set<String> = entries.flatMap { it.packages }.toSet()

        fun fromPackage(packageName: String?): SourcePlatform? =
            if (packageName == null) null else entries.firstOrNull { packageName in it.packages }

        fun isSupported(packageName: String?): Boolean = fromPackage(packageName) != null

        /** Human-readable source app, e.g. "WhatsApp Business" or "Viber". */
        fun displayNameFor(packageName: String): String = when (fromPackage(packageName)) {
            WHATSAPP -> WhatsAppPackages.displayName(packageName)
            VIBER -> VIBER.displayName
            null -> packageName
        }
    }
}

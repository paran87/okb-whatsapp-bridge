package com.okb.whatsappbridge.util.fingerprint

import java.security.MessageDigest
import java.text.Normalizer

/**
 * Stable identity of a captured message, used for duplicate prevention both locally
 * (unique index) and on the backend (idempotency key).
 *
 * WhatsApp re-posts the whole conversation every time a new message arrives, so the same message is
 * seen many times; the fingerprint is derived only from fields that do not change between re-posts.
 * The package name is intentionally excluded so that a message seen through both WhatsApp and
 * WhatsApp Business on the same phone is stored once.
 *
 * [platformScope] separates messaging platforms: identical text posted in a WhatsApp group and a Viber
 * group are different source messages. WhatsApp passes `null`, which keeps its fingerprints exactly as
 * they were before Viber support existed (so already-stored messages still deduplicate).
 */
object MessageFingerprint {

    private const val VERSION = "v1"
    private const val SEPARATOR = '\u001F'

    fun compute(
        groupName: String?,
        senderName: String?,
        messageText: String?,
        timestamp: Long,
        platformScope: String? = null,
    ): String {
        val canonical = buildString {
            append(VERSION).append(SEPARATOR)
            if (platformScope != null) append("platform=").append(platformScope).append(SEPARATOR)
            append(normalizeName(groupName)).append(SEPARATOR)
            append(normalizeName(senderName)).append(SEPARATOR)
            append(normalizeText(messageText)).append(SEPARATOR)
            append(timestamp)
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun normalizeName(value: String?): String =
        normalizeText(value).lowercase()

    private fun normalizeText(value: String?): String =
        Normalizer.normalize(value.orEmpty(), Normalizer.Form.NFC).trim().replace(Regex("\\s+"), " ")
}

package com.okb.whatsappbridge.whatsapp

import com.okb.whatsappbridge.domain.model.MediaStatus
import com.okb.whatsappbridge.domain.model.MediaType

/**
 * Infers the media type of a WhatsApp message from its notification text.
 *
 * WhatsApp prefixes media notifications with an emoji ("📷 Photo", "🎤 Voice message (0:12)",
 * "📄 report.pdf", "📍 Location"). The emoji prefix is locale-independent, so it is checked first;
 * a few English labels are recognised as a fallback. The original media file is never accessed:
 * any media type results in [MediaStatus.UNAVAILABLE] while the text (e.g. caption) is still kept.
 */
class MediaTypeDetector {

    fun detect(text: String?, hasPicture: Boolean = false): MediaType {
        val trimmed = text?.trim().orEmpty()
        if (trimmed.isNotEmpty()) {
            PREFIXES.firstOrNull { (prefix, _) -> trimmed.startsWith(prefix) }?.let { return it.second }
            val lower = trimmed.lowercase()
            LABELS[lower]?.let { return it }
            if (STICKER.matches(trimmed)) return MediaType.STICKER
            if (GIF.matches(trimmed)) return MediaType.VIDEO
            if (VOICE.matches(trimmed)) return MediaType.AUDIO
        }
        return when {
            hasPicture -> MediaType.IMAGE
            trimmed.isEmpty() -> MediaType.UNKNOWN
            else -> MediaType.TEXT
        }
    }

    /** Media kind from a MIME type (used for caption-less media carried as MessagingStyle data). */
    fun fromMime(mimeType: String?): MediaType? {
        val mime = mimeType?.substringBefore(';')?.trim()?.lowercase() ?: return null
        return when {
            mime.isEmpty() -> null
            mime.startsWith("image/") -> if (mime.contains("webp")) MediaType.STICKER else MediaType.IMAGE
            mime.startsWith("video/") -> MediaType.VIDEO
            mime.startsWith("audio/") -> MediaType.AUDIO
            mime == "text/x-vcard" || mime == "text/vcard" -> MediaType.UNKNOWN
            mime.contains('/') -> MediaType.DOCUMENT
            else -> null
        }
    }

    fun statusFor(type: MediaType): MediaStatus =
        if (type == MediaType.TEXT) MediaStatus.NONE else MediaStatus.UNAVAILABLE

    private companion object {
        val PREFIXES: List<Pair<String, MediaType>> = listOf(
            "📷" to MediaType.IMAGE,
            "📸" to MediaType.IMAGE,
            "🖼" to MediaType.IMAGE,
            "🎥" to MediaType.VIDEO,
            "📹" to MediaType.VIDEO,
            "🎬" to MediaType.VIDEO,
            "👾" to MediaType.VIDEO, // GIF
            "🎤" to MediaType.AUDIO,
            "🎙" to MediaType.AUDIO,
            "🎵" to MediaType.AUDIO,
            "🎶" to MediaType.AUDIO,
            "📄" to MediaType.DOCUMENT,
            "📃" to MediaType.DOCUMENT,
            "📑" to MediaType.DOCUMENT,
            "📎" to MediaType.DOCUMENT,
            "📍" to MediaType.LOCATION,
            "🗺" to MediaType.LOCATION,
            "👤" to MediaType.UNKNOWN, // contact card
            "📊" to MediaType.UNKNOWN, // poll
        )

        val LABELS: Map<String, MediaType> = mapOf(
            "photo" to MediaType.IMAGE,
            "image" to MediaType.IMAGE,
            "video" to MediaType.VIDEO,
            "gif" to MediaType.VIDEO,
            "audio" to MediaType.AUDIO,
            "voice message" to MediaType.AUDIO,
            "document" to MediaType.DOCUMENT,
            "location" to MediaType.LOCATION,
            "live location" to MediaType.LOCATION,
            "sticker" to MediaType.STICKER,
        )

        // Sticker notifications are "Sticker", optionally preceded by the sticker's emoji.
        val STICKER = Regex("^(\\S{1,8}\\s+)?sticker$", RegexOption.IGNORE_CASE)
        val GIF = Regex("^(\\S{1,8}\\s+)?gif$", RegexOption.IGNORE_CASE)
        val VOICE = Regex("^voice message( \\(\\d{1,2}:\\d{2}\\))?$", RegexOption.IGNORE_CASE)
    }
}

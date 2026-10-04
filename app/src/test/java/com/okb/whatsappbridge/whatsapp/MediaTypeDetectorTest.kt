package com.okb.whatsappbridge.whatsapp

import com.okb.whatsappbridge.domain.model.MediaStatus
import com.okb.whatsappbridge.domain.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaTypeDetectorTest {

    private val detector = MediaTypeDetector()

    @Test
    fun `detects media types from WhatsApp notification text`() {
        mapOf(
            "Flooding observed" to MediaType.TEXT,
            "📷 Photo" to MediaType.IMAGE,
            "📷 Water level at 2m" to MediaType.IMAGE,
            "🎥 Video" to MediaType.VIDEO,
            "GIF" to MediaType.VIDEO,
            "🎤 Voice message (0:12)" to MediaType.AUDIO,
            "Voice message" to MediaType.AUDIO,
            "🎵 Audio" to MediaType.AUDIO,
            "📄 evacuation-plan.pdf" to MediaType.DOCUMENT,
            "📍 Location" to MediaType.LOCATION,
            "📍 Live location" to MediaType.LOCATION,
            "Sticker" to MediaType.STICKER,
            "😀 Sticker" to MediaType.STICKER,
            "👤 Contact card" to MediaType.UNKNOWN,
            "Photo" to MediaType.IMAGE,
        ).forEach { (text, expected) -> assertEquals(text, expected, detector.detect(text)) }
    }

    @Test
    fun `preview picture without text is an image`() {
        assertEquals(MediaType.IMAGE, detector.detect(null, hasPicture = true))
        assertEquals(MediaType.UNKNOWN, detector.detect("  "))
    }

    @Test
    fun `media is always marked unavailable in phase 1`() {
        assertEquals(MediaStatus.NONE, detector.statusFor(MediaType.TEXT))
        MediaType.entries.filter { it != MediaType.TEXT }.forEach {
            assertEquals(MediaStatus.UNAVAILABLE, detector.statusFor(it))
        }
    }
}

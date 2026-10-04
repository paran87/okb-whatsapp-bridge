package com.okb.whatsappbridge.whatsapp

import com.okb.whatsappbridge.domain.model.MediaStatus
import com.okb.whatsappbridge.domain.model.MediaType
import com.okb.whatsappbridge.fakes.Snapshots
import com.okb.whatsappbridge.fakes.Snapshots.T0
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A photo (or other media) sent with NO caption must still be captured: WhatsApp delivers it as a
 * MessagingStyle message that carries media (a dataUri/dataMimeType) but empty text, or as a plain
 * BigPicture notification. Earlier these were dropped because the message had no text.
 */
class CaptionlessMediaTest {

    private val parser = WhatsAppNotificationParser()
    private val filter = WhatsAppNotificationFilter()
    private val detector = MediaTypeDetector()

    private fun mediaMessage(mime: String?, uri: String? = "content://com.whatsapp/media/1", text: String? = null) =
        Snapshots.groupMessaging(
            messages = listOf(SnapshotMessage(text, T0, "Ana", dataUri = uri, dataMimeType = mime)),
        )

    @Test
    fun `fromMime maps common types`() {
        assertEquals(MediaType.IMAGE, detector.fromMime("image/jpeg"))
        assertEquals(MediaType.STICKER, detector.fromMime("image/webp"))
        assertEquals(MediaType.VIDEO, detector.fromMime("video/mp4"))
        assertEquals(MediaType.AUDIO, detector.fromMime("audio/ogg; codecs=opus"))
        assertEquals(MediaType.DOCUMENT, detector.fromMime("application/pdf"))
        assertNull(detector.fromMime(null))
        assertNull(detector.fromMime(""))
    }

    @Test
    fun `caption-less photo carried as MessagingStyle data is captured as IMAGE`() {
        val parsed = parser.parse(mediaMessage("image/jpeg"))
        val m = parsed.messages.single()
        assertEquals(MediaType.IMAGE, m.mediaType)
        assertEquals(MediaStatus.UNAVAILABLE, m.mediaStatus)
        assertNull("no caption means null text", m.text)
        assertEquals("Ana", m.senderName)
        assertEquals("content://com.whatsapp/media/1", m.dataUri)
    }

    @Test
    fun `caption-less video and document derive their type from the MIME`() {
        assertEquals(MediaType.VIDEO, parser.parse(mediaMessage("video/mp4")).messages.single().mediaType)
        assertEquals(MediaType.DOCUMENT, parser.parse(mediaMessage("application/pdf")).messages.single().mediaType)
    }

    @Test
    fun `caption-less photo with only a preview picture is captured as IMAGE`() {
        val snap = Snapshots.groupMessaging(
            messages = listOf(SnapshotMessage(null, T0, "Ana")),
            hasPicture = true,
        )
        assertEquals(MediaType.IMAGE, parser.parse(snap).messages.single().mediaType)
    }

    @Test
    fun `the filter accepts a MessagingStyle message that carries media but no text`() {
        assertEquals(FilterDecision.Accept, filter.evaluate(mediaMessage("image/jpeg")))
    }

    @Test
    fun `a truly empty message (no text, no media) is still ignored`() {
        val snap = Snapshots.groupMessaging(messages = listOf(SnapshotMessage(null, T0, "Ana")))
        // No text, no data, no picture → nothing to capture.
        assertTrue(parser.parse(snap).messages.isEmpty())
    }

    @Test
    fun `a caption with the photo is preserved alongside the IMAGE type`() {
        val parsed = parser.parse(mediaMessage("image/jpeg", text = "Flooding at the bridge"))
        val m = parsed.messages.single()
        assertEquals(MediaType.IMAGE, m.mediaType)
        assertEquals("Flooding at the bridge", m.text)
    }
}

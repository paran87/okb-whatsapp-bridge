package com.okb.whatsappbridge.whatsapp

import com.okb.whatsappbridge.domain.model.MediaStatus
import com.okb.whatsappbridge.domain.model.MediaType
import com.okb.whatsappbridge.fakes.Snapshots
import com.okb.whatsappbridge.fakes.Snapshots.T0
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WhatsAppNotificationParserTest {

    private val parser = WhatsAppNotificationParser()

    @Test
    fun `messaging style group notification yields group, sender, text and message time`() {
        val parsed = parser.parse(Snapshots.groupMessaging())
        assertEquals(ConversationType.GROUP, parsed.conversationType)
        assertEquals("OKB Monitoring", parsed.groupName)
        val m = parsed.messages.single()
        assertEquals("Juan Santos", m.senderName)
        assertEquals("Flooding observed at Barangay San Jose", m.text)
        assertEquals(T0, m.timestamp)
        assertEquals(TimestampSource.MESSAGE, m.timestampSource)
        assertEquals(MediaType.TEXT, m.mediaType)
        assertEquals(MediaStatus.NONE, m.mediaStatus)
    }

    @Test
    fun `message counter suffix is stripped from the conversation title`() {
        val parsed = parser.parse(Snapshots.groupMessaging(conversationTitle = "OKB Monitoring (3 messages)"))
        assertEquals("OKB Monitoring", parsed.groupName)
        assertTrue("OKB Monitoring (3 messages)" in parsed.groupNameCandidates)
    }

    @Test
    fun `group names with parenthesised numbers are kept as candidates`() {
        val parsed = parser.parse(Snapshots.groupMessaging(group = "Team (2)", conversationTitle = "Team (2)"))
        assertEquals("Team (2)", parsed.groupName)
    }

    @Test
    fun `every message of a multi-message notification is returned`() {
        val parsed = parser.parse(
            Snapshots.groupMessaging(
                messages = listOf(
                    SnapshotMessage("Water rising", T0, "Ana"),
                    SnapshotMessage("Road closed", T0 + 60_000, "Ben"),
                ),
            ),
        )
        assertEquals(listOf("Ana", "Ben"), parsed.messages.map { it.senderName })
        assertEquals(listOf(T0, T0 + 60_000), parsed.messages.map { it.timestamp })
    }

    @Test
    fun `null sender in messaging style is the device owner`() {
        val parsed = parser.parse(Snapshots.groupMessaging(messages = listOf(SnapshotMessage("On my way", T0, null))))
        assertEquals("You", parsed.messages.single().senderName)
    }

    @Test
    fun `private chat jid is detected and has no group candidates`() {
        val parsed = parser.parse(
            Snapshots.groupMessaging(group = "Maria", conversationTitle = null, jid = "639171234567@s.whatsapp.net", isGroup = false),
        )
        assertEquals(ConversationType.PRIVATE, parsed.conversationType)
        assertTrue(parsed.groupNameCandidates.isEmpty())
    }

    @Test
    fun `group jid wins over a missing group flag`() {
        val parsed = parser.parse(Snapshots.groupMessaging(isGroup = null, conversationTitle = null))
        assertEquals(ConversationType.GROUP, parsed.conversationType)
        assertEquals("OKB Monitoring", parsed.groupName)
    }

    @Test
    fun `legacy title Group colon Sender is parsed`() {
        val parsed = parser.parse(Snapshots.plain(title = "Regional Flood Reports: Juan Santos", text = "Bridge underwater"))
        assertTrue("Regional Flood Reports" in parsed.groupNameCandidates)
        val m = parsed.messages.single()
        assertEquals("Juan Santos", m.senderName)
        assertEquals("Bridge underwater", m.text)
        assertEquals(TimestampSource.NOTIFICATION_WHEN, m.timestampSource)
    }

    @Test
    fun `legacy title Sender at Group is parsed`() {
        val parsed = parser.parse(Snapshots.plain(title = "Juan Santos @ OKB Monitoring", text = "Need rescue boat"))
        assertEquals(ConversationType.GROUP, parsed.conversationType)
        assertEquals("OKB Monitoring", parsed.groupName)
        assertEquals("Juan Santos", parsed.messages.single().senderName)
    }

    @Test
    fun `group title with sender prefix in text is parsed`() {
        val parsed = parser.parse(Snapshots.plain(title = "OKB Monitoring", text = "Juan Santos: Evacuation started", tag = "1203@g.us"))
        val m = parsed.messages.single()
        assertEquals("Juan Santos", m.senderName)
        assertEquals("Evacuation started", m.text)
    }

    @Test
    fun `photo notification keeps caption and marks media unavailable`() {
        val parsed = parser.parse(
            Snapshots.groupMessaging(messages = listOf(SnapshotMessage("📷 Flood at the bridge", T0, "Ana")), hasPicture = true),
        )
        val m = parsed.messages.single()
        assertEquals(MediaType.IMAGE, m.mediaType)
        assertEquals(MediaStatus.UNAVAILABLE, m.mediaStatus)
        assertEquals("📷 Flood at the bridge", m.text)
    }

    @Test
    fun `preview picture only applies to the last message`() {
        val parsed = parser.parse(
            Snapshots.groupMessaging(
                messages = listOf(SnapshotMessage("Status update", T0, "Ana"), SnapshotMessage("Photo", T0 + 1, "Ben")),
                hasPicture = true,
            ),
        )
        assertEquals(listOf(MediaType.TEXT, MediaType.IMAGE), parsed.messages.map { it.mediaType })
    }

    @Test
    fun `missing message time falls back to notification when`() {
        val snapshot = Snapshots.groupMessaging(messages = listOf(SnapshotMessage("Hello", 0, "Ana"))).copy(whenTime = T0 + 5)
        val m = parser.parse(snapshot).messages.single()
        assertEquals(T0 + 5, m.timestamp)
        assertEquals(TimestampSource.NOTIFICATION_WHEN, m.timestampSource)
    }

    @Test
    fun `blank messages are skipped and unknown layout degrades gracefully`() {
        val parsed = parser.parse(Snapshots.plain(title = null, text = null))
        assertEquals(ConversationType.UNKNOWN, parsed.conversationType)
        assertTrue(parsed.messages.isEmpty())
        assertNull(parsed.groupName)
    }

    @Test
    fun `stripMessageCounter handles other languages`() {
        assertEquals("Grupo", WhatsAppNotificationParser.stripMessageCounter("Grupo (12 mensajes)"))
        assertEquals("Grupo", WhatsAppNotificationParser.stripMessageCounter("Grupo"))
    }
}

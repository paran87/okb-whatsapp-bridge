package com.okb.whatsappbridge.whatsapp

import com.okb.whatsappbridge.fakes.Snapshots
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WhatsAppNotificationFilterTest {

    private val filter = WhatsAppNotificationFilter()

    @Test
    fun `only WhatsApp and WhatsApp Business packages are accepted`() {
        assertTrue(filter.isWhatsAppPackage("com.whatsapp"))
        assertTrue(filter.isWhatsAppPackage("com.whatsapp.w4b"))
        assertFalse(filter.isWhatsAppPackage("org.telegram.messenger"))
        assertFalse(filter.isWhatsAppPackage("com.whatsapp.fake"))
        assertFalse(filter.isWhatsAppPackage(null))
        assertEquals(
            FilterDecision.Ignore(IgnoreReason.NOT_WHATSAPP),
            filter.evaluate(Snapshots.groupMessaging(packageName = "com.google.android.gm")),
        )
    }

    @Test
    fun `regular group message is accepted`() {
        assertEquals(FilterDecision.Accept, filter.evaluate(Snapshots.groupMessaging()))
        assertEquals(FilterDecision.Accept, filter.evaluate(Snapshots.groupMessaging(packageName = "com.whatsapp.w4b")))
    }

    @Test
    fun `group summary and ongoing notifications are ignored`() {
        assertEquals(
            FilterDecision.Ignore(IgnoreReason.GROUP_SUMMARY),
            filter.evaluate(Snapshots.plain("WhatsApp", "5 new messages from 2 chats", isGroupSummary = true)),
        )
        assertEquals(
            FilterDecision.Ignore(IgnoreReason.ONGOING),
            filter.evaluate(Snapshots.plain("Ongoing voice call", "Tap to return", isOngoing = true)),
        )
    }

    @Test
    fun `call category is ignored`() {
        assertEquals(
            FilterDecision.Ignore(IgnoreReason.SYSTEM_CATEGORY),
            filter.evaluate(Snapshots.plain("Juan Santos", "Incoming voice call", category = "call")),
        )
    }

    @Test
    fun `known WhatsApp system notifications are ignored`() {
        listOf(
            "WhatsApp Web is currently active",
            "WhatsApp is running",
            "Backup completed",
            "Checking for new messages",
            "3 new messages from 2 chats",
            "Missed voice call",
        ).forEach { text ->
            assertEquals(text, FilterDecision.Ignore(IgnoreReason.SYSTEM_NOTIFICATION), filter.evaluate(Snapshots.plain("WhatsApp", text)))
        }
    }

    @Test
    fun `system patterns do not drop real reports that mention similar words`() {
        assertEquals(FilterDecision.Accept, filter.evaluate(Snapshots.plain("Backup Team: Ana", "Backup generator running at the evacuation center")))
    }

    @Test
    fun `counter text next to real messaging style messages is accepted`() {
        val snapshot = Snapshots.groupMessaging().copy(text = "3 new messages")
        assertEquals(FilterDecision.Accept, filter.evaluate(snapshot))
    }

    @Test
    fun `empty notification is ignored`() {
        assertEquals(FilterDecision.Ignore(IgnoreReason.NO_CONTENT), filter.evaluate(Snapshots.plain("OKB Monitoring", null)))
    }

    @Test
    fun `deleted-message placeholders are ignored per message`() {
        assertTrue(filter.isIgnoredMessageText("This message was deleted"))
        assertTrue(filter.isIgnoredMessageText("Waiting for this message. This may take a while."))
        assertFalse(filter.isIgnoredMessageText("Flooding observed at Barangay San Jose"))
    }

    @Test
    fun `ignore list is expandable without code changes elsewhere`() {
        val custom = WhatsAppNotificationFilter(
            SystemNotificationRules.DEFAULT + SystemNotificationRules(
                ignoredCategories = emptySet(),
                ignoredTitlePatterns = emptyList(),
                ignoredTextPatterns = listOf(Regex("(?i)security code changed.*")),
                ignoredMessagePatterns = emptyList(),
            ),
        )
        assertEquals(
            FilterDecision.Ignore(IgnoreReason.SYSTEM_NOTIFICATION),
            custom.evaluate(Snapshots.plain("OKB Monitoring", "Security code changed for Juan")),
        )
        assertEquals(FilterDecision.Accept, filter.evaluate(Snapshots.plain("OKB Monitoring", "Security code changed for Juan")))
    }
}

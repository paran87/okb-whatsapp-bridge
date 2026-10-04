package com.okb.whatsappbridge.viber

import com.okb.whatsappbridge.domain.model.BridgeSettings
import com.okb.whatsappbridge.domain.usecase.ProcessNotificationUseCase
import com.okb.whatsappbridge.domain.usecase.ProcessingOutcome
import com.okb.whatsappbridge.fakes.FakeGroupRepository
import com.okb.whatsappbridge.fakes.FakeMessageRepository
import com.okb.whatsappbridge.fakes.FakeScheduler
import com.okb.whatsappbridge.fakes.FakeSettingsRepository
import com.okb.whatsappbridge.fakes.Snapshots
import com.okb.whatsappbridge.fakes.Snapshots.T0
import com.okb.whatsappbridge.whatsapp.FilterDecision
import com.okb.whatsappbridge.whatsapp.IgnoreReason
import com.okb.whatsappbridge.whatsapp.SnapshotMessage
import com.okb.whatsappbridge.whatsapp.WhatsAppNotificationFilter
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Viber group messages go through the same capture pipeline as WhatsApp. */
class ViberCaptureTest {

    private val now = T0 + 2_000
    private val settings = FakeSettingsRepository(BridgeSettings(monitoringEnabled = true))
    private val groups = FakeGroupRepository("DPWH Flood Monitoring")
    private val messages = FakeMessageRepository()
    private val scheduler = FakeScheduler()
    private val useCase = ProcessNotificationUseCase(settings, groups, messages, scheduler, clock = { now })
    private val filter = WhatsAppNotificationFilter()

    private val report = "Current flood height: 0.20 m\nFlood subsided at 3:30 PM."

    /** Viber posts standard MessagingStyle notifications; it has no WhatsApp-style chat JIDs. */
    private fun viber(
        messages: List<SnapshotMessage> = listOf(SnapshotMessage(report, T0, "Engineer A")),
        group: String = "DPWH Flood Monitoring",
        isGroup: Boolean? = true,
    ) = Snapshots.groupMessaging(group = group, messages = messages, packageName = VIBER, jid = null, isGroup = isGroup)

    @Test
    fun `viber group message from an authorized group is captured with its package`() = runTest {
        val outcome = useCase(viber())
        assertEquals(ProcessingOutcome.Captured("DPWH Flood Monitoring", inserted = 1, duplicates = 0), outcome)
        val saved = messages.saved.single()
        assertEquals(VIBER, saved.packageName)
        assertEquals("Engineer A", saved.senderName)
        assertEquals(report, saved.messageText)
        assertEquals(T0, saved.timestamp)
        assertEquals(1, scheduler.requests.size)
    }

    @Test
    fun `viber notifications pass the same filter`() {
        assertTrue(filter.isSupportedPackage(VIBER))
        assertEquals(FilterDecision.Accept, filter.evaluate(viber()))
    }

    @Test
    fun `viber system notifications are ignored`() {
        assertEquals(
            FilterDecision.Ignore(IgnoreReason.SYSTEM_CATEGORY),
            filter.evaluate(Snapshots.plain("Engineer A", "Incoming Viber call", packageName = VIBER, category = "call")),
        )
        assertEquals(
            FilterDecision.Ignore(IgnoreReason.SYSTEM_NOTIFICATION),
            filter.evaluate(Snapshots.plain("Viber", "3 new messages", packageName = VIBER)),
        )
        assertEquals(
            FilterDecision.Ignore(IgnoreReason.SYSTEM_NOTIFICATION),
            filter.evaluate(Snapshots.plain("Missed Viber call", "Engineer A", packageName = VIBER)),
        )
        assertTrue(filter.isIgnoredMessageText("This message was deleted", com.okb.whatsappbridge.source.SourcePlatform.VIBER))
    }

    @Test
    fun `reposted viber notification is deduplicated`() = runTest {
        useCase(viber())
        val again = useCase(viber())
        assertEquals(ProcessingOutcome.Captured("DPWH Flood Monitoring", inserted = 0, duplicates = 1), again)
        assertEquals(1, messages.saved.size)
    }

    @Test
    fun `identical text in a WhatsApp and a Viber group stays two messages`() = runTest {
        useCase(viber())
        useCase(Snapshots.groupMessaging(group = "DPWH Flood Monitoring", messages = listOf(SnapshotMessage(report, T0, "Engineer A"))))
        assertEquals(2, messages.saved.size)
        assertEquals(setOf(VIBER, "com.whatsapp"), messages.saved.map { it.packageName }.toSet())
        assertNotEquals(messages.saved[0].fingerprint, messages.saved[1].fingerprint)
    }

    @Test
    fun `viber message with incomplete metadata keeps the missing parts null`() = runTest {
        // No per-message sender and no self name: the sender is unknown, not invented.
        val snapshot = viber(messages = listOf(SnapshotMessage(report, T0, null))).copy(selfDisplayName = null)
        useCase(snapshot)
        assertNull(messages.saved.single().senderName)
    }

    @Test
    fun `viber private chats and unauthorized groups are not captured`() = runTest {
        assertEquals(ProcessingOutcome.Ignored(IgnoreReason.NOT_A_GROUP), useCase(viber(isGroup = false)))
        assertEquals(ProcessingOutcome.Ignored(IgnoreReason.GROUP_NOT_AUTHORIZED), useCase(viber(group = "Family")))
        assertTrue(messages.saved.isEmpty())
    }

    private companion object {
        const val VIBER = "com.viber.voip"
    }
}

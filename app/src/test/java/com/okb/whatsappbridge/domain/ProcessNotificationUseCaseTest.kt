package com.okb.whatsappbridge.domain

import com.okb.whatsappbridge.domain.model.BridgeSettings
import com.okb.whatsappbridge.domain.model.MediaType
import com.okb.whatsappbridge.domain.usecase.ProcessNotificationUseCase
import com.okb.whatsappbridge.domain.usecase.ProcessingOutcome
import com.okb.whatsappbridge.domain.usecase.SyncTrigger
import com.okb.whatsappbridge.fakes.FakeGroupRepository
import com.okb.whatsappbridge.fakes.FakeMessageRepository
import com.okb.whatsappbridge.fakes.FakeScheduler
import com.okb.whatsappbridge.fakes.FakeSettingsRepository
import com.okb.whatsappbridge.fakes.Snapshots
import com.okb.whatsappbridge.fakes.Snapshots.T0
import com.okb.whatsappbridge.whatsapp.IgnoreReason
import com.okb.whatsappbridge.whatsapp.SnapshotMessage
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessNotificationUseCaseTest {

    private val now = T0 + 2_000
    private val settings = FakeSettingsRepository(BridgeSettings(monitoringEnabled = true))
    private val groups = FakeGroupRepository("OKB Monitoring")
    private val messages = FakeMessageRepository()
    private val scheduler = FakeScheduler()
    private val useCase = ProcessNotificationUseCase(settings, groups, messages, scheduler, clock = { now })

    @Test
    fun `authorized group message is stored and an upload is scheduled`() = runTest {
        val outcome = useCase(Snapshots.groupMessaging())
        assertEquals(ProcessingOutcome.Captured("OKB Monitoring", inserted = 1, duplicates = 0), outcome)
        val saved = messages.saved.single()
        assertEquals("OKB Monitoring", saved.groupName)
        assertEquals("Juan Santos", saved.senderName)
        assertEquals("Flooding observed at Barangay San Jose", saved.messageText)
        assertEquals(T0, saved.timestamp)
        assertEquals("com.whatsapp", saved.packageName)
        assertEquals(now, saved.capturedAt)
        assertEquals(listOf(SyncTrigger.IMMEDIATE), scheduler.requests)
        assertEquals(now, settings.state.value.lastNotificationAt)
        assertEquals(now, settings.state.value.lastProcessedAt)
    }

    @Test
    fun `reposted notification does not create duplicates or extra uploads`() = runTest {
        useCase(Snapshots.groupMessaging())
        val second = useCase(
            Snapshots.groupMessaging(
                messages = listOf(
                    SnapshotMessage("Flooding observed at Barangay San Jose", T0, "Juan Santos"),
                    SnapshotMessage("Water is knee deep", T0 + 60_000, "Ana"),
                ),
            ),
        )
        assertEquals(ProcessingOutcome.Captured("OKB Monitoring", inserted = 1, duplicates = 1), second)
        assertEquals(2, messages.saved.size)
        val third = useCase(Snapshots.groupMessaging())
        assertEquals(ProcessingOutcome.Captured("OKB Monitoring", inserted = 0, duplicates = 1), third)
        assertEquals(2, scheduler.requests.size)
    }

    @Test
    fun `nothing is processed while monitoring is paused`() = runTest {
        settings.setMonitoringEnabled(false)
        val outcome = useCase(Snapshots.groupMessaging())
        assertEquals(ProcessingOutcome.Ignored(IgnoreReason.MONITORING_PAUSED), outcome)
        assertTrue(messages.saved.isEmpty())
        assertTrue(scheduler.requests.isEmpty())
        assertNull(settings.state.value.lastProcessedAt)
    }

    @Test
    fun `unrelated packages are ignored without touching state`() = runTest {
        val outcome = useCase(Snapshots.groupMessaging(packageName = "org.telegram.messenger"))
        assertEquals(ProcessingOutcome.Ignored(IgnoreReason.UNSUPPORTED_APP), outcome)
        assertNull(settings.state.value.lastNotificationAt)
    }

    @Test
    fun `unauthorized group is not stored but remembered as discovered`() = runTest {
        val outcome = useCase(Snapshots.groupMessaging(group = "Family Group"))
        assertEquals(ProcessingOutcome.Ignored(IgnoreReason.GROUP_NOT_AUTHORIZED), outcome)
        assertTrue(messages.saved.isEmpty())
        val discovered = groups.groups.single { it.name == "Family Group" }
        assertFalse(discovered.authorized)
        assertTrue(discovered.discoveredAutomatically)
    }

    @Test
    fun `private chats are never captured or listed`() = runTest {
        val outcome = useCase(
            Snapshots.groupMessaging(group = "OKB Monitoring", conversationTitle = null, jid = "63917@s.whatsapp.net", isGroup = false),
        )
        assertEquals(ProcessingOutcome.Ignored(IgnoreReason.NOT_A_GROUP), outcome)
        assertTrue(messages.saved.isEmpty())
        assertEquals(1, groups.groups.size)
    }

    @Test
    fun `system notifications are ignored`() = runTest {
        val outcome = useCase(Snapshots.plain("WhatsApp", "WhatsApp Web is currently active"))
        assertEquals(ProcessingOutcome.Ignored(IgnoreReason.SYSTEM_NOTIFICATION), outcome)
    }

    @Test
    fun `photo notification text report is saved with unavailable media`() = runTest {
        useCase(Snapshots.groupMessaging(messages = listOf(SnapshotMessage("📷 Bridge collapsed", T0, "Ana")), hasPicture = true))
        val saved = messages.saved.single()
        assertEquals(MediaType.IMAGE, saved.mediaType)
        assertEquals("📷 Bridge collapsed", saved.messageText)
    }

    @Test
    fun `deleted message placeholders are skipped`() = runTest {
        useCase(Snapshots.groupMessaging(messages = listOf(SnapshotMessage("This message was deleted", T0, "Ana"))))
        assertTrue(messages.saved.isEmpty())
        assertTrue(scheduler.requests.isEmpty())
    }

    @Test
    fun `whatsapp business groups are captured as well`() = runTest {
        val outcome = useCase(Snapshots.groupMessaging(packageName = "com.whatsapp.w4b"))
        assertTrue(outcome is ProcessingOutcome.Captured)
        assertEquals("com.whatsapp.w4b", messages.saved.single().packageName)
    }
}

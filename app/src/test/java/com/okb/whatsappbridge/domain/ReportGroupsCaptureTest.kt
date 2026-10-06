package com.okb.whatsappbridge.domain

import com.okb.whatsappbridge.domain.model.BridgeSettings
import com.okb.whatsappbridge.domain.usecase.ProcessNotificationUseCase
import com.okb.whatsappbridge.domain.usecase.ProcessingOutcome
import com.okb.whatsappbridge.fakes.FakeGroupRepository
import com.okb.whatsappbridge.fakes.FakeMessageRepository
import com.okb.whatsappbridge.fakes.FakeScheduler
import com.okb.whatsappbridge.fakes.FakeSettingsRepository
import com.okb.whatsappbridge.fakes.Snapshots
import com.okb.whatsappbridge.fakes.Snapshots.T0
import com.okb.whatsappbridge.whatsapp.IgnoreReason
import com.okb.whatsappbridge.whatsapp.ReportGroups
import com.okb.whatsappbridge.whatsapp.SnapshotMessage
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** SOURCE group (reports are captured) vs DESTINATION group (consolidated reports are sent; never captured). */
class ReportGroupsCaptureTest {

    private val source = "NMDEO FLOOD MONITORING"
    private val destination = "OKB COMMAND CENTER"
    private val settings = FakeSettingsRepository(
        BridgeSettings(monitoringEnabled = true, sourceGroupName = source, destinationGroupName = destination),
    )
    private val groups = FakeGroupRepository("Some Other Authorized Group", destination)
    private val messages = FakeMessageRepository()
    private val scheduler = FakeScheduler()
    private val useCase = ProcessNotificationUseCase(settings, groups, messages, scheduler, clock = { T0 + 2_000 })

    private fun from(group: String, text: String) =
        Snapshots.groupMessaging(group = group, messages = listOf(SnapshotMessage(text, T0, "Field Engineer")))

    @Test
    fun `TEST 1 - a report from the source group is captured and an upload is scheduled`() = runTest {
        val outcome = useCase(from(source, "Flood height at Mel Lopez Blvd is 0.45m"))
        assertEquals(ProcessingOutcome.Captured(source, inserted = 1, duplicates = 0), outcome)
        assertEquals("Flood height at Mel Lopez Blvd is 0.45m", messages.saved.single().messageText)
        assertEquals(1, scheduler.requests.size)
    }

    @Test
    fun `source group matching ignores case and extra spaces`() = runTest {
        val outcome = useCase(from("  nmdeo   flood monitoring ", "Water level rising"))
        assertTrue(outcome is ProcessingOutcome.Captured)
    }

    @Test
    fun `TEST 2 - another group is ignored while a source group is set, even if it is on the allowlist`() = runTest {
        val outcome = useCase(from("Some Other Authorized Group", "Unrelated message"))
        assertEquals(ProcessingOutcome.Ignored(IgnoreReason.GROUP_NOT_AUTHORIZED), outcome)
        assertTrue(messages.saved.isEmpty())
    }

    @Test
    fun `TEST 3 - the destination group is never captured, even if it was authorized on the Groups tab`() = runTest {
        val outcome = useCase(from(destination, "Please see attached report"))
        assertEquals(ProcessingOutcome.Ignored(IgnoreReason.DESTINATION_GROUP), outcome)
        assertTrue(messages.saved.isEmpty())
        assertTrue(scheduler.requests.isEmpty())
    }

    @Test
    fun `system notifications such as WhatsApp Web is active stay ignored`() = runTest {
        val outcome = useCase(Snapshots.plain("WhatsApp", "WhatsApp Web is currently active"))
        assertEquals(ProcessingOutcome.Ignored(IgnoreReason.SYSTEM_NOTIFICATION), outcome)
    }

    @Test
    fun `without a source group the existing Groups allowlist still applies, minus the destination`() = runTest {
        settings.setSourceGroupName("")
        assertTrue(useCase(from("Some Other Authorized Group", "Flood at Molino")) is ProcessingOutcome.Captured)
        assertEquals(ProcessingOutcome.Ignored(IgnoreReason.DESTINATION_GROUP), useCase(from(destination, "Report attached")))
    }

    @Test
    fun `source and destination must be different groups`() {
        assertNotNull(ReportGroups.validate("OKB Command Center", "okb  command center"))
        assertNull(ReportGroups.validate(source, destination))
        assertNull(ReportGroups.validate("", ""))
        assertNotNull(ReportGroups.validate("x".repeat(101), destination))
    }

    @Test
    fun `allowlist spelling is kept for the source group so fingerprints stay stable`() {
        assertEquals(listOf("NMDEO Flood Monitoring"), ReportGroups.captureNames("nmdeo flood monitoring", listOf("NMDEO Flood Monitoring", "X")))
        assertEquals(listOf(source), ReportGroups.captureNames(source, emptyList()))
        assertEquals(listOf("A", "B"), ReportGroups.captureNames("", listOf("A", "B")))
    }
}

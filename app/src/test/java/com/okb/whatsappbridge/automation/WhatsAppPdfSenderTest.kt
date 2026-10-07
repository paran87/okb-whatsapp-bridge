package com.okb.whatsappbridge.automation

import com.okb.whatsappbridge.fakes.FakeWhatsApp
import com.okb.whatsappbridge.fakes.FakeWhatsApp.PickerMode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The automatic PDF send flow against a simulated WhatsApp "Send to" screen: right group only, every WhatsApp
 * variant of the confirmation steps, never twice, "sent" only when the PDF is in the chat.
 */
class WhatsAppPdfSenderTest {

    private val destination = "OKB COMMAND CENTER"
    private val source = "NMDEO FLOOD MONITORING"
    private val fileName = "OKB_Consolidated_Flood_Report_2026-10-06_1800.pdf"
    private val file = File(fileName)

    private var now = 0L
    private val events = mutableListOf<String>()
    private val progress = object : PdfSendProgress {
        override suspend fun beforePressSend() { events += "press" }
        override suspend fun sendNotRegistered() { events += "not registered" }
    }

    private fun whatsApp() = FakeWhatsApp(listOf("Family", source, destination, "DPWH Staff"))

    private fun sender(ui: WhatsAppUi) = WhatsAppPdfSender(ui, sleep = { now += it }, clock = { now })

    private fun request(dest: String = destination, pressedEarlier: Boolean = false) =
        PdfSendRequest("d1", dest, source, file, fileName, "📄 OKB CONSOLIDATED FLOOD MONITORING REPORT", pressedEarlier)

    @Test
    fun `selects the destination group, presses Send and confirms the PDF in the chat`() = runTest {
        val wa = whatsApp()
        val outcome = sender(wa).send(wa.pkg, request(), progress)

        assertEquals(SendOutcome.Sent("$fileName visible in \"$destination\" after Send"), outcome)
        assertEquals(listOf(fileName), wa.documentsIn(destination))
        assertTrue(wa.documentsIn(source).isEmpty())
        assertEquals(listOf(fileName), wa.shares)
        // Send on the list, then Send on the preview: progress saved before each press.
        assertEquals(listOf("press", "press"), events)
    }

    @Test
    fun `a WhatsApp version that asks to confirm in a dialog`() = runTest {
        val wa = whatsApp().apply { pickerMode = PickerMode.DIALOG }
        val outcome = sender(wa).send(wa.pkg, request(), progress)
        assertTrue(outcome is SendOutcome.Sent)
        assertEquals(listOf(fileName), wa.documentsIn(destination))
    }

    @Test
    fun `a WhatsApp version where the row opens the preview directly`() = runTest {
        val wa = whatsApp().apply { pickerMode = PickerMode.ROW_OPENS_PREVIEW }
        val outcome = sender(wa).send(wa.pkg, request(), progress)
        assertTrue(outcome is SendOutcome.Sent)
        assertEquals(listOf(fileName), wa.documentsIn(destination))
        assertEquals(listOf("press"), events)
    }

    @Test
    fun `a group not listed on the Send to screen is found with WhatsApp search`() = runTest {
        val wa = whatsApp().apply { pickerVisibleChats = listOf("Family", source) }
        val outcome = sender(wa).send(wa.pkg, request(), progress)
        assertTrue(outcome is SendOutcome.Sent)
        assertEquals(listOf(fileName), wa.documentsIn(destination))
        assertTrue(wa.documentsIn(source).isEmpty())
    }

    @Test
    fun `WhatsApp returns to the previous app after sending - the chat is checked`() = runTest {
        val wa = whatsApp().apply { returnToCallerAfterSend = true }
        val outcome = sender(wa).send(wa.pkg, request(), progress)
        assertEquals(SendOutcome.Sent("$fileName visible in \"$destination\" after Send"), outcome)
        assertEquals(1, wa.documentsIn(destination).size)
    }

    @Test
    fun `never sends to the source group`() = runTest {
        val wa = whatsApp()
        val outcome = sender(wa).send(wa.pkg, request(dest = " nmdeo  flood monitoring "), progress)
        assertEquals(SendOutcome.Failed("The destination group is the source group; refusing to send", retryable = false), outcome)
        assertTrue(wa.shares.isEmpty())
    }

    @Test
    fun `a missing destination group fails and shares nothing`() = runTest {
        val wa = whatsApp()
        val outcome = sender(wa).send(wa.pkg, request(dest = "  "), progress)
        assertEquals(SendOutcome.Failed("No destination group is set for this report", retryable = false), outcome)
        assertTrue(wa.shares.isEmpty())
    }

    @Test
    fun `a group that does not exist fails before anything is pressed`() = runTest {
        val wa = whatsApp()
        val outcome = sender(wa).send(wa.pkg, request(dest = "OKB COMMAND CENTRE 2"), progress)
        assertTrue(outcome is SendOutcome.Failed && outcome.retryable && outcome.reason.contains("was not found in WhatsApp"))
        assertTrue(events.isEmpty())
        assertEquals(0, wa.sendPresses)
    }

    @Test
    fun `the final Send does not register - fails with Send recorded as pressed`() = runTest {
        val wa = whatsApp().apply { pickerMode = PickerMode.ROW_OPENS_PREVIEW; finalSendIgnored = true }
        val outcome = sender(wa).send(wa.pkg, request(), progress)
        assertEquals(SendOutcome.Failed("Send was pressed but WhatsApp stayed on the same screen"), outcome)
        // The only press did nothing: safe to share again on the next attempt.
        assertEquals(listOf("press", "not registered"), events)
        assertTrue(wa.documentsIn(destination).isEmpty())
    }

    @Test
    fun `an earlier attempt that pressed Send - the PDF in the chat is confirmed, not shared again`() = runTest {
        val wa = whatsApp()
        wa.addMessage(destination, fileName)
        val outcome = sender(wa).send(wa.pkg, request(pressedEarlier = true), progress)
        assertEquals(SendOutcome.Sent("$fileName found in \"$destination\" (sent by an earlier attempt)"), outcome)
        assertTrue(wa.shares.isEmpty())
        assertEquals(1, wa.documentsIn(destination).size)
    }

    @Test
    fun `an earlier attempt that pressed Send but the PDF is not in the chat - never sent twice automatically`() = runTest {
        val wa = whatsApp()
        val outcome = sender(wa).send(wa.pkg, request(pressedEarlier = true), progress)
        assertEquals(SendOutcome.Failed(WhatsAppPdfSender.UNCONFIRMED_EARLIER, retryable = false), outcome)
        assertTrue(wa.shares.isEmpty())
        assertTrue(wa.documentsIn(destination).isEmpty())
    }

    @Test
    fun `the Send to screen cannot be opened`() = runTest {
        val wa = whatsApp().apply { shareWorks = false }
        val outcome = sender(wa).send(wa.pkg, request(), progress)
        assertEquals(SendOutcome.Failed("WhatsApp's \"Send to\" screen could not be opened"), outcome)
    }

    @Test
    fun `rows that ignore an accessibility click are selected by touching them`() = runTest {
        val wa = whatsApp().apply { pickerRowsTouchOnly = true }
        val outcome = sender(wa).send(wa.pkg, request(), progress)
        assertEquals(SendOutcome.Sent("$fileName visible in \"$destination\" after Send"), outcome)
        assertEquals(listOf(fileName), wa.documentsIn(destination))
        assertTrue(wa.documentsIn(source).isEmpty())
        assertEquals(1, wa.taps)
    }

    @Test
    fun `a click that WhatsApp accepts but ignores falls back to a touch`() = runTest {
        val wa = whatsApp().apply { pickerClickDoesNothing = true; pickerMode = PickerMode.DIALOG }
        val outcome = sender(wa).send(wa.pkg, request(), progress)
        assertTrue(outcome is SendOutcome.Sent)
        assertEquals(listOf(fileName), wa.documentsIn(destination))
        assertEquals(1, wa.taps)
    }

    @Test
    fun `touch-only rows found with WhatsApp search`() = runTest {
        val wa = whatsApp().apply { pickerRowsTouchOnly = true; pickerVisibleChats = listOf("Family") }
        val outcome = sender(wa).send(wa.pkg, request(), progress)
        assertTrue(outcome is SendOutcome.Sent)
        assertEquals(listOf(fileName), wa.documentsIn(destination))
        assertTrue(wa.documentsIn(source).isEmpty())
    }

    @Test
    fun `a row that neither a click nor a touch selects fails before anything is pressed`() = runTest {
        val wa = whatsApp().apply { pickerRowsTouchOnly = true; tapWorks = false }
        val outcome = sender(wa).send(wa.pkg, request(), progress)
        assertEquals(SendOutcome.Failed("\"$destination\" could not be selected in WhatsApp's \"Send to\" list"), outcome)
        assertTrue(events.isEmpty())
        assertEquals(0, wa.sendPresses)
    }
}

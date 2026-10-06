package com.okb.whatsappbridge.automation

import com.okb.whatsappbridge.fakes.FakeWhatsApp
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The automatic TEXT send flow against a simulated WhatsApp: right group only, never twice, "sent" only when the
 * message is in the chat (and not pending), clear reasons when something is wrong.
 */
class WhatsAppTextSenderTest {

    private val destination = "OKB COMMAND CENTER"
    private val source = "NMDEO FLOOD MONITORING"
    private val report = "📋 OKB CONSOLIDATED FLOOD MONITORING REPORT\nReporting Period: October 6, 2026, 06:00 PM – 12:00 AM\n" +
        "Reports: 2 · Ref: OKB-1A2B3C4D\n\n1) MM1DEO\nOVERALL SITUATION:\nFlooding along Acacia Lane."
    private val part = MessagePart(report, "OKB-1A2B3C4D")

    private var now = 0L
    private val events = mutableListOf<String>()
    private val progress = object : SendProgress {
        override suspend fun beforePressSend(ref: String) { events += "press $ref" }
        override suspend fun partConfirmed(ref: String, verification: String) { events += "confirmed $ref" }
    }

    private fun whatsApp(vararg chats: String = arrayOf("Family", source, destination, "DPWH Staff")) = FakeWhatsApp(chats.toList())

    private fun sender(ui: WhatsAppUi) = WhatsAppTextSender(ui, sleep = { now += it }, clock = { now })

    private fun request(parts: List<MessagePart> = listOf(part), sent: Set<String> = emptySet(), dest: String = destination) =
        TextSendRequest("d1", dest, source, parts, sent)

    @Test
    fun `sends the report into the destination group only and confirms it from the chat`() = runTest {
        val wa = whatsApp()
        val outcome = sender(wa).send(wa.pkg, request(), progress)

        assertTrue(outcome is SendOutcome.Sent)
        assertEquals(listOf(report), wa.messagesIn(destination))
        assertTrue(wa.messagesIn(source).isEmpty())
        assertEquals(1, wa.sendPresses)
        assertEquals(listOf("press OKB-1A2B3C4D", "confirmed OKB-1A2B3C4D"), events)
        assertTrue((outcome as SendOutcome.Sent).verification.contains("WhatsApp status: Delivered"))
    }

    @Test
    fun `never sends to the source group`() = runTest {
        val wa = whatsApp()
        val outcome = sender(wa).send(wa.pkg, request(dest = " nmdeo  flood monitoring "), progress)
        assertEquals(SendOutcome.Failed("The destination group is the source group; refusing to send", retryable = false), outcome)
        assertTrue(wa.typedTexts.isEmpty())
        assertEquals(0, wa.sendPresses)
    }

    @Test
    fun `a missing destination group fails with a clear reason and sends nothing`() = runTest {
        val wa = whatsApp("Family", source)
        val outcome = sender(wa).send(wa.pkg, request(), progress) as SendOutcome.Failed
        assertTrue(outcome.reason, outcome.reason.contains("was not found in WhatsApp"))
        assertTrue(outcome.retryable)
        assertEquals(0, wa.sendPresses)
        assertTrue(wa.history.values.all { it.isEmpty() })
    }

    @Test
    fun `wrong chat after a search result is refused before typing`() = runTest {
        val wa = SearchOnlyWhatsApp(listOf("Family", source), hidden = destination)
        wa.searchOpensInsteadOf = destination to "Family"
        val outcome = sender(wa).send(wa.pkg, request(), progress) as SendOutcome.Failed
        assertTrue(outcome.reason, outcome.reason.contains("not \"$destination\""))
        assertTrue(wa.typedTexts.isEmpty())
        assertTrue(wa.messagesIn("Family").isEmpty())
    }

    @Test
    fun `search path finds a group that is not visible on the chat list`() = runTest {
        val wa = SearchOnlyWhatsApp(listOf("Family", source), hidden = destination)
        val outcome = sender(wa).send(wa.pkg, request(), progress)
        assertTrue(outcome is SendOutcome.Sent)
        assertEquals(listOf(report), wa.messagesIn(destination))
    }

    @Test
    fun `a report already in the chat (earlier interrupted attempt) is confirmed, not sent again`() = runTest {
        val wa = whatsApp()
        wa.addMessage(destination, report, status = "Read")
        repeat(12) { wa.addMessage(destination, "later message $it") } // scrolled out of view
        val outcome = sender(wa).send(wa.pkg, request(), progress) as SendOutcome.Sent
        assertEquals(1, wa.messagesIn(destination).count { it == report })
        assertEquals(0, wa.sendPresses)
        assertTrue(wa.typedTexts.isEmpty())
        assertTrue(outcome.verification, outcome.verification.contains("earlier attempt"))
    }

    @Test
    fun `pending in WhatsApp is not sent - the next attempt confirms it without a duplicate`() = runTest {
        val wa = whatsApp()
        wa.newMessageStatus = "Pending"
        val first = sender(wa).send(wa.pkg, request(), progress) as SendOutcome.Failed
        assertTrue(first.reason, first.reason.contains("has not sent it yet"))
        assertTrue(first.retryable)
        assertEquals(listOf(report), wa.messagesIn(destination))

        wa.connectionRestored()
        val second = sender(wa).send(wa.pkg, request(), progress)
        assertTrue(second is SendOutcome.Sent)
        assertEquals(listOf(report), wa.messagesIn(destination)) // still exactly one
        assertEquals(1, wa.sendPresses)
    }

    @Test
    fun `a Send press that did not register fails, and the retry sends exactly once`() = runTest {
        val wa = whatsApp()
        wa.sendPressIgnored = true
        val first = sender(wa).send(wa.pkg, request(), progress) as SendOutcome.Failed
        assertTrue(first.reason, first.reason.contains("did not appear"))
        assertTrue(wa.messagesIn(destination).isEmpty())

        wa.sendPressIgnored = false
        assertTrue(sender(wa).send(wa.pkg, request(), progress) is SendOutcome.Sent)
        assertEquals(listOf(report), wa.messagesIn(destination))
    }

    @Test
    fun `the typed draft in the message box never counts as sent`() = runTest {
        val wa = whatsApp()
        wa.sendPressIgnored = true // text stays in the box
        sender(wa).send(wa.pkg, request(), progress)
        wa.sendPressIgnored = false
        // The draft (with the reference) is in the box, not in the chat: it must be sent, not "confirmed".
        val outcome = sender(wa).send(wa.pkg, request(), progress)
        assertTrue(outcome is SendOutcome.Sent)
        assertEquals(1, wa.messagesIn(destination).size)
        assertTrue(events.count { it.startsWith("press") } == 2)
    }

    @Test
    fun `multi-part report resumes after the parts already confirmed`() = runTest {
        val wa = whatsApp()
        val p1 = MessagePart("Part 1 · Ref: OKB-1A2B3C4D-1\nA", "OKB-1A2B3C4D-1")
        val p2 = MessagePart("Part 2 · Ref: OKB-1A2B3C4D-2\nB", "OKB-1A2B3C4D-2")
        wa.addMessage(destination, p1.text, "Delivered")
        val outcome = sender(wa).send(wa.pkg, request(listOf(p1, p2), sent = setOf(p1.ref)), progress)
        assertTrue(outcome is SendOutcome.Sent)
        assertEquals(listOf(p1.text, p2.text), wa.messagesIn(destination))
    }

    @Test
    fun `two chats with the same name are refused`() = runTest {
        val wa = whatsApp("Family", destination, destination)
        val outcome = sender(wa).send(wa.pkg, request(), progress) as SendOutcome.Failed
        assertTrue(outcome.reason.contains("Several chats"))
        assertFalse(outcome.retryable)
        assertEquals(0, wa.sendPresses)
    }

    @Test
    fun `unreadable tick status - the visible message is the confirmation`() = runTest {
        val wa = whatsApp()
        wa.showStatusIcons = false
        val outcome = sender(wa).send(wa.pkg, request(), progress) as SendOutcome.Sent
        assertTrue(outcome.verification.contains("status not readable"))
        assertEquals(1, wa.messagesIn(destination).size)
    }

    @Test
    fun `WhatsApp not starting (e g locked screen) fails without sending`() = runTest {
        val wa = whatsApp()
        wa.launchWorks = false
        val outcome = sender(wa).send(wa.pkg, request(), progress) as SendOutcome.Failed
        assertEquals("WhatsApp could not be opened", outcome.reason)
    }

    @Test
    fun `no destination or empty text is refused`() = runTest {
        val wa = whatsApp()
        assertFalse((sender(wa).send(wa.pkg, request(dest = " "), progress) as SendOutcome.Failed).retryable)
        assertFalse((sender(wa).send(wa.pkg, request(parts = listOf(MessagePart(" ", "x"))), progress) as SendOutcome.Failed).retryable)
    }

    /** WhatsApp whose chat list does not show [hidden]; only search finds it. */
    private class SearchOnlyWhatsApp(visible: List<String>, private val hidden: String) : WhatsAppUi {
        val inner = FakeWhatsApp(visible + hidden)
        val pkg get() = inner.pkg
        var searchOpensInsteadOf: Pair<String, String>?
            get() = inner.searchOpensInsteadOf
            set(v) { inner.searchOpensInsteadOf = v }
        val typedTexts get() = inner.typedTexts
        fun messagesIn(chat: String) = inner.messagesIn(chat)

        override fun root(): UiNode? {
            val root = inner.root() ?: return null
            // On the chat list, drop the hidden group's row.
            val isList = UiTree.byId(root, "menuitem_search").isNotEmpty()
            return if (isList) Filtered(root) { n -> n.children.none { c -> c.text == hidden } } else root
        }
        override fun foregroundPackage() = inner.foregroundPackage()
        override fun launch(packageName: String) = inner.launch(packageName)
        override fun back() = inner.back()
        override fun home() = inner.home()

        private class Filtered(private val node: UiNode, private val keep: (UiNode) -> Boolean) : UiNode by node {
            override val children: List<UiNode> get() = node.children.filter(keep).map { Filtered(it, keep) }
        }
    }
}

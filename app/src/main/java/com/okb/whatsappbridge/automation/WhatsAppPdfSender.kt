package com.okb.whatsappbridge.automation

import kotlinx.coroutines.delay
import java.io.File

data class PdfSendRequest(
    val deliveryId: String,
    /** The group to send to, fixed by the backend when the report was generated (else the phone's setting). */
    val destinationGroup: String,
    /** The group reports are captured from. Never a destination. */
    val sourceGroup: String?,
    /** The verified local PDF. */
    val file: File,
    /** Its file name, as WhatsApp shows it in the chat. */
    val fileName: String,
    val caption: String,
    /**
     * An earlier attempt pressed Send without confirming the result. The PDF is then NOT shared again: the chat
     * is checked for it instead, so nothing is duplicated.
     */
    val pressedEarlier: Boolean = false,
)

/** Lets the caller persist progress before the irreversible step (pressing Send). */
interface PdfSendProgress {
    suspend fun beforePressSend()

    /** The first press did not register (WhatsApp stayed on the same screen): nothing was sent. */
    suspend fun sendNotRegistered() {}
}

/**
 * Sends a consolidated report PDF into one WhatsApp group with no operator interaction, by driving WhatsApp's
 * own "Send to" screen through the accessibility service on the phone:
 *
 *   share the PDF to WhatsApp → "Send to" list: find the DESTINATION group (visible, else WhatsApp search) and
 *   select it → press WhatsApp's Send / confirm buttons until the chat opens → check the chat is that group and
 *   the PDF (its file name) is in it.
 *
 * Safety rules (as for the TEXT report, see [WhatsAppTextSender]):
 *  - The destination must be set and must not be the source group; only a row named exactly like it is selected.
 *  - Progress is saved before every Send press. When an attempt pressed Send but could not confirm the result,
 *    the next attempt only looks for the PDF in the chat and never shares it a second time.
 *  - A WhatsApp redesign can break a step; it then fails with a clear reason, and the operator can still send
 *    the PDF by hand ("Send as PDF").
 */
class WhatsAppPdfSender(
    private val ui: WhatsAppUi,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
    private val timing: WhatsAppTextSender.Timing = WhatsAppTextSender.Timing(),
) {

    private class Abort(val outcome: SendOutcome.Failed) : Exception(outcome.reason)

    private enum class Screen { LEFT, CHAT, DIALOG, PICKER, PREVIEW, UNKNOWN }

    suspend fun send(packageName: String, request: PdfSendRequest, progress: PdfSendProgress): SendOutcome {
        val destination = request.destinationGroup.trim()
        if (destination.isEmpty()) return SendOutcome.Failed("No destination group is set for this report", retryable = false)
        val source = request.sourceGroup?.trim().orEmpty()
        if (source.isNotEmpty() && WhatsAppTextSender.same(source, destination)) {
            return SendOutcome.Failed("The destination group is the source group; refusing to send", retryable = false)
        }
        if (request.fileName.isBlank()) return SendOutcome.Failed("The report has no file name", retryable = false)
        val chat = WhatsAppTextSender(ui, sleep, clock, log, timing)

        if (request.pressedEarlier) return lookForEarlierSend(chat, packageName, destination, request.fileName)

        return try {
            openSendTo(packageName, request, destination)
            pressThrough(chat, packageName, destination, request.fileName, progress)
        } catch (e: Abort) {
            e.outcome
        }
    }

    /** An earlier attempt pressed Send: confirm from the chat, never send again. */
    private suspend fun lookForEarlierSend(chat: WhatsAppTextSender, pkg: String, destination: String, fileName: String): SendOutcome =
        when (val found = chat.lookInChat(pkg, destination, fileName)) {
            ChatSearch.Found -> SendOutcome.Sent("$fileName found in \"$destination\" (sent by an earlier attempt)")
            ChatSearch.NotFound -> SendOutcome.Failed(UNCONFIRMED_EARLIER, retryable = false)
            is ChatSearch.Failed -> SendOutcome.Failed(found.reason)
        }

    // ---- WhatsApp's "Send to" screen ------------------------------------------------------------------------

    private suspend fun openSendTo(pkg: String, request: PdfSendRequest, destination: String) {
        if (!ui.shareFile(pkg, request.file, request.caption)) fail("WhatsApp's \"Send to\" screen could not be opened")
        var inFront: String? = null
        val appeared = poll(timing.stepTimeoutMs) {
            inFront = ui.foregroundPackage()
            inFront == pkg && screen(ui.root()) == Screen.PICKER
        }
        if (!appeared) {
            fail(
                if (inFront == pkg) "WhatsApp did not show its \"Send to\" list for the PDF"
                else "WhatsApp did not come to the foreground (on screen: ${inFront ?: "another app or the lock screen"}). " +
                    "On Xiaomi/Redmi/POCO allow \"Display pop-up windows while running in the background\" and \"Show on Lock screen\" " +
                    "for OKB WhatsApp Bridge; a PIN, pattern or password lock also prevents it.",
            )
        }
        val row = matchingRows(ui.root(), destination).firstOrNull() ?: searchFor(destination)
        log("Selecting \"$destination\" for the PDF")
        select(row, destination)
    }

    /**
     * Selects the destination row and waits until WhatsApp shows it is selected (its Send button, a preview or
     * a "Send to …?" dialog). Some WhatsApp versions ignore an accessibility click on these rows, so the row
     * is then touched on the screen like a finger would.
     */
    private suspend fun select(row: UiNode, destination: String) {
        if (UiTree.click(row) && poll(SELECT_WAIT_MS) { selectionShown() }) return
        // The click was refused or did nothing: touch the row (found again, it may have moved).
        val target = matchingRows(ui.root(), destination).firstOrNull() ?: row
        val area = touchArea(target)
        if (area != null && ui.tap(area.centerX, area.centerY) && poll(SELECT_WAIT_MS) { selectionShown() }) {
            log("\"$destination\" selected by touching the row")
            return
        }
        log("Row not selectable: ${describe(target)}")
        fail("\"$destination\" could not be selected in WhatsApp's \"Send to\" list")
    }

    /** WhatsApp reacted to the selection: a Send button on the list, or the next screen. */
    private fun selectionShown(): Boolean {
        val root = ui.root() ?: return false
        return when (screen(root)) {
            Screen.PICKER -> sendButton(root) != null
            Screen.UNKNOWN -> false
            else -> true
        }
    }

    /** Presses [node]: an accessibility click, else a touch on it. */
    private suspend fun press(node: UiNode): Boolean {
        if (UiTree.click(node)) return true
        val area = touchArea(node) ?: return false
        return ui.tap(area.centerX, area.centerY)
    }

    /** The on-screen area of [node]'s row (its clickable ancestor, else itself), when visible. */
    private fun touchArea(node: UiNode): ScreenRect? =
        listOfNotNull(UiTree.clickTarget(node), node).firstNotNullOfOrNull { it.bounds?.takeIf { b -> !b.isEmpty } }

    /** Shape of a row for the log (classes, ids and clickability up the tree; no message content). */
    private fun describe(node: UiNode): String = generateSequence(node) { it.parent }.take(5).joinToString(" < ") {
        "${it.className?.substringAfterLast('.')}${UiTree.idName(it)?.let { id -> "#$id" } ?: ""}${if (it.isClickable) "(clickable)" else ""}"
    }

    private suspend fun searchFor(destination: String): UiNode {
        val button = awaitNode("WhatsApp search was not found on the \"Send to\" screen") { searchButton(it) }
        press(button)
        val field = awaitNode("WhatsApp search field was not found") { searchField(it) }
        if (!field.setText(destination)) fail("The group name could not be entered in WhatsApp search")
        return awaitNode(
            "The destination group \"$destination\" was not found in WhatsApp. Check the name on the phone " +
                "(Settings → WhatsApp Report Groups) and that this WhatsApp account is in the group.",
        ) { matchingRows(it, destination).firstOrNull() }
    }

    /**
     * Presses WhatsApp's Send (and any confirmation) until the destination chat opens, then confirms the PDF is
     * there. Different WhatsApp versions show a Send button on the list, a preview with a caption, or a "Send to
     * …?" dialog; each is handled as it appears.
     */
    private suspend fun pressThrough(
        chat: WhatsAppTextSender,
        pkg: String,
        destination: String,
        fileName: String,
        progress: PdfSendProgress,
    ): SendOutcome {
        var presses = 0
        while (true) {
            var current = Screen.UNKNOWN
            var button: UiNode? = null
            val ready = poll(timing.stepTimeoutMs) {
                current = settledScreen(pkg)
                button = if (current == Screen.LEFT || current == Screen.CHAT) null else pressable(ui.root(), current)
                current == Screen.LEFT || current == Screen.CHAT || button != null
            }
            when {
                !ready -> fail(if (presses == 0) "WhatsApp did not show its Send button for the PDF" else "WhatsApp stopped on an unexpected screen after Send")
                current == Screen.CHAT -> {
                    if (presses == 0) fail("WhatsApp opened a chat before the PDF was sent")
                    return confirmInChat(destination, fileName)
                }
                current == Screen.LEFT -> {
                    if (presses == 0) fail("WhatsApp closed the \"Send to\" screen before the PDF was sent")
                    // WhatsApp went back to the previous app after sending: look in the chat.
                    return when (val found = chat.lookInChat(pkg, destination, fileName)) {
                        ChatSearch.Found -> SendOutcome.Sent("$fileName visible in \"$destination\" after Send")
                        ChatSearch.NotFound -> SendOutcome.Failed(UNCONFIRMED_NOW, retryable = false)
                        is ChatSearch.Failed -> SendOutcome.Failed("${found.reason} (Send was pressed; the next attempt checks the chat)")
                    }
                }
            }
            if (presses >= MAX_PRESSES) fail("WhatsApp kept asking to confirm; the PDF may not have been sent (the next attempt checks the chat)")
            val before = current
            progress.beforePressSend()
            if (!press(button!!)) {
                if (presses == 0) progress.sendNotRegistered()
                fail("The WhatsApp Send button could not be pressed")
            }
            presses++
            log("Send pressed for the PDF (step $presses, ${before.name.lowercase()})")
            val moved = poll(timing.confirmTimeoutMs) { settledScreen(pkg) != before }
            if (!moved) {
                if (presses == 1) progress.sendNotRegistered()
                fail("Send was pressed but WhatsApp stayed on the same screen")
            }
        }
    }

    /** The destination chat is open after Send: check it is the right group and the PDF is in it. */
    private suspend fun confirmInChat(destination: String, fileName: String): SendOutcome {
        val title = chatTitle(ui.root())
        if (title != null && !WhatsAppTextSender.same(title, destination)) {
            return SendOutcome.Failed("After Send WhatsApp opened \"$title\", not \"$destination\". Check that chat.", retryable = false)
        }
        val seen = poll(timing.confirmTimeoutMs) { inChat(ui.root(), fileName) }
        return SendOutcome.Sent(
            if (seen) "$fileName visible in \"$destination\" after Send"
            else "WhatsApp opened \"$destination\" after Send; the file name could not be read back from the chat",
        )
    }

    // ---- reading WhatsApp's screens -----------------------------------------------------------------------

    /** The current screen; leaving WhatsApp only counts once it lasts (transitions briefly show no window). */
    private suspend fun settledScreen(pkg: String): Screen {
        val now = screenOf(pkg)
        if (now != Screen.LEFT) return now
        sleep(LEFT_SETTLE_MS)
        return screenOf(pkg)
    }

    private fun screenOf(pkg: String): Screen {
        if (ui.foregroundPackage() != pkg) return Screen.LEFT
        return screen(ui.root())
    }

    private fun screen(root: UiNode?): Screen {
        if (root == null) return Screen.UNKNOWN
        if (chatTitle(root) != null && entryField(root) != null) return Screen.CHAT
        if (UiTree.walk(root).any { it.viewId == DIALOG_OK }) return Screen.DIALOG
        if (pickerRows(root).isNotEmpty()) return Screen.PICKER
        if (sendButton(root) != null) return Screen.PREVIEW
        return if (searchField(root) != null || searchButton(root) != null) Screen.PICKER else Screen.UNKNOWN
    }

    private fun pressable(root: UiNode?, screen: Screen): UiNode? = when (screen) {
        Screen.DIALOG -> UiTree.walk(root).firstOrNull { it.viewId == DIALOG_OK }
        Screen.PICKER, Screen.PREVIEW -> sendButton(root)
        else -> null
    }

    private fun pickerRows(root: UiNode?): List<UiNode> = UiTree.byId(root, *PICKER_ROW_IDS)

    /** Rows named exactly like the destination (case and spacing ignored); the same group may be listed twice. */
    private fun matchingRows(root: UiNode?, destination: String): List<UiNode> {
        val byId = pickerRows(root).filter { it.text?.let { t -> WhatsAppTextSender.same(t, destination) } == true }
        if (byId.isNotEmpty()) return byId
        if (entryField(root) != null) return emptyList()
        return UiTree.walk(root).filter { node ->
            !node.isEditable && UiTree.idName(node) !in WhatsAppTextSender.SEARCH_FIELD_IDS &&
                node.text?.let { WhatsAppTextSender.same(it, destination) } == true &&
                (UiTree.clickTarget(node) != null || node.bounds?.isEmpty == false)
        }.toList()
    }

    private fun inChat(root: UiNode?, fileName: String): Boolean = UiTree.walk(root).any { node ->
        !node.isEditable && (node.text?.contains(fileName) == true || node.contentDescription?.contains(fileName) == true)
    }

    private fun chatTitle(root: UiNode?): String? =
        UiTree.byId(root, *WhatsAppTextSender.TITLE_IDS).firstNotNullOfOrNull { it.text?.takeIf(String::isNotBlank) }

    private fun entryField(root: UiNode?): UiNode? = UiTree.byId(root, *WhatsAppTextSender.ENTRY_IDS).firstOrNull { it.isEditable }

    private fun sendButton(root: UiNode?): UiNode? =
        UiTree.byId(root, *WhatsAppTextSender.SEND_IDS).firstOrNull()
            ?: UiTree.walk(root).firstOrNull { it.contentDescription?.trim().equals("Send", ignoreCase = true) }

    private fun searchButton(root: UiNode?): UiNode? =
        UiTree.byId(root, *WhatsAppTextSender.SEARCH_BUTTON_IDS).firstOrNull()
            ?: UiTree.walk(root).firstOrNull { it.contentDescription?.trim().equals("Search", ignoreCase = true) }

    private fun searchField(root: UiNode?): UiNode? = UiTree.byId(root, *WhatsAppTextSender.SEARCH_FIELD_IDS).firstOrNull { it.isEditable }

    // ---- waiting ------------------------------------------------------------------------------------------

    private suspend fun poll(timeoutMs: Long, condition: suspend () -> Boolean): Boolean {
        val start = clock()
        while (true) {
            if (condition()) return true
            if (clock() - start >= timeoutMs) return false
            sleep(timing.pollMs)
        }
    }

    private suspend fun awaitNode(problem: String, find: (UiNode?) -> UiNode?): UiNode {
        var found: UiNode? = null
        if (!poll(timing.stepTimeoutMs) { find(ui.root()).also { found = it } != null }) fail(problem)
        return found!!
    }

    private fun fail(reason: String, retryable: Boolean = true): Nothing = throw Abort(SendOutcome.Failed(reason, retryable))

    companion object {
        const val UNCONFIRMED_EARLIER =
            "An earlier attempt pressed Send for this PDF but it cannot be found in the chat. It is not sent again " +
                "automatically (no duplicates); check the group and tap \"Send as PDF\" only if it is missing."
        const val UNCONFIRMED_NOW =
            "Send was pressed but the PDF cannot be found in the chat. It is not sent again automatically (no " +
                "duplicates); check the group and tap \"Send as PDF\" only if it is missing."
        /** WhatsApp's "Send to" list rows (older versions reuse the chat list row). */
        val PICKER_ROW_IDS = arrayOf("contactpicker_row_name", "contact_row_name", "conversations_row_contact_name")
        /** The positive button of an Android dialog ("Send", "OK"). */
        const val DIALOG_OK = "android:id/button1"
        const val MAX_PRESSES = 4
        const val LEFT_SETTLE_MS = 1_500L
        /** How long WhatsApp gets to show a selection before the row is touched instead. */
        const val SELECT_WAIT_MS = 3_000L
    }
}

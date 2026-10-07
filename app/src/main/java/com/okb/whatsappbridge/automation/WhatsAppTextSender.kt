package com.okb.whatsappbridge.automation

import com.okb.whatsappbridge.whatsapp.GroupAllowlist
import kotlinx.coroutines.delay

/** One WhatsApp message of a consolidated TEXT report; [ref] ("OKB-1A2B3C4D") is printed in its header. */
data class MessagePart(val text: String, val ref: String)

data class TextSendRequest(
    val deliveryId: String,
    /** The group to send to, fixed by the backend when the report was generated. */
    val destinationGroup: String,
    /** The group reports are captured from. Never a destination. */
    val sourceGroup: String?,
    val parts: List<MessagePart>,
    /** Parts already confirmed in the chat by an earlier attempt (never sent again). */
    val alreadySentRefs: Set<String> = emptySet(),
)

sealed interface SendOutcome {
    /** Every part is in the destination chat. */
    data class Sent(val verification: String) : SendOutcome

    /** Not (completely) sent. [retryable] = false only for problems another attempt cannot fix. */
    data class Failed(val reason: String, val retryable: Boolean = true) : SendOutcome
}

/** Callbacks that let the caller persist progress before and after the irreversible step (pressing Send). */
interface SendProgress {
    suspend fun beforePressSend(ref: String)
    suspend fun partConfirmed(ref: String, verification: String)
}

/**
 * Sends a consolidated TEXT report into one WhatsApp group by driving WhatsApp's own UI (through the
 * accessibility service on the phone), with no operator interaction:
 *
 *   open WhatsApp → find the DESTINATION group (chat list, else WhatsApp search) → open it → check the chat
 *   title is exactly that group → look for the report's reference in the chat (an earlier attempt may already
 *   have sent it) → enter the text → press Send → confirm the message with that reference is in the chat →
 *   read WhatsApp's tick status where it is readable.
 *
 * Safety rules:
 *  - The destination must be set and must not be the source group; the chat title is checked before typing.
 *  - The typed draft in the message box is never mistaken for a sent message (editable nodes are ignored).
 *  - A message whose reference is already in the chat is never sent again.
 *  - "Sent" needs the message to be visible in the chat after Send. A message WhatsApp still shows as pending
 *    (clock icon, e.g. no connection) is NOT reported as sent; the next attempt finds it and confirms it.
 *
 * WhatsApp has no public API for this; view ids below are the ones WhatsApp has used for years, each with a
 * fallback by role (editable field, "Send"/"Search" descriptions). A WhatsApp redesign can still break a step;
 * every step then fails with a clear reason and nothing is sent.
 */
class WhatsAppTextSender(
    private val ui: WhatsAppUi,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
    private val timing: Timing = Timing(),
) {

    data class Timing(
        val stepTimeoutMs: Long = 12_000,
        val pollMs: Long = 250,
        val confirmTimeoutMs: Long = 25_000,
        val statusTimeoutMs: Long = 30_000,
        /** Unknown tick status for this long → accept the visible message as the confirmation. */
        val unknownStatusMs: Long = 2_500,
        /** Screens of chat history searched for an earlier copy before sending. */
        val historyPages: Int = 8,
    )

    private class Abort(val outcome: SendOutcome.Failed) : Exception(outcome.reason)

    suspend fun send(packageName: String, request: TextSendRequest, progress: SendProgress): SendOutcome {
        val destination = request.destinationGroup.trim()
        if (destination.isEmpty()) return SendOutcome.Failed("No destination group is set for this report", retryable = false)
        val source = request.sourceGroup?.trim().orEmpty()
        if (source.isNotEmpty() && same(source, destination)) {
            return SendOutcome.Failed("The destination group is the source group; refusing to send", retryable = false)
        }
        if (request.parts.isEmpty() || request.parts.any { it.text.isBlank() || it.ref.isBlank() }) {
            return SendOutcome.Failed("The report has no message text", retryable = false)
        }
        return try {
            openChat(packageName, destination)
            val confirmations = mutableListOf<String>()
            for (part in request.parts) {
                if (part.ref in request.alreadySentRefs) continue
                confirmations += sendPart(part, destination, progress)
            }
            SendOutcome.Sent(confirmations.lastOrNull() ?: "already confirmed in \"$destination\" by an earlier attempt")
        } catch (e: Abort) {
            e.outcome
        }
    }

    // ---- open the destination chat ------------------------------------------------------------------------

    private suspend fun openChat(packageName: String, destination: String) {
        if (!ui.launch(packageName)) fail("WhatsApp could not be opened")
        var inFront: String? = null
        val appeared = poll(timing.stepTimeoutMs) {
            inFront = ui.foregroundPackage()
            inFront == packageName && ui.root() != null
        }
        if (!appeared) {
            fail(
                "WhatsApp did not come to the foreground (on screen: ${inFront ?: "another app or the lock screen"}). " +
                    "On Xiaomi/Redmi/POCO allow \"Display pop-up windows while running in the background\" and \"Show on Lock screen\" " +
                    "for OKB WhatsApp Bridge; a PIN, pattern or password lock also prevents it.",
            )
        }
        // WhatsApp may reopen a chat or another tab: go back to the chat list (bounded).
        for (i in 0 until 3) {
            val root = ui.root()
            if (chatRows(root).isNotEmpty() || searchButton(root) != null) break
            ui.back()
            sleep(timing.pollMs * 2)
        }

        val visible = matchingRows(ui.root(), destination)
        if (visible.size > 1) fail("Several chats are named \"$destination\"; give the destination group a unique name", retryable = false)
        val row = visible.firstOrNull() ?: searchFor(destination)
        log("Opening \"$destination\"")
        if (!UiTree.click(row)) fail("The \"$destination\" chat could not be opened")

        var title: String? = null
        val opened = poll(timing.stepTimeoutMs) {
            val root = ui.root()
            title = chatTitle(root)
            entryField(root) != null && title != null
        }
        if (!opened) fail("The \"$destination\" chat did not open")
        if (!same(title!!, destination)) fail("The open chat is \"$title\", not \"$destination\"; nothing was sent")
    }

    private suspend fun searchFor(destination: String): UiNode {
        val button = awaitNode("WhatsApp search was not found") { searchButton(it) }
        UiTree.click(button)
        val field = awaitNode("WhatsApp search field was not found") { searchField(it) }
        if (!field.setText(destination)) fail("The group name could not be entered in WhatsApp search")
        // Search results: the first exact match (the Chats section comes first; a message result opens the same chat).
        return awaitNode("The destination group \"$destination\" was not found in WhatsApp. Check the name on the phone (Settings → WhatsApp Report Groups) and that this WhatsApp account is in the group.") {
            matchingRows(it, destination).firstOrNull()
        }
    }

    // ---- one message ----------------------------------------------------------------------------------------

    private suspend fun sendPart(part: MessagePart, destination: String, progress: SendProgress): String {
        // Never twice: an earlier attempt (interrupted, or offline) may already have put it in the chat.
        if (findInChat(part.ref, searchHistory = true) != null) {
            val verification = confirmStatus(part.ref, destination, alreadyThere = true)
            progress.partConfirmed(part.ref, verification)
            log("Ref ${part.ref} already in the chat: not sent again")
            return verification
        }

        val entry = awaitNode("The WhatsApp message box was not found") { entryField(it) }
        if (!entry.setText(part.text)) fail("The report text could not be entered in WhatsApp")
        val typed = poll(timing.stepTimeoutMs) { entryField(ui.root())?.text?.let { sameText(it, part.text) } == true }
        if (!typed) fail("WhatsApp did not accept the report text")

        val sendButton = awaitNode("The WhatsApp Send button was not found") { sendButton(it) }
        progress.beforePressSend(part.ref)
        if (!UiTree.click(sendButton)) fail("The WhatsApp Send button could not be pressed")
        log("Send pressed for Ref ${part.ref}")

        val appeared = poll(timing.confirmTimeoutMs) {
            val root = ui.root()
            entryField(root)?.text.isNullOrBlank() && findInChat(part.ref, searchHistory = false, root = root) != null
        }
        if (!appeared) fail("Send was pressed but the message did not appear in the chat")
        val verification = confirmStatus(part.ref, destination, alreadyThere = false)
        progress.partConfirmed(part.ref, verification)
        return verification
    }

    /** Waits for WhatsApp's tick status of the message. Pending → not sent (yet); unreadable → the visible message counts. */
    private suspend fun confirmStatus(ref: String, destination: String, alreadyThere: Boolean): String {
        val start = clock()
        var unknownSince: Long? = null
        while (true) {
            val node = findInChat(ref, searchHistory = false)
            val status = node?.let(::messageStatus)
            when {
                status == Status.CONFIRMED -> return verificationText(ref, destination, alreadyThere, statusLabel(node))
                status == Status.UNKNOWN || node == null -> {
                    val since = unknownSince ?: clock().also { unknownSince = it }
                    if (clock() - since >= timing.unknownStatusMs) {
                        if (node == null) fail("The message disappeared from the chat after sending")
                        return verificationText(ref, destination, alreadyThere, null)
                    }
                }
                else -> unknownSince = null // pending: keep waiting
            }
            if (clock() - start >= timing.statusTimeoutMs) {
                fail("The message is in the chat but WhatsApp has not sent it yet (pending, waiting for a connection). It is confirmed on the next attempt, not sent again.")
            }
            sleep(timing.pollMs)
        }
    }

    private fun verificationText(ref: String, destination: String, alreadyThere: Boolean, status: String?) =
        buildString {
            append(if (alreadyThere) "Ref $ref found in \"$destination\" (sent by an earlier attempt)" else "Ref $ref visible in \"$destination\" after Send")
            append(if (status != null) "; WhatsApp status: $status" else "; WhatsApp status not readable")
        }

    // ---- reading WhatsApp's screens ---------------------------------------------------------------------------

    private enum class Status { CONFIRMED, PENDING, UNKNOWN }

    private fun messageStatus(message: UiNode): Status {
        val labels = rowDescriptions(message)
        return when {
            labels.any { PENDING.matches(it) } -> Status.PENDING
            labels.any { CONFIRMED.matches(it) } -> Status.CONFIRMED
            else -> Status.UNKNOWN
        }
    }

    private fun statusLabel(message: UiNode): String? = rowDescriptions(message).firstOrNull { CONFIRMED.matches(it) }

    /**
     * Content descriptions in the message's own row (bubble, time and tick icon): the row is the ancestor that
     * sits directly in the chat list, so ticks of neighbouring messages are never read.
     */
    private fun rowDescriptions(message: UiNode): List<String> {
        var row: UiNode = message
        var depth = 0
        var inList = false
        while (depth < 6) {
            val parent = row.parent ?: break
            if (isList(parent)) { inList = true; break }
            row = parent
            depth++
        }
        if (!inList) row = message.parent ?: message
        return UiTree.walk(row).mapNotNull { it.contentDescription?.trim()?.trimEnd('.')?.takeIf(String::isNotEmpty) }.toList()
    }

    /** A sent message containing [ref]; the message box (editable) never counts. */
    private suspend fun findInChat(ref: String, searchHistory: Boolean, root: UiNode? = ui.root()): UiNode? {
        sentMessage(root, ref)?.let { return it }
        if (!searchHistory) return null
        repeat(timing.historyPages) {
            val list = UiTree.walk(ui.root()).firstOrNull { isList(it) } ?: return null
            if (!list.scrollBackward()) return null
            sleep(timing.pollMs)
            sentMessage(ui.root(), ref)?.let { return it }
        }
        return null
    }

    private fun sentMessage(root: UiNode?, ref: String): UiNode? = UiTree.walk(root).firstOrNull { node ->
        !node.isEditable && UiTree.idName(node) !in ENTRY_IDS && node.text?.contains(ref) == true
    }

    private fun chatRows(root: UiNode?): List<UiNode> = UiTree.byId(root, *CHAT_ROW_IDS)

    /** Rows whose chat name is exactly the destination (case and spacing ignored); search fields excluded. */
    private fun matchingRows(root: UiNode?, destination: String): List<UiNode> {
        val byId = chatRows(root).filter { it.text?.let { t -> same(t, destination) } == true }
        if (byId.isNotEmpty()) return byId.distinctBy { UiTree.clickTarget(it) ?: it }
        if (entryField(root) != null) return emptyList() // inside a chat, not a list
        return UiTree.walk(root).filter { node ->
            !node.isEditable && UiTree.idName(node) !in SEARCH_FIELD_IDS && node.text?.let { same(it, destination) } == true &&
                UiTree.clickTarget(node) != null
        }.distinctBy { UiTree.clickTarget(it) ?: it }.toList()
    }

    private fun chatTitle(root: UiNode?): String? = UiTree.byId(root, *TITLE_IDS).firstNotNullOfOrNull { it.text?.takeIf(String::isNotBlank) }

    private fun entryField(root: UiNode?): UiNode? {
        UiTree.byId(root, *ENTRY_IDS).firstOrNull { it.isEditable }?.let { return it }
        // Fallback by role, only on a chat screen (a chat title is shown).
        if (chatTitle(root) == null) return null
        return UiTree.walk(root).firstOrNull {
            it.isEditable && UiTree.idName(it) !in SEARCH_FIELD_IDS && it.className?.endsWith("EditText") == true
        }
    }

    private fun sendButton(root: UiNode?): UiNode? =
        UiTree.byId(root, *SEND_IDS).firstOrNull()
            ?: UiTree.walk(root).firstOrNull { it.contentDescription?.trim().equals("Send", ignoreCase = true) }

    private fun searchButton(root: UiNode?): UiNode? =
        UiTree.byId(root, *SEARCH_BUTTON_IDS).firstOrNull()
            ?: UiTree.walk(root).firstOrNull { it.contentDescription?.trim().equals("Search", ignoreCase = true) }

    private fun searchField(root: UiNode?): UiNode? =
        UiTree.byId(root, *SEARCH_FIELD_IDS).firstOrNull { it.isEditable } ?: UiTree.walk(root).firstOrNull { it.isEditable }

    private fun isList(node: UiNode): Boolean = node.className?.let { it.endsWith("ListView") || it.endsWith("RecyclerView") } == true

    // ---- waiting ----------------------------------------------------------------------------------------------

    private suspend fun poll(timeoutMs: Long, condition: suspend () -> Boolean): Boolean {
        val start = clock()
        while (true) {
            if (condition()) return true
            if (clock() - start >= timeoutMs) return false
            sleep(timing.pollMs)
        }
    }

    private suspend fun await(problem: String, condition: suspend () -> Boolean) {
        if (!poll(timing.stepTimeoutMs, condition)) fail(problem)
    }

    private suspend fun awaitNode(problem: String, find: (UiNode?) -> UiNode?): UiNode {
        var found: UiNode? = null
        await(problem) { find(ui.root()).also { found = it } != null }
        return found!!
    }

    private fun fail(reason: String, retryable: Boolean = true): Nothing = throw Abort(SendOutcome.Failed(reason, retryable))

    companion object {
        val CHAT_ROW_IDS = arrayOf("conversations_row_contact_name", "contact_row_name")
        val TITLE_IDS = arrayOf("conversation_contact_name", "conversation_title")
        val ENTRY_IDS = arrayOf("entry")
        val SEND_IDS = arrayOf("send")
        val SEARCH_BUTTON_IDS = arrayOf("menuitem_search", "my_search_bar", "search_bar", "search_bar_inner", "search_icon")
        val SEARCH_FIELD_IDS = arrayOf("search_src_text", "search_input", "search_view_edit_text")
        /** WhatsApp's tick icon descriptions (English UI): whole labels only, so "Read more" is not a status. */
        private val PENDING = Regex("(pending|sending|waiting)", RegexOption.IGNORE_CASE)
        private val CONFIRMED = Regex("(sent|delivered|read|seen)", RegexOption.IGNORE_CASE)

        fun same(a: String, b: String): Boolean = GroupAllowlist.normalize(a) == GroupAllowlist.normalize(b)

        /** The message box holds exactly the report text (line endings and trailing spaces aside). */
        fun sameText(actual: String, expected: String): Boolean =
            actual.replace("\r\n", "\n").trimEnd() == expected.replace("\r\n", "\n").trimEnd()
    }
}

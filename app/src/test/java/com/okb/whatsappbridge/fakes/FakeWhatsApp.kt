package com.okb.whatsappbridge.fakes

import com.okb.whatsappbridge.automation.UiNode
import com.okb.whatsappbridge.automation.WhatsAppUi

/**
 * A simulated WhatsApp for the send-flow tests: chat list → search → chat, with the view ids WhatsApp uses
 * (conversations_row_contact_name, conversation_contact_name, entry, send, message_text, status). Each root()
 * call builds a fresh tree from the current state, like reading a live window.
 *
 * Switches simulate the problems a real phone has: a Send press that does not register, messages left pending
 * (no connection), unreadable tick status, a search that opens a different chat, an app that does not start.
 */
class FakeWhatsApp(
    chats: List<String>,
    val pkg: String = "com.whatsapp",
) : WhatsAppUi {

    class Message(val text: String, var status: String?)

    private enum class Screen { HOME, SEARCH, CHAT, OTHER_APP }

    val history: MutableMap<String, MutableList<Message>> = chats.associateWith { mutableListOf<Message>() }.toMutableMap()
    private val chatNames = chats.toMutableList()

    private var screen = Screen.OTHER_APP
    private var openChat: String? = null
    private var searchQuery = ""
    private val drafts = mutableMapOf<String, String>()
    private var scrollBack = 0

    // ---- switches ----
    var launchWorks = true
    var sendPressIgnored = false
    /** Tick description of newly sent messages ("Sent", "Delivered", "Pending", or null = no description). */
    var newMessageStatus: String? = "Delivered"
    /** Opening this chat from search opens another one instead (simulates a mis-tap). */
    var searchOpensInsteadOf: Pair<String, String>? = null
    /** Messages visible on one screen of a chat (older ones need scrolling back). */
    var visibleMessages = 5
    var showStatusIcons = true

    val typedTexts = mutableListOf<String>()
    var sendPresses = 0

    fun messagesIn(chat: String): List<String> = history[chat].orEmpty().map { it.text }

    fun addMessage(chat: String, text: String, status: String? = "Read") {
        history.getOrPut(chat) { mutableListOf() }.add(Message(text, status))
    }

    /** WhatsApp reconnects: pending messages are sent. */
    fun connectionRestored(status: String = "Delivered") {
        history.values.flatten().filter { it.status == "Pending" }.forEach { it.status = status }
    }

    // ---- WhatsAppUi ----

    override fun foregroundPackage(): String? = if (screen == Screen.OTHER_APP) "com.android.launcher" else pkg

    override fun launch(packageName: String): Boolean {
        if (!launchWorks || packageName != pkg) return false
        screen = Screen.HOME
        openChat = null
        searchQuery = ""
        return true
    }

    override fun back(): Boolean {
        screen = when (screen) {
            Screen.CHAT, Screen.SEARCH -> Screen.HOME
            else -> screen
        }
        return true
    }

    override fun home(): Boolean {
        screen = Screen.OTHER_APP
        return true
    }

    override fun root(): UiNode? = when (screen) {
        Screen.OTHER_APP -> null
        Screen.HOME -> node("android.widget.FrameLayout", children = listOf(
            node("android.widget.ImageButton", id = "menuitem_search", desc = "Search", onClick = { screen = Screen.SEARCH; true }),
            node("androidx.recyclerview.widget.RecyclerView", id = "list", children = chatNames.map(::chatRow)),
        ))
        Screen.SEARCH -> node("android.widget.FrameLayout", children = listOf(
            node("android.widget.EditText", id = "search_src_text", text = searchQuery, editable = true, onSetText = { searchQuery = it; true }),
            node("androidx.recyclerview.widget.RecyclerView", id = "result_list", children =
                if (searchQuery.isBlank()) emptyList()
                else chatNames.filter { it.contains(searchQuery.trim(), ignoreCase = true) }.map(::chatRow)),
        ))
        Screen.CHAT -> chatScreen(openChat!!)
    }

    private fun chatRow(name: String): FakeNode {
        val row = node("android.widget.RelativeLayout", clickable = true, onClick = {
            val target = searchOpensInsteadOf?.takeIf { it.first == name && screen == Screen.SEARCH }?.second ?: name
            openChat = target
            screen = Screen.CHAT
            scrollBack = 0
            true
        }, children = listOf(
            node("android.widget.TextView", id = "conversations_row_contact_name", text = name),
            node("android.widget.TextView", id = "single_msg_tv", text = history[name]?.lastOrNull()?.text?.take(30) ?: ""),
        ))
        return row
    }

    private fun chatScreen(chat: String): FakeNode {
        val all = history.getOrPut(chat) { mutableListOf() }
        val end = (all.size - scrollBack).coerceAtLeast(0)
        val start = (end - visibleMessages).coerceAtLeast(0)
        val shown = all.subList(start, end)
        val list = node("android.widget.ListView", id = "list", onScrollBack = {
            if (all.size - scrollBack > visibleMessages) { scrollBack = (scrollBack + visibleMessages).coerceAtMost(all.size); true } else false
        }, children = shown.map { m ->
            node("android.widget.LinearLayout", id = "main_layout", children = listOfNotNull(
                node("android.widget.TextView", id = "message_text", text = m.text),
                node("android.widget.TextView", id = "date", text = "12:01 AM"),
                if (showStatusIcons && m.status != null) node("android.widget.ImageView", id = "status", desc = m.status) else null,
            ))
        })
        return node("android.widget.FrameLayout", children = listOf(
            node("android.widget.TextView", id = "conversation_contact_name", text = chat),
            list,
            node("android.widget.EditText", id = "entry", text = drafts[chat].orEmpty(), editable = true, onSetText = {
                drafts[chat] = it
                typedTexts += it
                true
            }),
            node("android.widget.ImageButton", id = "send", desc = "Send", clickable = true, onClick = {
                sendPresses++
                val draft = drafts[chat].orEmpty()
                if (!sendPressIgnored && draft.isNotBlank()) {
                    all.add(Message(draft, newMessageStatus))
                    drafts[chat] = ""
                    scrollBack = 0
                }
                true
            }),
        ))
    }

    private fun node(
        className: String,
        id: String? = null,
        text: String? = null,
        desc: String? = null,
        editable: Boolean = false,
        clickable: Boolean = false,
        children: List<FakeNode> = emptyList(),
        onClick: (() -> Boolean)? = null,
        onSetText: ((String) -> Boolean)? = null,
        onScrollBack: (() -> Boolean)? = null,
    ): FakeNode = FakeNode(
        className, id?.let { "$pkg:id/$it" }, text, desc, editable, clickable || onClick != null, children, onClick, onSetText, onScrollBack,
    ).also { n -> children.forEach { it.parentNode = n } }

    class FakeNode(
        override val className: String?,
        override val viewId: String?,
        override val text: String?,
        override val contentDescription: String?,
        override val isEditable: Boolean,
        override val isClickable: Boolean,
        override val children: List<UiNode>,
        private val onClick: (() -> Boolean)?,
        private val onSetText: ((String) -> Boolean)?,
        private val onScrollBack: (() -> Boolean)?,
    ) : UiNode {
        var parentNode: UiNode? = null
        override val parent: UiNode? get() = parentNode
        override fun click(): Boolean = onClick?.invoke() ?: false
        override fun setText(value: String): Boolean = onSetText?.invoke(value) ?: false
        override fun scrollBackward(): Boolean = onScrollBack?.invoke() ?: false
    }
}

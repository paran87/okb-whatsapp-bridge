package com.okb.whatsappbridge.automation

/**
 * Minimal view of an on-screen UI element, implemented over Android's AccessibilityNodeInfo
 * ([AccessibilityUiNode]) and by a simulated WhatsApp in unit tests. Keeping the send flow on this interface
 * is what makes it testable without a phone.
 */
interface UiNode {
    val text: String?
    val contentDescription: String?
    /** Full resource name, e.g. "com.whatsapp:id/entry" (null when the app does not expose one). */
    val viewId: String?
    val className: String?
    val isEditable: Boolean
    val isClickable: Boolean
    val children: List<UiNode>
    val parent: UiNode?
    fun click(): Boolean
    fun setText(value: String): Boolean
    fun scrollBackward(): Boolean
}

/** The phone-side operations the send flow needs. */
interface WhatsAppUi {
    /** Root of the window in the foreground, or null when there is none (or it is not readable). */
    fun root(): UiNode?
    /** Package of the foreground window (null when unknown). */
    fun foregroundPackage(): String?
    /** Starts [packageName] fresh on its home screen (chat list). */
    fun launch(packageName: String): Boolean
    fun back(): Boolean
    fun home(): Boolean
}

/** Tree helpers. */
object UiTree {

    fun walk(root: UiNode?): Sequence<UiNode> = sequence {
        if (root == null) return@sequence
        val stack = ArrayDeque<UiNode>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            yield(node)
            node.children.asReversed().forEach(stack::addLast)
        }
    }

    /** Resource name without the package, e.g. "entry" for "com.whatsapp:id/entry". */
    fun idName(node: UiNode): String? = node.viewId?.substringAfter(":id/", missingDelimiterValue = "")?.ifEmpty { null }

    fun byId(root: UiNode?, vararg names: String): List<UiNode> = walk(root).filter { idName(it) in names }.toList()

    /** The node itself when clickable, else its nearest clickable ancestor. */
    fun clickTarget(node: UiNode): UiNode? {
        var current: UiNode? = node
        var depth = 0
        while (current != null && depth < 8) {
            if (current.isClickable) return current
            current = current.parent
            depth++
        }
        return null
    }

    fun click(node: UiNode): Boolean = clickTarget(node)?.click() ?: false
}

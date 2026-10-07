package com.okb.whatsappbridge.automation

import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo

/** [UiNode] over a live AccessibilityNodeInfo. */
class AccessibilityUiNode(private val info: AccessibilityNodeInfo) : UiNode {
    override val text: String? get() = info.text?.toString()
    override val contentDescription: String? get() = info.contentDescription?.toString()
    override val viewId: String? get() = info.viewIdResourceName
    override val className: String? get() = info.className?.toString()
    override val isEditable: Boolean get() = info.isEditable
    override val isClickable: Boolean get() = info.isClickable
    override val isShowingHint: Boolean get() = info.isShowingHintText

    override val children: List<UiNode>
        get() = (0 until info.childCount).mapNotNull { info.getChild(it) }.map(::AccessibilityUiNode)

    override val parent: UiNode? get() = info.parent?.let(::AccessibilityUiNode)

    override fun click(): Boolean = info.performAction(AccessibilityNodeInfo.ACTION_CLICK)

    override fun setText(value: String): Boolean {
        info.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }
        return info.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    override fun scrollBackward(): Boolean = info.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)

    override fun equals(other: Any?): Boolean = other is AccessibilityUiNode && other.info == info
    override fun hashCode(): Int = info.hashCode()
}

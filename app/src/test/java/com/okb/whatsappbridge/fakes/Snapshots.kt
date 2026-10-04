package com.okb.whatsappbridge.fakes

import com.okb.whatsappbridge.whatsapp.NotificationSnapshot
import com.okb.whatsappbridge.whatsapp.SnapshotMessage

/** Builders for realistic WhatsApp notification snapshots. */
object Snapshots {
    const val T0 = 1_791_073_320_000L // 2026-10-04T00:22:00Z

    fun groupMessaging(
        group: String = "OKB Monitoring",
        messages: List<SnapshotMessage> = listOf(SnapshotMessage("Flooding observed at Barangay San Jose", T0, "Juan Santos")),
        packageName: String = "com.whatsapp",
        conversationTitle: String? = group,
        jid: String? = "120363025246125486@g.us",
        isGroup: Boolean? = true,
        hasPicture: Boolean = false,
    ) = NotificationSnapshot(
        packageName = packageName,
        key = "0|$packageName|1|${jid ?: "tag"}|10123",
        tag = jid,
        postTime = T0 + 500,
        whenTime = messages.lastOrNull()?.timestamp ?: T0,
        shortcutId = jid,
        title = group,
        text = messages.lastOrNull()?.text,
        conversationTitle = conversationTitle,
        isGroupConversation = isGroup,
        selfDisplayName = "You",
        messages = messages,
        hasPicture = hasPicture,
    )

    fun plain(
        title: String?,
        text: String?,
        packageName: String = "com.whatsapp",
        category: String? = null,
        isGroupSummary: Boolean = false,
        isOngoing: Boolean = false,
        tag: String? = null,
        whenTime: Long = T0,
    ) = NotificationSnapshot(
        packageName = packageName,
        key = "0|$packageName|2|$tag|10123",
        tag = tag,
        postTime = T0 + 1000,
        whenTime = whenTime,
        category = category,
        isGroupSummary = isGroupSummary,
        isOngoing = isOngoing,
        title = title,
        text = text,
    )
}

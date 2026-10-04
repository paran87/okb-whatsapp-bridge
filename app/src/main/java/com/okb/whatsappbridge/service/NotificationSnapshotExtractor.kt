package com.okb.whatsappbridge.service

import android.app.Notification
import android.os.Build
import android.os.Bundle
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import com.okb.whatsappbridge.whatsapp.NotificationSnapshot
import com.okb.whatsappbridge.whatsapp.SnapshotMessage

/**
 * Copies the relevant fields out of a [StatusBarNotification]. Every access is defensive: WhatsApp's
 * extras are not a public contract and any of them may be missing or of an unexpected type.
 */
object NotificationSnapshotExtractor {

    fun extract(sbn: StatusBarNotification): NotificationSnapshot {
        val notification = sbn.notification
        val extras: Bundle = notification.extras ?: Bundle.EMPTY
        val style = runCatching {
            NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
        }.getOrNull()

        val messages = style?.messages?.map { m ->
            SnapshotMessage(
                text = m.text?.toString(),
                timestamp = m.timestamp,
                sender = m.person?.name?.toString(),
            )
        } ?: rawMessages(extras)

        return NotificationSnapshot(
            packageName = sbn.packageName,
            key = sbn.key,
            tag = sbn.tag,
            postTime = sbn.postTime,
            whenTime = notification.`when`,
            category = notification.category,
            channelId = notification.channelId,
            shortcutId = notification.shortcutId,
            isGroupSummary = notification.flags and Notification.FLAG_GROUP_SUMMARY != 0,
            isOngoing = sbn.isOngoing,
            title = extras.text(Notification.EXTRA_TITLE),
            text = extras.text(Notification.EXTRA_TEXT),
            bigText = extras.text(Notification.EXTRA_BIG_TEXT),
            subText = extras.text(Notification.EXTRA_SUB_TEXT),
            summaryText = extras.text(Notification.EXTRA_SUMMARY_TEXT),
            conversationTitle = extras.text(Notification.EXTRA_CONVERSATION_TITLE)
                ?: style?.conversationTitle?.toString(),
            isGroupConversation = if (extras.containsKey(EXTRA_IS_GROUP_CONVERSATION)) {
                extras.getBoolean(EXTRA_IS_GROUP_CONVERSATION)
            } else {
                null
            },
            selfDisplayName = style?.user?.name?.toString() ?: extras.text(EXTRA_SELF_DISPLAY_NAME),
            textLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
                ?.mapNotNull { it?.toString() }
                .orEmpty(),
            messages = messages,
            hasPicture = extras.containsKey(Notification.EXTRA_PICTURE) || extras.containsKey(EXTRA_PICTURE_ICON),
        )
    }

    /** Fallback when androidx cannot rebuild the MessagingStyle: read the raw message bundles. */
    @Suppress("DEPRECATION")
    private fun rawMessages(extras: Bundle): List<SnapshotMessage> {
        val parcelables = runCatching { extras.getParcelableArray(Notification.EXTRA_MESSAGES) }.getOrNull()
            ?: return emptyList()
        return parcelables.mapNotNull { item ->
            val bundle = item as? Bundle ?: return@mapNotNull null
            val sender = bundle.getCharSequence("sender")?.toString()
                ?: runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        (bundle.getParcelable<android.os.Parcelable>("sender_person") as? android.app.Person)
                            ?.name?.toString()
                    } else {
                        null
                    }
                }.getOrNull()
            SnapshotMessage(
                text = bundle.getCharSequence("text")?.toString(),
                timestamp = bundle.getLong("time", 0L),
                sender = sender,
            )
        }
    }

    private fun Bundle.text(key: String): String? =
        runCatching { getCharSequence(key)?.toString() }.getOrNull()?.takeIf { it.isNotBlank() }

    private const val EXTRA_IS_GROUP_CONVERSATION = "android.isGroupConversation"
    private const val EXTRA_PICTURE_ICON = "android.pictureIcon"
    private const val EXTRA_SELF_DISPLAY_NAME = "android.selfDisplayName"
}

package com.okb.whatsappbridge.whatsapp

/**
 * Plain, Android-free copy of the fields of a posted notification that the bridge cares about.
 *
 * Extracting this snapshot is the only place that touches [android.service.notification.StatusBarNotification];
 * everything downstream (filtering, parsing, fingerprinting) is pure Kotlin and unit-tested on the JVM.
 * Every field is optional/defensive because WhatsApp's notification layout is not a stable API.
 */
data class NotificationSnapshot(
    val packageName: String,
    val key: String,
    val tag: String? = null,
    val postTime: Long,
    val whenTime: Long = 0L,
    val category: String? = null,
    val channelId: String? = null,
    val shortcutId: String? = null,
    val isGroupSummary: Boolean = false,
    val isOngoing: Boolean = false,
    val title: String? = null,
    val text: String? = null,
    val bigText: String? = null,
    val subText: String? = null,
    val summaryText: String? = null,
    val conversationTitle: String? = null,
    /** `null` when the extra was absent (older Android / older WhatsApp builds). */
    val isGroupConversation: Boolean? = null,
    val selfDisplayName: String? = null,
    val textLines: List<String> = emptyList(),
    /** Messages from `Notification.MessagingStyle`, oldest first. */
    val messages: List<SnapshotMessage> = emptyList(),
    /** True when the notification carries a preview picture (BigPictureStyle). */
    val hasPicture: Boolean = false,
)

data class SnapshotMessage(
    val text: String?,
    /** Message time reported by WhatsApp, or 0 when unavailable. */
    val timestamp: Long,
    /** `null` means the message was sent by the device owner (MessagingStyle convention). */
    val sender: String?,
    /**
     * The ONLY legitimate original-media reference a notification can carry: a content URI set by the
     * sender app via `MessagingStyle.Message.setData(mimeType, uri)`. Usually absent for WhatsApp group
     * media. A string here is an opaque `content://`/`file://` URI; reading it may still be denied.
     */
    val dataUri: String? = null,
    val dataMimeType: String? = null,
)

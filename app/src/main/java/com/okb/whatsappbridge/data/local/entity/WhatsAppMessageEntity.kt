package com.okb.whatsappbridge.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A captured WhatsApp group message. This table is the source of truth until the backend confirms
 * receipt ([uploadStatus] = UPLOADED). Rows are written *before* any network activity.
 */
@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["timestamp"]),
        Index(value = ["groupName"]),
        Index(value = ["uploadStatus"]),
        Index(value = ["createdAt"]),
        Index(value = ["fingerprint"], unique = true),
    ],
)
data class WhatsAppMessageEntity(
    @PrimaryKey val id: String,
    val serverId: String?,
    val deviceId: String,
    val groupName: String?,
    val senderName: String?,
    val messageText: String?,
    /** Message time (epoch millis) as reported by WhatsApp. */
    val timestamp: Long,
    val mediaType: String,
    val mediaStatus: String,
    val fingerprint: String,
    val uploadStatus: String,
    /** When the bridge captured the message (epoch millis). */
    val createdAt: Long,
    val packageName: String,
    val notificationKey: String?,
    @ColumnInfo(defaultValue = "NULL") val uploadedAt: Long? = null,
    @ColumnInfo(defaultValue = "NULL") val lastError: String? = null,
)

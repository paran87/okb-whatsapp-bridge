package com.okb.whatsappbridge.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/**
 * Persistent upload queue. A row exists for every message that has not been confirmed by the
 * backend; it is removed in the same transaction that marks the message UPLOADED.
 */
@Entity(
    tableName = "upload_queue",
    foreignKeys = [
        ForeignKey(
            entity = WhatsAppMessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class UploadQueueEntity(
    @PrimaryKey val messageId: String,
    val enqueuedAt: Long,
    val attemptCount: Int = 0,
    val lastAttemptAt: Long? = null,
    val lastHttpCode: Int? = null,
    val lastError: String? = null,
)

package com.okb.whatsappbridge.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/**
 * Persistent media upload queue. A row exists for every acquired media file not yet confirmed by the
 * backend; it is removed in the same transaction that marks the media UPLOADED.
 */
@Entity(
    tableName = "media_upload_queue",
    foreignKeys = [
        ForeignKey(
            entity = MediaAttachmentEntity::class,
            parentColumns = ["id"],
            childColumns = ["mediaId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class MediaUploadQueueEntity(
    @PrimaryKey val mediaId: String,
    val enqueuedAt: Long,
    val attemptCount: Int = 0,
    val lastAttemptAt: Long? = null,
    val lastError: String? = null,
)

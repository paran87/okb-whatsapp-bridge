package com.okb.whatsappbridge.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A media attachment detected for a captured message.
 *
 * One row is created per media-bearing message the first time that message is inserted (reposts are
 * deduplicated at the message level, so media is never duplicated by WhatsApp re-posting). The row is
 * the local source of truth for the file until the backend confirms the R2 object.
 *
 * [sha256] is content-derived and only known once a local file exists; it drives content-based
 * deduplication so identical media is not uploaded to R2 twice.
 */
@Entity(
    tableName = "media",
    foreignKeys = [
        ForeignKey(
            entity = WhatsAppMessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["messageId"]),
        Index(value = ["sha256"]),
        Index(value = ["acquisitionStatus"]),
        Index(value = ["uploadStatus"]),
        Index(value = ["createdAt"]),
    ],
)
data class MediaAttachmentEntity(
    @PrimaryKey val id: String,
    val messageId: String,
    val deviceId: String,
    val groupName: String?,
    val senderName: String?,
    val mediaType: String,
    val mimeType: String?,
    val originalFileName: String?,
    /** App-controlled local file path once acquired, else null. Never a WhatsApp-owned path. */
    val localPath: String?,
    val fileSizeBytes: Long?,
    val sha256: String?,
    val acquisitionStatus: String,
    val uploadStatus: String,
    /** Human-readable reason for UNAVAILABLE/FAILED, safe to show the operator. */
    val statusDetail: String?,
    val uploadAttempts: Int,
    val lastError: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val r2ObjectKey: String?,
    val r2ETag: String?,
    val remoteRef: String?,
    @ColumnInfo(defaultValue = "NULL") val uploadedAt: Long? = null,
)

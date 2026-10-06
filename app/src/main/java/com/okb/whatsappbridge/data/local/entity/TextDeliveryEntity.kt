package com.okb.whatsappbridge.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Local queue of consolidated TEXT reports sent automatically to the destination group (Room v5). One row per
 * backend TEXT delivery; [dedupeKey] (reporting period + group) is unique too, so the same report can never be
 * queued twice. Progress around the irreversible step (pressing Send) is written before and after it.
 */
@Entity(
    tableName = "text_report_deliveries",
    indices = [Index(value = ["dedupeKey"], unique = true), Index(value = ["status"])],
)
data class TextDeliveryEntity(
    /** Backend TEXT delivery id. */
    @PrimaryKey val id: String,
    val reportId: String,
    val kind: String,
    val dedupeKey: String,
    val destinationGroup: String,
    val sourceGroup: String?,
    /** JSON array of {text, ref}. */
    val partsJson: String,
    val periodStart: String?,
    val periodEnd: String?,
    val reportCount: Int?,
    /** TextDeliveryStatus name. */
    val status: String,
    /** Backend attempt number of the latest claim. */
    val attempt: Int,
    val lastError: String?,
    val verification: String?,
    /** Refs of parts confirmed in the chat (comma-separated). */
    val sentRefs: String,
    /** Refs whose Send button was pressed (written before pressing). */
    val pressedRefs: String,
    /** Result still to be reported to the backend: "sent" | "failed" (retried while offline). */
    val pendingResult: String?,
    val pendingRetryable: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val lastAttemptAt: Long?,
    val sentAt: Long?,
)

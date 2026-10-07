package com.okb.whatsappbridge.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Local delivery queue for consolidated report PDFs (Room v4). One row per backend report id, so a report is
 * never downloaded or offered twice, and its state survives restarts and offline periods.
 */
@Entity(
    tableName = "consolidated_report_deliveries",
    indices = [Index(value = ["status"])],
)
data class ConsolidatedDeliveryEntity(
    /** Backend consolidated report id. */
    @PrimaryKey val id: String,
    val kind: String,
    val fileName: String,
    val caption: String,
    val sourceGroup: String?,
    val destinationGroup: String?,
    val periodStart: String?,
    val periodEnd: String?,
    val reportCount: Int?,
    /** API path of the PDF relative to the backend URL (never a storage URL or credential). */
    val pdfPath: String,
    /** ConsolidatedDeliveryStatus name. */
    val status: String,
    val errorMessage: String?,
    val downloadAttempts: Int,
    /** Delivery state still to be reported to the backend (retried on the next check while offline). */
    val pendingAck: String?,
    val pendingAckError: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val downloadedAt: Long?,
    val openedAt: Long?,
    val sentAt: Long?,
    /** Automatic PDF sending (Room v6). */
    @ColumnInfo(defaultValue = "0") val autoAttempts: Int = 0,
    @ColumnInfo(defaultValue = "0") val autoPressed: Boolean = false,
    @ColumnInfo(defaultValue = "0") val sentAutomatically: Boolean = false,
)

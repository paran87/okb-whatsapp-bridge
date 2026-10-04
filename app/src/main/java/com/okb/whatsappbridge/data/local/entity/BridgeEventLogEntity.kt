package com.okb.whatsappbridge.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Diagnostic event log shown under Diagnostics → View Logs. Never stores message content or secrets. */
@Entity(tableName = "event_log", indices = [Index(value = ["timestamp"])])
data class BridgeEventLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val level: String,
    val tag: String,
    val message: String,
)

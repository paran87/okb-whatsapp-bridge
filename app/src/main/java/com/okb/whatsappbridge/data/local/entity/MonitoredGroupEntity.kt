package com.okb.whatsappbridge.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Group allowlist entry. Groups seen in WhatsApp notifications but not yet authorized are stored with
 * [authorized] = false (name only, never content) so the operator can authorize them with one tap.
 */
@Entity(
    tableName = "monitored_groups",
    indices = [Index(value = ["normalizedName"], unique = true)],
)
data class MonitoredGroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val normalizedName: String,
    val authorized: Boolean,
    val discoveredAutomatically: Boolean,
    val createdAt: Long,
    val lastSeenAt: Long? = null,
)

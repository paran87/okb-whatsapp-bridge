package com.okb.whatsappbridge.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Key/value store for non-secret settings and activity timestamps. Secrets never go here. */
@Entity(tableName = "bridge_settings")
data class BridgeSettingsEntity(
    @PrimaryKey val key: String,
    val value: String?,
    val updatedAt: Long,
)

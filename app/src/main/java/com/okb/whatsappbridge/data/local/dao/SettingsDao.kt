package com.okb.whatsappbridge.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.okb.whatsappbridge.data.local.entity.BridgeSettingsEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SettingsDao {
    @Query("SELECT * FROM bridge_settings")
    fun observeAll(): Flow<List<BridgeSettingsEntity>>

    @Query("SELECT * FROM bridge_settings")
    suspend fun getAll(): List<BridgeSettingsEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entity: BridgeSettingsEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putAll(entities: List<BridgeSettingsEntity>)

    @Query("SELECT 1")
    suspend fun ping(): Int
}

package com.okb.whatsappbridge.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.okb.whatsappbridge.data.local.entity.BridgeEventLogEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface EventLogDao {
    @Insert
    suspend fun insert(entry: BridgeEventLogEntity): Long

    @Query("SELECT * FROM event_log ORDER BY id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<BridgeEventLogEntity>>

    @Query("DELETE FROM event_log WHERE id NOT IN (SELECT id FROM event_log ORDER BY id DESC LIMIT :keep)")
    suspend fun prune(keep: Int)

    @Query("DELETE FROM event_log")
    suspend fun clear()
}

package com.okb.whatsappbridge.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.okb.whatsappbridge.data.local.entity.MonitoredGroupEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface GroupDao {
    @Query("SELECT * FROM monitored_groups ORDER BY authorized DESC, name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<MonitoredGroupEntity>>

    @Query("SELECT name FROM monitored_groups WHERE authorized = 1")
    suspend fun authorizedNames(): List<String>

    @Query("SELECT * FROM monitored_groups WHERE normalizedName = :normalizedName")
    suspend fun findByNormalizedName(normalizedName: String): MonitoredGroupEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(group: MonitoredGroupEntity): Long

    @Query("UPDATE monitored_groups SET authorized = :authorized WHERE id = :id")
    suspend fun setAuthorized(id: Long, authorized: Boolean)

    @Query("UPDATE monitored_groups SET lastSeenAt = :at WHERE normalizedName = :normalizedName")
    suspend fun touch(normalizedName: String, at: Long)

    @Query("DELETE FROM monitored_groups WHERE id = :id")
    suspend fun delete(id: Long)
}

package com.okb.whatsappbridge.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.okb.whatsappbridge.data.local.entity.TextDeliveryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TextDeliveryDao {
    @Query("SELECT * FROM text_report_deliveries WHERE id = :id")
    suspend fun get(id: String): TextDeliveryEntity?

    @Query("SELECT * FROM text_report_deliveries WHERE dedupeKey = :key")
    suspend fun getByDedupeKey(key: String): TextDeliveryEntity?

    /** Fails (ABORT) when another delivery already holds the same dedupe key. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: TextDeliveryEntity)

    @Update
    suspend fun update(entity: TextDeliveryEntity)

    @Query("SELECT * FROM text_report_deliveries ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<TextDeliveryEntity>>

    @Query("SELECT * FROM text_report_deliveries WHERE pendingResult IS NOT NULL")
    suspend fun withPendingResult(): List<TextDeliveryEntity>

    @Query("DELETE FROM text_report_deliveries WHERE status IN ('SENT', 'FAILED') AND updatedAt < :before")
    suspend fun deleteFinishedBefore(before: Long): Int
}

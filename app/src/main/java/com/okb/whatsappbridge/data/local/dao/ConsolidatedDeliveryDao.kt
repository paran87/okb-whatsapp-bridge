package com.okb.whatsappbridge.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.okb.whatsappbridge.data.local.entity.ConsolidatedDeliveryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ConsolidatedDeliveryDao {
    @Query("SELECT * FROM consolidated_report_deliveries WHERE id = :id")
    suspend fun get(id: String): ConsolidatedDeliveryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ConsolidatedDeliveryEntity)

    @Query("SELECT * FROM consolidated_report_deliveries ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<ConsolidatedDeliveryEntity>>

    @Query("SELECT * FROM consolidated_report_deliveries WHERE pendingAck IS NOT NULL")
    suspend fun withPendingAck(): List<ConsolidatedDeliveryEntity>

    @Query("SELECT status, COUNT(*) AS count FROM consolidated_report_deliveries GROUP BY status")
    fun observeStatusCounts(): Flow<List<DeliveryStatusCount>>

    @Query("DELETE FROM consolidated_report_deliveries WHERE status IN ('SENT', 'FAILED') AND updatedAt < :before")
    suspend fun deleteFinishedBefore(before: Long): Int
}

data class DeliveryStatusCount(val status: String, val count: Int)

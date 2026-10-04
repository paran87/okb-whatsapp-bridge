package com.okb.whatsappbridge.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import com.okb.whatsappbridge.data.local.entity.UploadQueueEntity

@Dao
interface UploadQueueDao {
    @Query("SELECT * FROM upload_queue WHERE messageId = :messageId")
    suspend fun get(messageId: String): UploadQueueEntity?

    @Query("SELECT COUNT(*) FROM upload_queue")
    suspend fun count(): Int
}

package com.okb.whatsappbridge.data.local.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.okb.whatsappbridge.data.local.entity.UploadQueueEntity
import com.okb.whatsappbridge.data.local.entity.WhatsAppMessageEntity
import kotlinx.coroutines.flow.Flow

data class MessageWithQueue(
    @Embedded val message: WhatsAppMessageEntity,
    val attemptCount: Int,
)

data class StatusCount(val status: String, val count: Int)

@Dao
abstract class MessageDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertMessage(message: WhatsAppMessageEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertQueueEntry(entry: UploadQueueEntity): Long

    /**
     * Atomically stores a message and its upload-queue entry.
     * @return false when a message with the same fingerprint already exists (duplicate).
     */
    @Transaction
    open suspend fun insertCaptured(message: WhatsAppMessageEntity, enqueuedAt: Long): Boolean {
        val rowId = insertMessage(message)
        if (rowId == -1L) return false
        insertQueueEntry(UploadQueueEntity(messageId = message.id, enqueuedAt = enqueuedAt))
        return true
    }

    @Query("SELECT * FROM messages WHERE id = :id")
    abstract suspend fun getById(id: String): WhatsAppMessageEntity?

    @Query("SELECT COUNT(*) FROM messages WHERE fingerprint = :fingerprint")
    abstract suspend fun countByFingerprint(fingerprint: String): Int

    @Query(
        """
        SELECT m.*, COALESCE(q.attemptCount, 0) AS attemptCount
        FROM messages m LEFT JOIN upload_queue q ON q.messageId = m.id
        WHERE (:status IS NULL OR m.uploadStatus = :status)
        ORDER BY m.timestamp DESC, m.createdAt DESC
        LIMIT :limit
        """,
    )
    abstract fun observeRecent(status: String?, limit: Int): Flow<List<MessageWithQueue>>

    @Query("SELECT uploadStatus AS status, COUNT(*) AS count FROM messages GROUP BY uploadStatus")
    abstract fun observeStatusCounts(): Flow<List<StatusCount>>

    @Query("SELECT COUNT(*) FROM messages WHERE createdAt >= :since")
    abstract fun observeCountSince(since: Long): Flow<Int>

    @Query("SELECT MAX(timestamp) FROM messages")
    abstract fun observeLatestTimestamp(): Flow<Long?>

    /**
     * Next messages to upload, oldest first. FAILED messages are only included for reconciliation or
     * manual runs, and only while they are below [maxFailedAttempts].
     */
    @Query(
        """
        SELECT m.*, q.attemptCount AS attemptCount
        FROM messages m INNER JOIN upload_queue q ON q.messageId = m.id
        WHERE m.id NOT IN (:excludedIds)
          AND (
            m.uploadStatus IN ('PENDING_UPLOAD', 'RETRYING', 'UPLOADING')
            OR (:includeFailed AND m.uploadStatus = 'FAILED' AND q.attemptCount < :maxFailedAttempts)
          )
        ORDER BY m.timestamp ASC, m.createdAt ASC
        LIMIT :limit
        """,
    )
    abstract suspend fun uploadBatch(
        excludedIds: List<String>,
        includeFailed: Boolean,
        maxFailedAttempts: Int,
        limit: Int,
    ): List<MessageWithQueue>

    @Query(
        """
        SELECT COUNT(*) FROM messages m INNER JOIN upload_queue q ON q.messageId = m.id
        WHERE m.uploadStatus IN ('PENDING_UPLOAD', 'RETRYING', 'UPLOADING')
          OR (:includeFailed AND m.uploadStatus = 'FAILED' AND q.attemptCount < :maxFailedAttempts)
        """,
    )
    abstract suspend fun countUploadable(includeFailed: Boolean, maxFailedAttempts: Int): Int

    @Query("UPDATE messages SET uploadStatus = 'UPLOADING' WHERE id = :id")
    abstract suspend fun markUploading(id: String)

    @Query(
        """
        UPDATE messages SET uploadStatus = 'UPLOADED', serverId = :serverId, uploadedAt = :at, lastError = NULL
        WHERE id = :id
        """,
    )
    protected abstract suspend fun setUploaded(id: String, serverId: String?, at: Long)

    @Query("DELETE FROM upload_queue WHERE messageId = :id")
    protected abstract suspend fun deleteQueueEntry(id: String)

    @Transaction
    open suspend fun markUploaded(id: String, serverId: String?, at: Long) {
        setUploaded(id, serverId, at)
        deleteQueueEntry(id)
    }

    @Query("UPDATE messages SET uploadStatus = :status, lastError = :error WHERE id = :id")
    protected abstract suspend fun setStatus(id: String, status: String, error: String?)

    @Query(
        """
        UPDATE upload_queue SET attemptCount = attemptCount + 1, lastAttemptAt = :at,
            lastHttpCode = :httpCode, lastError = :error
        WHERE messageId = :id
        """,
    )
    protected abstract suspend fun recordAttempt(id: String, at: Long, httpCode: Int?, error: String?)

    @Transaction
    open suspend fun markAttemptFailed(id: String, status: String, error: String, httpCode: Int?, at: Long) {
        setStatus(id, status, error)
        recordAttempt(id, at, httpCode, error)
    }

    /** An upload interrupted by process death leaves UPLOADING rows behind; they are simply retried. */
    @Query("UPDATE messages SET uploadStatus = 'RETRYING' WHERE uploadStatus = 'UPLOADING'")
    abstract suspend fun recoverInterruptedUploads(): Int

    @Query("UPDATE messages SET uploadStatus = 'PENDING_UPLOAD', lastError = NULL WHERE uploadStatus = 'FAILED'")
    protected abstract suspend fun setFailedToPending(): Int

    @Query(
        """
        UPDATE upload_queue SET attemptCount = 0
        WHERE messageId IN (SELECT id FROM messages WHERE uploadStatus = 'PENDING_UPLOAD')
        """,
    )
    protected abstract suspend fun resetPendingAttempts()

    @Transaction
    open suspend fun resetFailedToPending(): Int {
        val count = setFailedToPending()
        resetPendingAttempts()
        return count
    }

    /** Self-repair: every not-yet-uploaded message must have a queue entry. */
    @Query(
        """
        INSERT OR IGNORE INTO upload_queue (messageId, enqueuedAt, attemptCount)
        SELECT id, :now, 0 FROM messages
        WHERE uploadStatus != 'UPLOADED' AND id NOT IN (SELECT messageId FROM upload_queue)
        """,
    )
    abstract suspend fun repairQueue(now: Long)

    @Query("DELETE FROM messages WHERE uploadStatus = 'UPLOADED' AND uploadedAt IS NOT NULL AND uploadedAt < :cutoff")
    abstract suspend fun deleteUploadedBefore(cutoff: Long): Int

    @Query("SELECT COUNT(*) FROM messages")
    abstract suspend fun countAll(): Int
}

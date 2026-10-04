package com.okb.whatsappbridge.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.okb.whatsappbridge.data.local.entity.MediaAttachmentEntity
import com.okb.whatsappbridge.data.local.entity.MediaUploadQueueEntity
import kotlinx.coroutines.flow.Flow

data class MediaWithQueue(
    @androidx.room.Embedded val media: MediaAttachmentEntity,
    val attemptCount: Int,
)

/** Compact media summary joined onto a message row for the Messages list. */
data class MessageMediaSummary(
    val messageId: String,
    val mediaType: String,
    val mimeType: String?,
    val fileSizeBytes: Long?,
    val acquisitionStatus: String,
    val uploadStatus: String,
)

@Dao
abstract class MediaDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertMedia(media: MediaAttachmentEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertQueueEntry(entry: MediaUploadQueueEntity): Long

    @Query("SELECT * FROM media WHERE id = :id")
    abstract suspend fun getById(id: String): MediaAttachmentEntity?

    @Query("SELECT COUNT(*) FROM media WHERE messageId = :messageId")
    abstract suspend fun countForMessage(messageId: String): Int

    @Query("SELECT * FROM media WHERE messageId = :messageId ORDER BY createdAt ASC")
    abstract fun observeForMessage(messageId: String): Flow<List<MediaAttachmentEntity>>

    /**
     * Updates acquisition results and, when a file became AVAILABLE, enqueues an upload row in the
     * same transaction so an acquired file always has a queue entry.
     */
    @Query(
        """
        UPDATE media SET acquisitionStatus = :acquisition, uploadStatus = :upload, localPath = :localPath,
            fileSizeBytes = :size, sha256 = :sha256, mimeType = COALESCE(:mimeType, mimeType),
            originalFileName = COALESCE(:fileName, originalFileName), statusDetail = :detail, updatedAt = :at
        WHERE id = :id
        """,
    )
    protected abstract suspend fun setAcquisition(
        id: String, acquisition: String, upload: String, localPath: String?, size: Long?, sha256: String?,
        mimeType: String?, fileName: String?, detail: String?, at: Long,
    )

    @Transaction
    open suspend fun markAvailableAndEnqueue(
        id: String, localPath: String, size: Long, sha256: String, mimeType: String?, fileName: String?,
        upload: String, at: Long,
    ) {
        setAcquisition(id, "AVAILABLE", upload, localPath, size, sha256, mimeType, fileName, null, at)
        insertQueueEntry(MediaUploadQueueEntity(mediaId = id, enqueuedAt = at))
    }

    @Query(
        """
        UPDATE media SET acquisitionStatus = :acquisition, uploadStatus = 'PENDING', statusDetail = :detail,
            updatedAt = :at WHERE id = :id
        """,
    )
    abstract suspend fun markAcquisitionTerminal(id: String, acquisition: String, detail: String?, at: Long)

    @Query("UPDATE media SET acquisitionStatus = 'ACQUIRING', updatedAt = :at WHERE id = :id")
    abstract suspend fun markAcquiring(id: String, at: Long)

    // ---- Upload queue ----

    @Query(
        """
        SELECT m.*, q.attemptCount AS attemptCount
        FROM media m INNER JOIN media_upload_queue q ON q.mediaId = m.id
        WHERE m.acquisitionStatus = 'AVAILABLE' AND m.id NOT IN (:excludedIds)
          AND (
            m.uploadStatus IN ('PENDING', 'RETRYING', 'UPLOADING')
            OR (:includeFailed AND m.uploadStatus = 'FAILED' AND q.attemptCount < :maxFailedAttempts)
          )
        ORDER BY m.createdAt ASC
        LIMIT :limit
        """,
    )
    abstract suspend fun uploadBatch(
        excludedIds: List<String>, includeFailed: Boolean, maxFailedAttempts: Int, limit: Int,
    ): List<MediaWithQueue>

    @Query(
        """
        SELECT COUNT(*) FROM media m INNER JOIN media_upload_queue q ON q.mediaId = m.id
        WHERE m.acquisitionStatus = 'AVAILABLE' AND (
            m.uploadStatus IN ('PENDING', 'RETRYING', 'UPLOADING')
            OR (:includeFailed AND m.uploadStatus = 'FAILED' AND q.attemptCount < :maxFailedAttempts))
        """,
    )
    abstract suspend fun countUploadable(includeFailed: Boolean, maxFailedAttempts: Int): Int

    @Query("UPDATE media SET uploadStatus = 'UPLOADING', updatedAt = :at WHERE id = :id")
    abstract suspend fun markUploading(id: String, at: Long)

    @Query(
        """
        UPDATE media SET uploadStatus = 'UPLOADED', r2ObjectKey = :objectKey, r2ETag = :etag,
            remoteRef = :remoteRef, uploadedAt = :at, lastError = NULL, updatedAt = :at WHERE id = :id
        """,
    )
    protected abstract suspend fun setUploaded(id: String, objectKey: String?, etag: String?, remoteRef: String?, at: Long)

    @Query("DELETE FROM media_upload_queue WHERE mediaId = :id")
    protected abstract suspend fun deleteQueueEntry(id: String)

    @Transaction
    open suspend fun markUploaded(id: String, objectKey: String?, etag: String?, remoteRef: String?, at: Long) {
        setUploaded(id, objectKey, etag, remoteRef, at)
        deleteQueueEntry(id)
    }

    @Query("UPDATE media SET uploadStatus = :status, lastError = :error, uploadAttempts = uploadAttempts + 1, updatedAt = :at WHERE id = :id")
    protected abstract suspend fun setUploadStatus(id: String, status: String, error: String?, at: Long)

    @Query("UPDATE media_upload_queue SET attemptCount = attemptCount + 1, lastAttemptAt = :at, lastError = :error WHERE mediaId = :id")
    protected abstract suspend fun recordAttempt(id: String, at: Long, error: String?)

    @Transaction
    open suspend fun markUploadFailed(id: String, status: String, error: String, at: Long) {
        setUploadStatus(id, status, error, at)
        recordAttempt(id, at, error)
    }

    @Query("UPDATE media SET uploadStatus = 'RETRYING', updatedAt = :at WHERE uploadStatus = 'UPLOADING'")
    abstract suspend fun recoverInterruptedUploads(at: Long): Int

    @Query("UPDATE media SET uploadStatus = 'PENDING', lastError = NULL, updatedAt = :at WHERE uploadStatus = 'FAILED' AND acquisitionStatus = 'AVAILABLE'")
    protected abstract suspend fun setFailedToPending(at: Long): Int

    @Query("UPDATE media_upload_queue SET attemptCount = 0 WHERE mediaId IN (SELECT id FROM media WHERE uploadStatus = 'PENDING')")
    protected abstract suspend fun resetPendingAttempts()

    @Transaction
    open suspend fun resetFailedToPending(at: Long): Int {
        val count = setFailedToPending(at)
        resetPendingAttempts()
        return count
    }

    /** Self-repair: every AVAILABLE media not yet uploaded must have a queue entry. */
    @Query(
        """
        INSERT OR IGNORE INTO media_upload_queue (mediaId, enqueuedAt, attemptCount)
        SELECT id, :now, 0 FROM media
        WHERE acquisitionStatus = 'AVAILABLE' AND uploadStatus != 'UPLOADED'
          AND id NOT IN (SELECT mediaId FROM media_upload_queue)
        """,
    )
    abstract suspend fun repairQueue(now: Long)

    /** Content-based dedupe: an already-uploaded media row with the same bytes. */
    @Query("SELECT * FROM media WHERE sha256 = :sha256 AND uploadStatus = 'UPLOADED' AND r2ObjectKey IS NOT NULL LIMIT 1")
    abstract suspend fun findUploadedBySha256(sha256: String): MediaAttachmentEntity?

    // ---- Metrics / UI ----

    @Query("SELECT uploadStatus AS status, COUNT(*) AS count FROM media WHERE acquisitionStatus = 'AVAILABLE' GROUP BY uploadStatus")
    abstract fun observeUploadStatusCounts(): Flow<List<StatusCount>>

    @Query("SELECT acquisitionStatus AS status, COUNT(*) AS count FROM media GROUP BY acquisitionStatus")
    abstract fun observeAcquisitionCounts(): Flow<List<StatusCount>>

    @Query("SELECT COUNT(*) FROM media WHERE createdAt >= :since")
    abstract fun observeCountSince(since: Long): Flow<Int>

    @Query("SELECT COALESCE(SUM(fileSizeBytes), 0) FROM media WHERE localPath IS NOT NULL AND uploadStatus != 'UPLOADED'")
    abstract fun observeQueuedBytes(): Flow<Long>

    @Query("SELECT MAX(createdAt) FROM media")
    abstract fun observeLastCaptureAt(): Flow<Long?>

    @Query("SELECT MAX(uploadedAt) FROM media")
    abstract fun observeLastUploadAt(): Flow<Long?>

    @Query("SELECT COALESCE(MAX(fileSizeBytes), 0) FROM media m INNER JOIN media_upload_queue q ON q.mediaId = m.id")
    abstract suspend fun largestQueuedBytes(): Long

    @Query(
        """
        SELECT messageId, mediaType, mimeType, fileSizeBytes, acquisitionStatus, uploadStatus
        FROM media WHERE messageId IN (:messageIds)
        """,
    )
    abstract suspend fun summariesForMessages(messageIds: List<String>): List<MessageMediaSummary>

    @Query(
        """
        SELECT messageId, mediaType, mimeType, fileSizeBytes, acquisitionStatus, uploadStatus
        FROM media
        """,
    )
    abstract fun observeAllSummaries(): Flow<List<MessageMediaSummary>>

    /** Media whose local copy may be deleted after a confirmed upload (returns paths to delete). */
    @Query("SELECT localPath FROM media WHERE uploadStatus = 'UPLOADED' AND localPath IS NOT NULL AND uploadedAt IS NOT NULL AND uploadedAt < :cutoff")
    abstract suspend fun localPathsToCleanup(cutoff: Long): List<String>

    @Query("UPDATE media SET localPath = NULL, updatedAt = :at WHERE uploadStatus = 'UPLOADED' AND localPath IS NOT NULL AND uploadedAt IS NOT NULL AND uploadedAt < :cutoff")
    abstract suspend fun clearCleanedLocalPaths(cutoff: Long, at: Long): Int

    @Query("SELECT COUNT(*) FROM media")
    abstract suspend fun countAll(): Int
}

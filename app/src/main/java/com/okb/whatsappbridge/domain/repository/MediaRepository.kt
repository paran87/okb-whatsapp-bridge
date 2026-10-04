package com.okb.whatsappbridge.domain.repository

import com.okb.whatsappbridge.domain.model.MediaAttachment
import com.okb.whatsappbridge.domain.model.MediaCounts
import com.okb.whatsappbridge.domain.model.MediaSummary
import com.okb.whatsappbridge.domain.model.MediaType
import kotlinx.coroutines.flow.Flow

/** A media attachment detected at capture time, before any acquisition. */
data class NewMediaAttachment(
    val messageId: String,
    val deviceId: String,
    val groupName: String?,
    val senderName: String?,
    val mediaType: MediaType,
    val mimeType: String?,
    val originalFileName: String?,
    val createdAt: Long,
)

/** A media row selected for upload (its local file exists). */
data class MediaUploadCandidate(
    val id: String,
    val deviceId: String,
    val messageId: String,
    val groupName: String?,
    val senderName: String?,
    val mediaType: MediaType,
    val mimeType: String?,
    val originalFileName: String?,
    val localPath: String,
    val fileSizeBytes: Long,
    val sha256: String,
    val createdAt: Long,
    val attemptCount: Int,
)

/** Already-uploaded media with identical bytes (for content-based dedupe). */
data class ExistingRemoteMedia(val objectKey: String, val etag: String?, val remoteRef: String?)

interface MediaRepository {
    /** Inserts a DETECTED media row; returns its id, or null if the message already has media. */
    suspend fun createDetected(media: NewMediaAttachment): String?

    suspend fun markAcquiring(id: String, at: Long)
    suspend fun markAvailableAndEnqueue(
        id: String, localPath: String, sizeBytes: Long, sha256: String, mimeType: String?, originalFileName: String?, at: Long,
    )
    suspend fun markUnavailable(id: String, reason: String, at: Long)
    suspend fun markAcquisitionFailed(id: String, reason: String, at: Long)

    suspend fun findUploadedBySha256(sha256: String): ExistingRemoteMedia?

    suspend fun nextUploadBatch(excludedIds: Set<String>, includeFailed: Boolean, limit: Int): List<MediaUploadCandidate>
    suspend fun countUploadable(includeFailed: Boolean): Int
    suspend fun markUploading(id: String, at: Long)
    suspend fun markUploaded(id: String, objectKey: String?, etag: String?, remoteRef: String?, at: Long)
    suspend fun markUploadRetrying(id: String, error: String, at: Long)
    suspend fun markUploadFailed(id: String, error: String, at: Long)
    suspend fun recoverInterruptedUploads(at: Long): Int
    suspend fun resetFailedToPending(at: Long): Int
    suspend fun repairQueue(now: Long)

    /** Deletes local copies of media confirmed uploaded before [cutoff]; returns count removed. */
    suspend fun cleanupUploadedLocalFiles(cutoff: Long, at: Long): Int
    suspend fun largestQueuedBytes(): Long
    fun storageUsedBytes(): Long
    fun usableSpaceBytes(): Long?

    fun observeMediaCounts(): Flow<MediaCounts>
    fun observeCapturedSince(since: Long): Flow<Int>
    fun observeQueuedBytes(): Flow<Long>
    fun observeLastCaptureAt(): Flow<Long?>
    fun observeLastUploadAt(): Flow<Long?>
    fun observeForMessage(messageId: String): Flow<List<MediaAttachment>>
    /** messageId -> compact media summary, for the Messages list. */
    fun observeMessageMediaSummaries(): Flow<Map<String, MediaSummary>>

    suspend fun isHealthy(): Boolean

    companion object {
        const val MAX_AUTOMATIC_FAILED_ATTEMPTS = 20
    }
}

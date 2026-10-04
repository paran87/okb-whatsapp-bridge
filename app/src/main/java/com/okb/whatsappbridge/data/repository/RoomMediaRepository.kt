package com.okb.whatsappbridge.data.repository

import com.okb.whatsappbridge.data.local.dao.MediaDao
import com.okb.whatsappbridge.data.local.dao.MediaWithQueue
import com.okb.whatsappbridge.data.local.entity.MediaAttachmentEntity
import com.okb.whatsappbridge.domain.model.MediaAcquisitionStatus
import com.okb.whatsappbridge.domain.model.MediaAttachment
import com.okb.whatsappbridge.domain.model.MediaCounts
import com.okb.whatsappbridge.domain.model.MediaSummary
import com.okb.whatsappbridge.domain.model.MediaType
import com.okb.whatsappbridge.domain.model.MediaUploadStatus
import com.okb.whatsappbridge.domain.repository.ExistingRemoteMedia
import com.okb.whatsappbridge.domain.repository.MediaRepository
import com.okb.whatsappbridge.domain.repository.MediaRepository.Companion.MAX_AUTOMATIC_FAILED_ATTEMPTS
import com.okb.whatsappbridge.domain.repository.MediaUploadCandidate
import com.okb.whatsappbridge.domain.repository.NewMediaAttachment
import com.okb.whatsappbridge.util.media.MediaFileStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

class RoomMediaRepository(
    private val dao: MediaDao,
    private val store: MediaFileStore,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) : MediaRepository {

    override suspend fun createDetected(media: NewMediaAttachment): String? {
        // One media row per message in Phase 2 (a notification message carries at most one attachment).
        if (dao.countForMessage(media.messageId) > 0) return null
        val id = idGenerator()
        val row = MediaAttachmentEntity(
            id = id,
            messageId = media.messageId,
            deviceId = media.deviceId,
            groupName = media.groupName,
            senderName = media.senderName,
            mediaType = media.mediaType.name,
            mimeType = media.mimeType,
            originalFileName = media.originalFileName,
            localPath = null,
            fileSizeBytes = null,
            sha256 = null,
            acquisitionStatus = MediaAcquisitionStatus.DETECTED.name,
            uploadStatus = MediaUploadStatus.PENDING.name,
            statusDetail = null,
            uploadAttempts = 0,
            lastError = null,
            createdAt = media.createdAt,
            updatedAt = media.createdAt,
            r2ObjectKey = null,
            r2ETag = null,
            remoteRef = null,
        )
        return if (dao.insertMedia(row) != -1L) id else null
    }

    override suspend fun markAcquiring(id: String, at: Long) = dao.markAcquiring(id, at)

    override suspend fun markAvailableAndEnqueue(
        id: String, localPath: String, sizeBytes: Long, sha256: String, mimeType: String?, originalFileName: String?, at: Long,
    ) = dao.markAvailableAndEnqueue(id, localPath, sizeBytes, sha256, mimeType, originalFileName, MediaUploadStatus.PENDING.name, at)

    override suspend fun markUnavailable(id: String, reason: String, at: Long) =
        dao.markAcquisitionTerminal(id, MediaAcquisitionStatus.UNAVAILABLE.name, reason.take(300), at)

    override suspend fun markAcquisitionFailed(id: String, reason: String, at: Long) =
        dao.markAcquisitionTerminal(id, MediaAcquisitionStatus.FAILED.name, reason.take(300), at)

    override suspend fun findUploadedBySha256(sha256: String): ExistingRemoteMedia? =
        dao.findUploadedBySha256(sha256)?.let { ExistingRemoteMedia(it.r2ObjectKey!!, it.r2ETag, it.remoteRef) }

    override suspend fun nextUploadBatch(excludedIds: Set<String>, includeFailed: Boolean, limit: Int): List<MediaUploadCandidate> {
        val excluded = excludedIds.toList().ifEmpty { listOf("") }
        return dao.uploadBatch(excluded, includeFailed, MAX_AUTOMATIC_FAILED_ATTEMPTS, limit).mapNotNull { it.toCandidate() }
    }

    override suspend fun countUploadable(includeFailed: Boolean): Int =
        dao.countUploadable(includeFailed, MAX_AUTOMATIC_FAILED_ATTEMPTS)

    override suspend fun markUploading(id: String, at: Long) = dao.markUploading(id, at)
    override suspend fun markUploaded(id: String, objectKey: String?, etag: String?, remoteRef: String?, at: Long) =
        dao.markUploaded(id, objectKey, etag, remoteRef, at)
    override suspend fun markUploadRetrying(id: String, error: String, at: Long) =
        dao.markUploadFailed(id, MediaUploadStatus.RETRYING.name, error, at)
    override suspend fun markUploadFailed(id: String, error: String, at: Long) =
        dao.markUploadFailed(id, MediaUploadStatus.FAILED.name, error, at)
    override suspend fun recoverInterruptedUploads(at: Long): Int = dao.recoverInterruptedUploads(at)
    override suspend fun resetFailedToPending(at: Long): Int = dao.resetFailedToPending(at)
    override suspend fun repairQueue(now: Long) = dao.repairQueue(now)

    override suspend fun cleanupUploadedLocalFiles(cutoff: Long, at: Long): Int {
        val paths = dao.localPathsToCleanup(cutoff)
        paths.forEach { store.delete(it) }
        return dao.clearCleanedLocalPaths(cutoff, at)
    }

    override suspend fun largestQueuedBytes(): Long = dao.largestQueuedBytes()
    override fun storageUsedBytes(): Long = store.totalBytes()
    override fun usableSpaceBytes(): Long? = store.usableSpaceBytes()

    override fun observeMediaCounts(): Flow<MediaCounts> {
        val acquisition = dao.observeAcquisitionCounts()
        val upload = dao.observeUploadStatusCounts()
        return kotlinx.coroutines.flow.combine(acquisition, upload) { acq, up ->
            val a = acq.associate { MediaAcquisitionStatus.fromStorage(it.status) to it.count }
            val u = up.associate { MediaUploadStatus.fromStorage(it.status) to it.count }
            MediaCounts(
                detected = a[MediaAcquisitionStatus.DETECTED] ?: 0,
                acquiring = a[MediaAcquisitionStatus.ACQUIRING] ?: 0,
                available = a[MediaAcquisitionStatus.AVAILABLE] ?: 0,
                unavailable = a[MediaAcquisitionStatus.UNAVAILABLE] ?: 0,
                acquisitionFailed = a[MediaAcquisitionStatus.FAILED] ?: 0,
                pendingUpload = u[MediaUploadStatus.PENDING] ?: 0,
                uploading = u[MediaUploadStatus.UPLOADING] ?: 0,
                retrying = u[MediaUploadStatus.RETRYING] ?: 0,
                uploadFailed = u[MediaUploadStatus.FAILED] ?: 0,
                uploaded = u[MediaUploadStatus.UPLOADED] ?: 0,
            )
        }
    }

    override fun observeCapturedSince(since: Long): Flow<Int> = dao.observeCountSince(since)
    override fun observeQueuedBytes(): Flow<Long> = dao.observeQueuedBytes()
    override fun observeLastCaptureAt(): Flow<Long?> = dao.observeLastCaptureAt()
    override fun observeLastUploadAt(): Flow<Long?> = dao.observeLastUploadAt()

    override fun observeForMessage(messageId: String): Flow<List<MediaAttachment>> =
        dao.observeForMessage(messageId).map { rows -> rows.map { it.toDomain() } }

    override fun observeMessageMediaSummaries(): Flow<Map<String, MediaSummary>> =
        dao.observeAllSummaries().map { rows ->
            rows.associate { r ->
                r.messageId to MediaSummary(
                    mediaType = MediaType.fromStorage(r.mediaType),
                    mimeType = r.mimeType,
                    fileSizeBytes = r.fileSizeBytes,
                    acquisitionStatus = MediaAcquisitionStatus.fromStorage(r.acquisitionStatus),
                    uploadStatus = MediaUploadStatus.fromStorage(r.uploadStatus),
                )
            }
        }

    override suspend fun isHealthy(): Boolean = runCatching { dao.countAll() >= 0 }.getOrDefault(false)

    private fun MediaWithQueue.toCandidate(): MediaUploadCandidate? {
        val m = media
        return MediaUploadCandidate(
            id = m.id,
            deviceId = m.deviceId,
            messageId = m.messageId,
            groupName = m.groupName,
            senderName = m.senderName,
            mediaType = MediaType.fromStorage(m.mediaType),
            mimeType = m.mimeType,
            originalFileName = m.originalFileName,
            localPath = m.localPath ?: return null,
            fileSizeBytes = m.fileSizeBytes ?: return null,
            sha256 = m.sha256 ?: return null,
            createdAt = m.createdAt,
            attemptCount = attemptCount,
        )
    }

    private fun MediaAttachmentEntity.toDomain() = MediaAttachment(
        id = id,
        messageId = messageId,
        groupName = groupName,
        senderName = senderName,
        mediaType = MediaType.fromStorage(mediaType),
        mimeType = mimeType,
        originalFileName = originalFileName,
        fileSizeBytes = fileSizeBytes,
        sha256 = sha256,
        acquisitionStatus = MediaAcquisitionStatus.fromStorage(acquisitionStatus),
        uploadStatus = MediaUploadStatus.fromStorage(uploadStatus),
        statusDetail = statusDetail,
        uploadAttempts = uploadAttempts,
        lastError = lastError,
        createdAt = createdAt,
        updatedAt = updatedAt,
        uploadedAt = uploadedAt,
        r2ObjectKey = r2ObjectKey,
        r2ETag = r2ETag,
        hasLocalFile = localPath != null && MediaAcquisitionStatus.fromStorage(acquisitionStatus) == MediaAcquisitionStatus.AVAILABLE,
    )
}

package com.okb.whatsappbridge.data.repository

import com.okb.whatsappbridge.data.local.dao.MessageDao
import com.okb.whatsappbridge.data.local.dao.MessageWithQueue
import com.okb.whatsappbridge.data.local.dao.SettingsDao
import com.okb.whatsappbridge.data.local.entity.WhatsAppMessageEntity
import com.okb.whatsappbridge.domain.model.BridgeMessage
import com.okb.whatsappbridge.domain.model.MediaStatus
import com.okb.whatsappbridge.domain.model.MediaType
import com.okb.whatsappbridge.domain.model.QueueCounts
import com.okb.whatsappbridge.domain.model.UploadStatus
import com.okb.whatsappbridge.domain.repository.DeviceIdentityRepository
import com.okb.whatsappbridge.domain.repository.MessageRepository
import com.okb.whatsappbridge.domain.repository.MessageRepository.Companion.MAX_AUTOMATIC_FAILED_ATTEMPTS
import com.okb.whatsappbridge.domain.repository.NewCapturedMessage
import com.okb.whatsappbridge.domain.repository.SaveOutcome
import com.okb.whatsappbridge.domain.repository.SaveResult
import com.okb.whatsappbridge.domain.repository.UploadCandidate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

class RoomMessageRepository(
    private val dao: MessageDao,
    private val settingsDao: SettingsDao,
    private val identity: DeviceIdentityRepository,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) : MessageRepository {

    override suspend fun saveCaptured(message: NewCapturedMessage): SaveOutcome {
        val id = idGenerator()
        val entity = WhatsAppMessageEntity(
            id = id,
            serverId = null,
            deviceId = identity.deviceId(),
            groupName = message.groupName,
            senderName = message.senderName,
            messageText = message.messageText,
            timestamp = message.timestamp,
            mediaType = message.mediaType.name,
            mediaStatus = message.mediaStatus.name,
            fingerprint = message.fingerprint,
            uploadStatus = UploadStatus.PENDING_UPLOAD.name,
            createdAt = message.capturedAt,
            packageName = message.packageName,
            notificationKey = message.notificationKey,
        )
        return if (dao.insertCaptured(entity, enqueuedAt = message.capturedAt)) SaveOutcome(SaveResult.INSERTED, id)
        else SaveOutcome(SaveResult.DUPLICATE, dao.activeIdByFingerprint(message.fingerprint))
    }

    override fun observeRecent(status: UploadStatus?, limit: Int): Flow<List<BridgeMessage>> =
        dao.observeRecent(status?.name, limit).map { rows -> rows.map { it.toDomain() } }

    override fun observeById(id: String): Flow<BridgeMessage?> =
        dao.observeById(id).map { it?.toDomain() }

    override fun observeQueueCounts(): Flow<QueueCounts> = dao.observeStatusCounts().map { rows ->
        val byStatus = rows.associate { UploadStatus.fromStorage(it.status) to it.count }
        QueueCounts(
            pending = byStatus[UploadStatus.PENDING_UPLOAD] ?: 0,
            uploading = byStatus[UploadStatus.UPLOADING] ?: 0,
            retrying = byStatus[UploadStatus.RETRYING] ?: 0,
            failed = byStatus[UploadStatus.FAILED] ?: 0,
            uploaded = byStatus[UploadStatus.UPLOADED] ?: 0,
        )
    }

    override fun observeCapturedSince(since: Long): Flow<Int> = dao.observeCountSince(since)

    override fun observeLatestMessageTimestamp(): Flow<Long?> = dao.observeLatestTimestamp()

    override suspend fun nextUploadBatch(excludedIds: Set<String>, includeFailed: Boolean, limit: Int): List<UploadCandidate> {
        // SQLite needs a non-empty IN list; an impossible id keeps the query valid.
        val excluded = excludedIds.toList().ifEmpty { listOf("") }
        return dao.uploadBatch(excluded, includeFailed, MAX_AUTOMATIC_FAILED_ATTEMPTS, limit).map { row ->
            val m = row.message
            UploadCandidate(
                id = m.id,
                deviceId = m.deviceId,
                groupName = m.groupName,
                senderName = m.senderName,
                messageText = m.messageText,
                timestamp = m.timestamp,
                mediaType = MediaType.fromStorage(m.mediaType),
                mediaStatus = MediaStatus.fromStorage(m.mediaStatus),
                fingerprint = m.fingerprint,
                packageName = m.packageName,
                createdAt = m.createdAt,
                attemptCount = row.attemptCount,
            )
        }
    }

    override suspend fun countUploadable(includeFailed: Boolean): Int =
        dao.countUploadable(includeFailed, MAX_AUTOMATIC_FAILED_ATTEMPTS)

    override suspend fun markUploading(id: String) = dao.markUploading(id)

    override suspend fun markUploaded(id: String, serverId: String?, at: Long) = dao.markUploaded(id, serverId, at)

    override suspend fun markRetrying(id: String, error: String, httpCode: Int?, at: Long) =
        dao.markAttemptFailed(id, UploadStatus.RETRYING.name, error, httpCode, at)

    override suspend fun markFailed(id: String, error: String, httpCode: Int?, at: Long) =
        dao.markAttemptFailed(id, UploadStatus.FAILED.name, error, httpCode, at)

    override suspend fun recoverInterruptedUploads(): Int = dao.recoverInterruptedUploads()

    override suspend fun resetFailedToPending(): Int = dao.resetFailedToPending()

    override suspend fun repairQueue(now: Long) = dao.repairQueue(now)

    override suspend fun deleteUploadedBefore(cutoff: Long): Int = dao.deleteUploadedBefore(cutoff)

    override suspend fun moveToRecycleBin(ids: Collection<String>, at: Long): Int =
        ids.chunked(CHUNK).sumOf { dao.moveToBin(it, at) }

    override suspend fun restoreFromRecycleBin(ids: Collection<String>): Int =
        ids.chunked(CHUNK).sumOf { dao.restoreFromBin(it) }

    override fun observeRecycleBin(limit: Int): Flow<List<BridgeMessage>> =
        dao.observeBin(limit).map { rows -> rows.map { it.toDomain() } }

    override fun observeRecycleBinCount(): Flow<Int> = dao.observeBinCount()

    override suspend fun recycleBinIdsDeletedBefore(cutoff: Long): List<String> = dao.binIdsDeletedBefore(cutoff)

    override suspend fun purgeFromRecycleBin(ids: Collection<String>, at: Long): Int =
        ids.chunked(CHUNK).sumOf { dao.purgeFromBin(it, at) }

    override suspend fun deleteTombstonesBefore(cutoff: Long): Int = dao.deleteTombstonesBefore(cutoff)

    override suspend fun isDatabaseHealthy(): Boolean = runCatching {
        settingsDao.ping() == 1 && dao.countAll() >= 0
    }.getOrDefault(false)

    private fun MessageWithQueue.toDomain() = BridgeMessage(
        id = message.id,
        serverId = message.serverId,
        groupName = message.groupName,
        senderName = message.senderName,
        messageText = message.messageText,
        timestamp = message.timestamp,
        mediaType = MediaType.fromStorage(message.mediaType),
        mediaStatus = MediaStatus.fromStorage(message.mediaStatus),
        uploadStatus = UploadStatus.fromStorage(message.uploadStatus),
        packageName = message.packageName,
        createdAt = message.createdAt,
        uploadedAt = message.uploadedAt,
        lastError = message.lastError,
        attemptCount = attemptCount,
        deletedAt = message.deletedAt,
    )

    private companion object {
        /** Stays well below SQLite's bound-parameter limit for IN (...) lists. */
        const val CHUNK = 500
    }
}

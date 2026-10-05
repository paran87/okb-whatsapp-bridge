package com.okb.whatsappbridge.domain.usecase

import com.okb.whatsappbridge.domain.repository.MediaRepository
import com.okb.whatsappbridge.domain.repository.MessageRepository
import com.okb.whatsappbridge.domain.repository.UploadScheduler
import com.okb.whatsappbridge.util.logging.BridgeLogger

/**
 * Recycle Bin for captured messages. Everything here is local to this phone: messages and media
 * already uploaded to the backend are never deleted there.
 *
 *  - Move to bin: the message is hidden from lists and counts and is not uploaded while it is there
 *    (its queue entry is kept, so nothing is lost).
 *  - Restore: back in the list; anything not yet uploaded is queued again.
 *  - Delete forever / empty bin / auto-empty after [retentionMillis]: text, sender, local media files
 *    and media rows are removed. Only a fingerprint (one-way hash) remains for [tombstoneMillis], so a
 *    notification WhatsApp/Viber re-posts cannot capture the deleted message again.
 *
 * Logs record counts only, never message content.
 */
class RecycleBinUseCase(
    private val messages: MessageRepository,
    private val media: MediaRepository?,
    private val scheduler: UploadScheduler,
    private val logger: BridgeLogger,
    private val clock: () -> Long = System::currentTimeMillis,
    val retentionMillis: Long = DEFAULT_RETENTION_MILLIS,
    private val tombstoneMillis: Long = DEFAULT_TOMBSTONE_MILLIS,
) {

    suspend fun moveToBin(ids: Collection<String>): Int {
        if (ids.isEmpty()) return 0
        val moved = messages.moveToRecycleBin(ids.toSet(), clock())
        if (moved > 0) logger.info(TAG, "Moved $moved message(s) to the Recycle Bin")
        return moved
    }

    suspend fun restore(ids: Collection<String>): Int {
        if (ids.isEmpty()) return 0
        val restored = messages.restoreFromRecycleBin(ids.toSet())
        if (restored > 0) {
            logger.info(TAG, "Restored $restored message(s) from the Recycle Bin")
            // Restored messages that were never uploaded go back into the normal upload flow.
            scheduler.requestUpload()
            scheduler.requestMediaUpload()
        }
        return restored
    }

    /** Deletes the given messages forever — only those that are in the bin. */
    suspend fun deleteForever(ids: Collection<String>): Int {
        if (ids.isEmpty()) return 0
        val inBin = messages.recycleBinIdsDeletedBefore(Long.MAX_VALUE).toSet()
        return purge(ids.filter { it in inBin })
    }

    suspend fun emptyBin(): Int = purge(messages.recycleBinIdsDeletedBefore(Long.MAX_VALUE))

    /** Periodic housekeeping: empties messages older than the retention period, drops old tombstones. */
    suspend fun purgeExpired(): Int {
        val now = clock()
        val purged = purge(messages.recycleBinIdsDeletedBefore(now - retentionMillis))
        messages.deleteTombstonesBefore(now - tombstoneMillis)
        return purged
    }

    private suspend fun purge(ids: List<String>): Int {
        if (ids.isEmpty()) return 0
        media?.deleteForMessages(ids)
        val purged = messages.purgeFromRecycleBin(ids, clock())
        if (purged > 0) logger.info(TAG, "Deleted $purged message(s) forever from the Recycle Bin")
        return purged
    }

    companion object {
        private const val TAG = "RecycleBin"
        const val DEFAULT_RETENTION_MILLIS = 30L * 24 * 60 * 60 * 1000
        const val DEFAULT_TOMBSTONE_MILLIS = 7L * 24 * 60 * 60 * 1000
    }
}

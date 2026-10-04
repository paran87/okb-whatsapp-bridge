package com.okb.whatsappbridge.domain.usecase

import com.okb.whatsappbridge.domain.repository.MediaRepository
import com.okb.whatsappbridge.domain.repository.SettingsRepository
import com.okb.whatsappbridge.domain.repository.UploadScheduler
import com.okb.whatsappbridge.media.MediaContentAccess
import com.okb.whatsappbridge.domain.model.MediaType
import com.okb.whatsappbridge.util.logging.BridgeLogger
import com.okb.whatsappbridge.util.media.MediaFileStore
import com.okb.whatsappbridge.util.media.MediaMimeTypes

/** What to acquire: the media row plus the (short-lived) legitimate notification URI, if any. */
data class MediaAcquisitionRequest(
    val mediaId: String,
    val mediaType: MediaType,
    val dataUri: String?,
    val dataMimeType: String?,
)

enum class AcquisitionOutcome { ACQUIRED, UNAVAILABLE, FAILED }

/**
 * Acquires the original media file **only** when Android legitimately provides it on the notification
 * (a readable `dataUri`). This runs inline during capture because the URI grant is tied to the
 * notification's lifetime; the file is streamed into app-controlled storage and hashed in one pass.
 *
 * When no legitimate file is available — the common case, because WhatsApp does not attach the
 * original photo/video to group notifications — the row is marked UNAVAILABLE with an
 * operator-readable reason. No workaround is attempted and the message/caption is already saved.
 */
class AcquireMediaUseCase(
    private val media: MediaRepository,
    private val settings: SettingsRepository,
    private val content: MediaContentAccess,
    private val store: MediaFileStore,
    private val scheduler: UploadScheduler,
    private val logger: BridgeLogger,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    suspend operator fun invoke(request: MediaAcquisitionRequest): AcquisitionOutcome {
        val now = clock()
        val uri = request.dataUri
        if (uri.isNullOrBlank()) {
            media.markUnavailable(request.mediaId, NO_URI_REASON, now)
            return AcquisitionOutcome.UNAVAILABLE
        }
        if (!content.isMediaAcquisitionSupported()) {
            media.markUnavailable(request.mediaId, NOT_SUPPORTED_REASON, now)
            return AcquisitionOutcome.UNAVAILABLE
        }

        media.markAcquiring(request.mediaId, now)
        val metadata = runCatching { content.queryMetadata(uri) }.getOrNull()
        val mimeType = MediaMimeTypes.normalizeMime(request.dataMimeType ?: metadata?.mimeType)
        val fileName = metadata?.displayName
        val extension = MediaMimeTypes.extension(mimeType, request.mediaType, fileName)

        // Refuse to fill the disk: if we know the size and it won't fit, fail cleanly.
        val declaredSize = metadata?.sizeBytes
        val usable = store.usableSpaceBytes()
        if (declaredSize != null && usable != null && declaredSize > usable - SAFETY_MARGIN_BYTES) {
            media.markAcquisitionFailed(request.mediaId, "Not enough local storage to save this media file.", now)
            return AcquisitionOutcome.FAILED
        }

        val stored = try {
            content.openStream(uri).use { input -> store.store(request.mediaId, extension, input) }
        } catch (e: SecurityException) {
            // Android did not grant read access to this URI. Expected; not an error to the operator.
            media.markUnavailable(request.mediaId, NO_ACCESS_REASON, now)
            return AcquisitionOutcome.UNAVAILABLE
        } catch (e: Exception) {
            logger.warn(TAG, "Media acquisition failed for ${request.mediaId}: ${e.javaClass.simpleName}")
            media.markAcquisitionFailed(request.mediaId, "Could not read the provided media file.", now)
            return AcquisitionOutcome.FAILED
        }

        if (stored.sizeBytes <= 0L) {
            store.delete(stored.file.absolutePath)
            media.markUnavailable(request.mediaId, "The provided media file was empty.", now)
            return AcquisitionOutcome.UNAVAILABLE
        }

        media.markAvailableAndEnqueue(
            id = request.mediaId,
            localPath = stored.file.absolutePath,
            sizeBytes = stored.sizeBytes,
            sha256 = stored.sha256,
            mimeType = mimeType,
            originalFileName = fileName,
            at = clock(),
        )
        settings.recordMediaCapture(clock())
        logger.info(TAG, "Acquired media ${request.mediaId} (${request.mediaType}, ${stored.sizeBytes} bytes)")
        scheduler.requestMediaUpload()
        return AcquisitionOutcome.ACQUIRED
    }

    private companion object {
        const val TAG = "Media"
        const val SAFETY_MARGIN_BYTES = 50L * 1024 * 1024
        const val NO_URI_REASON =
            "Media detected, but WhatsApp did not attach the original file to the notification. " +
                "Android does not expose WhatsApp's photos/videos to notification listeners, so only the text/caption was captured."
        const val NO_ACCESS_REASON =
            "Media detected, but Android did not provide an accessible media file for it. Only the text/caption was captured."
        const val NOT_SUPPORTED_REASON =
            "Media detected, but this device/OS does not expose the original file to the bridge. Only the text/caption was captured."
    }
}

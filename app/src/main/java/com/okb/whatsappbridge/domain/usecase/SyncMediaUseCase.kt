package com.okb.whatsappbridge.domain.usecase

import com.okb.whatsappbridge.data.remote.api.ApiResult
import com.okb.whatsappbridge.data.remote.api.BackendConfig
import com.okb.whatsappbridge.data.remote.api.BridgeApi
import com.okb.whatsappbridge.data.remote.api.MediaUploader
import com.okb.whatsappbridge.data.remote.dto.MediaCompleteRequest
import com.okb.whatsappbridge.data.remote.dto.MediaIntentRequest
import com.okb.whatsappbridge.domain.repository.DeviceIdentityRepository
import com.okb.whatsappbridge.domain.repository.MediaRepository
import com.okb.whatsappbridge.domain.repository.MediaUploadCandidate
import com.okb.whatsappbridge.domain.repository.SettingsRepository
import com.okb.whatsappbridge.util.logging.BridgeLogger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Drains the persistent media upload queue using the presigned (or dev local-sink) flow:
 * content-dedupe → `media/intent` → streamed PUT to the returned URL → `media/complete`.
 *
 * R2 credentials never reach the device: the backend issues a short-lived, key-scoped upload URL. A
 * media file is marked UPLOADED only after `complete` confirms it; transient failures leave it
 * RETRYING (WorkManager backs off), non-transient ones FAILED. Local files are never deleted here.
 */
class SyncMediaUseCase(
    private val settings: SettingsRepository,
    private val media: MediaRepository,
    private val identity: DeviceIdentityRepository,
    private val api: BridgeApi,
    private val uploader: MediaUploader,
    private val logger: BridgeLogger,
    private val fileFor: (String) -> File = ::File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zoneId: () -> ZoneId = ZoneId::systemDefault,
    private val batchSize: Int = 10,
    private val maxPerRun: Int = 50,
) {

    private val runLock = Mutex()

    suspend operator fun invoke(trigger: SyncTrigger): SyncOutcome = runLock.withLock { sync(trigger) }

    private suspend fun sync(trigger: SyncTrigger): SyncOutcome {
        val current = settings.current()
        if (current.syncPaused) return SyncOutcome.Paused
        if (!current.backendConfigured) return SyncOutcome.NotConfigured

        val config = BackendConfig(current.backendUrl, identity.deviceId(), identity.deviceToken())
        media.recoverInterruptedUploads(clock())

        val attempted = HashSet<String>()
        var uploaded = 0
        var failed = 0
        var retryNeeded = false
        var lastError: String? = null

        run loop@{
            repeat(maxPerRun) {
                val batch = media.nextUploadBatch(attempted, trigger.includeFailed, batchSize)
                if (batch.isEmpty()) return@loop
                for (candidate in batch) {
                    attempted += candidate.id
                    when (val result = uploadOne(config, candidate)) {
                        is UploadResult.Done -> uploaded++
                        is UploadResult.Retry -> {
                            lastError = result.error
                            retryNeeded = true
                            return@loop
                        }
                        is UploadResult.Failed -> {
                            lastError = result.error
                            failed++
                        }
                        is UploadResult.StopRetry -> {
                            lastError = result.error
                            retryNeeded = true
                            return@loop
                        }
                    }
                }
            }
            if (media.countUploadable(includeFailed = false) > 0) retryNeeded = true
        }

        val now = clock()
        if (uploaded > 0) settings.recordMediaUploadSuccess(now)
        lastError?.let { settings.recordMediaFailure(now, it) }
        if (uploaded > 0 || failed > 0 || retryNeeded) {
            logger.info(TAG, "Media sync ($trigger): uploaded=$uploaded failed=$failed retry=$retryNeeded")
        }
        return SyncOutcome.Completed(uploaded, failed, retryNeeded, lastError)
    }

    private sealed interface UploadResult {
        data object Done : UploadResult
        data class Retry(val error: String) : UploadResult
        data class StopRetry(val error: String) : UploadResult
        data class Failed(val error: String) : UploadResult
    }

    private suspend fun uploadOne(config: BackendConfig, c: MediaUploadCandidate): UploadResult {
        val now = clock()
        media.markUploading(c.id, now)

        // 1. Content-based dedupe: identical bytes already uploaded from this device → reuse the object.
        media.findUploadedBySha256(c.sha256)?.let { existing ->
            media.markUploaded(c.id, existing.objectKey, existing.etag, existing.remoteRef, clock())
            return UploadResult.Done
        }

        // 2. Intent: obtain an upload URL (or a duplicate verdict) from the authenticated backend.
        val intentReq = MediaIntentRequest(
            deviceId = c.deviceId,
            sha256 = c.sha256,
            mediaType = c.mediaType.name,
            mimeType = c.mimeType,
            fileSizeBytes = c.fileSizeBytes,
            originalFileName = c.originalFileName,
            groupName = c.groupName,
            capturedAt = iso(c.createdAt.takeIf { it > 0 } ?: now),
            messageFingerprint = null,
        )
        val intent = when (val r = api.mediaIntent(config, intentReq)) {
            is ApiResult.Success -> r.value
            is ApiResult.NetworkError -> return retry(c, r.message)
            is ApiResult.HttpError -> return if (r.retryable || r.authError) retry(c, r.message) else fail(c, r.message)
            is ApiResult.ConfigurationError -> return retry(c, r.message)
        }
        if (intent.isDuplicate) {
            media.markUploaded(c.id, intent.objectKey, null, intent.remoteRef, clock())
            return UploadResult.Done
        }
        val url = intent.uploadUrl
        val objectKey = intent.objectKey
        if (url.isNullOrBlank() || objectKey.isNullOrBlank()) {
            return fail(c, "Backend did not return an upload URL")
        }

        // 3. Stream the file to R2 (or the dev local-sink). Expired presign (403) → re-intent next run.
        val file = fileFor(c.localPath)
        val etag = when (val r = uploader.put(url, intent.headers.orEmpty(), file)) {
            is ApiResult.Success -> r.value
            is ApiResult.NetworkError -> return retry(c, r.message)
            is ApiResult.HttpError -> return if (r.retryable || r.httpCode == 403) retry(c, r.message) else fail(c, r.message)
            is ApiResult.ConfigurationError -> return fail(c, r.message)
        }

        // 4. Complete: record the object in the backend index. Idempotent on retries.
        val completeReq = MediaCompleteRequest(
            deviceId = c.deviceId,
            sha256 = c.sha256,
            objectKey = objectKey,
            etag = etag,
            fileSizeBytes = c.fileSizeBytes,
            mediaType = c.mediaType.name,
            mimeType = c.mimeType,
            originalFileName = c.originalFileName,
            groupName = c.groupName,
            senderName = c.senderName,
            messageFingerprint = null,
            capturedAt = iso(c.createdAt.takeIf { it > 0 } ?: now),
        )
        return when (val r = api.mediaComplete(config, completeReq)) {
            is ApiResult.Success -> {
                media.markUploaded(c.id, r.value.objectKey ?: objectKey, r.value.etag ?: etag, r.value.remoteRef, clock())
                UploadResult.Done
            }
            is ApiResult.NetworkError -> retry(c, r.message)
            is ApiResult.HttpError -> if (r.retryable || r.authError) retry(c, r.message) else fail(c, r.message)
            is ApiResult.ConfigurationError -> retry(c, r.message)
        }
    }

    private suspend fun retry(c: MediaUploadCandidate, error: String): UploadResult {
        media.markUploadRetrying(c.id, error, clock())
        return UploadResult.Retry(error)
    }

    private suspend fun fail(c: MediaUploadCandidate, error: String): UploadResult {
        media.markUploadFailed(c.id, error, clock())
        return UploadResult.Failed(error)
    }

    private fun iso(epochMillis: Long): String =
        OffsetDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), zoneId())
            .truncatedTo(ChronoUnit.SECONDS)
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    private companion object {
        const val TAG = "MediaSync"
    }
}

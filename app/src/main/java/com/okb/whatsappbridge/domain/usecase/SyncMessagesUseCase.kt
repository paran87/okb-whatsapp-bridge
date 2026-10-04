package com.okb.whatsappbridge.domain.usecase

import com.okb.whatsappbridge.data.remote.api.ApiResult
import com.okb.whatsappbridge.data.remote.api.BackendConfig
import com.okb.whatsappbridge.data.remote.api.BridgeApi
import com.okb.whatsappbridge.data.remote.dto.MessageUploadRequest
import com.okb.whatsappbridge.domain.repository.DeviceIdentityRepository
import com.okb.whatsappbridge.domain.repository.MessageRepository
import com.okb.whatsappbridge.domain.repository.SettingsRepository
import com.okb.whatsappbridge.domain.repository.UploadCandidate
import com.okb.whatsappbridge.util.logging.BridgeLogger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

enum class SyncTrigger {
    /** One-time work queued right after a message was stored. */
    IMMEDIATE,

    /** 15-minute periodic reconciliation – also re-attempts FAILED messages. */
    RECONCILE,

    /** Operator pressed "Sync now". */
    MANUAL;

    val includeFailed: Boolean get() = this != IMMEDIATE
}

sealed interface SyncOutcome {
    data object Paused : SyncOutcome
    data object NotConfigured : SyncOutcome

    data class Completed(
        val uploaded: Int,
        val failed: Int,
        /** True when a transient error occurred and WorkManager should retry with backoff. */
        val retryNeeded: Boolean,
        val lastError: String?,
    ) : SyncOutcome
}

/**
 * Drains the persistent upload queue. Each message is marked UPLOADED only after the backend
 * returns 2xx; transient errors leave it RETRYING (WorkManager retries with exponential backoff),
 * non-transient errors mark it FAILED (re-attempted by reconciliation or manually). Messages are
 * never deleted on failure.
 */
class SyncMessagesUseCase(
    private val settings: SettingsRepository,
    private val messages: MessageRepository,
    private val identity: DeviceIdentityRepository,
    private val api: BridgeApi,
    private val logger: BridgeLogger,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zoneId: () -> ZoneId = ZoneId::systemDefault,
    private val batchSize: Int = 50,
    private val maxBatchesPerRun: Int = 15,
) {

    private val runLock = Mutex()

    /** Runs are serialized so two workers can never upload the same message concurrently. */
    suspend operator fun invoke(trigger: SyncTrigger): SyncOutcome = runLock.withLock { sync(trigger) }

    private suspend fun sync(trigger: SyncTrigger): SyncOutcome {
        val current = settings.current()
        if (current.syncPaused) return SyncOutcome.Paused
        if (!current.backendConfigured) return SyncOutcome.NotConfigured

        val config = BackendConfig(current.backendUrl, identity.deviceId(), identity.deviceToken())
        // Only one upload worker runs at a time (unique work), so UPLOADING rows are leftovers.
        messages.recoverInterruptedUploads()

        val attempted = HashSet<String>()
        var uploaded = 0
        var failed = 0
        var retryNeeded = false
        var lastError: String? = null

        run loop@{
            repeat(maxBatchesPerRun) {
                val batch = messages.nextUploadBatch(attempted, trigger.includeFailed, batchSize)
                if (batch.isEmpty()) return@loop
                for (candidate in batch) {
                    attempted += candidate.id
                    messages.markUploading(candidate.id)
                    val now = clock()
                    when (val result = api.uploadMessage(config, candidate.toRequest(now))) {
                        is ApiResult.Success -> {
                            messages.markUploaded(candidate.id, result.value.resolvedServerId, now)
                            uploaded++
                        }
                        is ApiResult.NetworkError -> {
                            // Offline or backend unreachable: keep everything queued and back off.
                            messages.markRetrying(candidate.id, result.message, null, now)
                            lastError = result.message
                            retryNeeded = true
                            return@loop
                        }
                        is ApiResult.HttpError -> {
                            lastError = result.message
                            if (result.retryable) {
                                messages.markRetrying(candidate.id, result.message, result.httpCode, now)
                                retryNeeded = true
                                return@loop
                            }
                            messages.markFailed(candidate.id, result.message, result.httpCode, now)
                            failed++
                            // Bad credentials affect every message; stop instead of burning attempts.
                            if (result.authError) return@loop
                        }
                        is ApiResult.ConfigurationError -> {
                            messages.markFailed(candidate.id, result.message, null, now)
                            lastError = result.message
                            failed++
                            return@loop
                        }
                    }
                }
            }
            // Batches remain after the per-run cap: continue in a follow-up run.
            if (messages.countUploadable(includeFailed = false) > 0) retryNeeded = true
        }

        val now = clock()
        if (uploaded > 0) settings.recordUploadSuccess(now)
        lastError?.let { settings.recordUploadFailure(now, it) }
        if (uploaded > 0 || failed > 0 || retryNeeded) {
            logger.info(
                TAG,
                "Sync ($trigger): uploaded=$uploaded failed=$failed retry=$retryNeeded" +
                    (lastError?.let { " lastError=$it" } ?: ""),
            )
        }
        return SyncOutcome.Completed(uploaded, failed, retryNeeded, lastError)
    }

    private fun UploadCandidate.toRequest(now: Long) = MessageUploadRequest(
        deviceId = deviceId,
        clientMessageId = id,
        fingerprint = fingerprint,
        groupName = groupName,
        senderName = senderName,
        messageText = messageText,
        timestamp = isoTimestamp(timestamp),
        timestampMillis = timestamp,
        mediaType = mediaType.name,
        mediaStatus = mediaStatus.name,
        sourcePackage = packageName,
        capturedAt = isoTimestamp(createdAt.takeIf { it > 0 } ?: now),
    )

    private fun isoTimestamp(epochMillis: Long): String =
        OffsetDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), zoneId())
            .truncatedTo(ChronoUnit.SECONDS)
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    private companion object {
        const val TAG = "Sync"
    }
}

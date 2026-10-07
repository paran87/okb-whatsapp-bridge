package com.okb.whatsappbridge.domain.usecase

import com.okb.whatsappbridge.data.remote.api.ApiResult
import com.okb.whatsappbridge.data.remote.api.BackendConfig
import com.okb.whatsappbridge.data.remote.api.BridgeApi
import com.okb.whatsappbridge.domain.repository.DeviceIdentityRepository
import com.okb.whatsappbridge.domain.repository.SettingsRepository
import com.okb.whatsappbridge.util.logging.BridgeLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * Makes scheduled reports go out within seconds of their date of sending. While monitoring is on (the
 * monitoring foreground service keeps the process alive), it asks the backend every [POLL_MS] — a cheap,
 * read-only question — whether a report is due or a TEXT / PDF is waiting for this phone, and runs the full
 * report check at once when the answer is yes. Close to a known sending time it waits only until that moment.
 * It also keeps the exact alarm on the next sending time, so an entry added in the Command Center a minute
 * before its time is caught even if the phone dozes. A short, renewed partial wake lock keeps the loop running
 * with the screen off (Android must let the app run in the background: battery "No restrictions"). The
 * 15-minute background check stays as the fallback.
 */
class ReportWatcher(
    private val settings: SettingsRepository,
    private val identity: DeviceIdentityRepository,
    private val api: BridgeApi,
    private val wakeScheduler: ReportWakeScheduler?,
    private val runCheck: suspend () -> Unit,
    private val logger: BridgeLogger,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Called before every poll: keeps the CPU awake so the loop also runs with the screen off. */
    private val keepAwake: () -> Unit = {},
) {
    private var job: Job? = null
    private var lastAlarm: Pair<Long?, Long?>? = null
    private var lastCheckAt = 0L

    /** Starts the loop (no-op when it is already running). */
    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch {
            logger.info(TAG, "Report watcher started")
            while (isActive) {
                keepAwake()
                val wait = try {
                    pollOnce()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.warn(TAG, "Report watcher: ${e.javaClass.simpleName}")
                    POLL_MS
                }
                delay(wait)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** One question to the backend; returns how long to wait before the next one. */
    suspend fun pollOnce(): Long {
        val current = settings.current()
        if (!current.monitoringEnabled || !current.backendConfigured) return IDLE_MS
        val config = BackendConfig(current.backendUrl, identity.deviceId(), identity.deviceToken())
        val next = when (val r = api.consolidatedNext(config)) {
            is ApiResult.Success -> r.value
            // An older backend without the quick check: the alarm and the 15-minute check cover it.
            is ApiResult.HttpError -> return if (r.httpCode == 404) OLD_BACKEND_MS else POLL_MS
            is ApiResult.NetworkError, is ApiResult.ConfigurationError -> return POLL_MS
        }
        if ((next.due || next.textWaiting || next.pdfWaiting) && clock() - lastCheckAt >= MIN_CHECK_GAP_MS) {
            lastCheckAt = clock()
            logger.info(TAG, "Report due now: checking immediately")
            runCheck() // sets the alarms itself
            lastAlarm = null
            return AFTER_CHECK_MS
        }
        val nextCutoff = millis(next.nextCutoffAt)
        val nextRetry = millis(next.nextRetryAt)
        val alarm = nextCutoff to nextRetry
        if (alarm != lastAlarm) {
            wakeScheduler?.scheduleNext(nextCutoff, nextRetry)
            lastAlarm = alarm
        }
        // Wake right after the next known moment instead of up to POLL_MS later.
        val soonest = listOfNotNull(nextCutoff, nextRetry).minOrNull() ?: return POLL_MS
        return (soonest - clock() + JUST_AFTER_MS).coerceIn(MIN_WAIT_MS, POLL_MS)
    }

    private fun millis(iso: String?): Long? = iso?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

    companion object {
        private const val TAG = "TextReport"
        const val POLL_MS = 30_000L
        const val IDLE_MS = 60_000L
        const val OLD_BACKEND_MS = 10L * 60_000
        const val AFTER_CHECK_MS = 5_000L
        /** A due report is checked at most this often (e.g. a PDF that keeps failing to download). */
        const val MIN_CHECK_GAP_MS = 20_000L
        const val JUST_AFTER_MS = 1_500L
        const val MIN_WAIT_MS = 1_000L
    }
}

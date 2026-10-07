package com.okb.whatsappbridge.domain

import com.okb.whatsappbridge.data.remote.api.ApiResult
import com.okb.whatsappbridge.data.remote.api.BackendConfig
import com.okb.whatsappbridge.data.remote.api.BridgeApi
import com.okb.whatsappbridge.data.remote.dto.ConsolidatedNextResponse
import com.okb.whatsappbridge.data.repository.SecureDeviceIdentityRepository
import com.okb.whatsappbridge.domain.model.BridgeSettings
import com.okb.whatsappbridge.domain.usecase.ReportWakeScheduler
import com.okb.whatsappbridge.domain.usecase.ReportWatcher
import com.okb.whatsappbridge.fakes.FakeBridgeApi
import com.okb.whatsappbridge.fakes.FakeSettingsRepository
import com.okb.whatsappbridge.fakes.RecordingLogger
import com.okb.whatsappbridge.util.security.InMemorySecretStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class ReportWatcherTest {

    private var now = Instant.parse("2026-10-07T06:00:00Z").toEpochMilli()

    private inner class Api : BridgeApi by FakeBridgeApi() {
        var answer: ApiResult<ConsolidatedNextResponse> = ApiResult.Success(ConsolidatedNextResponse(), 200)
        var calls = 0
        override suspend fun consolidatedNext(config: BackendConfig): ApiResult<ConsolidatedNextResponse> {
            calls++
            return answer
        }
    }

    private val alarms = mutableListOf<Pair<Long?, Long?>>()
    private val scheduler = object : ReportWakeScheduler {
        override fun scheduleNext(nextCutoffAtMillis: Long?, nextRetryAtMillis: Long?) { alarms += nextCutoffAtMillis to nextRetryAtMillis }
        override fun scheduleRetry() = Unit
    }
    private var checks = 0

    private fun watcher(api: Api, monitoring: Boolean = true) = ReportWatcher(
        FakeSettingsRepository(BridgeSettings(backendUrl = "https://okb.test", monitoringEnabled = monitoring)),
        SecureDeviceIdentityRepository(InMemorySecretStore()), api, scheduler, { checks++ }, RecordingLogger(), clock = { now },
    )

    private fun iso(offsetMs: Long) = Instant.ofEpochMilli(now + offsetMs).toString()

    @Test
    fun `a due report is checked at once, and not again within the minimum gap`() = runTest {
        val api = Api().apply { answer = ApiResult.Success(ConsolidatedNextResponse(due = true), 200) }
        val w = watcher(api)
        assertEquals(ReportWatcher.AFTER_CHECK_MS, w.pollOnce())
        assertEquals(1, checks)
        now += 5_000
        w.pollOnce() // still reported due (e.g. backend busy): no second check within 20 s
        assertEquals(1, checks)
        now += ReportWatcher.MIN_CHECK_GAP_MS
        w.pollOnce()
        assertEquals(2, checks)
    }

    @Test
    fun `a TEXT or PDF waiting for this phone triggers the check too`() = runTest {
        val api = Api().apply { answer = ApiResult.Success(ConsolidatedNextResponse(textWaiting = true), 200) }
        watcher(api).pollOnce()
        assertEquals(1, checks)
        val pdf = Api().apply { answer = ApiResult.Success(ConsolidatedNextResponse(pdfWaiting = true), 200) }
        watcher(pdf).pollOnce()
        assertEquals(2, checks)
    }

    @Test
    fun `nothing due - the alarm follows the next sending time (set once) and the wait ends just after it`() = runTest {
        val api = Api().apply { answer = ApiResult.Success(ConsolidatedNextResponse(nextCutoffAt = iso(10_000)), 200) }
        val w = watcher(api)
        assertEquals(10_000 + ReportWatcher.JUST_AFTER_MS, w.pollOnce())
        assertEquals(1, alarms.size)
        assertEquals(now + 10_000, alarms.single().first)
        w.pollOnce()
        assertEquals(1, alarms.size) // unchanged: the alarm is not set again every poll
        assertEquals(0, checks)
    }

    @Test
    fun `a sending time far away - polls every 30 seconds (an entry added later is still caught)`() = runTest {
        val api = Api().apply { answer = ApiResult.Success(ConsolidatedNextResponse(nextCutoffAt = iso(3_600_000)), 200) }
        assertEquals(ReportWatcher.POLL_MS, watcher(api).pollOnce())
    }

    @Test
    fun `older backend (404) or offline - backs off, monitoring off - no request`() = runTest {
        val old = Api().apply { answer = ApiResult.HttpError(404, "not found") }
        assertEquals(ReportWatcher.OLD_BACKEND_MS, watcher(old).pollOnce())
        val offline = Api().apply { answer = ApiResult.NetworkError("UnknownHostException") }
        assertEquals(ReportWatcher.POLL_MS, watcher(offline).pollOnce())
        val off = Api()
        assertEquals(ReportWatcher.IDLE_MS, watcher(off, monitoring = false).pollOnce())
        assertEquals(0, off.calls)
    }
}

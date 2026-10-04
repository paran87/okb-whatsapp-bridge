package com.okb.whatsappbridge.domain

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.okb.whatsappbridge.data.remote.api.ApiResult
import com.okb.whatsappbridge.domain.model.UploadStatus
import com.okb.whatsappbridge.domain.usecase.SyncOutcome
import com.okb.whatsappbridge.domain.usecase.SyncTrigger
import com.okb.whatsappbridge.fakes.FakeBridgeApi
import com.okb.whatsappbridge.fakes.Snapshots
import com.okb.whatsappbridge.fakes.Snapshots.T0
import com.okb.whatsappbridge.fakes.TestBridge
import com.okb.whatsappbridge.whatsapp.SnapshotMessage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class SyncMessagesUseCaseTest {

    private val api = FakeBridgeApi()
    private lateinit var bridge: TestBridge

    @Before
    fun setUp() = runTest {
        bridge = TestBridge(ApplicationProvider.getApplicationContext(), api, clock = { T0 + 10_000 })
        bridge.settings.setMonitoringEnabled(true)
        bridge.settings.setBackendUrl("https://okb.test")
        bridge.groups.addAuthorizedGroup("OKB Monitoring", T0)
        bridge.process(
            Snapshots.groupMessaging(
                messages = listOf(
                    SnapshotMessage("Water rising", T0, "Ana"),
                    SnapshotMessage("Road closed", T0 + 60_000, "Ben"),
                ),
            ),
        )
    }

    @After
    fun tearDown() = bridge.db.close()

    private suspend fun statuses() = bridge.messages.observeRecent(null, 10).first()
        .sortedBy { it.timestamp }.map { it.uploadStatus }

    @Test
    fun `queued messages are uploaded oldest first and marked UPLOADED`() = runTest {
        val outcome = bridge.sync(SyncTrigger.IMMEDIATE) as SyncOutcome.Completed
        assertEquals(2, outcome.uploaded)
        assertFalse(outcome.retryNeeded)
        assertEquals(listOf("Water rising", "Road closed"), api.uploaded.map { it.messageText })
        assertEquals(listOf(UploadStatus.UPLOADED, UploadStatus.UPLOADED), statuses())
        assertEquals("2026-10-04T08:22:00+08:00", api.uploaded.first().timestamp)
        assertEquals(T0 + 10_000, bridge.settings.current().lastUploadSuccessAt)
        assertEquals("srv-1", bridge.messages.observeRecent(UploadStatus.UPLOADED, 10).first().last().serverId)
    }

    @Test
    fun `offline keeps messages queued and asks WorkManager to retry`() = runTest {
        api.uploadResults += ApiResult.NetworkError("UnknownHostException")
        val outcome = bridge.sync(SyncTrigger.IMMEDIATE) as SyncOutcome.Completed
        assertTrue(outcome.retryNeeded)
        assertEquals(0, outcome.uploaded)
        assertEquals(listOf(UploadStatus.RETRYING, UploadStatus.PENDING_UPLOAD), statuses())
        assertEquals("UnknownHostException", bridge.settings.current().lastUploadError)

        // Connectivity restored: the retry uploads everything.
        val retry = bridge.sync(SyncTrigger.IMMEDIATE) as SyncOutcome.Completed
        assertEquals(2, retry.uploaded)
        assertEquals(listOf(UploadStatus.UPLOADED, UploadStatus.UPLOADED), statuses())
    }

    @Test
    fun `server error is retried, client error marks FAILED and continues`() = runTest {
        api.uploadResults += ApiResult.HttpError(503, "HTTP 503")
        val first = bridge.sync(SyncTrigger.IMMEDIATE) as SyncOutcome.Completed
        assertTrue(first.retryNeeded)
        assertEquals(listOf(UploadStatus.RETRYING, UploadStatus.PENDING_UPLOAD), statuses())

        api.uploadResults += ApiResult.HttpError(422, "HTTP 422")
        val second = bridge.sync(SyncTrigger.IMMEDIATE) as SyncOutcome.Completed
        assertFalse(second.retryNeeded)
        assertEquals(1, second.failed)
        assertEquals(1, second.uploaded)
        assertEquals(listOf(UploadStatus.FAILED, UploadStatus.UPLOADED), statuses())

        // Immediate runs skip FAILED; reconciliation re-attempts it.
        val immediate = bridge.sync(SyncTrigger.IMMEDIATE) as SyncOutcome.Completed
        assertEquals(0, immediate.uploaded)
        val reconcile = bridge.sync(SyncTrigger.RECONCILE) as SyncOutcome.Completed
        assertEquals(1, reconcile.uploaded)
        assertEquals(listOf(UploadStatus.UPLOADED, UploadStatus.UPLOADED), statuses())
    }

    @Test
    fun `authentication failure stops the run without burning other messages`() = runTest {
        api.uploadResults += ApiResult.HttpError(401, "HTTP 401")
        val outcome = bridge.sync(SyncTrigger.IMMEDIATE) as SyncOutcome.Completed
        assertEquals(1, outcome.failed)
        assertEquals(listOf(UploadStatus.FAILED, UploadStatus.PENDING_UPLOAD), statuses())
        assertTrue(api.uploaded.isEmpty())
    }

    @Test
    fun `paused sync and missing backend leave the queue untouched`() = runTest {
        bridge.settings.setSyncPaused(true)
        assertEquals(SyncOutcome.Paused, bridge.sync(SyncTrigger.MANUAL))
        bridge.settings.setSyncPaused(false)
        bridge.settings.setBackendUrl("")
        assertEquals(SyncOutcome.NotConfigured, bridge.sync(SyncTrigger.MANUAL))
        assertEquals(listOf(UploadStatus.PENDING_UPLOAD, UploadStatus.PENDING_UPLOAD), statuses())
        assertTrue(api.uploaded.isEmpty())
    }

    @Test
    fun `upload request carries device id, fingerprint and client id for server-side dedupe`() = runTest {
        bridge.sync(SyncTrigger.IMMEDIATE)
        val request = api.uploaded.first()
        assertEquals(bridge.identity.deviceId(), request.deviceId)
        assertEquals(64, request.fingerprint.length)
        assertTrue(request.clientMessageId.isNotBlank())
        assertEquals("com.whatsapp", request.sourcePackage)
        assertEquals("OKB Monitoring", request.groupName)
    }
}

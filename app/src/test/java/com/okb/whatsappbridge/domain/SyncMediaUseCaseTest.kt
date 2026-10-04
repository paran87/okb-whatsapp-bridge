package com.okb.whatsappbridge.domain

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.okb.whatsappbridge.data.remote.api.ApiResult
import com.okb.whatsappbridge.data.remote.dto.MediaIntentResponse
import com.okb.whatsappbridge.domain.model.MediaUploadStatus
import com.okb.whatsappbridge.domain.usecase.SyncOutcome
import com.okb.whatsappbridge.domain.usecase.SyncTrigger
import com.okb.whatsappbridge.fakes.FakeBridgeApi
import com.okb.whatsappbridge.fakes.Snapshots
import com.okb.whatsappbridge.fakes.Snapshots.T0
import com.okb.whatsappbridge.fakes.TestBridge
import com.okb.whatsappbridge.media.MediaUriMetadata
import com.okb.whatsappbridge.whatsapp.SnapshotMessage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class SyncMediaUseCaseTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val api = FakeBridgeApi()
    private lateinit var bridge: TestBridge
    private val bytes = ByteArray(4096) { (it % 255).toByte() }

    @Before
    fun setUp() = runTest {
        bridge = TestBridge(ApplicationProvider.getApplicationContext(), api, tmp.newFolder("media"), clock = { T0 + 5000 })
        bridge.settings.setMonitoringEnabled(true)
        bridge.settings.setBackendUrl("https://okb.test")
        bridge.groups.addAuthorizedGroup("OKB Monitoring", T0)
    }

    @After
    fun tearDown() = bridge.db.close()

    private suspend fun capturePhoto(uri: String, caption: String = "📷 Flood") {
        bridge.content.bytesByUri[uri] = bytes
        bridge.content.metadataByUri[uri] = MediaUriMetadata("p.jpg", "image/jpeg", bytes.size.toLong())
        bridge.process(
            Snapshots.groupMessaging(messages = listOf(SnapshotMessage(caption, T0, "Ana", dataUri = uri, dataMimeType = "image/jpeg")), hasPicture = true),
        )
    }

    private suspend fun mediaStatus() =
        bridge.media.observeForMessage(bridge.messages.observeRecent(null, 1).first().first().id).first().first().uploadStatus

    @Test
    fun `media is uploaded via intent, PUT and complete`() = runTest {
        capturePhoto("content://x/1")
        val outcome = bridge.mediaSync(SyncTrigger.IMMEDIATE) as SyncOutcome.Completed
        assertEquals(1, outcome.uploaded)
        assertFalse(outcome.retryNeeded)
        assertEquals(1, api.intents.size)
        assertEquals(1, bridge.uploader.puts.size)
        assertEquals(1, api.completes.size)
        assertEquals(MediaUploadStatus.UPLOADED, mediaStatus())
        // The complete call carried the content hash and object key for server-side dedupe.
        assertEquals(64, api.completes.first().sha256.length)
        assertTrue(api.completes.first().objectKey.contains(api.completes.first().sha256))
        // ...and the owning message's fingerprint, so the backend can attach the photo to its report.
        val messageFingerprint = bridge.messages.observeRecent(null, 1).first().first().let { m ->
            bridge.db.messageDao().getById(m.id)!!.fingerprint
        }
        assertEquals(messageFingerprint, api.intents.first().messageFingerprint)
        assertEquals(messageFingerprint, api.completes.first().messageFingerprint)
        assertEquals(T0 + 5000, bridge.settings.current().lastMediaUploadAt)
    }

    @Test
    fun `backend duplicate verdict skips the byte transfer`() = runTest {
        capturePhoto("content://x/dup")
        api.intentResults += ApiResult.Success(
            MediaIntentResponse(status = "duplicate", objectKey = "whatsapp/k", remoteRef = "r2://b/k"), 200,
        )
        val outcome = bridge.mediaSync(SyncTrigger.IMMEDIATE) as SyncOutcome.Completed
        assertEquals(1, outcome.uploaded)
        assertTrue(bridge.uploader.puts.isEmpty())
        assertEquals(MediaUploadStatus.UPLOADED, mediaStatus())
    }

    @Test
    fun `content dedupe reuses an already-uploaded object without re-uploading`() = runTest {
        capturePhoto("content://x/a", caption = "📷 one")
        bridge.mediaSync(SyncTrigger.IMMEDIATE)
        api.intents.clear(); bridge.uploader.puts.clear()

        // A second, different message with identical bytes (same sha256).
        capturePhoto("content://x/b", caption = "📷 two")
        val outcome = bridge.mediaSync(SyncTrigger.IMMEDIATE) as SyncOutcome.Completed
        assertEquals(1, outcome.uploaded)
        assertTrue("no intent needed for duplicate content", api.intents.isEmpty())
        assertTrue("no bytes re-uploaded", bridge.uploader.puts.isEmpty())
        // The existing object is still linked to the second message (metadata only).
        assertEquals(2, api.completes.size)
        assertEquals(api.completes[0].objectKey, api.completes[1].objectKey)
        assertTrue(api.completes[0].messageFingerprint != api.completes[1].messageFingerprint)
    }

    @Test
    fun `offline PUT keeps media queued and retries`() = runTest {
        capturePhoto("content://x/off")
        bridge.uploader.results += ApiResult.NetworkError("SocketTimeout")
        val outcome = bridge.mediaSync(SyncTrigger.IMMEDIATE) as SyncOutcome.Completed
        assertTrue(outcome.retryNeeded)
        assertEquals(MediaUploadStatus.RETRYING, mediaStatus())

        // Connectivity returns: next run uploads.
        val retry = bridge.mediaSync(SyncTrigger.IMMEDIATE) as SyncOutcome.Completed
        assertEquals(1, retry.uploaded)
        assertEquals(MediaUploadStatus.UPLOADED, mediaStatus())
    }

    @Test
    fun `server error on complete is retried, 4xx on intent fails`() = runTest {
        capturePhoto("content://x/5xx")
        api.completeResults += ApiResult.HttpError(503, "HTTP 503")
        assertTrue((bridge.mediaSync(SyncTrigger.IMMEDIATE) as SyncOutcome.Completed).retryNeeded)
        assertEquals(MediaUploadStatus.RETRYING, mediaStatus())

        api.intentResults += ApiResult.HttpError(422, "HTTP 422")
        val out = bridge.mediaSync(SyncTrigger.RECONCILE) as SyncOutcome.Completed
        assertEquals(1, out.failed)
        assertEquals(MediaUploadStatus.FAILED, mediaStatus())
    }

    @Test
    fun `paused sync and missing backend leave media queued`() = runTest {
        capturePhoto("content://x/p")
        bridge.settings.setSyncPaused(true)
        assertEquals(SyncOutcome.Paused, bridge.mediaSync(SyncTrigger.MANUAL))
        bridge.settings.setSyncPaused(false)
        bridge.settings.setBackendUrl("")
        assertEquals(SyncOutcome.NotConfigured, bridge.mediaSync(SyncTrigger.MANUAL))
        assertEquals(MediaUploadStatus.PENDING, mediaStatus())
        assertTrue(bridge.uploader.puts.isEmpty())
    }
}

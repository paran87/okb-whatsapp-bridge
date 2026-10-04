package com.okb.whatsappbridge.worker

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.okb.whatsappbridge.data.remote.api.ApiResult
import com.okb.whatsappbridge.domain.usecase.SyncTrigger
import com.okb.whatsappbridge.fakes.FakeBridgeApi
import com.okb.whatsappbridge.fakes.Snapshots
import com.okb.whatsappbridge.fakes.Snapshots.T0
import com.okb.whatsappbridge.fakes.TestBridge
import com.okb.whatsappbridge.media.MediaUriMetadata
import com.okb.whatsappbridge.whatsapp.SnapshotMessage
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class MediaUploadWorkerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val api = FakeBridgeApi()
    private lateinit var bridge: TestBridge

    @Before
    fun setUp() = runTest {
        bridge = TestBridge(context, api, tmp.newFolder("media"))
        bridge.settings.setMonitoringEnabled(true)
        bridge.settings.setBackendUrl("https://okb.test")
        bridge.groups.addAuthorizedGroup("OKB Monitoring", T0)
        val uri = "content://x/w"
        bridge.content.bytesByUri[uri] = ByteArray(2048) { 3 }
        bridge.content.metadataByUri[uri] = MediaUriMetadata("p.jpg", "image/jpeg", 2048)
        bridge.process(
            Snapshots.groupMessaging(messages = listOf(SnapshotMessage("📷 x", T0, "Ana", dataUri = uri, dataMimeType = "image/jpeg")), hasPicture = true),
        )
    }

    @After
    fun tearDown() = bridge.db.close()

    private fun worker(): MediaUploadWorker =
        TestListenableWorkerBuilder<MediaUploadWorker>(context)
            .setInputData(workDataOf(MessageUploadWorker.KEY_TRIGGER to SyncTrigger.IMMEDIATE.name))
            .setWorkerFactory(BridgeWorkerFactory({ error("unused") }, { bridge.mediaSync }, { error("unused") }, { bridge.logger }))
            .build()

    @Test
    fun `worker uploads then succeeds`() = runTest {
        assertEquals(ListenableWorker.Result.success(), worker().doWork())
        assertEquals(1, bridge.uploader.puts.size)
        assertEquals(1, api.completes.size)
    }

    @Test
    fun `worker retries while offline`() = runTest {
        bridge.uploader.results += ApiResult.NetworkError("timeout")
        assertEquals(ListenableWorker.Result.retry(), worker().doWork())
    }

    @Test
    fun `worker never fails the chain on a permanent error`() = runTest {
        api.intentResults += ApiResult.HttpError(400, "HTTP 400")
        assertEquals(ListenableWorker.Result.success(), worker().doWork())
    }
}

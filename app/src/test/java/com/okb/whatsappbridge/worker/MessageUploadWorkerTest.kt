package com.okb.whatsappbridge.worker

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.okb.whatsappbridge.data.remote.api.ApiResult
import com.okb.whatsappbridge.domain.usecase.SyncOutcome
import com.okb.whatsappbridge.domain.usecase.SyncTrigger
import com.okb.whatsappbridge.fakes.FakeBridgeApi
import com.okb.whatsappbridge.fakes.Snapshots
import com.okb.whatsappbridge.fakes.TestBridge
import com.okb.whatsappbridge.worker.MessageUploadWorker.Companion.toWorkResult
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class MessageUploadWorkerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val api = FakeBridgeApi()
    private lateinit var bridge: TestBridge

    @Before
    fun setUp() = runTest {
        bridge = TestBridge(context, api)
        bridge.settings.setMonitoringEnabled(true)
        bridge.settings.setBackendUrl("https://okb.test")
        bridge.groups.addAuthorizedGroup("OKB Monitoring", 0)
        bridge.process(Snapshots.groupMessaging())
    }

    @After
    fun tearDown() = bridge.db.close()

    private fun worker(trigger: SyncTrigger = SyncTrigger.IMMEDIATE): MessageUploadWorker =
        TestListenableWorkerBuilder<MessageUploadWorker>(context)
            .setInputData(workDataOf(MessageUploadWorker.KEY_TRIGGER to trigger.name))
            .setWorkerFactory(BridgeWorkerFactory({ bridge.sync }, { error("unused media") }, { error("unused") }, { bridge.logger }))
            .build()

    @Test
    fun `worker returns retry while offline and success once uploaded`() = runTest {
        api.uploadResults += ApiResult.NetworkError("SocketTimeoutException")
        assertEquals(ListenableWorker.Result.retry(), worker().doWork())
        assertTrue(api.uploaded.isEmpty())

        assertEquals(ListenableWorker.Result.success(), worker().doWork())
        assertEquals(1, api.uploaded.size)
    }

    @Test
    fun `worker retries on 5xx`() = runTest {
        api.uploadResults += ApiResult.HttpError(500, "HTTP 500")
        assertEquals(ListenableWorker.Result.retry(), worker().doWork())
    }

    @Test
    fun `worker never fails the chain on permanent errors`() = runTest {
        api.uploadResults += ApiResult.HttpError(400, "HTTP 400")
        assertEquals(ListenableWorker.Result.success(), worker().doWork())
    }

    @Test
    fun `outcome mapping`() {
        assertEquals(ListenableWorker.Result.success(), SyncOutcome.Paused.toWorkResult())
        assertEquals(ListenableWorker.Result.success(), SyncOutcome.NotConfigured.toWorkResult())
        assertEquals(ListenableWorker.Result.retry(), SyncOutcome.Completed(0, 0, true, "x").toWorkResult())
        assertEquals(ListenableWorker.Result.success(), SyncOutcome.Completed(3, 0, false, null).toWorkResult())
    }
}

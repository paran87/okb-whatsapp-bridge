package com.okb.whatsappbridge.ui

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.okb.whatsappbridge.MainActivity
import com.okb.whatsappbridge.OkbBridgeApplication
import com.okb.whatsappbridge.data.remote.dto.ConsolidatedDelivery
import com.okb.whatsappbridge.domain.usecase.ConsolidatedReportCheckUseCase
import com.okb.whatsappbridge.ui.consolidated.ShareReportActivity
import com.okb.whatsappbridge.worker.ReconciliationWorker
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Starts the real application (OkbBridgeApplication + AppContainer + Room + WorkManager) the way the phone
 * does, so a crash on launch ("keeps stopping") fails here instead of on the device.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = OkbBridgeApplication::class)
class AppLaunchSmokeTest {

    private val app get() = ApplicationProvider.getApplicationContext<OkbBridgeApplication>()

    private val delivery = ConsolidatedDelivery(
        id = "5f0c6a2e-1b7d-4c1e-9a77-1d2f3e4a5b6c",
        kind = "test",
        fileName = "OKB_Consolidated_Flood_Report_2026-08-12_1200_TEST.pdf",
        caption = "TEST REPORT\n\n📄 OKB CONSOLIDATED FLOOD MONITORING REPORT",
        destinationGroup = "NCR Flood Monitoring",
        pdfPath = "api/v1/consolidated-reports/5f0c6a2e-1b7d-4c1e-9a77-1d2f3e4a5b6c/pdf",
    )

    @Test
    fun `main screen launches with the real application`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        assertTrue(!activity.isFinishing)
    }

    @Test
    fun `share screen without the PDF finishes instead of crashing`() {
        val activity = Robolectric.buildActivity(ShareReportActivity::class.java, ShareReportActivity.intent(app, delivery)).setup().get()
        assertTrue(activity.isFinishing)
    }

    @Test
    fun `share screen with the PDF opens a share intent carrying the PDF and caption`() {
        val file = ConsolidatedReportCheckUseCase.pdfFile(app.container.consolidatedReportDirectory, delivery)!!
        file.parentFile!!.mkdirs()
        file.writeText("%PDF-1.7")
        val activity = Robolectric.buildActivity(ShareReportActivity::class.java, ShareReportActivity.intent(app, delivery)).setup().get()
        val started = shadowOf(activity).nextStartedActivity
        assertNotNull(started)
        // WhatsApp is not installed under Robolectric, so the system share sheet wraps the share intent.
        val share = if (started.action == Intent.ACTION_CHOOSER) started.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!! else started
        assertEquals(Intent.ACTION_SEND, share.action)
        assertEquals("application/pdf", share.type)
        assertEquals(delivery.caption, share.getStringExtra(Intent.EXTRA_TEXT))
        assertNotNull(share.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java))
        assertTrue(activity.isFinishing)
    }

    @Test
    fun `15-minute worker runs health and consolidated checks without crashing`() = runBlocking {
        val worker = TestListenableWorkerBuilder<ReconciliationWorker>(app)
            .setWorkerFactory(app.workManagerConfiguration.workerFactory)
            .build()
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
    }
}

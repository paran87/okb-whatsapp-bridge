package com.okb.whatsappbridge.data

import com.okb.whatsappbridge.data.remote.api.ApiResult
import com.okb.whatsappbridge.data.remote.api.BackendConfig
import com.okb.whatsappbridge.data.remote.api.OkHttpBridgeApi
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class OkHttpConsolidatedApiTest {

    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: MockWebServer
    private val api = OkHttpBridgeApi()

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun config() = BackendConfig(server.url("/").toString(), "OKB-ANDROID-A82F19", "tok-123")

    @Test
    fun `run-due posts with the device token and parses deliveries`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"checkedAt":"2026-08-12T04:05:00Z","generated":{"id":"x"},"skipped":null,"deliveries":[{"id":"5f0c6a2e-1b7d","kind":"test","fileName":"a.pdf","caption":"c","destinationGroup":"OKB Command Center","pdfPath":"api/v1/consolidated-reports/5f0c6a2e-1b7d/pdf","reportCount":2}]}""",
        ))
        val result = api.consolidatedRunDue(config())
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/consolidated-reports/run-due", request.path)
        assertEquals("Bearer tok-123", request.getHeader("Authorization"))
        val deliveries = (result as ApiResult.Success).value.deliveries
        assertEquals(1, deliveries.size)
        assertTrue(deliveries[0].isTest)
        assertEquals(2, deliveries[0].reportCount)
    }

    @Test
    fun `pdf download writes the bytes and leaves no partial file on errors`() = runTest {
        val bytes = "%PDF-1.7 consolidated".toByteArray()
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "application/pdf").setBody(Buffer().write(bytes)))
        val target = File(tmp.root, "id/report.pdf")
        val ok = api.downloadConsolidatedPdf(config(), "api/v1/consolidated-reports/id/pdf", target)
        assertEquals(bytes.size.toLong(), (ok as ApiResult.Success).value)
        assertEquals("/api/v1/consolidated-reports/id/pdf", server.takeRequest().path)
        assertTrue(target.readBytes().contentEquals(bytes))

        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"consolidated report not found"}"""))
        val missing = File(tmp.root, "other/report.pdf")
        val err = api.downloadConsolidatedPdf(config(), "api/v1/consolidated-reports/other/pdf", missing)
        assertEquals(404, (err as ApiResult.HttpError).httpCode)
        assertFalse(missing.exists())
    }

    @Test
    fun `delivery acknowledgement sends the state`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"abc","whatsappStatus":"notified"}"""))
        val r = api.acknowledgeConsolidatedDelivery(config(), "abc", "notified")
        val request = server.takeRequest()
        assertEquals("/api/v1/consolidated-reports/abc/delivery", request.path)
        assertEquals("""{"state":"notified"}""", request.body.readUtf8())
        assertEquals("notified", (r as ApiResult.Success).value.whatsappStatus)
    }
}

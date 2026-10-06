package com.okb.whatsappbridge.data

import com.okb.whatsappbridge.data.remote.api.ApiResult
import com.okb.whatsappbridge.data.remote.api.BackendConfig
import com.okb.whatsappbridge.data.remote.api.OkHttpBridgeApi
import com.okb.whatsappbridge.data.remote.dto.ConsolidatedRunDueRequest
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
            """{"checkedAt":"2026-08-12T04:05:00Z","generated":{"id":"x"},"skipped":null,"deliveries":[{"id":"5f0c6a2e-1b7d","kind":"test","fileName":"a.pdf","caption":"c","destinationGroup":"OKB COMMAND CENTER","sourceGroup":"NMDEO FLOOD MONITORING","pdfPath":"api/v1/consolidated-reports/5f0c6a2e-1b7d/pdf","reportCount":2}]}""",
        ))
        val result = api.consolidatedRunDue(config(), ConsolidatedRunDueRequest("NMDEO FLOOD MONITORING", "OKB COMMAND CENTER"))
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals(
            """{"sourceGroupName":"NMDEO FLOOD MONITORING","destinationGroupName":"OKB COMMAND CENTER"}""",
            request.body.readUtf8(),
        )
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
        assertEquals("""{"state":"notified","error":null}""", request.body.readUtf8())
        assertEquals("notified", (r as ApiResult.Success).value.whatsappStatus)
    }

    @Test
    fun `run-due parses TEXT jobs (sent automatically) and the next cut-off`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"deliveries":[],"textDeliveries":[{"id":"9b2f6c1e-0000-4000-8000-000000000001","reportId":"r1","deliveryType":"TEXT","kind":"scheduled","status":"scheduled","destinationGroup":"OKB COMMAND CENTER","sourceGroup":"NMDEO FLOOD MONITORING","dedupeKey":"scheduled|2026-10-06T16:00:00.000Z|okb command center","parts":[{"text":"📋 OKB CONSOLIDATED\nRef: OKB-9B2F6C1E","ref":"OKB-9B2F6C1E"}],"attempts":0,"maxAttempts":10,"previousAttemptUncertain":false,"reportCount":3}],"nextCutoffAt":"2026-10-06T22:00:00.000Z","nextRetryAt":null}""",
        ))
        val response = (api.consolidatedRunDue(config(), ConsolidatedRunDueRequest()) as ApiResult.Success).value
        val job = response.textDeliveries.single()
        assertEquals("OKB COMMAND CENTER", job.destinationGroup)
        assertEquals("OKB-9B2F6C1E", job.parts.single().ref)
        assertEquals(3, job.reportCount)
        assertEquals("2026-10-06T22:00:00.000Z", response.nextCutoffAt)
    }

    @Test
    fun `TEXT claim and result use the device token, the delivery id and an idempotency key`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"claimed":true,"reason":null,"delivery":{"id":"d1","reportId":"r1","destinationGroup":"OKB COMMAND CENTER","dedupeKey":"k","parts":[],"attempts":2}}""",
        ))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"r1"}"""))
        val claim = (api.claimTextDelivery(config(), "d1") as ApiResult.Success).value
        assertTrue(claim.claimed)
        assertEquals(2, claim.delivery!!.attempts)
        val claimRequest = server.takeRequest()
        assertEquals("/api/v1/consolidated-reports/text-deliveries/d1/claim", claimRequest.path)
        assertEquals("Bearer tok-123", claimRequest.getHeader("Authorization"))
        assertEquals("OKB-ANDROID-A82F19", claimRequest.getHeader("X-OKB-Device-Id"))

        val result = com.okb.whatsappbridge.data.remote.dto.TextResultRequest(state = "failed", attempt = 2, error = "x".repeat(500), retryable = true)
        assertTrue(api.reportTextDeliveryResult(config(), "d1", result) is ApiResult.Success)
        val resultRequest = server.takeRequest()
        assertEquals("/api/v1/consolidated-reports/text-deliveries/d1/result", resultRequest.path)
        assertEquals("d1-2-failed", resultRequest.getHeader("Idempotency-Key"))
        val body = resultRequest.body.readUtf8()
        assertTrue(body, body.contains("\"state\":\"failed\"") && body.contains("\"attempt\":2"))
        assertFalse(body.contains("x".repeat(301))) // errors are capped
    }
}

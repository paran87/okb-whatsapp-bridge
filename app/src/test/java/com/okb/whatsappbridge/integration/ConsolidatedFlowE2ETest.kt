package com.okb.whatsappbridge.integration

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.okb.whatsappbridge.data.remote.api.OkHttpBridgeApi
import com.okb.whatsappbridge.automation.SendProgress
import com.okb.whatsappbridge.automation.TextSendRequest
import com.okb.whatsappbridge.automation.WhatsAppTextSender
import com.okb.whatsappbridge.data.repository.RoomConsolidatedDeliveryRepository
import com.okb.whatsappbridge.data.repository.RoomTextDeliveryRepository
import com.okb.whatsappbridge.domain.model.ConsolidatedDeliveryStatus
import com.okb.whatsappbridge.domain.usecase.BackendUseCases
import com.okb.whatsappbridge.domain.usecase.ConsolidatedReportCheckUseCase
import com.okb.whatsappbridge.domain.usecase.DeviceInfo
import com.okb.whatsappbridge.domain.usecase.AutomaticTextSender
import com.okb.whatsappbridge.domain.usecase.ProcessingOutcome
import com.okb.whatsappbridge.domain.usecase.TextDeliveryUseCase
import com.okb.whatsappbridge.domain.usecase.SyncTrigger
import com.okb.whatsappbridge.fakes.FakeWhatsApp
import com.okb.whatsappbridge.fakes.Snapshots
import com.okb.whatsappbridge.fakes.TestBridge
import com.okb.whatsappbridge.whatsapp.IgnoreReason
import com.okb.whatsappbridge.whatsapp.SnapshotMessage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * END-TO-END against a running backend (okb-bridge-cloud-backend/scripts/e2e-local-server.js):
 *
 *   SOURCE group notification → bridge capture (Room) → upload → backend report + AI extraction
 *   → scheduled cut-off → consolidated report with a TEXT and a PDF delivery
 *   → TEXT: claimed → sent automatically into the DESTINATION group (real WhatsAppTextSender driving a simulated
 *     WhatsApp UI) → backend "sent"; repeated checks never send it again
 *   → PDF: download + PDF verification → delivery queue → opened in WhatsApp → operator confirms → backend "sent"
 *
 * Skipped unless OKB_E2E_BACKEND_URL is set (CI has no backend):
 *   OKB_E2E_BACKEND_URL=http://127.0.0.1:8091 OKB_E2E_CONTROL_URL=http://127.0.0.1:8092 ./gradlew testDebugUnitTest --tests '*ConsolidatedFlowE2ETest'
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class ConsolidatedFlowE2ETest {

    private val backendUrl: String? = System.getenv("OKB_E2E_BACKEND_URL")
    private val controlUrl: String = System.getenv("OKB_E2E_CONTROL_URL") ?: "http://127.0.0.1:8092"
    private val http = OkHttpClient()
    private val jsonType = "application/json".toMediaType()

    private val source = "NMDEO FLOOD MONITORING"
    private val destination = "OKB COMMAND CENTER"
    private val fieldReport = """
        Deo: MM1DEO

        Date and time: August 12, 2026, 12:10 PM

        Location: Acacia Lane, Mandaluyong City

        Limit/landmark: Corner Shaw Boulevard

        Ht of flood: 0.20 m

        Time of occurence: 12:15 PM

        Time of receding:

        Remarks:
        Flooding was caused by simultaneous heavy rainfall within the site and garbages that obstructed the inlet, resulting in excessive runoff and restricting the waterflow in the drainage.
    """.trimIndent()

    private fun call(method: String, url: String, body: String? = null, admin: Boolean = false): String {
        val b = Request.Builder().url(url)
        if (admin) b.header("Authorization", "Bearer e2e-admin-token")
        when (method) {
            "PUT" -> b.put((body ?: "{}").toRequestBody(jsonType))
            "POST" -> b.post((body ?: "{}").toRequestBody(jsonType))
        }
        http.newCall(b.build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            check(r.isSuccessful) { "$method $url → HTTP ${r.code}: $text" }
            return text
        }
    }

    private fun control(path: String = "state"): JsonObject = Json.parseToJsonElement(call("GET", "$controlUrl/$path")).jsonObject

    /** The Command Center's view of the (single) consolidated report. */
    private fun historyRow(base: String): JsonObject =
        Json.parseToJsonElement(call("GET", "$base/api/v1/consolidated-reports", admin = true)).jsonObject["reports"]!!.jsonArray.single().jsonObject

    private fun JsonObject.delivery(type: String): String = this[if (type == "TEXT") "textDelivery" else "pdfDelivery"]!!.jsonObject["status"]!!.jsonPrimitive.content

    private fun notification(group: String, text: String) =
        Snapshots.groupMessaging(group = group, messages = listOf(SnapshotMessage(text, System.currentTimeMillis(), "Field Engineer")))

    @Test
    fun `source group report to automatic TEXT in the destination group and a manual PDF`() = runBlocking {
        assumeTrue("set OKB_E2E_BACKEND_URL to run against a backend", backendUrl != null)
        val base = backendUrl!!
        val context = ApplicationProvider.getApplicationContext<Application>()
        val api = OkHttpBridgeApi()
        val bridge = TestBridge(context, api)

        // Phone configuration: backend, token, the two separate groups.
        bridge.identity.setDeviceToken("e2e-device-token")
        bridge.settings.setBackendUrl(base)
        bridge.settings.setMonitoringEnabled(true)
        bridge.settings.setSourceGroupName(source)
        bridge.settings.setDestinationGroupName(destination)
        val backend = BackendUseCases(bridge.settings, bridge.identity, api, DeviceInfo("1.2.0", "Android 14", "Xiaomi", "2209116AG"), bridge.logger)
        assertTrue(backend.registerDevice().ok)
        val device = control()["devices"]!!.jsonArray.single().jsonObject
        assertEquals(source, device["sourceGroupName"]!!.jsonPrimitive.content)
        assertEquals(destination, device["destinationGroupName"]!!.jsonPrimitive.content)

        // Command Center: enable consolidated reports (destination set on the phone) and schedule one: monitoring
        // period from a minute ago to 15 minutes from now, sent at its end.
        call("PUT", "$base/api/v1/consolidated-reports/settings", """{"settings":{"enabled":true}}""", admin = true)
        val start = java.time.Instant.now().minusSeconds(60).truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
        val end = start.plusSeconds(16 * 60)
        call("POST", "$base/api/v1/consolidated-reports/schedules", """{"periodStart":"$start","periodEnd":"$end","sendAt":"$end"}""", admin = true)
        Thread.sleep(1_100) // message times are whole seconds: the report must come after the moment it was enabled

        // TEST 1-3: capture only from the source group.
        assertTrue(bridge.process(notification(source, fieldReport)) is ProcessingOutcome.Captured)
        assertEquals(ProcessingOutcome.Ignored(IgnoreReason.GROUP_NOT_AUTHORIZED), bridge.process(notification("Barkada Chat", "Kain tayo")))
        assertEquals(ProcessingOutcome.Ignored(IgnoreReason.DESTINATION_GROUP), bridge.process(notification(destination, "Please see attached report")))
        assertEquals(ProcessingOutcome.Ignored(IgnoreReason.SYSTEM_NOTIFICATION), bridge.process(Snapshots.plain("WhatsApp", "WhatsApp Web is currently active")))

        // Upload over real HTTP; the backend stores it and runs the AI extraction.
        bridge.sync(SyncTrigger.IMMEDIATE)
        val stored = control()["messages"]!!.jsonArray.map { it.jsonObject["groupName"]!!.jsonPrimitive.content }
        assertEquals(listOf(source), stored)
        var reportStatus = ""
        repeat(100) {
            reportStatus = control()["reports"]!!.jsonArray.firstOrNull()?.jsonObject?.get("status")?.jsonPrimitive?.content.orEmpty()
            if (reportStatus == "extracted" || reportStatus == "needs_review") return@repeat
            Thread.sleep(100)
        }
        assertTrue("report processed by the AI pipeline (was '$reportStatus')", reportStatus == "extracted" || reportStatus == "needs_review")

        // TEST 4: the sending time passes → the phone's periodic check makes the backend prepare the report.
        call("POST", "$controlUrl/offset?minutes=16")
        val dir = File(context.filesDir, "consolidated-e2e").apply { deleteRecursively() }
        val deliveries = RoomConsolidatedDeliveryRepository(bridge.db.consolidatedDeliveryDao())
        val notified = mutableListOf<String>()
        val whatsApp = FakeWhatsApp(listOf("Barkada Chat", source, destination))
        var virtualTime = 0L
        val sender = object : AutomaticTextSender {
            override fun unavailableReason(): String? = null
            override suspend fun send(request: TextSendRequest, progress: SendProgress) =
                WhatsAppTextSender(whatsApp, sleep = { virtualTime += it }, clock = { virtualTime }).send(whatsApp.pkg, request, progress)
        }
        val textDeliveries = RoomTextDeliveryRepository(bridge.db.textDeliveryDao())
        val check = ConsolidatedReportCheckUseCase(
            bridge.settings, bridge.identity, api, deliveries, dir, { d, _ -> notified += d.id; true }, bridge.logger,
            textDelivery = TextDeliveryUseCase(api, textDeliveries, sender, bridge.logger),
        )
        val first = check()
        assertEquals(null, first.error)
        assertEquals(1, first.textSent)
        assertEquals(1, first.newlyReady)

        // TEXT: in the destination group exactly once, nothing in the source group, SENT on the backend.
        val sentTexts = whatsApp.messagesIn(destination)
        assertEquals(1, sentTexts.size)
        assertTrue(sentTexts.single().startsWith("📋 OKB CONSOLIDATED FLOOD MONITORING REPORT"))
        assertTrue(sentTexts.single().contains("1) MM1DEO"))
        assertTrue(sentTexts.single().contains("Ref: OKB-"))
        assertTrue(whatsApp.messagesIn(source).isEmpty())
        File("build/e2e").apply { mkdirs() }.let { File(it, "consolidated-e2e.txt").writeText(sentTexts.single()) }
        assertEquals("sent", historyRow(base).delivery("TEXT"))
        assertEquals(com.okb.whatsappbridge.domain.model.TextDeliveryStatus.SENT, textDeliveries.observeRecent().first().single().status)

        // TEST 5: downloaded, verified PDF, ready to send, with both groups.
        val ready = deliveries.observeRecent().first().single()
        assertEquals(ConsolidatedDeliveryStatus.READY_FOR_WHATSAPP, ready.status)
        assertEquals(destination, ready.destinationGroup)
        assertEquals(source, ready.sourceGroup)
        val pdf = ConsolidatedReportCheckUseCase.pdfFile(dir, ready.id, ready.fileName)!!
        assertTrue(ConsolidatedReportCheckUseCase.isPdf(pdf))
        File("build/e2e").apply { mkdirs() }.let { pdf.copyTo(File(it, "consolidated-e2e.pdf"), overwrite = true) }
        val generated = control()["consolidated"]!!.jsonArray.single().jsonObject
        assertEquals(1, (generated["reportIds"] as JsonArray).size)
        // The PDF is independent of the TEXT: on the phone, waiting for the operator.
        assertEquals("notified", historyRow(base).delivery("PDF"))

        // TEST 10: duplicate syncs neither re-download nor re-notify, and never send the TEXT again.
        assertEquals(0, check().newlyReady)
        assertEquals(0, check().textSent)
        assertEquals(listOf(ready.id), notified)
        assertEquals(1, whatsApp.messagesIn(destination).size)
        assertEquals(1, whatsApp.sendPresses)

        // TEST 8: share screen opened then cancelled → not sent; then opened again and confirmed → sent.
        check.markOpened(ready.id)
        assertEquals("opened", historyRow(base).delivery("PDF"))
        check.markNotSent(ready.id)
        assertEquals(ConsolidatedDeliveryStatus.READY_FOR_WHATSAPP, deliveries.get(ready.id)!!.status)
        assertEquals("notified", historyRow(base).delivery("PDF"))
        check.markOpened(ready.id)
        check.confirmSent(ready.id)
        val final = historyRow(base)
        assertEquals("sent", final.delivery("PDF"))
        assertTrue(final["pdfDelivery"]!!.jsonObject["sentAt"]!!.jsonPrimitive.content.isNotBlank())
        assertEquals(ConsolidatedDeliveryStatus.SENT, deliveries.get(ready.id)!!.status)

        // Command Center history shows both outcomes and the phone's groups.
        val row = historyRow(base)
        assertEquals("sent", row.delivery("TEXT"))
        assertEquals(destination, row["textDelivery"]!!.jsonObject["destinationGroup"]!!.jsonPrimitive.content)
        assertEquals(destination, row["destinationGroup"]!!.jsonPrimitive.content)
        assertEquals(source, row["sourceGroup"]!!.jsonPrimitive.content)
    }
}

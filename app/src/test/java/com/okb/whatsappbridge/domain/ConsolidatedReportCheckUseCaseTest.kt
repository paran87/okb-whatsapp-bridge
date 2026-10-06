package com.okb.whatsappbridge.domain

import com.okb.whatsappbridge.data.remote.api.ApiResult
import com.okb.whatsappbridge.data.remote.api.BackendConfig
import com.okb.whatsappbridge.data.remote.api.BridgeApi
import com.okb.whatsappbridge.data.remote.dto.ConsolidatedDelivery
import com.okb.whatsappbridge.data.remote.dto.ConsolidatedDeliveryAckResponse
import com.okb.whatsappbridge.data.remote.dto.ConsolidatedRunDueRequest
import com.okb.whatsappbridge.data.remote.dto.ConsolidatedRunDueResponse
import com.okb.whatsappbridge.data.repository.SecureDeviceIdentityRepository
import com.okb.whatsappbridge.domain.model.BridgeSettings
import com.okb.whatsappbridge.domain.model.ConsolidatedDeliveryStatus
import com.okb.whatsappbridge.automation.SendOutcome
import com.okb.whatsappbridge.automation.SendProgress
import com.okb.whatsappbridge.automation.TextSendRequest
import com.okb.whatsappbridge.data.remote.dto.TextClaimResponse
import com.okb.whatsappbridge.data.remote.dto.TextDeliveryJob
import com.okb.whatsappbridge.data.remote.dto.TextMessagePart
import com.okb.whatsappbridge.data.remote.dto.TextResultRequest
import com.okb.whatsappbridge.data.remote.dto.TextResultResponse
import com.okb.whatsappbridge.domain.usecase.AutomaticTextSender
import com.okb.whatsappbridge.domain.usecase.ConsolidatedReportCheckUseCase
import com.okb.whatsappbridge.domain.usecase.ReportWakeScheduler
import com.okb.whatsappbridge.domain.usecase.TextDeliveryUseCase
import com.okb.whatsappbridge.fakes.FakeTextDeliveryRepository
import com.okb.whatsappbridge.fakes.FakeBridgeApi
import com.okb.whatsappbridge.fakes.FakeConsolidatedDeliveryRepository
import com.okb.whatsappbridge.fakes.FakeSettingsRepository
import com.okb.whatsappbridge.fakes.RecordingLogger
import com.okb.whatsappbridge.service.AndroidConsolidatedReportNotifier
import com.okb.whatsappbridge.util.security.InMemorySecretStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ConsolidatedReportCheckUseCaseTest {

    @get:Rule val tmp = TemporaryFolder()

    private val validPdf = "%PDF-1.7\n1 0 obj << >> endobj\ntrailer << >>\n%%EOF\n"

    /** Backend stand-in: a list of pending deliveries plus switchable failures. */
    private inner class Api(var deliveries: List<ConsolidatedDelivery>) : BridgeApi by FakeBridgeApi() {
        var online = true
        var downloadBody: String = validPdf
        var downloadHttpError: Int? = null
        var ackOk = true
        var textJobs: List<TextDeliveryJob> = emptyList()
        var nextCutoffAt: String? = null
        var nextRetryAt: String? = null
        val order = mutableListOf<String>()
        val requests = mutableListOf<ConsolidatedRunDueRequest>()
        val downloads = mutableListOf<String>()
        val acks = mutableListOf<Pair<String, String>>()

        override suspend fun consolidatedRunDue(config: BackendConfig, request: ConsolidatedRunDueRequest): ApiResult<ConsolidatedRunDueResponse> {
            if (!online) return ApiResult.NetworkError("UnknownHostException")
            requests += request
            return ApiResult.Success(
                ConsolidatedRunDueResponse(deliveries = deliveries, textDeliveries = textJobs, nextCutoffAt = nextCutoffAt, nextRetryAt = nextRetryAt),
                200,
            )
        }

        override suspend fun downloadConsolidatedPdf(config: BackendConfig, pdfPath: String, target: File): ApiResult<Long> {
            if (!online) return ApiResult.NetworkError("SocketTimeoutException")
            downloads += pdfPath
            order += "pdf"
            downloadHttpError?.let { return ApiResult.HttpError(it, "HTTP $it") }
            target.parentFile?.mkdirs()
            target.writeText(downloadBody)
            return ApiResult.Success(target.length(), 200)
        }

        override suspend fun claimTextDelivery(config: BackendConfig, id: String): ApiResult<TextClaimResponse> {
            order += "text"
            return ApiResult.Success(TextClaimResponse(claimed = true, delivery = textJobs.first { it.id == id }.copy(attempts = 1)), 200)
        }

        override suspend fun reportTextDeliveryResult(config: BackendConfig, id: String, result: TextResultRequest): ApiResult<TextResultResponse> =
            ApiResult.Success(TextResultResponse(id), 200)

        override suspend fun acknowledgeConsolidatedDelivery(config: BackendConfig, id: String, state: String, error: String?): ApiResult<ConsolidatedDeliveryAckResponse> {
            if (!online || !ackOk) return ApiResult.NetworkError("offline")
            acks += id to state
            return ApiResult.Success(ConsolidatedDeliveryAckResponse(id, state), 200)
        }
    }

    private val remote = ConsolidatedDelivery(
        id = "5f0c6a2e-1b7d-4c1e-9a77-1d2f3e4a5b6c",
        kind = "scheduled",
        fileName = "OKB_Consolidated_Flood_Report_2026-10-06_1800.pdf",
        caption = "📄 OKB CONSOLIDATED FLOOD MONITORING REPORT",
        destinationGroup = "OKB COMMAND CENTER",
        sourceGroup = "NMDEO FLOOD MONITORING",
        pdfPath = "api/v1/consolidated-reports/5f0c6a2e-1b7d-4c1e-9a77-1d2f3e4a5b6c/pdf",
    )

    private val repo = FakeConsolidatedDeliveryRepository()
    private val notified = mutableListOf<String>()
    private val settings = FakeSettingsRepository(
        BridgeSettings(backendUrl = "https://okb.test", sourceGroupName = "NMDEO FLOOD MONITORING", destinationGroupName = "OKB COMMAND CENTER"),
    )

    private val wakes = mutableListOf<String>()
    private val wakeScheduler = object : ReportWakeScheduler {
        override fun scheduleNext(nextCutoffAtMillis: Long?, nextRetryAtMillis: Long?) { wakes += "next $nextCutoffAtMillis $nextRetryAtMillis" }
        override fun scheduleRetry() { wakes += "retry" }
    }
    private val textSender = object : AutomaticTextSender {
        val sent = mutableListOf<String>()
        override fun unavailableReason(): String? = null
        override suspend fun send(request: TextSendRequest, progress: SendProgress): SendOutcome {
            sent += request.destinationGroup
            return SendOutcome.Sent("visible")
        }
    }

    private fun useCase(api: BridgeApi, canNotify: Boolean = true) = ConsolidatedReportCheckUseCase(
        settings, SecureDeviceIdentityRepository(InMemorySecretStore()), api, repo, tmp.root,
        { d, _ -> if (canNotify) notified += d.id; canNotify }, RecordingLogger(),
        textDelivery = TextDeliveryUseCase(api, FakeTextDeliveryRepository(), textSender, RecordingLogger()),
        wakeScheduler = wakeScheduler,
    )

    private val textJob = TextDeliveryJob(
        id = "9b2f6c1e-0000-4000-8000-000000000001", reportId = "5f0c6a2e-1b7d-4c1e-9a77-1d2f3e4a5b6c",
        destinationGroup = "OKB COMMAND CENTER", dedupeKey = "scheduled|2026-10-06T16:00:00.000Z|okb command center",
        parts = listOf(TextMessagePart("📋 OKB CONSOLIDATED FLOOD MONITORING REPORT\nRef: OKB-9B2F6C1E", "OKB-9B2F6C1E")),
    )

    @Test
    fun `the TEXT report is sent automatically before the PDF is downloaded, and the next cut-off alarm is set`() = runTest {
        val api = Api(listOf(remote)).apply {
            textJobs = listOf(textJob)
            nextCutoffAt = "2026-10-06T22:00:00Z"
        }
        val result = useCase(api)()
        assertEquals(1, result.textSent)
        assertEquals(1, result.newlyReady)
        assertEquals(listOf("text", "pdf"), api.order)
        assertEquals(listOf("OKB COMMAND CENTER"), textSender.sent)
        // The PDF is still manual: offered to the operator, not sent.
        assertEquals(ConsolidatedDeliveryStatus.READY_FOR_WHATSAPP, status())
        assertEquals(listOf("next ${java.time.Instant.parse("2026-10-06T22:00:00Z").toEpochMilli()} null"), wakes)
    }

    @Test
    fun `backend unreachable at the cut-off - a quick retry is scheduled`() = runTest {
        val api = Api(listOf(remote)).apply { online = false }
        val result = useCase(api)()
        assertEquals("Backend unreachable", result.error)
        assertEquals(listOf("retry"), wakes)
    }

    private suspend fun status() = repo.get(remote.id)?.status

    @Test
    fun `TEST 5 - a pending report is downloaded, verified as a PDF and offered once`() = runTest {
        val api = Api(listOf(remote))
        val result = useCase(api)()
        assertEquals(1, result.newlyReady)
        assertEquals(ConsolidatedDeliveryStatus.READY_FOR_WHATSAPP, status())
        assertEquals(listOf(remote.pdfPath), api.downloads)
        assertEquals(listOf(remote.id), notified)
        assertEquals(listOf(remote.id to "notified"), api.acks)
        assertTrue(ConsolidatedReportCheckUseCase.isPdf(ConsolidatedReportCheckUseCase.pdfFile(tmp.root, remote.id, remote.fileName)!!))
        // Both groups travel with the check so the backend knows this phone's configuration.
        assertEquals(ConsolidatedRunDueRequest("NMDEO FLOOD MONITORING", "OKB COMMAND CENTER"), api.requests.single())
    }

    @Test
    fun `TEST 10 - a duplicate sync never downloads or notifies the same report twice`() = runTest {
        val api = Api(listOf(remote))
        val uc = useCase(api)
        uc()
        uc() // the backend still lists it (e.g. its "notified" acknowledgement was lost)
        uc()
        assertEquals(1, api.downloads.size)
        assertEquals(1, notified.size)
        assertEquals(1, repo.rows.size)
    }

    @Test
    fun `a download that is not a PDF is rejected and retried, then fails after five attempts`() = runTest {
        val api = Api(listOf(remote)).apply { downloadBody = "<html>Login required</html>" }
        val uc = useCase(api)
        uc()
        assertEquals(ConsolidatedDeliveryStatus.READY_TO_SEND, status())
        assertEquals("Downloaded file is not a PDF", repo.get(remote.id)!!.errorMessage)
        repeat(4) { uc() }
        assertEquals(ConsolidatedDeliveryStatus.FAILED, status())
        assertEquals(5, api.downloads.size)
        assertTrue(api.acks.contains(remote.id to "failed"))
        assertTrue(notified.isEmpty())
        assertFalse(ConsolidatedReportCheckUseCase.pdfFile(tmp.root, remote.id, remote.fileName)!!.exists())
    }

    @Test
    fun `an expired or deleted report (404) fails immediately`() = runTest {
        val api = Api(listOf(remote)).apply { downloadHttpError = 404 }
        useCase(api)()
        assertEquals(ConsolidatedDeliveryStatus.FAILED, status())
        assertEquals(listOf(remote.id to "failed"), api.acks)
    }

    @Test
    fun `TEST 9 - offline keeps the report pending and the acknowledgement is delivered later`() = runTest {
        val api = Api(listOf(remote)).apply { online = false }
        val uc = useCase(api)
        assertEquals("Backend unreachable", uc().error)
        assertNull(repo.get(remote.id))

        // Online for the download, but the acknowledgement is lost.
        api.online = true
        api.ackOk = false
        uc()
        assertEquals(ConsolidatedDeliveryStatus.READY_FOR_WHATSAPP, status())
        assertEquals("notified", repo.get(remote.id)!!.pendingAck)

        // Next check: the acknowledgement is retried first; nothing is downloaded again.
        api.ackOk = true
        api.deliveries = emptyList()
        uc()
        assertEquals(listOf(remote.id to "notified"), api.acks)
        assertNull(repo.get(remote.id)!!.pendingAck)
        assertEquals(1, api.downloads.size)
    }

    @Test
    fun `TEST 8 - opening WhatsApp is not sending, and a cancelled share stays ready to send`() = runTest {
        val api = Api(listOf(remote))
        val uc = useCase(api)
        uc()
        uc.markOpened(remote.id)
        assertEquals(ConsolidatedDeliveryStatus.OPENED_IN_WHATSAPP, status())
        uc.markNotSent(remote.id)
        assertEquals(ConsolidatedDeliveryStatus.READY_FOR_WHATSAPP, status())
        assertNull(repo.get(remote.id)!!.sentAt)
        assertEquals(listOf("notified", "opened", "not_sent"), api.acks.map { it.second })
        assertFalse(api.acks.any { it.second == "sent" })
    }

    @Test
    fun `only the operator's confirmation marks a report sent`() = runTest {
        val api = Api(listOf(remote))
        val uc = useCase(api)
        uc()
        uc.markOpened(remote.id)
        uc.confirmSent(remote.id)
        val d = repo.get(remote.id)!!
        assertEquals(ConsolidatedDeliveryStatus.SENT, d.status)
        assertTrue(d.sentAt != null)
        assertEquals("sent", api.acks.last().second)
    }

    @Test
    fun `a Resend from the Command Center offers the report again without downloading it again`() = runTest {
        val api = Api(listOf(remote))
        val uc = useCase(api)
        uc()
        uc.markOpened(remote.id)
        uc.confirmSent(remote.id)
        uc() // backend lists it as pending again
        assertEquals(ConsolidatedDeliveryStatus.READY_FOR_WHATSAPP, status())
        assertEquals(1, api.downloads.size)
        assertEquals(2, notified.size)
    }

    @Test
    fun `send re-downloads a PDF that is no longer on the phone`() = runTest {
        val api = Api(listOf(remote))
        val uc = useCase(api)
        uc()
        ConsolidatedReportCheckUseCase.pdfFile(tmp.root, remote.id, remote.fileName)!!.delete()
        val (target, problem) = uc.prepareShare(remote.id)
        assertNull(problem)
        assertTrue(target!!.file.exists())
        assertEquals(2, api.downloads.size)
    }

    @Test
    fun `notifications blocked - the report is still ready in the app`() = runTest {
        useCase(Api(listOf(remote)), canNotify = false)()
        assertEquals(ConsolidatedDeliveryStatus.READY_FOR_WHATSAPP, status())
    }

    @Test
    fun `unsafe file names are never written`() = runTest {
        assertNull(ConsolidatedReportCheckUseCase.pdfFile(tmp.root, remote.id, "../../evil.pdf"))
        assertNull(ConsolidatedReportCheckUseCase.pdfFile(tmp.root, "../x", remote.fileName))
        val api = Api(listOf(remote.copy(fileName = "../evil.pdf")))
        useCase(api)()
        assertEquals(ConsolidatedDeliveryStatus.FAILED, status())
        assertTrue(api.downloads.isEmpty())
    }

    @Test
    fun `share instruction names the configured group and never claims automatic selection`() {
        assertEquals(
            "WhatsApp share screen will open. Select “OKB COMMAND CENTER” and press Send.",
            AndroidConsolidatedReportNotifier.shareInstruction("OKB COMMAND CENTER"),
        )
        assertTrue(AndroidConsolidatedReportNotifier.shareInstruction(" ").contains("No destination group is configured"))
    }
}

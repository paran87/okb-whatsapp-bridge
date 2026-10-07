package com.okb.whatsappbridge.domain

import com.okb.whatsappbridge.automation.SendOutcome
import com.okb.whatsappbridge.automation.SendProgress
import com.okb.whatsappbridge.automation.TextSendRequest
import com.okb.whatsappbridge.data.remote.api.ApiResult
import com.okb.whatsappbridge.data.remote.api.BackendConfig
import com.okb.whatsappbridge.data.remote.api.BridgeApi
import com.okb.whatsappbridge.data.remote.dto.TextClaimResponse
import com.okb.whatsappbridge.data.remote.dto.TextDeliveryJob
import com.okb.whatsappbridge.data.remote.dto.TextMessagePart
import com.okb.whatsappbridge.data.remote.dto.TextResultRequest
import com.okb.whatsappbridge.data.remote.dto.TextResultResponse
import com.okb.whatsappbridge.domain.model.TextDeliveryStatus
import com.okb.whatsappbridge.domain.usecase.AutomaticTextSender
import com.okb.whatsappbridge.domain.usecase.TextDeliveryUseCase
import com.okb.whatsappbridge.fakes.FakeBridgeApi
import com.okb.whatsappbridge.fakes.FakeTextDeliveryRepository
import com.okb.whatsappbridge.fakes.RecordingLogger
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextDeliveryUseCaseTest {

    private val config = BackendConfig("https://okb.test", "OKB-ANDROID-A82F19", "device-token")
    private val job = TextDeliveryJob(
        id = "9b2f6c1e-0000-4000-8000-000000000001",
        reportId = "5f0c6a2e-1b7d-4c1e-9a77-1d2f3e4a5b6c",
        kind = "scheduled",
        destinationGroup = "OKB COMMAND CENTER",
        sourceGroup = "NMDEO FLOOD MONITORING",
        dedupeKey = "scheduled|2026-10-06T16:00:00.000Z|okb command center",
        parts = listOf(TextMessagePart("📋 OKB CONSOLIDATED FLOOD MONITORING REPORT\nRef: OKB-9B2F6C1E", "OKB-9B2F6C1E")),
        periodStart = "2026-10-06T10:00:00.000Z",
        periodEnd = "2026-10-06T16:00:00.000Z",
        reportCount = 2,
    )

    /** Backend stand-in for the claim/result endpoints (attempt counter like the real compare-and-set). */
    private inner class Api : BridgeApi by FakeBridgeApi() {
        var online = true
        private val statuses = mutableMapOf<String, String>()
        private val attempts = mutableMapOf<String, Int>()
        /** Status of the main [job] (other ids start "scheduled"). */
        var status: String
            get() = statuses[job.id] ?: "scheduled"
            set(v) { statuses[job.id] = v }
        val results = mutableListOf<TextResultRequest>()
        var claims = 0

        override suspend fun claimTextDelivery(config: BackendConfig, id: String): ApiResult<TextClaimResponse> {
            if (!online) return ApiResult.NetworkError("UnknownHostException")
            claims++
            when (statuses[id] ?: "scheduled") {
                "sent" -> return ApiResult.Success(TextClaimResponse(false, "already_sent", job), 200)
                "sending" -> return ApiResult.Success(TextClaimResponse(false, "in_progress", job), 200)
            }
            val n = (attempts[id] ?: 0) + 1
            attempts[id] = n
            statuses[id] = "sending"
            return ApiResult.Success(TextClaimResponse(true, null, job.copy(id = id, attempts = n)), 200)
        }

        /** Backend error message of the main [job] (e.g. "Cancelled from the Command Center"). */
        var errorMessage: String? = null
        /** The main [job] was deleted on the backend (404). */
        var gone = false
        var statusReads = 0

        override suspend fun getTextDelivery(config: BackendConfig, id: String): ApiResult<TextDeliveryJob> {
            if (!online) return ApiResult.NetworkError("UnknownHostException")
            statusReads++
            if (gone) return ApiResult.HttpError(404, "text delivery not found")
            return ApiResult.Success(
                job.copy(
                    id = id, status = statuses[id] ?: "scheduled", errorMessage = errorMessage,
                    cancelled = errorMessage == "Cancelled from the Command Center",
                ),
                200,
            )
        }

        override suspend fun reportTextDeliveryResult(config: BackendConfig, id: String, result: TextResultRequest): ApiResult<TextResultResponse> {
            if (!online) return ApiResult.NetworkError("SocketTimeoutException")
            results += result
            statuses[id] = if (result.state == "sent") "sent" else "scheduled"
            return ApiResult.Success(TextResultResponse(id), 200)
        }
    }

    private inner class Sender(var unavailable: String? = null, var outcome: (suspend (TextSendRequest, SendProgress) -> SendOutcome)? = null) : AutomaticTextSender {
        val requests = mutableListOf<TextSendRequest>()
        var concurrent = 0
        var maxConcurrent = 0
        override fun unavailableReason() = unavailable
        override suspend fun send(request: TextSendRequest, progress: SendProgress): SendOutcome {
            requests += request
            concurrent++
            maxConcurrent = maxOf(maxConcurrent, concurrent)
            try {
                outcome?.let { return it(request, progress) }
                delay(10)
                progress.beforePressSend(request.parts[0].ref)
                progress.partConfirmed(request.parts[0].ref, "Ref ${request.parts[0].ref} visible in \"${request.destinationGroup}\" after Send; WhatsApp status: Delivered")
                return SendOutcome.Sent("Ref ${request.parts[0].ref} visible; WhatsApp status: Delivered")
            } finally {
                concurrent--
            }
        }
    }

    private val repo = FakeTextDeliveryRepository()
    private val logger = RecordingLogger()
    private var clock = 1_791_074_536_000L

    private fun useCase(api: Api, sender: Sender) = TextDeliveryUseCase(api, repo, sender, logger, clock = { clock })

    @Test
    fun `a TEXT job is sent automatically to its destination group and reported SENT`() = runTest {
        val api = Api()
        val sender = Sender()
        val result = useCase(api, sender).process(config, listOf(job), sourceGroupName = "NMDEO FLOOD MONITORING")

        assertEquals(1, result.sent)
        assertEquals("OKB COMMAND CENTER", sender.requests.single().destinationGroup)
        assertEquals("NMDEO FLOOD MONITORING", sender.requests.single().sourceGroup)
        val local = repo.get(job.id)!!
        assertEquals(TextDeliveryStatus.SENT, local.status)
        assertEquals(setOf("OKB-9B2F6C1E"), local.pressedRefs)
        assertEquals(setOf("OKB-9B2F6C1E"), local.sentRefs)
        assertNull(local.pendingResult)
        val reported = api.results.single()
        assertEquals("sent", reported.state)
        assertEquals(1, reported.attempt)
        assertTrue(reported.verification!!.contains("Delivered"))
        assertTrue(reported.sentAt!!.matches(Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z")))
    }

    @Test
    fun `offered again after SENT (e g a lost acknowledgement) - never sent twice, the backend is told again`() = runTest {
        val api = Api()
        val sender = Sender()
        val uc = useCase(api, sender)
        uc.process(config, listOf(job), null)
        api.status = "sending" // the backend did not get the result
        uc.process(config, listOf(job), null)
        assertEquals(1, sender.requests.size)
        assertEquals(1, api.claims)
        assertEquals(listOf("sent", "sent"), api.results.map { it.state })
    }

    @Test
    fun `offline after sending - the result is kept and reported on the next check`() = runTest {
        val api = Api()
        val sender = Sender(outcome = { r, p ->
            p.beforePressSend(r.parts[0].ref)
            api.online = false // the connection drops right after the message went out
            p.partConfirmed(r.parts[0].ref, "visible")
            SendOutcome.Sent("visible")
        })
        val uc = useCase(api, sender)
        uc.process(config, listOf(job), null)
        assertEquals(TextDeliveryStatus.SENT, repo.get(job.id)!!.status)
        assertEquals("sent", repo.get(job.id)!!.pendingResult)
        assertTrue(api.results.isEmpty())

        api.online = true
        uc.flushPendingResults(config)
        assertEquals("sent", api.results.single().state)
        assertNull(repo.get(job.id)!!.pendingResult)
    }

    @Test
    fun `backend says already sent - nothing is sent and the local row becomes SENT`() = runTest {
        val api = Api().apply { status = "sent" }
        val sender = Sender()
        useCase(api, sender).process(config, listOf(job), null)
        assertTrue(sender.requests.isEmpty())
        assertEquals(TextDeliveryStatus.SENT, repo.get(job.id)!!.status)
    }

    @Test
    fun `automatic sending unavailable - the attempt fails with the reason and is retried by the backend`() = runTest {
        val api = Api()
        val sender = Sender(unavailable = "Automatic sending is off: enable OKB WhatsApp Bridge in Android Settings → Accessibility")
        val result = useCase(api, sender).process(config, listOf(job), null)
        assertEquals(1, result.failed)
        assertTrue(sender.requests.isEmpty())
        val reported = api.results.single()
        assertEquals("failed", reported.state)
        assertEquals(1, reported.attempt)
        assertEquals(true, reported.retryable)
        assertTrue(reported.error!!.contains("Accessibility"))
        assertEquals(TextDeliveryStatus.SCHEDULED, repo.get(job.id)!!.status)
    }

    @Test
    fun `a crash after pressing Send is recorded before it happens, so the next attempt checks the chat first`() = runTest {
        val api = Api()
        val sender = Sender(outcome = { r, p ->
            p.beforePressSend(r.parts[0].ref)
            throw IllegalStateException("WhatsApp window vanished")
        })
        useCase(api, sender).process(config, listOf(job), null)
        val local = repo.get(job.id)!!
        assertEquals(setOf("OKB-9B2F6C1E"), local.pressedRefs)
        assertEquals(TextDeliveryStatus.SCHEDULED, local.status)
        assertEquals("failed", api.results.single().state)

        // Next attempt: the sender gets the job again and (in the real flow) finds the reference in the chat.
        val second = Sender()
        api.status = "scheduled"
        useCase(api, second).process(config, listOf(job), null)
        assertEquals(1, second.requests.size)
        assertEquals(TextDeliveryStatus.SENT, repo.get(job.id)!!.status)
        assertEquals(2, api.results.last().attempt)
    }

    @Test
    fun `a second delivery for the same period and group is never sent`() = runTest {
        val api = Api()
        val sender = Sender()
        val uc = useCase(api, sender)
        uc.process(config, listOf(job), null)
        uc.process(config, listOf(job.copy(id = "9b2f6c1e-0000-4000-8000-000000000002")), null)
        assertEquals(1, sender.requests.size)
        assertEquals(1, repo.rows.size)
    }

    @Test
    fun `non-retryable failure is final on the phone too`() = runTest {
        val api = Api()
        val sender = Sender(outcome = { _, _ -> SendOutcome.Failed("The destination group is the source group; refusing to send", retryable = false) })
        useCase(api, sender).process(config, listOf(job), null)
        assertEquals(TextDeliveryStatus.FAILED, repo.get(job.id)!!.status)
        assertEquals(false, api.results.single().retryable)
    }

    @Test
    fun `overlapping checks never drive WhatsApp at the same time`() = runTest {
        val api = Api()
        val sender = Sender()
        val uc = useCase(api, sender)
        val other = job.copy(id = "9b2f6c1e-0000-4000-8000-000000000003", dedupeKey = "test|x", kind = "test")
        val a = async { uc.process(config, listOf(job), null) }
        val b = async { uc.process(config, listOf(other), null) }
        a.await(); b.await()
        assertEquals(2, sender.requests.size)
        assertEquals(1, sender.maxConcurrent)
    }

    @Test
    fun `backend unreachable when claiming - nothing is sent, tried again on the next check`() = runTest {
        val api = Api().apply { online = false }
        val sender = Sender()
        useCase(api, sender).process(config, listOf(job), null)
        assertTrue(sender.requests.isEmpty())
        assertEquals(TextDeliveryStatus.SCHEDULED, repo.get(job.id)!!.status)
    }

    @Test
    fun `a press that did not register is forgotten, an unconfirmed press blocks re-sending until Retry from the Command Center`() = runTest {
        val api = Api()
        val notRegistered = Sender(outcome = { r, p ->
            p.beforePressSend(r.parts[0].ref)
            p.sendNotRegistered(r.parts[0].ref)
            SendOutcome.Failed("Send was pressed but WhatsApp did not take the message", retryable = true)
        })
        useCase(api, notRegistered).process(config, listOf(job), null)
        assertTrue(repo.get(job.id)!!.pressedRefs.isEmpty())

        // Taken but unconfirmable: the press is remembered and passed on, so the sender will not press again.
        api.status = "scheduled"
        val unconfirmed = Sender(outcome = { r, p ->
            p.beforePressSend(r.parts[0].ref)
            SendOutcome.Failed("unconfirmed", retryable = false)
        })
        useCase(api, unconfirmed).process(config, listOf(job.copy(attempts = 1)), null)
        assertEquals(setOf("OKB-9B2F6C1E"), repo.get(job.id)!!.pressedRefs)
        assertEquals(TextDeliveryStatus.FAILED, repo.get(job.id)!!.status)

        // Retry from the Command Center resets the backend attempt count to 0: earlier presses are cleared.
        api.status = "scheduled"
        val retried = Sender()
        useCase(api, retried).process(config, listOf(job.copy(attempts = 0)), null)
        assertTrue(retried.requests.single().pressedRefs.isEmpty())
        assertEquals(TextDeliveryStatus.SENT, repo.get(job.id)!!.status)
    }

    /** A failed attempt leaves the row SCHEDULED (retried later), like the stuck card on the Dashboard. */
    private suspend fun retryingRow(api: Api): TextDeliveryUseCase {
        val uc = useCase(api, Sender(unavailable = "Automatic sending is off"))
        uc.process(config, listOf(job), null)
        assertEquals(TextDeliveryStatus.SCHEDULED, repo.get(job.id)!!.status)
        return uc
    }

    @Test
    fun `refresh - a report cancelled in the Command Center shows as Cancelled and can then be removed`() = runTest {
        val api = Api()
        val uc = retryingRow(api)
        assertEquals(false, uc.remove(job.id)) // still scheduled: keeps its row
        api.status = "failed"
        api.errorMessage = "Cancelled from the Command Center"

        uc.syncWithBackend(config, offeredIds = emptySet())

        val local = repo.get(job.id)!!
        assertEquals(TextDeliveryStatus.FAILED, local.status)
        assertTrue(local.isCancelled)
        assertTrue(uc.remove(job.id))
        assertNull(repo.get(job.id))
    }

    @Test
    fun `refresh - a report offered in this check is not read again, and offline leaves the row as it is`() = runTest {
        val api = Api()
        val uc = retryingRow(api)
        uc.syncWithBackend(config, offeredIds = setOf(job.id))
        assertEquals(0, api.statusReads)
        api.online = false
        api.status = "failed"
        uc.syncWithBackend(config, offeredIds = emptySet())
        assertEquals(TextDeliveryStatus.SCHEDULED, repo.get(job.id)!!.status)
    }

    @Test
    fun `refresh - sent elsewhere becomes SENT, deleted on the backend becomes FAILED`() = runTest {
        val api = Api()
        val uc = retryingRow(api)
        api.status = "sent"
        uc.syncWithBackend(config, emptySet())
        assertEquals(TextDeliveryStatus.SENT, repo.get(job.id)!!.status)

        repo.update(repo.get(job.id)!!.copy(status = TextDeliveryStatus.SCHEDULED))
        api.gone = true
        uc.syncWithBackend(config, emptySet())
        assertEquals(TextDeliveryStatus.FAILED, repo.get(job.id)!!.status)
        assertEquals("No longer in the Command Center", repo.get(job.id)!!.lastError)
    }

    @Test
    fun `refresh - a retry that is not due yet stays Scheduled with the backend's latest reason`() = runTest {
        val api = Api()
        val uc = retryingRow(api)
        api.errorMessage = "WhatsApp did not come to the foreground"
        uc.syncWithBackend(config, emptySet())
        val local = repo.get(job.id)!!
        assertEquals(TextDeliveryStatus.SCHEDULED, local.status)
        assertEquals("WhatsApp did not come to the foreground", local.lastError)
    }
}

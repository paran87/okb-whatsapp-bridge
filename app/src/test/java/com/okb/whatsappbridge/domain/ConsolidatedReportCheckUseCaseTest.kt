package com.okb.whatsappbridge.domain

import com.okb.whatsappbridge.data.remote.api.ApiResult
import com.okb.whatsappbridge.data.remote.api.BackendConfig
import com.okb.whatsappbridge.data.remote.api.BridgeApi
import com.okb.whatsappbridge.data.remote.dto.ConsolidatedDelivery
import com.okb.whatsappbridge.data.remote.dto.ConsolidatedDeliveryAckResponse
import com.okb.whatsappbridge.data.remote.dto.ConsolidatedRunDueResponse
import com.okb.whatsappbridge.data.repository.SecureDeviceIdentityRepository
import com.okb.whatsappbridge.domain.model.BridgeSettings
import com.okb.whatsappbridge.domain.usecase.ConsolidatedReportCheckUseCase
import com.okb.whatsappbridge.service.AndroidConsolidatedReportNotifier
import com.okb.whatsappbridge.fakes.FakeBridgeApi
import com.okb.whatsappbridge.fakes.FakeSettingsRepository
import com.okb.whatsappbridge.fakes.RecordingLogger
import com.okb.whatsappbridge.util.security.InMemorySecretStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ConsolidatedReportCheckUseCaseTest {

    @get:Rule val tmp = TemporaryFolder()

    private class Api(private val deliveries: List<ConsolidatedDelivery>) : BridgeApi by FakeBridgeApi() {
        var runDueCalls = 0
        val downloads = mutableListOf<String>()
        val acks = mutableListOf<Pair<String, String>>()
        var runDueResult: ApiResult<ConsolidatedRunDueResponse>? = null

        override suspend fun consolidatedRunDue(config: BackendConfig): ApiResult<ConsolidatedRunDueResponse> {
            runDueCalls++
            return runDueResult ?: ApiResult.Success(ConsolidatedRunDueResponse(deliveries = deliveries), 200)
        }

        override suspend fun downloadConsolidatedPdf(config: BackendConfig, pdfPath: String, target: File): ApiResult<Long> {
            downloads += pdfPath
            target.parentFile?.mkdirs()
            target.writeText("%PDF-1.7")
            return ApiResult.Success(8, 200)
        }

        override suspend fun acknowledgeConsolidatedDelivery(config: BackendConfig, id: String, state: String): ApiResult<ConsolidatedDeliveryAckResponse> {
            acks += id to state
            return ApiResult.Success(ConsolidatedDeliveryAckResponse(id, state), 200)
        }
    }

    private val delivery = ConsolidatedDelivery(
        id = "5f0c6a2e-1b7d-4c1e-9a77-1d2f3e4a5b6c",
        kind = "scheduled",
        fileName = "OKB_Consolidated_Flood_Report_2026-08-12_1200.pdf",
        caption = "📄 OKB CONSOLIDATED FLOOD MONITORING REPORT\n\nReporting Period:\nAugust 12, 2026\n06:00 AM – 12:00 PM",
        destinationGroup = "NCR Flood Monitoring",
        pdfPath = "api/v1/consolidated-reports/5f0c6a2e-1b7d-4c1e-9a77-1d2f3e4a5b6c/pdf",
    )

    private fun useCase(api: BridgeApi, ready: MutableList<ConsolidatedDelivery>, canNotify: Boolean = true, url: String = "https://okb.test") =
        ConsolidatedReportCheckUseCase(
            FakeSettingsRepository(BridgeSettings(backendUrl = url)),
            SecureDeviceIdentityRepository(InMemorySecretStore()),
            api,
            tmp.root,
            { d, _ -> if (canNotify) ready += d; canNotify },
            RecordingLogger(),
        )

    @Test
    fun `ready report is downloaded once, notified and acknowledged`() = runTest {
        val api = Api(listOf(delivery))
        val ready = mutableListOf<ConsolidatedDelivery>()
        assertEquals(1, useCase(api, ready)())
        assertEquals(listOf(delivery.pdfPath), api.downloads)
        assertEquals(listOf(delivery.id to "notified"), api.acks)
        assertEquals(listOf(delivery), ready)
        assertTrue(ConsolidatedReportCheckUseCase.pdfFile(tmp.root, delivery)!!.exists())

        // A resend of the same report reuses the PDF already on the phone.
        useCase(api, ready)()
        assertEquals(1, api.downloads.size)
    }

    @Test
    fun `nothing is acknowledged when notifications are disabled`() = runTest {
        val api = Api(listOf(delivery))
        assertEquals(0, useCase(api, mutableListOf(), canNotify = false)())
        assertTrue(api.acks.isEmpty())
    }

    @Test
    fun `no backend configured means no call`() = runTest {
        val api = Api(listOf(delivery))
        useCase(api, mutableListOf(), url = "")()
        assertEquals(0, api.runDueCalls)
    }

    @Test
    fun `backend without consolidated storage is ignored quietly`() = runTest {
        val api = Api(emptyList()).apply { runDueResult = ApiResult.HttpError(503, "HTTP 503") }
        assertEquals(0, useCase(api, mutableListOf())())
    }

    @Test
    fun `unsafe file names are never written`() {
        assertNull(ConsolidatedReportCheckUseCase.pdfFile(tmp.root, delivery.copy(fileName = "../../evil.pdf")))
        assertNull(ConsolidatedReportCheckUseCase.pdfFile(tmp.root, delivery.copy(id = "../x")))
    }

    @Test
    fun `opening the share screen is reported as opened, never as sent`() = runTest {
        val api = Api(emptyList())
        useCase(api, mutableListOf()).markOpened(delivery.id)
        assertEquals(listOf(delivery.id to "opened"), api.acks)
    }

    @Test
    fun `share instruction names the configured group and never claims automatic selection`() {
        assertEquals(
            "WhatsApp share screen will open. Select “NCR Flood Monitoring” and press Send.",
            AndroidConsolidatedReportNotifier.shareInstruction("NCR Flood Monitoring"),
        )
        assertTrue(AndroidConsolidatedReportNotifier.shareInstruction(" ").contains("No destination group is configured"))
    }
}

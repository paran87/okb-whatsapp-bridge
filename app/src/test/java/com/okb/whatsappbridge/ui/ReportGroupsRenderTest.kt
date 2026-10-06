package com.okb.whatsappbridge.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.okb.whatsappbridge.domain.model.BridgeSettings
import com.okb.whatsappbridge.domain.model.ConsolidatedDeliveryCounts
import com.okb.whatsappbridge.domain.model.ConsolidatedDeliveryStatus
import com.okb.whatsappbridge.domain.model.ConsolidatedReportDelivery
import com.okb.whatsappbridge.ui.dashboard.DashboardScreen
import com.okb.whatsappbridge.ui.settings.ReportGroupsPanel
import com.okb.whatsappbridge.ui.theme.OkbBridgeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Phone-width (411dp) rendering of the WhatsApp Report Groups settings and the "Consolidated Report Ready"
 * card. PNGs are written to app/build/screenshots for visual review.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w411dp-h1400dp-xhdpi")
class ReportGroupsRenderTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val now = 1_791_074_536_000L
    private val settings = BridgeSettings(
        monitoringEnabled = true,
        backendUrl = "https://okb-bridge-api.onrender.com",
        lastBackendCheckAt = now - 60_000,
        lastBackendCheckOk = true,
        lastBackendCheckMessage = "Connected (v3.0.0)",
        sourceGroupName = "NMDEO FLOOD MONITORING",
        destinationGroupName = "OKB COMMAND CENTER",
    )

    private fun delivery(status: ConsolidatedDeliveryStatus) = ConsolidatedReportDelivery(
        id = "5f0c6a2e-1b7d-4c1e-9a77-1d2f3e4a5b6c",
        kind = "scheduled",
        fileName = "OKB_Consolidated_Flood_Report_2026-10-06_1800.pdf",
        caption = "📄 OKB CONSOLIDATED FLOOD MONITORING REPORT",
        sourceGroup = "NMDEO FLOOD MONITORING",
        destinationGroup = "OKB COMMAND CENTER",
        periodStart = null,
        periodEnd = null,
        reportCount = 3,
        pdfPath = "api/v1/consolidated-reports/5f0c6a2e-1b7d-4c1e-9a77-1d2f3e4a5b6c/pdf",
        status = status,
        createdAt = now - 60_000,
        updatedAt = now - 60_000,
    )

    private fun render(name: String, content: @Composable () -> Unit) {
        compose.setContent { OkbBridgeTheme(darkTheme = false) { Surface(Modifier.fillMaxSize()) { content() } } }
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val dir = File("build/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun state(vararg deliveries: ConsolidatedReportDelivery) = StatusUiState(
        settings = settings,
        deliveries = deliveries.toList(),
        deliveryCounts = ConsolidatedDeliveryCounts(pending = 0, ready = deliveries.size, sent = 4, failed = 0),
        loaded = true,
    )

    @Test
    fun `TEST 7 - the ready card names the destination group and offers Send to WhatsApp`() {
        var sent: String? = null
        render("report-ready-card") {
            DashboardScreen(state(delivery(ConsolidatedDeliveryStatus.READY_FOR_WHATSAPP)), now, {}, {}, {}, {}, {}, onSendReport = { sent = it })
        }
        compose.onNodeWithText("CONSOLIDATED REPORT READY").assertExists()
        compose.onNodeWithText("READY TO SEND", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("WhatsApp share screen will open. Select “OKB COMMAND CENTER” and press Send.").assertExists()
        compose.onNodeWithText("OKB_Consolidated_Flood_Report_2026-10-06_1800.pdf").assertExists()
        compose.onNodeWithText("Send to WhatsApp").performClick()
        assertEquals("5f0c6a2e-1b7d-4c1e-9a77-1d2f3e4a5b6c", sent)
    }

    @Test
    fun `after the share screen the operator confirms - nothing is marked sent automatically`() {
        var confirmed = false
        var notSent = false
        render("report-opened-card") {
            DashboardScreen(
                state(delivery(ConsolidatedDeliveryStatus.OPENED_IN_WHATSAPP)), now, {}, {}, {}, {}, {},
                onConfirmReportSent = { confirmed = true }, onReportNotSent = { notSent = true },
            )
        }
        compose.onNodeWithText("OPENED IN WHATSAPP", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Not sent").performClick()
        compose.onNodeWithText("Yes, sent").performClick()
        assertEquals(true, notSent)
        assertEquals(true, confirmed)
    }

    @Test
    fun `status panel shows both groups and the delivery counts`() {
        render("report-groups-status") { DashboardScreen(state(), now, {}, {}, {}, {}, {}) }
        compose.onNodeWithText("REPORT GROUPS & DELIVERY").assertExists()
        compose.onNodeWithText("NMDEO FLOOD MONITORING").assertExists()
        compose.onNodeWithText("OKB COMMAND CENTER").assertExists()
        compose.onNodeWithText("Pending 0 · Ready 0 · Sent 4 · Failed 0").assertExists()
    }

    @Test
    fun `settings - two clearly separate group fields and identical names cannot be saved`() {
        render("report-groups-settings") {
            ReportGroupsPanel("OKB COMMAND CENTER", "okb command center", {}, {}, {}, busy = false)
        }
        compose.onNodeWithText("Source Group").assertExists()
        compose.onNodeWithText("Destination Group").assertExists()
        compose.onNodeWithText("WhatsApp group where flood/activity reports are received.").assertExists()
        compose.onNodeWithText("WhatsApp group where consolidated reports will be sent.").assertExists()
        compose.onNodeWithText("The source and destination groups must be different groups.").assertExists()
        compose.onNodeWithText("Save groups").assertIsNotEnabled()
    }

    @Test
    fun `settings screenshot with valid groups`() {
        render("report-groups-settings-valid") {
            ReportGroupsPanel("NMDEO FLOOD MONITORING", "OKB COMMAND CENTER", {}, {}, {}, busy = false)
        }
        compose.onNodeWithText("Save groups").assertExists()
    }
}

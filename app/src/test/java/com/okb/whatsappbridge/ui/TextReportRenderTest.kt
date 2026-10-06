package com.okb.whatsappbridge.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.okb.whatsappbridge.automation.MessagePart
import com.okb.whatsappbridge.domain.model.AutomationReadiness
import com.okb.whatsappbridge.domain.model.BridgeSettings
import com.okb.whatsappbridge.domain.model.ConsolidatedDeliveryCounts
import com.okb.whatsappbridge.domain.model.ConsolidatedDeliveryStatus
import com.okb.whatsappbridge.domain.model.ConsolidatedReportDelivery
import com.okb.whatsappbridge.domain.model.TextDeliveryStatus
import com.okb.whatsappbridge.domain.model.TextReportDelivery
import com.okb.whatsappbridge.ui.dashboard.DashboardScreen
import com.okb.whatsappbridge.ui.theme.OkbBridgeTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Phone-width (390dp) Dashboard with the AUTOMATIC TEXT REPORT card (status only, no Send button) next to the
 * manual "PDF Ready" card. PNGs go to app/build/screenshots for visual review.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w390dp-h1800dp-xhdpi")
class TextReportRenderTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val now = System.currentTimeMillis()
    private val settings = BridgeSettings(
        monitoringEnabled = true, backendUrl = "https://okb-bridge-api.onrender.com", lastBackendCheckAt = now - 60_000,
        lastBackendCheckOk = true, lastBackendCheckMessage = "Connected (v3.0.0)",
        sourceGroupName = "NMDEO FLOOD MONITORING", destinationGroupName = "OKB COMMAND CENTER",
    )
    private val ready = AutomationReadiness(
        accessibilityEnabled = true, accessibilityConnected = true, exactAlarmsAllowed = true, secureLockScreen = false,
        nextCheckAt = now + 3 * 60 * 60 * 1000, nextCheckExact = true,
    )

    private fun text(status: TextDeliveryStatus, error: String? = null) = TextReportDelivery(
        id = "9b2f6c1e-0000-4000-8000-000000000001", reportId = "5f0c6a2e", kind = "scheduled",
        dedupeKey = "k", destinationGroup = "OKB COMMAND CENTER", sourceGroup = "NMDEO FLOOD MONITORING",
        parts = listOf(MessagePart("📋 …", "OKB-9B2F6C1E")),
        periodStart = "2026-10-06T10:00:00Z", periodEnd = "2026-10-06T16:00:00Z", reportCount = 4,
        status = status, attempt = 1, lastError = error,
        verification = if (status == TextDeliveryStatus.SENT) "Ref OKB-9B2F6C1E visible in \"OKB COMMAND CENTER\" after Send; WhatsApp status: Delivered" else null,
        sentAt = if (status == TextDeliveryStatus.SENT) now - 30_000 else null,
        createdAt = now - 60_000, updatedAt = now - 30_000,
    )

    private val pdf = ConsolidatedReportDelivery(
        id = "5f0c6a2e-1b7d-4c1e-9a77-1d2f3e4a5b6c", kind = "scheduled", fileName = "OKB_Consolidated_Flood_Report_2026-10-07_0000.pdf",
        caption = "📄", sourceGroup = "NMDEO FLOOD MONITORING", destinationGroup = "OKB COMMAND CENTER", periodStart = null,
        periodEnd = null, reportCount = 4, pdfPath = "p", status = ConsolidatedDeliveryStatus.READY_FOR_WHATSAPP,
        createdAt = now - 60_000, updatedAt = now - 60_000,
    )

    private fun state(textDelivery: TextReportDelivery, automation: AutomationReadiness = ready) = StatusUiState(
        settings = settings,
        polled = PolledState(automation = automation),
        deliveries = listOf(pdf),
        deliveryCounts = ConsolidatedDeliveryCounts(ready = 1),
        textDeliveries = listOf(textDelivery),
        loaded = true,
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

    @Test
    fun `sent TEXT report shows status and destination, no Send button, PDF keeps Send as PDF`() {
        render("text-report-sent") { DashboardScreen(state(text(TextDeliveryStatus.SENT)), now, {}, {}, {}, {}, {}) }
        compose.onNodeWithText("AUTOMATIC TEXT REPORT").assertExists()
        compose.onNodeWithText("SENT", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("PDF READY").assertExists()
        // The PDF button is the only send action on the screen.
        compose.onAllNodesWithText("Send as PDF").assertCountEquals(1)
        compose.onAllNodesWithText("Send").assertCountEquals(0)
        compose.onAllNodesWithText("Send to WhatsApp").assertCountEquals(0)
    }

    @Test
    fun `scheduled, retrying and failed TEXT states`() {
        render("text-report-retrying") {
            DashboardScreen(state(text(TextDeliveryStatus.SCHEDULED, "Destination group \"OKB COMMAND CENTER\" was not found in WhatsApp")), now, {}, {}, {}, {}, {})
        }
        compose.onNodeWithText("SCHEDULED · RETRYING", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `failed TEXT report explains the cause`() {
        render("text-report-failed") {
            DashboardScreen(
                state(
                    text(TextDeliveryStatus.FAILED, "The phone is locked with a PIN, pattern or password, which Android does not let apps unlock. Set Screen lock to None or Swipe on the bridge phone."),
                    ready.copy(secureLockScreen = true),
                ),
                now, {}, {}, {}, {}, {},
            )
        }
        compose.onNodeWithText("FAILED", useUnmergedTree = true).assertExists()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("AUTOMATIC TEXT REPORTS"))
        compose.onNodeWithText("PIN / PATTERN / PASSWORD", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `automatic sending checklist at phone width`() {
        render("automatic-sending-panel") {
            com.okb.whatsappbridge.ui.consolidated.AutomaticSendingPanel(
                readiness = ready.copy(secureLockScreen = true, exactAlarmsAllowed = false),
                destinationGroup = "OKB COMMAND CENTER",
                onOpenAccessibility = {}, onOpenAlarms = {}, onOpenScreenLock = {},
            )
        }
        compose.onNodeWithText("PIN / PATTERN / PASSWORD", useUnmergedTree = true).assertExists()
        compose.onAllNodesWithText("Screen lock").assertCountEquals(2) // status line + settings button
        compose.onNodeWithText("Alarms").assertExists()
    }
}

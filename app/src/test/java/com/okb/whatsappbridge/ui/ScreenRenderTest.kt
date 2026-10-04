package com.okb.whatsappbridge.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.runtime.Composable
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.okb.whatsappbridge.domain.model.BridgeSettings
import com.okb.whatsappbridge.domain.model.MediaCounts
import com.okb.whatsappbridge.domain.model.QueueCounts
import com.okb.whatsappbridge.domain.model.SystemStatus
import com.okb.whatsappbridge.ui.dashboard.DashboardScreen
import com.okb.whatsappbridge.ui.diagnostics.DiagnosticsScreen
import com.okb.whatsappbridge.ui.theme.OkbBridgeTheme
import com.okb.whatsappbridge.worker.ReconciliationState
import com.okb.whatsappbridge.worker.SyncWorkerState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders key screens from explicit state (Robolectric native graphics) and checks that status
 * labels follow the state. PNGs are written to app/build/screenshots for visual review.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w411dp-h1400dp-xhdpi")
class ScreenRenderTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val now = 1_791_074_536_000L

    private fun system(access: Boolean, connected: Boolean) = SystemStatus(
        notificationAccessGranted = access,
        listenerConnected = connected,
        installedWhatsAppPackages = listOf("com.whatsapp"),
        ignoringBatteryOptimizations = true,
        backgroundRestricted = false,
        standbyBucket = "Active",
        powerSaveMode = false,
        appNotificationsEnabled = true,
        networkAvailable = true,
        sdkInt = 34,
        manufacturer = "Google",
        model = "Pixel 7",
    )

    private fun state(access: Boolean, connected: Boolean, enabled: Boolean) = StatusUiState(
        settings = BridgeSettings(
            monitoringEnabled = enabled,
            backendUrl = "https://okb.example.org",
            lastNotificationAt = now - 120_000,
            lastProcessedAt = now - 119_000,
            lastUploadSuccessAt = now - 117_000,
            lastBackendCheckAt = now - 300_000,
            lastBackendCheckOk = true,
            lastBackendCheckMessage = "Connected (v1.0.0)",
            lastMediaCaptureAt = now - 118_000,
            lastMediaUploadAt = now - 116_000,
        ),
        polled = PolledState(
            system = system(access, connected), databaseHealthy = true, deviceId = "OKB-ANDROID-A82F19",
            mediaStorageUsedBytes = 48_234_496, mediaLargestQueuedBytes = 27_000_000, mediaUsableSpaceBytes = 6_000_000_000,
        ),
        listenerConnected = connected,
        counts = QueueCounts(pending = 2, retrying = 1, uploaded = 244),
        capturedToday = 247,
        latestMessageAt = now - 120_000,
        uploadWorker = SyncWorkerState.IDLE,
        reconciliation = ReconciliationState(true, now + 600_000),
        authorizedGroups = 2,
        mediaCounts = MediaCounts(available = 18, unavailable = 42, pendingUpload = 2, uploaded = 15, uploadFailed = 1),
        mediaCapturedToday = 60,
        lastMediaCaptureAt = now - 118_000,
        lastMediaUploadAt = now - 116_000,
        loaded = true,
    )

    private fun render(name: String, dark: Boolean, content: @Composable () -> Unit) {
        compose.setContent { OkbBridgeTheme(darkTheme = dark) { Surface(Modifier.fillMaxSize()) { content() } } }
        compose.waitForIdle()
        // Draw the content view directly (PixelCopy-based captureToImage is not available here).
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val dir = File("build/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Composable
    private fun Dashboard(s: StatusUiState) = DashboardScreen(s, now, {}, {}, {}, {}, {})

    @Test
    fun dashboardActiveDark() {
        render("dashboard-active-dark", dark = true) { Dashboard(state(access = true, connected = true, enabled = true)) }
        // Monitoring and Notification Access both report ACTIVE.
        compose.onAllNodesWithText("ACTIVE", useUnmergedTree = true).assertCountEquals(2)
        compose.onNodeWithText("247").assertExists()
    }

    @Test
    fun dashboardPausedLight() {
        render("dashboard-paused-light", dark = false) { Dashboard(state(access = true, connected = true, enabled = false)) }
        compose.onNodeWithText("PAUSED", useUnmergedTree = true).assertExists()
    }

    @Test
    fun dashboardNoAccessShowsWarning() {
        render("dashboard-no-access-dark", dark = true) { Dashboard(state(access = false, connected = false, enabled = true)) }
        compose.onNodeWithText("NOT RECEIVING", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Open Notification Access Settings").assertExists()
    }

    @Test
    fun diagnosticsDark() {
        render("diagnostics-dark", dark = true) {
            DiagnosticsScreen(state(access = true, connected = true, enabled = true), now, false, {}, {}, {})
        }
        compose.onNodeWithText("Room Database").assertExists()
    }
}

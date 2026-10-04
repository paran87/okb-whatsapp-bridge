package com.okb.whatsappbridge.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.okb.whatsappbridge.domain.model.MonitoringState
import com.okb.whatsappbridge.ui.StatusPresentation
import com.okb.whatsappbridge.ui.StatusUiState
import com.okb.whatsappbridge.ui.components.ButtonRow
import com.okb.whatsappbridge.ui.components.Formatters
import com.okb.whatsappbridge.ui.components.MetricTile
import com.okb.whatsappbridge.ui.components.MonitoringToggleCard
import com.okb.whatsappbridge.ui.components.Panel
import com.okb.whatsappbridge.ui.components.StatusLevel
import com.okb.whatsappbridge.ui.components.StatusLine
import com.okb.whatsappbridge.ui.components.WarningBanner
import com.okb.whatsappbridge.ui.onboarding.SetupChecklist
import com.okb.whatsappbridge.ui.onboarding.SetupStep
import com.okb.whatsappbridge.util.system.SystemSettingsIntents

@Composable
fun DashboardScreen(
    state: StatusUiState,
    now: Long,
    onToggleMonitoring: (Boolean) -> Unit,
    onTestBackend: () -> Unit,
    onOpenGroups: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    val context = LocalContext.current
    val settings = state.settings
    val system = state.system
    val counts = state.counts

    val steps = listOf(
        SetupStep("Grant Notification Access", system?.notificationAccessGranted == true, "Open") {
            SystemSettingsIntents.openNotificationAccess(context)
        },
        SetupStep("Authorize at least one WhatsApp group", state.authorizedGroups > 0, "Groups", onOpenGroups),
        SetupStep("Configure the backend URL", settings.backendConfigured, "Settings", onOpenSettings),
        SetupStep("Turn on Background Monitoring", settings.monitoringEnabled, "Enable") { onToggleMonitoring(true) },
    )

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 300.dp),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.loaded) warnings(state, context)

        if (state.loaded && steps.any { !it.done }) {
            item(span = { GridItemSpan(maxLineSpan) }) { SetupChecklist(steps) }
        }

        item(span = { GridItemSpan(maxLineSpan) }) {
            MonitoringToggleCard(
                enabled = settings.monitoringEnabled,
                state = state.monitoringState,
                lastNotificationAt = settings.lastNotificationAt,
                lastProcessedAt = settings.lastProcessedAt,
                lastUploadAt = settings.lastUploadSuccessAt,
                lastFailureAt = settings.lastUploadFailureAt,
                onToggle = onToggleMonitoring,
            )
        }

        item {
            Panel(title = "System Status") {
                val access = StatusPresentation.notificationAccess(system?.notificationAccessGranted, state.listenerConnected)
                StatusLine("Notification Access", access.level, access.label)
                val wa = StatusPresentation.whatsApp(system?.installedWhatsAppPackages, settings.lastNotificationAt)
                StatusLine("WhatsApp", wa.level, wa.label)
                val backend = StatusPresentation.backend(
                    settings.backendConfigured, settings.lastBackendCheckOk, settings.lastBackendCheckAt,
                    settings.lastBackendCheckMessage, settings.lastUploadSuccessAt, settings.lastUploadFailureAt,
                )
                StatusLine("Backend", backend.level, backend.label)
                val worker = StatusPresentation.syncWorker(state.uploadWorker, settings.syncPaused, counts.notUploaded)
                StatusLine("Sync Worker", worker.level, worker.label)
                val mediaWorker = StatusPresentation.syncWorker(state.mediaUploadWorker, settings.syncPaused, state.mediaCounts.pendingUploadTotal)
                StatusLine("Media Worker", mediaWorker.level, mediaWorker.label)
                val db = StatusPresentation.database(state.polled.databaseHealthy)
                StatusLine("Database", db.level, db.label)
                ButtonRow {
                    OutlinedButton(onClick = onTestBackend) { Text("Test Backend") }
                    OutlinedButton(onClick = onOpenDiagnostics) { Text("Diagnostics") }
                }
            }
        }

        item {
            MetricTile(
                label = "Messages Today",
                value = state.capturedToday.toString(),
                caption = "Captured from ${state.authorizedGroups} authorized group(s)",
            )
        }
        item {
            MetricTile(
                label = "Pending Upload",
                value = counts.notUploaded.toString(),
                level = when {
                    counts.failed > 0 -> StatusLevel.ERROR
                    counts.notUploaded > 0 -> StatusLevel.WARNING
                    else -> StatusLevel.OK
                },
                caption = "${counts.retrying} retrying · ${counts.failed} failed · ${counts.uploaded} uploaded",
            )
        }
        item {
            MetricTile(
                label = "Last Message",
                value = Formatters.shortTime(state.latestMessageAt),
                caption = "Last event ${Formatters.relative(settings.lastNotificationAt, now)}",
            )
        }

        val media = state.mediaCounts
        item { MetricTile(label = "Media Today", value = state.mediaCapturedToday.toString(), caption = "${media.available} with a file · ${media.unavailable} unavailable") }
        item {
            MetricTile(
                label = "Pending Media Uploads",
                value = media.pendingUploadTotal.toString(),
                level = when { media.uploadFailed > 0 -> StatusLevel.ERROR; media.pendingUploadTotal > 0 -> StatusLevel.WARNING; else -> StatusLevel.OK },
                caption = "${media.retrying} retrying · ${media.uploadFailed} failed",
            )
        }
        item { MetricTile(label = "Uploaded Media", value = media.uploaded.toString(), level = StatusLevel.OK) }
        item { MetricTile(label = "Failed Media", value = media.uploadFailed.toString(), level = if (media.uploadFailed > 0) StatusLevel.ERROR else null) }
        item { MetricTile(label = "Storage Used", value = Formatters.bytes(state.polled.mediaStorageUsedBytes), caption = "Local media awaiting or kept after upload") }
        item { MetricTile(label = "Last Media Capture", value = Formatters.shortTime(state.lastMediaCaptureAt)) }
        item { MetricTile(label = "Last Media Upload", value = Formatters.shortTime(state.lastMediaUploadAt)) }
    }
}

private fun LazyGridScope.warnings(state: StatusUiState, context: android.content.Context) {
    val monitoring = state.monitoringState
    if (monitoring == MonitoringState.NO_ACCESS || monitoring == MonitoringState.LISTENER_DISCONNECTED) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            WarningBanner(
                text = "⚠ Background monitoring may be inactive. " +
                    if (monitoring == MonitoringState.NO_ACCESS) "Notification Access is not enabled."
                    else "Android has not connected the notification listener.",
                actionLabel = "Open Notification Access Settings",
                onAction = { SystemSettingsIntents.openNotificationAccess(context) },
                level = if (monitoring == MonitoringState.NO_ACCESS) StatusLevel.ERROR else StatusLevel.WARNING,
            )
        }
    }
    val system = state.system
    if (system != null && system.backgroundRestricted == true) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            WarningBanner(
                text = "Android is restricting OKB Bridge in the background. Set battery usage to Unrestricted.",
                actionLabel = "Open Battery Settings",
                onAction = { SystemSettingsIntents.openAppDetails(context) },
                level = StatusLevel.ERROR,
            )
        }
    }
    if (state.counts.failed > 0) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            WarningBanner(
                text = "${state.counts.failed} message(s) failed to upload: ${state.settings.lastUploadError ?: "see Sync"}",
                actionLabel = null,
                onAction = null,
                level = StatusLevel.ERROR,
            )
        }
    }
}

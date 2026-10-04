package com.okb.whatsappbridge.ui.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
import com.okb.whatsappbridge.ui.components.KeyValueLine
import com.okb.whatsappbridge.ui.components.Panel
import com.okb.whatsappbridge.ui.components.SectionDivider
import com.okb.whatsappbridge.ui.components.StatusLevel
import com.okb.whatsappbridge.ui.components.StatusLine
import com.okb.whatsappbridge.ui.components.WarningBanner
import com.okb.whatsappbridge.util.system.SystemSettingsIntents

@Composable
fun DiagnosticsScreen(
    state: StatusUiState,
    now: Long,
    busy: Boolean,
    onTestBackend: () -> Unit,
    onRunHealthCheck: () -> Unit,
    onViewLogs: () -> Unit,
) {
    val context = LocalContext.current
    val settings = state.settings
    val system = state.system

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 320.dp),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        val monitoring = state.monitoringState
        if (monitoring == MonitoringState.NO_ACCESS || monitoring == MonitoringState.LISTENER_DISCONNECTED) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                WarningBanner(
                    "⚠ Background monitoring may be inactive.",
                    "Open Notification Access Settings",
                    { SystemSettingsIntents.openNotificationAccess(context) },
                )
            }
        }

        item {
            Panel(title = "Health") {
                val access = StatusPresentation.notificationAccess(system?.notificationAccessGranted, state.listenerConnected)
                StatusLine("Notification Listener", access.level, access.label, access.detail)
                StatusLine(
                    "Background Monitoring",
                    if (settings.monitoringEnabled) StatusLevel.OK else StatusLevel.NEUTRAL,
                    if (settings.monitoringEnabled) "Enabled" else "Disabled",
                )
                val wa = StatusPresentation.whatsApp(system?.installedWhatsAppPackages, settings.lastNotificationAt)
                StatusLine("WhatsApp", wa.level, wa.label, wa.detail)
                val viber = StatusPresentation.viber(system?.installedViberPackages)
                StatusLine("Viber", viber.level, viber.label, viber.detail)
                val db = StatusPresentation.database(state.polled.databaseHealthy)
                StatusLine("Room Database", db.level, if (db.level == StatusLevel.OK) "Healthy" else db.label, db.detail)
                StatusLine(
                    "WorkManager",
                    if (state.reconciliation.scheduled) StatusLevel.OK else StatusLevel.WARNING,
                    if (state.reconciliation.scheduled) "Healthy" else "Not scheduled",
                    "Upload worker: ${StatusPresentation.syncWorker(state.uploadWorker, settings.syncPaused, state.counts.notUploaded).label}",
                )
                val backend = StatusPresentation.backend(
                    settings.backendConfigured, settings.lastBackendCheckOk, settings.lastBackendCheckAt,
                    settings.lastBackendCheckMessage, settings.lastUploadSuccessAt, settings.lastUploadFailureAt,
                )
                StatusLine("Backend", backend.level, backend.label, backend.detail)
                val battery = StatusPresentation.battery(system?.ignoringBatteryOptimizations, system?.backgroundRestricted, system?.standbyBucket)
                StatusLine("Battery", battery.level, battery.label)
            }
        }

        item {
            Panel(title = "Activity") {
                KeyValueLine("Last notification", Formatters.time(settings.lastNotificationAt))
                KeyValueLine("Last processed", Formatters.time(settings.lastProcessedAt))
                KeyValueLine("Last upload", Formatters.time(settings.lastUploadSuccessAt))
                KeyValueLine("Last failed upload", Formatters.time(settings.lastUploadFailureAt))
                KeyValueLine("Pending messages", state.counts.notUploaded.toString())
                KeyValueLine("Failed messages", state.counts.failed.toString())
                SectionDivider()
                KeyValueLine("Listener connected", Formatters.time(settings.lastListenerConnectedAt))
                KeyValueLine("Listener disconnected", Formatters.time(settings.lastListenerDisconnectedAt))
                KeyValueLine("Last health check", Formatters.relative(settings.lastHealthCheckAt, now))
                KeyValueLine("Last boot / update", Formatters.time(settings.lastBootAt))
                KeyValueLine("Backend check", Formatters.time(settings.lastBackendCheckAt))
            }
        }

        item {
            Panel(title = "Device") {
                KeyValueLine("Device ID", state.polled.deviceId)
                KeyValueLine("Model", system?.let { "${it.manufacturer} ${it.model}" } ?: "—", mono = false)
                KeyValueLine("Android SDK", system?.sdkInt?.toString() ?: "—")
                KeyValueLine("Battery optimization", if (system?.ignoringBatteryOptimizations == true) "Exempt" else "Optimized", mono = false)
                KeyValueLine("Background restricted", system?.backgroundRestricted?.let { if (it) "Yes" else "No" } ?: "n/a", mono = false)
                KeyValueLine("App standby bucket", system?.standbyBucket ?: "n/a", mono = false)
                KeyValueLine("Power saver", if (system?.powerSaveMode == true) "On" else "Off", mono = false)
                KeyValueLine("Network", if (system?.networkAvailable == true) "Connected" else "Unavailable", mono = false)
            }
        }

        item {
            Panel(title = "Media") {
                val m = state.mediaCounts
                StatusLine(
                    "Media acquisition capability",
                    if (state.polled.mediaAcquisitionSupported) StatusLevel.OK else StatusLevel.WARNING,
                    if (state.polled.mediaAcquisitionSupported) "Available" else "Unavailable",
                    "Acquires media only from notification-provided URIs.",
                )
                StatusLine(
                    "Media permissions",
                    StatusLevel.OK,
                    "None required",
                    "No storage permission is used; WhatsApp's private files are never read.",
                )
                StatusLine(
                    "Media capture",
                    if (state.settings.captureMedia) StatusLevel.OK else StatusLevel.NEUTRAL,
                    if (state.settings.captureMedia) "Enabled" else "Disabled",
                )
                KeyValueLine("Available (file acquired)", m.available.toString())
                KeyValueLine("Unavailable (no file provided)", m.unavailable.toString())
                KeyValueLine("Pending media uploads", m.pendingUploadTotal.toString())
                KeyValueLine("Uploaded media", m.uploaded.toString())
                KeyValueLine("Failed media", m.uploadFailed.toString())
                KeyValueLine("Local storage used", Formatters.bytes(state.polled.mediaStorageUsedBytes))
                KeyValueLine("Largest queued file", Formatters.bytes(state.polled.mediaLargestQueuedBytes))
                KeyValueLine("Free space", Formatters.bytes(state.polled.mediaUsableSpaceBytes))
                KeyValueLine("Last media capture", Formatters.time(state.settings.lastMediaCaptureAt))
                KeyValueLine("Last media upload", Formatters.time(state.settings.lastMediaUploadAt))
                state.settings.lastMediaError?.let {
                    Text("Last media error: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }

        item {
            Panel(title = "Actions") {
                ButtonRow {
                    Button(onClick = onTestBackend, enabled = !busy && settings.backendConfigured) { Text("Test Backend") }
                    OutlinedButton(onClick = onRunHealthCheck, enabled = !busy) { Text("Run health check") }
                }
                ButtonRow {
                    OutlinedButton(onClick = { SystemSettingsIntents.openNotificationAccess(context) }) { Text("Open Notification Settings") }
                }
                ButtonRow {
                    OutlinedButton(onClick = { SystemSettingsIntents.openBatteryOptimization(context) }) { Text("Open Battery Settings") }
                    OutlinedButton(onClick = onViewLogs) { Text("View Logs") }
                }
            }
        }

        item(span = { GridItemSpan(maxLineSpan) }) {
            Panel(title = "Troubleshooting background restrictions") {
                val tips = listOf(
                    "Auto-start: some manufacturers (e.g. Xiaomi, Huawei, Oppo, Vivo, Realme) block apps from starting " +
                        "after boot unless \"Auto-start\" / \"Auto-launch\" is allowed for OKB WhatsApp Bridge.",
                    "Background activity: set Battery usage to \"Unrestricted\" (App info → Battery) and allow background activity.",
                    "Battery saver: power-saving modes can defer uploads; capture continues but sync may wait for a charger or network.",
                    "Sleeping apps: Samsung \"Sleeping / Deep sleeping apps\" lists must NOT contain OKB WhatsApp Bridge – add it to \"Never sleeping apps\".",
                    "Recents lock: on some devices, lock the app in the recent-apps screen to stop task killers from clearing it.",
                    "After a reboot the phone must be unlocked once before Android starts the listener (Direct Boot).",
                    "If the status shows \"Waiting for Android\", toggle Notification Access off and on for OKB WhatsApp Bridge.",
                    "WhatsApp must post notifications: do not mute monitored groups, and avoid actively using WhatsApp Web/Desktop " +
                        "with this account – WhatsApp may silence the phone's notifications while you are active there.",
                    "See dontkillmyapp.com for model-specific instructions.",
                )
                tips.forEach {
                    Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

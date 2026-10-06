package com.okb.whatsappbridge.ui.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.okb.whatsappbridge.BuildConfig
import com.okb.whatsappbridge.ui.StatusPresentation
import com.okb.whatsappbridge.ui.StatusUiState
import com.okb.whatsappbridge.ui.components.ButtonRow
import com.okb.whatsappbridge.ui.components.Formatters
import com.okb.whatsappbridge.ui.components.KeyValueLine
import com.okb.whatsappbridge.ui.components.MonitoringToggleCard
import com.okb.whatsappbridge.ui.components.Panel
import com.okb.whatsappbridge.ui.components.SectionDivider
import com.okb.whatsappbridge.ui.components.StatusLevel
import com.okb.whatsappbridge.ui.components.StatusLine
import com.okb.whatsappbridge.util.system.SystemSettingsIntents
import com.okb.whatsappbridge.whatsapp.WhatsAppPackages

@Composable
fun SettingsScreen(
    state: StatusUiState,
    busy: Boolean,
    onToggleMonitoring: (Boolean) -> Unit,
    onSaveBackend: (url: String, token: String?) -> Unit,
    onSaveDeviceName: (String) -> Unit,
    onSaveReportGroups: (source: String, destination: String) -> Unit = { _, _ -> },
    onRegisterDevice: () -> Unit,
    onTestBackend: () -> Unit,
    onSetSyncPaused: (Boolean) -> Unit,
    onSetCaptureMedia: (Boolean) -> Unit,
    onSetDeleteLocalAfterUpload: (Boolean) -> Unit,
    onOpenDiagnostics: () -> Unit,
    onRefresh: () -> Unit,
) {
    val context = LocalContext.current
    val settings = state.settings
    val system = state.system
    val narrow = Modifier.widthIn(max = 900.dp)

    var url by rememberSaveable { mutableStateOf("") }
    var token by rememberSaveable { mutableStateOf("") }
    var deviceName by rememberSaveable { mutableStateOf("") }
    var sourceGroup by rememberSaveable { mutableStateOf("") }
    var destinationGroup by rememberSaveable { mutableStateOf("") }
    var initialized by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.loaded) {
        if (state.loaded && !initialized) {
            url = settings.backendUrl
            sourceGroup = settings.sourceGroupName
            destinationGroup = settings.destinationGroupName
            deviceName = settings.deviceName.ifBlank { system?.let { "${it.manufacturer} ${it.model}" }.orEmpty() }
            initialized = true
        }
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onRefresh() }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            MonitoringToggleCard(
                enabled = settings.monitoringEnabled,
                state = state.monitoringState,
                lastNotificationAt = settings.lastNotificationAt,
                lastProcessedAt = settings.lastProcessedAt,
                lastUploadAt = settings.lastUploadSuccessAt,
                lastFailureAt = settings.lastUploadFailureAt,
                onToggle = onToggleMonitoring,
                modifier = narrow,
            )
        }

        item {
            ReportGroupsPanel(
                source = sourceGroup,
                destination = destinationGroup,
                onSourceChange = { sourceGroup = it },
                onDestinationChange = { destinationGroup = it },
                onSave = { onSaveReportGroups(sourceGroup, destinationGroup) },
                busy = busy,
                modifier = narrow,
            )
        }

        item {
            Panel(title = "Notification Access", modifier = narrow) {
                val access = StatusPresentation.notificationAccess(system?.notificationAccessGranted, state.listenerConnected)
                StatusLine("Notification Access", access.level, access.label, access.detail)
                Text(
                    "Path: Settings → Notification Access → OKB WhatsApp Bridge. On Android 13+ a side-loaded APK may " +
                        "first need App info → ⋮ → Allow restricted settings. The bridge never grants access itself.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ButtonRow {
                    OutlinedButton(onClick = { SystemSettingsIntents.openAppDetails(context) }) { Text("App info") }
                    Button(onClick = { SystemSettingsIntents.openNotificationAccess(context) }) { Text("Open Android Settings") }
                }
            }
        }

        item {
            Panel(title = "Battery Optimization", modifier = narrow) {
                val battery = StatusPresentation.battery(system?.ignoringBatteryOptimizations, system?.backgroundRestricted, system?.standbyBucket)
                StatusLine("Status", battery.level, battery.label, battery.detail)
                Text(
                    "For reliable background operation, configure the device so Android does not aggressively restrict " +
                        "OKB WhatsApp Bridge: exempt it from battery optimization and set battery usage to Unrestricted." +
                        if (SystemSettingsIntents.hasAutostartSettings()) {
                            " This phone also has an Autostart permission: allow it, or the system may stop the bridge when it is closed."
                        } else {
                            ""
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ButtonRow {
                    OutlinedButton(onClick = { SystemSettingsIntents.openAppDetails(context) }) { Text("App battery usage") }
                    Button(onClick = { SystemSettingsIntents.openBatteryOptimization(context) }) { Text("Open Battery Settings") }
                }
                if (SystemSettingsIntents.hasAutostartSettings()) {
                    ButtonRow {
                        Button(onClick = { SystemSettingsIntents.openAutostartSettings(context) }) { Text("Autostart settings") }
                    }
                }
            }
        }

        item {
            Panel(title = "WhatsApp Status", modifier = narrow) {
                val wa = StatusPresentation.whatsApp(system?.installedWhatsAppPackages, settings.lastNotificationAt)
                StatusLine("WhatsApp", wa.level, wa.label, wa.detail)
                KeyValueLine("Last WhatsApp notification", Formatters.time(settings.lastNotificationAt))
                Text(
                    "WhatsApp must show notifications for the monitored groups: do not mute them, and keep WhatsApp " +
                        "notifications enabled in Android.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ButtonRow {
                    system?.installedWhatsAppPackages.orEmpty().forEach { pkg ->
                        OutlinedButton(onClick = { SystemSettingsIntents.openAppNotificationSettings(context, pkg) }) {
                            Text("${WhatsAppPackages.displayName(pkg)} notifications")
                        }
                    }
                }
            }
        }

        item {
            Panel(title = "Backend Configuration", modifier = narrow) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Backend URL") },
                    placeholder = { Text("https://okb.example.org") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (url.trim().startsWith("http://", ignoreCase = true)) {
                    Text(
                        if (BuildConfig.DEBUG) "Warning: HTTP is unencrypted. Use only for local testing."
                        else "Release builds require HTTPS.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("Device Token") },
                    placeholder = { Text(if (state.polled.hasToken) "Stored – leave blank to keep" else "Paste token") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                KeyValueLine("Stored token", state.polled.tokenPreview)
                Text(
                    "The token is encrypted with an Android Keystore key and is never logged or displayed in full.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val backend = StatusPresentation.backend(
                    settings.backendConfigured, settings.lastBackendCheckOk, settings.lastBackendCheckAt,
                    settings.lastBackendCheckMessage, settings.lastUploadSuccessAt, settings.lastUploadFailureAt,
                )
                StatusLine("Backend", backend.level, backend.label, backend.detail)
                ButtonRow {
                    if (state.polled.hasToken) {
                        TextButton(onClick = { onSaveBackend(url, "") }, enabled = !busy) { Text("Remove token") }
                    }
                    OutlinedButton(onClick = onTestBackend, enabled = !busy && settings.backendConfigured) { Text("Test Backend") }
                    Button(
                        onClick = {
                            onSaveBackend(url, token.takeIf { it.isNotBlank() })
                            token = ""
                        },
                        enabled = !busy,
                    ) { Text("Save") }
                }
            }
        }

        item {
            Panel(title = "Device Configuration", modifier = narrow) {
                KeyValueLine("Device ID", state.polled.deviceId)
                OutlinedTextField(
                    value = deviceName,
                    onValueChange = { deviceName = it },
                    label = { Text("Device Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                StatusLine(
                    "Registration",
                    if (settings.deviceRegisteredAt != null) StatusLevel.OK else StatusLevel.NEUTRAL,
                    if (settings.deviceRegisteredAt != null) "Registered" else "Not registered",
                    settings.deviceRegisteredAt?.let { "Registered ${Formatters.time(it)}" },
                )
                ButtonRow {
                    OutlinedButton(onClick = { onSaveDeviceName(deviceName) }) { Text("Save name") }
                    Button(onClick = onRegisterDevice, enabled = !busy && settings.backendConfigured) { Text("Register device") }
                }
            }
        }

        item {
            Panel(title = "Synchronization", modifier = narrow) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Upload captured messages", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Switch(checked = !settings.syncPaused, onCheckedChange = { onSetSyncPaused(!it) })
                }
                Text(
                    "Pausing synchronization keeps capturing (if monitoring is on) but holds uploads. Uploaded messages " +
                        "are kept on the device for 30 days.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            Panel(title = "Media Capture", modifier = narrow) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Capture media attachments", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Switch(checked = settings.captureMedia, onCheckedChange = onSetCaptureMedia)
                }
                Text(
                    "When on, the bridge records media attachments it detects and acquires the original file only when " +
                        "Android legitimately provides it on the notification. Text and captions are always captured. " +
                        "No storage permission is requested: the bridge reads only notification-provided media URIs and " +
                        "never WhatsApp's private storage.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SectionDivider()
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Delete local copy after upload", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Switch(checked = settings.deleteLocalAfterUpload, onCheckedChange = onSetDeleteLocalAfterUpload)
                }
                Text(
                    "A local media file is deleted only after the backend confirms its upload. When off, uploaded media is " +
                        "kept locally for 30 days.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                KeyValueLine("Local media storage used", Formatters.bytes(state.polled.mediaStorageUsedBytes))
            }
        }

        item {
            Panel(title = "Health Alerts", modifier = narrow) {
                StatusLine(
                    "Alert notifications",
                    if (system?.appNotificationsEnabled == true) StatusLevel.OK else StatusLevel.WARNING,
                    if (system?.appNotificationsEnabled == true) "Allowed" else "Blocked",
                    "Used only to warn you when background monitoring may be inactive.",
                )
                if (system?.appNotificationsEnabled != true) {
                    ButtonRow {
                        Button(onClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                SystemSettingsIntents.openAppDetails(context)
                            }
                        }) { Text("Allow alerts") }
                    }
                }
            }
        }

        item {
            Panel(title = "Diagnostics", modifier = narrow) {
                Text(
                    "Health of the listener, database, WorkManager and backend, plus troubleshooting for devices with " +
                        "aggressive background restrictions.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ButtonRow { Button(onClick = onOpenDiagnostics) { Text("Open Diagnostics") } }
            }
        }

        item {
            Panel(title = "About", modifier = narrow) {
                KeyValueLine("Application", "OKB WhatsApp Bridge", mono = false)
                KeyValueLine("Version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                KeyValueLine("Package", BuildConfig.APPLICATION_ID)
                Text(
                    "The bridge is designed for reliable background operation using Android-supported mechanisms " +
                        "(NotificationListenerService, Room, WorkManager). Android and device manufacturers can still " +
                        "restrict background apps; see Diagnostics for recommended settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

package com.okb.whatsappbridge.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.okb.whatsappbridge.domain.model.MonitoringState
import com.okb.whatsappbridge.ui.StatusPresentation

/** The prominent Background Monitoring control, with confirmation before pausing. */
@Composable
fun MonitoringToggleCard(
    enabled: Boolean,
    state: MonitoringState,
    lastNotificationAt: Long?,
    lastProcessedAt: Long?,
    lastUploadAt: Long?,
    lastFailureAt: Long?,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmPause by remember { mutableStateOf(false) }
    val presented = StatusPresentation.monitoring(state)
    Panel(title = "Background Monitoring", modifier = modifier, accent = presented.level.color()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                StatusIndicator(presented.level, presented.label, large = true)
                Spacer(Modifier.height(6.dp))
                Text(
                    presented.detail.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Switch(
                    checked = enabled,
                    onCheckedChange = { checked -> if (checked) onToggle(true) else confirmPause = true },
                    modifier = Modifier.semantics { contentDescription = "Background monitoring switch" },
                )
                Text(if (enabled) "ON" else "OFF", style = MaterialTheme.typography.labelMedium)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Monitors authorized WhatsApp and Viber groups while this app is closed, the screen is off or the phone is " +
                "locked. A small \"OKB Bridge is monitoring\" notification keeps it running. Force stop always stops it " +
                "until the app is opened again (an Android rule).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SectionDivider()
        KeyValueLine("Last WhatsApp notification", Formatters.time(lastNotificationAt))
        KeyValueLine("Last processed message", Formatters.time(lastProcessedAt))
        KeyValueLine("Last successful sync", Formatters.time(lastUploadAt))
        if (lastFailureAt != null && lastFailureAt > (lastUploadAt ?: 0)) {
            KeyValueLine("Last failed upload", Formatters.time(lastFailureAt))
        }
    }

    if (confirmPause) {
        ConfirmDialog(
            title = "Pause Background Monitoring?",
            text = "New WhatsApp messages will not be processed while monitoring is disabled.",
            confirmLabel = "Pause",
            onConfirm = {
                confirmPause = false
                onToggle(false)
            },
            onDismiss = { confirmPause = false },
        )
    }
}

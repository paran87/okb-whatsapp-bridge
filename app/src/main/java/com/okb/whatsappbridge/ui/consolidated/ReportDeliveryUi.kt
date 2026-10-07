package com.okb.whatsappbridge.ui.consolidated

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.okb.whatsappbridge.domain.model.BridgeSettings
import com.okb.whatsappbridge.domain.model.ConsolidatedDeliveryCounts
import com.okb.whatsappbridge.domain.model.ConsolidatedDeliveryStatus
import com.okb.whatsappbridge.domain.model.ConsolidatedReportDelivery
import com.okb.whatsappbridge.ui.StatusPresentation
import com.okb.whatsappbridge.ui.components.ButtonRow
import com.okb.whatsappbridge.ui.components.ConfirmDialog
import com.okb.whatsappbridge.ui.components.KeyValueLine
import com.okb.whatsappbridge.ui.components.Panel
import com.okb.whatsappbridge.ui.components.StatusLevel
import com.okb.whatsappbridge.ui.components.StatusLine
import com.okb.whatsappbridge.ui.components.color

/** Statuses that get a card on the Dashboard (SENT is shown in the counts only). */
fun ConsolidatedReportDelivery.needsAttention(now: Long): Boolean = when (status) {
    ConsolidatedDeliveryStatus.SENT -> false
    ConsolidatedDeliveryStatus.FAILED -> now - updatedAt < 24L * 60 * 60 * 1000
    else -> true
}

fun ConsolidatedDeliveryStatus.label(): Pair<StatusLevel, String> = when (this) {
    ConsolidatedDeliveryStatus.READY_TO_SEND -> StatusLevel.WARNING to "Waiting to download"
    ConsolidatedDeliveryStatus.DOWNLOADING -> StatusLevel.INFO to "Downloading"
    ConsolidatedDeliveryStatus.READY_FOR_WHATSAPP -> StatusLevel.OK to "Waiting for operator"
    ConsolidatedDeliveryStatus.OPENED_IN_WHATSAPP -> StatusLevel.INFO to "Opened in WhatsApp"
    ConsolidatedDeliveryStatus.SENT -> StatusLevel.OK to "Sent (confirmed)"
    ConsolidatedDeliveryStatus.FAILED -> StatusLevel.ERROR to "Failed"
}

/** Compact Dashboard status: both report groups, the backend and the delivery counts. */
@Composable
fun ReportGroupsStatusPanel(
    settings: BridgeSettings,
    counts: ConsolidatedDeliveryCounts,
    onCheckNow: () -> Unit,
    onOpenSettings: () -> Unit,
    busy: Boolean,
) {
    Panel(title = "Report Groups & Delivery") {
        StatusLine(
            "Source Group",
            if (settings.sourceGroupConfigured) StatusLevel.OK else StatusLevel.WARNING,
            if (settings.sourceGroupConfigured) "Configured" else "Not set",
            settings.sourceGroupName.ifBlank { "Using the Groups allowlist" },
        )
        StatusLine(
            "Destination Group",
            if (settings.destinationGroupConfigured) StatusLevel.OK else StatusLevel.WARNING,
            if (settings.destinationGroupConfigured) "Configured" else "Not set",
            settings.destinationGroupName.ifBlank { "Set it in Settings → WhatsApp Report Groups" },
        )
        val backend = StatusPresentation.backend(
            settings.backendConfigured, settings.lastBackendCheckOk, settings.lastBackendCheckAt,
            settings.lastBackendCheckMessage, settings.lastUploadSuccessAt, settings.lastUploadFailureAt,
        )
        StatusLine("Backend", backend.level, backend.label)
        // Label above the counts: side by side, the long counts line squeezed the label on phones.
        Text("PDFs (manual)", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        Text(
            "Pending ${counts.pending} · Ready ${counts.ready} · Sent ${counts.sent} · Failed ${counts.failed}",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(bottom = 2.dp),
        )
        ButtonRow {
            OutlinedButton(onClick = onOpenSettings) { Text("Groups") }
            OutlinedButton(onClick = onCheckNow, enabled = !busy && settings.backendConfigured) { Text("Check now") }
        }
    }
}

/**
 * Consolidated PDF (MANUAL): "PDF Ready" with "Send as PDF", the only manual action. WhatsApp's share screen
 * opens; the operator selects the destination group, presses Send and confirms here. Never marked sent by itself.
 */
@Composable
fun ConsolidatedReportCard(
    delivery: ConsolidatedReportDelivery,
    settings: BridgeSettings,
    onSend: () -> Unit,
    onConfirmSent: () -> Unit,
    onNotSent: () -> Unit,
    onRetry: () -> Unit,
    onRemove: () -> Unit = {},
) {
    var confirmRemove by remember { mutableStateOf(false) }
    val destination = delivery.destinationGroup?.takeIf { it.isNotBlank() } ?: settings.destinationGroupName.ifBlank { null }
    val source = delivery.sourceGroup?.takeIf { it.isNotBlank() } ?: settings.sourceGroupName.ifBlank { null }
    val (level, label) = delivery.status.label()
    val title = when {
        delivery.status == ConsolidatedDeliveryStatus.FAILED -> if (delivery.isTest) "CONSOLIDATED PDF · TEST" else "CONSOLIDATED PDF"
        delivery.isTest -> "PDF Ready · TEST"
        else -> "PDF Ready"
    }
    Panel(title = title, accent = level.color()) {
        KeyValueLine("Source", source ?: "—", mono = false)
        KeyValueLine("Destination", destination ?: "Not set", mono = false)
        // Long file names get their own line so the label never gets squeezed.
        Text("Report", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        Text(delivery.fileName, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 2.dp))
        StatusLine("Status", level, label, delivery.errorMessage)
        when (delivery.status) {
            ConsolidatedDeliveryStatus.READY_FOR_WHATSAPP -> {
                Button(onClick = onSend, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Send as PDF") }
                Hint(
                    if (destination != null) "WhatsApp share screen will open. Select “$destination” and press Send."
                    else "WhatsApp share screen will open. No destination group is set — select the correct group and press Send.",
                )
            }
            ConsolidatedDeliveryStatus.OPENED_IN_WHATSAPP -> {
                Hint("Did you send it${destination?.let { " to “$it”" } ?: ""} in WhatsApp? It is only marked sent when you confirm.")
                ButtonRow {
                    OutlinedButton(onClick = onNotSent) { Text("Not sent") }
                    Button(onClick = onConfirmSent) { Text("Yes, sent") }
                }
                OutlinedButton(onClick = onSend, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) { Text("Open WhatsApp again") }
            }
            ConsolidatedDeliveryStatus.READY_TO_SEND, ConsolidatedDeliveryStatus.DOWNLOADING -> {
                Hint("The PDF is downloaded automatically at the next check (about every 15 minutes) when online.")
                ButtonRow { OutlinedButton(onClick = onRetry) { Text("Try now") } }
            }
            ConsolidatedDeliveryStatus.FAILED ->
                Hint("Ask the Command Center to Resend this report (Settings → Automated WhatsApp reports → History).")
            ConsolidatedDeliveryStatus.SENT -> Unit
        }
        OutlinedButton(onClick = { confirmRemove = true }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Remove") }
    }
    if (confirmRemove) {
        ConfirmDialog(
            title = "Remove this PDF?",
            text = "${delivery.fileName} is removed from this phone and will not be sent from here. " +
                "The Command Center shows it as removed; Resend there brings it back.",
            confirmLabel = "Remove",
            onConfirm = {
                confirmRemove = false
                onRemove()
            },
            onDismiss = { confirmRemove = false },
        )
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp),
    )
}

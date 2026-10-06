package com.okb.whatsappbridge.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.CallMissedOutgoing
import androidx.compose.material.icons.automirrored.outlined.CallReceived
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.okb.whatsappbridge.ui.components.ButtonRow
import com.okb.whatsappbridge.ui.components.Panel
import com.okb.whatsappbridge.whatsapp.ReportGroups

/**
 * "WhatsApp Report Groups": two independent settings with different purposes. Reports are captured from the
 * SOURCE group; consolidated reports go to the DESTINATION group (TEXT automatically, PDF via the share screen).
 * The destination group is never used to capture reports, and the source group is never a destination.
 */
@Composable
fun ReportGroupsPanel(
    source: String,
    destination: String,
    onSourceChange: (String) -> Unit,
    onDestinationChange: (String) -> Unit,
    onSave: () -> Unit,
    busy: Boolean,
    modifier: Modifier = Modifier,
) {
    val problem = ReportGroups.validate(source, destination)
    Panel(title = "WhatsApp Report Groups", modifier = modifier) {
        OutlinedTextField(
            value = source,
            onValueChange = { onSourceChange(it.take(ReportGroups.MAX_LENGTH)) },
            label = { Text("Source Group") },
            placeholder = { Text("NMDEO FLOOD MONITORING") },
            leadingIcon = { Icon(Icons.AutoMirrored.Outlined.CallReceived, contentDescription = "Incoming") },
            supportingText = { Text("WhatsApp group where flood/activity reports are received.") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = destination,
            onValueChange = { onDestinationChange(it.take(ReportGroups.MAX_LENGTH)) },
            label = { Text("Destination Group") },
            placeholder = { Text("OKB COMMAND CENTER") },
            leadingIcon = { Icon(Icons.AutoMirrored.Outlined.CallMissedOutgoing, contentDescription = "Outgoing") },
            supportingText = { Text("WhatsApp group the consolidated TEXT report is sent to automatically (and the PDF manually).") },
            isError = problem != null,
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
        Text(
            problem ?: "Enter each name exactly as it appears in WhatsApp (case and extra spaces are ignored). " +
                "Messages from the destination group are never captured as reports. With no source group set, " +
                "the groups authorized on the Groups tab are captured.",
            style = MaterialTheme.typography.bodySmall,
            color = if (problem != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ButtonRow {
            Button(onClick = onSave, enabled = !busy && problem == null) { Text("Save groups") }
        }
    }
}

package com.okb.whatsappbridge.ui.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.okb.whatsappbridge.ui.StatusPresentation
import com.okb.whatsappbridge.ui.StatusUiState
import com.okb.whatsappbridge.ui.components.ButtonRow
import com.okb.whatsappbridge.ui.components.Formatters
import com.okb.whatsappbridge.ui.components.KeyValueLine
import com.okb.whatsappbridge.ui.components.MetricTile
import com.okb.whatsappbridge.ui.components.Panel
import com.okb.whatsappbridge.ui.components.SectionDivider
import com.okb.whatsappbridge.ui.components.StatusLevel
import com.okb.whatsappbridge.ui.components.StatusLine

@Composable
fun SyncScreen(
    state: StatusUiState,
    onSyncNow: () -> Unit,
    onRetryFailed: () -> Unit,
    onSetSyncPaused: (Boolean) -> Unit,
) {
    val counts = state.counts
    val settings = state.settings
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 160.dp),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { MetricTile("Pending", counts.pending.toString(), level = if (counts.pending > 0) StatusLevel.INFO else null) }
        item { MetricTile("Retrying", counts.retrying.toString(), level = if (counts.retrying > 0) StatusLevel.WARNING else null) }
        item { MetricTile("Failed", counts.failed.toString(), level = if (counts.failed > 0) StatusLevel.ERROR else null) }
        item { MetricTile("Uploaded", counts.uploaded.toString(), level = StatusLevel.OK) }

        item(span = { GridItemSpan(maxLineSpan) }) {
            Panel(title = "Synchronization") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Upload to backend", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Switch(checked = !settings.syncPaused, onCheckedChange = { onSetSyncPaused(!it) })
                }
                Text(
                    if (settings.syncPaused) {
                        "Paused: captured messages are kept locally and uploaded when synchronization is resumed."
                    } else {
                        "Messages are uploaded by WorkManager whenever a network connection is available, with " +
                            "exponential backoff on errors. A health check re-tries the queue every 15 minutes."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SectionDivider()
                val worker = StatusPresentation.syncWorker(state.uploadWorker, settings.syncPaused, counts.notUploaded)
                StatusLine("Upload worker", worker.level, worker.label, worker.detail)
                StatusLine(
                    "Periodic health check",
                    if (state.reconciliation.scheduled) StatusLevel.OK else StatusLevel.WARNING,
                    if (state.reconciliation.scheduled) "Scheduled" else "Not scheduled",
                    state.reconciliation.nextRunAt?.let { "Next run around ${Formatters.time(it)} (Android may defer it)" },
                )
                SectionDivider()
                KeyValueLine("Last successful upload", Formatters.time(settings.lastUploadSuccessAt))
                KeyValueLine("Last failed upload", Formatters.time(settings.lastUploadFailureAt))
                settings.lastUploadError?.let {
                    Text("Last error: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                KeyValueLine("Backend URL", settings.backendUrl.ifBlank { "Not configured" }, mono = false)
                ButtonRow {
                    OutlinedButton(onClick = onRetryFailed, enabled = counts.failed > 0) { Text("Retry failed") }
                    Button(onClick = onSyncNow, enabled = !settings.syncPaused && settings.backendConfigured) { Text("Sync now") }
                }
            }
        }
    }
}

package com.okb.whatsappbridge.ui.messages

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.okb.whatsappbridge.ui.components.ButtonRow
import com.okb.whatsappbridge.ui.components.Formatters
import com.okb.whatsappbridge.ui.components.Panel

private enum class BinConfirm { DELETE_SELECTED, EMPTY }

/**
 * Messages the operator deleted. They can be restored, deleted forever, or are deleted automatically
 * after the retention period. Nothing here touches copies already uploaded to the backend.
 */
@Composable
fun RecycleBinScreen(viewModel: RecycleBinViewModel) {
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf<BinConfirm?>(null) }
    val selecting = selected.isNotEmpty()
    val days = viewModel.retentionDays

    BackHandler(enabled = selecting) { viewModel.clearSelection() }

    confirm?.let { which ->
        val count = if (which == BinConfirm.EMPTY) messages.orEmpty().size else selected.size
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(if (which == BinConfirm.EMPTY) "Empty the Recycle Bin?" else "Delete $count message(s) forever?") },
            text = {
                Text(
                    "This permanently removes $count message(s) and any media files stored for them on this phone. " +
                        "It cannot be undone. Copies already uploaded to the backend are not affected.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    if (which == BinConfirm.EMPTY) viewModel.emptyBin() else viewModel.deleteSelectedForever()
                }) { Text("Delete forever", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }

    Column(Modifier.fillMaxSize()) {
        if (selecting) {
            SelectionBar(count = selected.size, onSelectAll = viewModel::selectAll, onCancel = viewModel::clearSelection) {
                Button(onClick = viewModel::restoreSelected) {
                    Icon(Icons.Filled.RestoreFromTrash, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Restore")
                }
                OutlinedButton(onClick = { confirm = BinConfirm.DELETE_SELECTED }) {
                    Icon(Icons.Filled.DeleteForever, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(6.dp))
                    Text("Delete forever", color = MaterialTheme.colorScheme.error)
                }
            }
        }
        val list = messages
        when {
            list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            list.isEmpty() -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    "The Recycle Bin is empty.\nLong-press messages in Messages to select and delete them. " +
                        "Deleted messages stay here for $days days before they are removed for good.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item {
                    Panel(title = "Recycle Bin", modifier = Modifier.widthIn(max = 900.dp)) {
                        Text(
                            "${list.size} deleted message(s). Tap or long-press to select, then restore or delete forever. " +
                                "Messages are deleted for good $days days after they were moved here. While here, they are " +
                                "not uploaded; copies already on the backend are not affected.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (!selecting) {
                            ButtonRow {
                                OutlinedButton(onClick = { confirm = BinConfirm.EMPTY }) {
                                    Icon(Icons.Filled.DeleteForever, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                                    Spacer(Modifier.width(6.dp))
                                    Text("Empty Recycle Bin", color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
                items(list, key = { it.id }) { message ->
                    val deletedAt = message.deletedAt
                    MessageCard(
                        message = message,
                        selected = message.id in selected,
                        onClick = { viewModel.toggleSelection(message.id) },
                        onLongClick = { viewModel.toggleSelection(message.id) },
                        footer = deletedAt?.let {
                            "Deleted ${Formatters.time(it)} · removed for good on ${Formatters.time(it + viewModel.retentionDays * DAY_MS)}"
                        },
                    )
                }
            }
        }
    }
}

private const val DAY_MS = 24L * 60 * 60 * 1000

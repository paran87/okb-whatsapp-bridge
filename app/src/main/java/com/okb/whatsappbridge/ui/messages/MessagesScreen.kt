package com.okb.whatsappbridge.ui.messages

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.okb.whatsappbridge.domain.model.BridgeMessage
import com.okb.whatsappbridge.domain.model.MediaType
import com.okb.whatsappbridge.domain.model.UploadStatus
import com.okb.whatsappbridge.ui.components.Formatters
import com.okb.whatsappbridge.ui.components.Panel
import com.okb.whatsappbridge.ui.components.StatusIndicator
import com.okb.whatsappbridge.ui.components.StatusLevel
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.sp
import com.okb.whatsappbridge.ui.components.color
import com.okb.whatsappbridge.ui.theme.MonoValue
import com.okb.whatsappbridge.source.SourcePlatform

@Composable
fun MessagesScreen(viewModel: MessagesViewModel, onOpenDetail: (String) -> Unit, onOpenRecycleBin: () -> Unit) {
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val binCount by viewModel.recycleBinCount.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    val selecting = selected.isNotEmpty()

    BackHandler(enabled = selecting) { viewModel.clearSelection() }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Move ${selected.size} message(s) to the Recycle Bin?") },
            text = {
                Text(
                    "They are kept in the Recycle Bin for 30 days and can be restored. Messages not yet uploaded " +
                        "are not uploaded while they are in the bin. Copies already uploaded to the backend are not deleted.",
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; viewModel.deleteSelected() }) { Text("Move to Recycle Bin") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }

    Column(Modifier.fillMaxSize()) {
        if (selecting) {
            SelectionBar(
                count = selected.size,
                onSelectAll = viewModel::selectAll,
                onCancel = viewModel::clearSelection,
            ) {
                Button(onClick = { confirmDelete = true }) {
                    Icon(Icons.Filled.Delete, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Delete")
                }
            }
        } else {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(selected = filter == null, onClick = { viewModel.setFilter(null) }, label = { Text("All") })
                UploadStatus.entries.forEach { status ->
                    FilterChip(
                        selected = filter == status,
                        onClick = { viewModel.setFilter(status) },
                        label = { Text(status.label()) },
                    )
                }
                OutlinedButton(onClick = onOpenRecycleBin) {
                    Icon(Icons.Filled.Delete, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (binCount > 0) "Recycle Bin ($binCount)" else "Recycle Bin")
                }
            }
        }
        val list = messages
        when {
            list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            list.isEmpty() -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    if (filter == null) {
                        "No messages captured yet.\nMessages from authorized WhatsApp and Viber groups appear here as soon as " +
                            "their notifications arrive – the app does not need to be open."
                    } else {
                        "No messages with status ${filter!!.label()}."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item {
                    Text(
                        "Showing the latest ${list.size} message(s) stored on this device · long-press to select and delete",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(list, key = { it.id }) { message ->
                    MessageCard(
                        message = message,
                        selected = message.id in selected,
                        onClick = { if (selecting) viewModel.toggleSelection(message.id) else onOpenDetail(message.id) },
                        onLongClick = { viewModel.toggleSelection(message.id) },
                    )
                }
            }
        }
    }
}

/** Header shown while messages are selected: count, select all, cancel, plus screen-specific actions. */
@Composable
internal fun SelectionBar(count: Int, onSelectAll: () -> Unit, onCancel: () -> Unit, actions: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onCancel) { Icon(Icons.Filled.Close, contentDescription = "Cancel selection") }
        Text("$count selected", style = MaterialTheme.typography.titleSmall)
        TextButton(onClick = onSelectAll) { Text("Select all") }
        actions()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MessageCard(
    message: BridgeMessage,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    footer: String? = null,
) {
    val highlight = if (selected) {
        Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
    } else {
        Modifier
    }
    Panel(
        title = message.groupName ?: "Unknown group",
        modifier = Modifier.widthIn(max = 900.dp).then(highlight).combinedClickable(onClick = onClick, onLongClick = onLongClick),
        trailing = {
            if (selected) {
                Icon(Icons.Filled.CheckCircle, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary)
            } else {
                StatusIndicator(message.uploadStatus.level(), message.uploadStatus.label())
            }
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                message.senderName ?: "Unknown sender",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(Formatters.time(message.timestamp), style = MonoValue, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(4.dp))
        if (!message.messageText.isNullOrBlank()) {
            Text(message.messageText!!, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(6.dp))
        }
        message.media?.let { m ->
            MediaChip(com.okb.whatsappbridge.ui.MediaPresentation.chipLabel(m), com.okb.whatsappbridge.ui.MediaPresentation.chipLevel(m), m.fileSizeBytes)
            Spacer(Modifier.height(6.dp))
        }
        val meta = buildList {
            if (message.media == null) add(message.mediaType.name)
            add(SourcePlatform.displayNameFor(message.packageName))
            if (message.attemptCount > 0) add("${message.attemptCount} attempt(s)")
            message.serverId?.let { add("server id $it") }
        }
        Text(meta.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        message.lastError?.let {
            Text("Last error: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        footer?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
        }
    }
}

@Composable
private fun MediaChip(label: String, level: StatusLevel, sizeBytes: Long?) {
    val color = level.color()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(color.copy(alpha = 0.14f))
                .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 3.dp),
        ) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = color, fontSize = 11.sp)
        }
        if (sizeBytes != null) {
            Spacer(Modifier.width(8.dp))
            Text(Formatters.bytes(sizeBytes), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

fun UploadStatus.label(): String = when (this) {
    UploadStatus.PENDING_UPLOAD -> "Pending"
    UploadStatus.UPLOADING -> "Uploading"
    UploadStatus.RETRYING -> "Retrying"
    UploadStatus.FAILED -> "Failed"
    UploadStatus.UPLOADED -> "Uploaded"
}

fun UploadStatus.level(): StatusLevel = when (this) {
    UploadStatus.PENDING_UPLOAD -> StatusLevel.INFO
    UploadStatus.UPLOADING -> StatusLevel.INFO
    UploadStatus.RETRYING -> StatusLevel.WARNING
    UploadStatus.FAILED -> StatusLevel.ERROR
    UploadStatus.UPLOADED -> StatusLevel.OK
}

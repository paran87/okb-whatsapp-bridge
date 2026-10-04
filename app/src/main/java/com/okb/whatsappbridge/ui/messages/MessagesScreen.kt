package com.okb.whatsappbridge.ui.messages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import com.okb.whatsappbridge.whatsapp.WhatsAppPackages

@Composable
fun MessagesScreen(viewModel: MessagesViewModel, onOpenDetail: (String) -> Unit) {
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(selected = filter == null, onClick = { viewModel.setFilter(null) }, label = { Text("All") })
            UploadStatus.entries.forEach { status ->
                FilterChip(
                    selected = filter == status,
                    onClick = { viewModel.setFilter(status) },
                    label = { Text(status.label()) },
                )
            }
        }
        val list = messages
        when {
            list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            list.isEmpty() -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    if (filter == null) {
                        "No messages captured yet.\nMessages from authorized WhatsApp groups appear here as soon as " +
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
                        "Showing the latest ${list.size} message(s) stored on this device",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(list, key = { it.id }) { MessageCard(it, onOpenDetail) }
            }
        }
    }
}

@Composable
private fun MessageCard(message: BridgeMessage, onOpenDetail: (String) -> Unit) {
    Panel(
        title = message.groupName ?: "Unknown group",
        modifier = Modifier.widthIn(max = 900.dp).clickable { onOpenDetail(message.id) },
        trailing = { StatusIndicator(message.uploadStatus.level(), message.uploadStatus.label()) },
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
            add(WhatsAppPackages.displayName(message.packageName))
            if (message.attemptCount > 0) add("${message.attemptCount} attempt(s)")
            message.serverId?.let { add("server id $it") }
        }
        Text(meta.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        message.lastError?.let {
            Text("Last error: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
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

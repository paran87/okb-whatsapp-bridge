package com.okb.whatsappbridge.ui.messages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.okb.whatsappbridge.domain.model.MediaAttachment
import com.okb.whatsappbridge.domain.model.MediaType
import com.okb.whatsappbridge.ui.MediaPresentation
import com.okb.whatsappbridge.ui.components.Formatters
import com.okb.whatsappbridge.ui.components.KeyValueLine
import com.okb.whatsappbridge.ui.components.Panel
import com.okb.whatsappbridge.ui.components.SectionDivider
import com.okb.whatsappbridge.ui.components.StatusLine
import com.okb.whatsappbridge.whatsapp.WhatsAppPackages

@Composable
fun MediaDetailScreen(viewModel: MediaDetailViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val narrow = Modifier.widthIn(max = 900.dp)

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        val message = state.message
        if (message == null) {
            item { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Message not found.") } }
            return@LazyColumn
        }
        item {
            Panel(title = message.groupName ?: "Message", modifier = narrow) {
                KeyValueLine("Group", message.groupName ?: "—", mono = false)
                KeyValueLine("Sender", message.senderName ?: "—", mono = false)
                KeyValueLine("Timestamp", Formatters.time(message.timestamp))
                KeyValueLine("Source", WhatsAppPackages.displayName(message.packageName), mono = false)
                KeyValueLine("Upload", message.uploadStatus.label(), mono = false)
                SectionDivider()
                Text("Caption / text", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(message.messageText?.ifBlank { "(no text)" } ?: "(no text)", style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (state.media.isEmpty()) {
            item {
                Panel(title = "Media", modifier = narrow) {
                    Text("No media attachment for this message.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        items(state.media, key = { it.id }) { MediaCard(it, narrow) }
    }
}

@Composable
private fun MediaCard(media: MediaAttachment, modifier: Modifier) {
    val acq = MediaPresentation.acquisition(media.acquisitionStatus)
    Panel(title = "Media · ${media.mediaType.name}", modifier = modifier) {
        StatusLine("Acquisition", acq.level, acq.label, media.statusDetail)
        val up = MediaPresentation.upload(media.uploadStatus)
        StatusLine("Upload", up.level, up.label)
        SectionDivider()
        KeyValueLine("Media type", media.mediaType.name)
        KeyValueLine("MIME type", media.mimeType ?: "—", mono = false)
        media.originalFileName?.let { KeyValueLine("File name", it, mono = false) }
        KeyValueLine("File size", Formatters.bytes(media.fileSizeBytes))
        KeyValueLine("SHA-256", media.sha256?.take(16)?.plus("…") ?: "—")
        media.r2ObjectKey?.let { KeyValueLine("R2 object key", it, mono = false) }
        media.r2ETag?.let { KeyValueLine("R2 ETag", it) }
        KeyValueLine("Captured", Formatters.time(media.createdAt))
        media.uploadedAt?.let { KeyValueLine("Uploaded", Formatters.time(it)) }
        if (media.uploadAttempts > 0) KeyValueLine("Upload attempts", media.uploadAttempts.toString())
        media.lastError?.let {
            Text("Last error: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        // A real preview is only shown when an accessible local file exists; no fake previews.
        if (!media.hasLocalFile && media.mediaType != MediaType.TEXT) {
            SectionDivider()
            Text(
                "No local media file is available to preview. " + (media.statusDetail ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

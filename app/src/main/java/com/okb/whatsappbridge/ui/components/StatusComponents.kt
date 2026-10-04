package com.okb.whatsappbridge.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.okb.whatsappbridge.ui.theme.LocalStatusColors
import com.okb.whatsappbridge.ui.theme.MonoFamily
import com.okb.whatsappbridge.ui.theme.MonoValue

enum class StatusLevel { OK, WARNING, ERROR, NEUTRAL, INFO }

@Composable
fun StatusLevel.color(): Color {
    val c = LocalStatusColors.current
    return when (this) {
        StatusLevel.OK -> c.ok
        StatusLevel.WARNING -> c.warning
        StatusLevel.ERROR -> c.error
        StatusLevel.NEUTRAL -> c.neutral
        StatusLevel.INFO -> c.info
    }
}

/** "● ACTIVE" / "○ PAUSED" indicator. Hollow dot for neutral/inactive states. */
@Composable
fun StatusIndicator(level: StatusLevel, label: String, modifier: Modifier = Modifier, large: Boolean = false) {
    val color = level.color()
    val dotSize = if (large) 14.dp else 10.dp
    Row(
        modifier = modifier.semantics(mergeDescendants = true) { contentDescription = "Status $label" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val dot = Modifier.size(dotSize)
        if (level == StatusLevel.NEUTRAL) {
            Box(dot.border(2.dp, color, CircleShape))
        } else {
            Box(dot.background(color, CircleShape))
        }
        Spacer(Modifier.width(if (large) 10.dp else 8.dp))
        Text(
            text = label.uppercase(),
            color = color,
            style = if (large) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.labelLarge,
            fontFamily = MonoFamily,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** Compact bordered panel with an uppercase "overline" title, used throughout the dashboard. */
@Composable
fun Panel(
    title: String,
    modifier: Modifier = Modifier,
    accent: Color? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, accent ?: MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title.uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                trailing?.invoke(this)
            }
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

/** One labelled status line inside a panel. */
@Composable
fun StatusLine(label: String, level: StatusLevel, status: String, detail: String? = null) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            StatusIndicator(level, status)
        }
        if (!detail.isNullOrBlank()) {
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
fun KeyValueLine(key: String, value: String, mono: Boolean = true) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            key,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            style = if (mono) MonoValue else MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun MetricTile(label: String, value: String, modifier: Modifier = Modifier, level: StatusLevel? = null, caption: String? = null) {
    Panel(title = label, modifier = modifier) {
        Text(
            value,
            style = MaterialTheme.typography.displaySmall,
            fontFamily = MonoFamily,
            color = level?.color() ?: MaterialTheme.colorScheme.onSurface,
        )
        if (caption != null) {
            Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Prominent warning with an action, e.g. "⚠ Background monitoring may be inactive". */
@Composable
fun WarningBanner(
    text: String,
    actionLabel: String?,
    onAction: (() -> Unit)?,
    level: StatusLevel = StatusLevel.WARNING,
    extraActions: List<Pair<String, () -> Unit>> = emptyList(),
) {
    val color = level.color()
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.12f)),
        border = BorderStroke(1.dp, color.copy(alpha = 0.6f)),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = color)
                Spacer(Modifier.width(12.dp))
                Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            }
            if ((actionLabel != null && onAction != null) || extraActions.isNotEmpty()) {
                ButtonRow {
                    if (actionLabel != null && onAction != null) OutlinedButton(onClick = onAction) { Text(actionLabel) }
                    extraActions.forEach { (label, action) -> OutlinedButton(onClick = action) { Text(label) } }
                }
            }
        }
    }
}

@Composable
fun SectionDivider() {
    HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
fun ButtonRow(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

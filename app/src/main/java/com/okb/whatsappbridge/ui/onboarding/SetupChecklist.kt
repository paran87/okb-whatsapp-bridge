package com.okb.whatsappbridge.ui.onboarding

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.okb.whatsappbridge.ui.components.Panel
import com.okb.whatsappbridge.ui.components.StatusLevel
import com.okb.whatsappbridge.ui.components.color

data class SetupStep(
    val title: String,
    val done: Boolean,
    val actionLabel: String? = null,
    val onAction: (() -> Unit)? = null,
)

/** First-run checklist shown on the dashboard until every step is complete. */
@Composable
fun SetupChecklist(steps: List<SetupStep>, modifier: Modifier = Modifier) {
    val remaining = steps.count { !it.done }
    Panel(title = "Setup · $remaining step(s) remaining", modifier = modifier, accent = StatusLevel.WARNING.color()) {
        steps.forEachIndexed { index, step ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (step.done) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                    contentDescription = if (step.done) "Done" else "To do",
                    tint = if (step.done) StatusLevel.OK.color() else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("${index + 1}. ${step.title}", style = MaterialTheme.typography.bodyMedium)
                }
                if (!step.done && step.actionLabel != null && step.onAction != null) {
                    TextButton(onClick = step.onAction) { Text(step.actionLabel) }
                }
            }
        }
    }
}

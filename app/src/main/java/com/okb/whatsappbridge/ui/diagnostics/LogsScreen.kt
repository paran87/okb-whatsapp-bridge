package com.okb.whatsappbridge.ui.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.okb.whatsappbridge.domain.repository.LogRepository
import com.okb.whatsappbridge.ui.components.Formatters
import com.okb.whatsappbridge.ui.components.StatusLevel
import com.okb.whatsappbridge.ui.components.color
import com.okb.whatsappbridge.ui.theme.MonoFamily
import kotlinx.coroutines.launch

@Composable
fun LogsScreen(logs: LogRepository) {
    val entries by logs.observeRecent(500).collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Diagnostic events (no message content or credentials are logged)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { scope.launch { logs.clear() } }) { Text("Clear") }
        }
        val list = entries.orEmpty()
        if (entries != null && list.isEmpty()) {
            Text("No events recorded yet.", modifier = Modifier.padding(16.dp))
        }
        LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(list, key = { it.id }) { entry ->
                val level = when (entry.level) {
                    "ERROR" -> StatusLevel.ERROR
                    "WARN" -> StatusLevel.WARNING
                    else -> StatusLevel.INFO
                }
                Column {
                    Text(
                        "${Formatters.time(entry.timestamp)}  ${entry.level}  ${entry.tag}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = MonoFamily,
                        color = level.color(),
                    )
                    Text(entry.message, style = MaterialTheme.typography.bodySmall, fontFamily = MonoFamily)
                    HorizontalDivider(Modifier.padding(top = 6.dp), color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

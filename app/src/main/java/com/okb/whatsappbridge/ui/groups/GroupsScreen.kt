package com.okb.whatsappbridge.ui.groups

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.okb.whatsappbridge.domain.model.MonitoredGroup
import com.okb.whatsappbridge.ui.components.ConfirmDialog
import com.okb.whatsappbridge.ui.components.Formatters
import com.okb.whatsappbridge.ui.components.Panel

@Composable
fun GroupsScreen(viewModel: GroupsViewModel) {
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    var newName by rememberSaveable { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<MonitoredGroup?>(null) }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            Panel(title = "Authorize a WhatsApp group", modifier = Modifier.widthIn(max = 900.dp)) {
                Text(
                    "Only groups explicitly authorized here are captured and uploaded. Enter the group name exactly " +
                        "as it appears in WhatsApp (case and extra spaces are ignored). Groups the bridge has seen in " +
                        "notifications are listed below unchecked – tick them to authorize.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    val submit = {
                        if (newName.isNotBlank()) {
                            viewModel.add(newName)
                            newName = ""
                        }
                    }
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("Group name") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { submit() }),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = submit, enabled = newName.isNotBlank()) { Text("Add") }
                }
            }
        }

        val list = groups.orEmpty()
        item {
            Text(
                "MONITORED GROUPS · ${list.count { it.authorized }} authorized of ${list.size}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (groups != null && list.isEmpty()) {
            item {
                Text(
                    "No groups yet. Add the groups this bridge is allowed to monitor.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(list, key = { it.id }) { group ->
            Row(
                Modifier.widthIn(max = 900.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = group.authorized, onCheckedChange = { viewModel.setAuthorized(group, it) })
                Column(Modifier.weight(1f)) {
                    Text(group.name, style = MaterialTheme.typography.bodyLarge)
                    val detail = buildList {
                        add(if (group.authorized) "Authorized" else "Not monitored")
                        if (group.discoveredAutomatically) add("seen in notifications")
                        add("last seen ${Formatters.time(group.lastSeenAt)}")
                    }
                    Text(
                        detail.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { pendingDelete = group }) {
                    Icon(Icons.Outlined.Delete, contentDescription = "Remove ${group.name}")
                }
            }
        }
    }

    pendingDelete?.let { group ->
        ConfirmDialog(
            title = "Remove group?",
            text = "\"${group.name}\" will no longer be monitored. Messages already captured are kept.",
            confirmLabel = "Remove",
            onConfirm = {
                viewModel.delete(group)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

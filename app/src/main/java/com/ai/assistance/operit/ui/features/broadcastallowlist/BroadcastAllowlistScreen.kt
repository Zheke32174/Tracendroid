package com.ai.assistance.operit.ui.features.broadcastallowlist

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.integrations.intent.BroadcastSenderAllowlist
import com.ai.assistance.operit.integrations.intent.ExternalChatReceiver
import com.ai.assistance.operit.integrations.tasker.WorkflowTaskerReceiver

/**
 * Settings screen for the broadcast sender allowlist (§ 4.1 follow-up).
 *
 * Shows one section per exported cross-app receiver — currently ExternalChat and
 * WorkflowTasker. Each section lists the currently-allowed package names with a per-row
 * delete button, plus an input row to add a new package.
 *
 * No "allow all" affordance and no wildcard support — adding a sender is an intentional
 * per-package decision.
 */
@Composable
fun BroadcastAllowlistScreen() {
    val context = LocalContext.current
    val allowlist = remember { BroadcastSenderAllowlist(context.applicationContext) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Spacer(modifier = Modifier.height(16.dp))
            Header()
        }
        item {
            AllowlistSection(
                allowlist = allowlist,
                label = ExternalChatReceiver.ALLOWLIST_LABEL,
                title = "External chat (EXTERNAL_CHAT broadcast)",
                description = "Apps allowed to start a chat session via broadcast. " +
                    "Default empty — every external sender is refused.",
                placeholderPackage = "com.example.myapp",
            )
        }
        item {
            AllowlistSection(
                allowlist = allowlist,
                label = WorkflowTaskerReceiver.ALLOWLIST_LABEL,
                title = "Workflow Tasker (TRIGGER_WORKFLOW broadcast)",
                description = "Apps allowed to fire workflow automation. Tasker " +
                    "packages are seeded on first launch; remove or add freely.",
                placeholderPackage = "net.dinglisch.android.taskerm",
            )
        }
        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}

@Composable
private fun Header() {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Broadcast sender allowlist (§ 4.1)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Cross-app receivers reject every sender by default. Add a package " +
                "name to allow it. The check uses intent.package on incoming broadcasts.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AllowlistSection(
    allowlist: BroadcastSenderAllowlist,
    label: String,
    title: String,
    description: String,
    placeholderPackage: String,
) {
    val entries by allowlist.observe(label).collectAsState()
    var newEntry by rememberSaveable(label) { mutableStateOf("") }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (entries.isEmpty()) {
                Text(
                    "No senders allowed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (pkg in entries.sorted()) {
                        EntryRow(
                            pkg = pkg,
                            onRemove = { allowlist.remove(label, pkg) }
                        )
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newEntry,
                    onValueChange = { newEntry = it.trim() },
                    placeholder = { Text(placeholderPackage, fontFamily = FontFamily.Monospace) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    enabled = newEntry.isNotBlank(),
                    onClick = {
                        allowlist.add(label, newEntry)
                        newEntry = ""
                    },
                ) { Text("Add") }
            }
        }
    }
}

@Composable
private fun EntryRow(pkg: String, onRemove: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            pkg,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onRemove) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = "Remove $pkg",
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

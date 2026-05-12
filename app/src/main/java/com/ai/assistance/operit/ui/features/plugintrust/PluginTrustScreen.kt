package com.ai.assistance.operit.ui.features.plugintrust

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.core.plugintrust.PluginPublisherTofuStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Plugin trust settings (§ 4.3, AUDIT_PLAN § 1.1).
 *
 * Lists the TOFU records the device has recorded for installed plugins. Each row shows:
 *  - the pluginId
 *  - the publisher's display name (verbatim from the manifest at first install)
 *  - the SHA-256 fingerprint of the publisher's Ed25519 key
 *  - when the record was created
 *
 * Each row has a "Forget" action. Forgetting an entry means the next install of that
 * pluginId is treated as a fresh first-install — even from the same publisher, the user
 * sees a fresh TOFU prompt. Capability grants from JsPluginGate are *not* automatically
 * cleared here; revoke those separately from the Plugin & AI gate screen if desired.
 */
@Composable
fun PluginTrustScreen() {
    val context = LocalContext.current
    val store = remember { PluginPublisherTofuStore(context.applicationContext) }
    val records by store.records.collectAsState()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Spacer(modifier = Modifier.height(16.dp))
            Header()
        }
        if (records.isEmpty()) {
            item { EmptyState() }
        } else {
            items(records, key = { it.pluginId }) { record ->
                RecordCard(
                    record = record,
                    onForget = { store.forget(record.pluginId) },
                )
            }
        }
        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}

@Composable
private fun Header() {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.VerifiedUser,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Plugin trust (§ 4.3)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Trust-on-first-use records. Each plugin is pinned to the publisher " +
                "key fingerprint you accepted on first install. Updates that present a " +
                "different fingerprint are refused.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EmptyState() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                "No plugins installed yet.",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Once you install a signed plugin, the TOFU prompt records its publisher " +
                    "key here. See docs/TOOLPKG_MANIFEST.md for the manifest format.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RecordCard(
    record: PluginPublisherTofuStore.Record,
    onForget: () -> Unit,
) {
    var confirming by remember(record.pluginId) { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = record.pluginId,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Publisher: ${record.publisherName.ifBlank { "<unnamed>" }}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = "Fingerprint: ${record.publisherKeyFingerprint}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Recorded: ${formatTimestamp(record.recordedAtMillis)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { confirming = true },
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Forget")
                }
            }
        }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = {
                Text("Forget trust for ${record.pluginId}?", fontWeight = FontWeight.SemiBold)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "The next install of this pluginId — including from the same " +
                            "publisher — will show a fresh trust-on-first-use prompt.",
                    )
                    Text(
                        "Capability grants in the Plugin & AI gate screen are not changed " +
                            "by this action. Revoke them separately if you want a clean " +
                            "slate.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onForget()
                        confirming = false
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("Forget") }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text("Cancel") }
            },
        )
    }
}

private fun formatTimestamp(millis: Long): String {
    if (millis <= 0L) return "—"
    val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT)
    return fmt.format(Date(millis))
}

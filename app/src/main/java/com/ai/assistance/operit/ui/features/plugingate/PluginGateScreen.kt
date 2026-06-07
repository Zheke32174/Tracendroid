package com.ai.assistance.operit.ui.features.plugingate

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.core.tools.AiToolGate
import com.ai.assistance.operit.core.tools.javascript.JsCapabilityClass
import com.ai.assistance.operit.core.tools.javascript.JsPluginGate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * § 4.2 plugin gate settings screen.
 *
 * Two-section layout:
 *  - "Audit" lists recent JS-originated tool calls. Each entry shows the (plugin,
 *    capability) pair and the decision the gate made. The user can promote a denied
 *    pair to GRANTED, or reverse a granted pair to DENIED / forget it.
 *  - "Active grants" lists every (plugin, capability) the user has explicitly decided
 *    on. Each row has the same toggle affordances.
 *
 * No "approve all" button. Per docs/SECURITY.md the user grants per (plugin, capability)
 * intentionally; bulk-approve would defeat the purpose of the gate.
 */
@Composable
fun PluginGateScreen() {
    val grants by JsPluginGate.grantsFlow.collectAsState()
    val audit by JsPluginGate.auditFlow.collectAsState()

    val recentDistinct = remember(audit) {
        // Deduplicate the audit ring to one entry per (pluginId, capability), keeping the
        // most recent decision. Shown newest-first.
        val seen = mutableSetOf<Pair<String, JsCapabilityClass>>()
        audit
            .asReversed()
            .filter { it.pluginId != null }
            .filter { event ->
                val key = (event.pluginId ?: return@filter false) to event.capability
                seen.add(key)
            }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Spacer(modifier = Modifier.height(16.dp))
            Header(
                title = "Plugin & AI gate (§ 4.2)",
                subtitle = "Default-deny per (caller × capability). Grants apply to JS plugins; AI uses the synthetic 'ai:default' id.",
            )
        }

        item { AiGateEnforceCard() }

        item {
            SectionTitle("Active grants")
            if (grants.isEmpty()) {
                EmptyHint(
                    "No grants yet. Plugins start with zero capabilities. " +
                        "Use the Audit list below to grant capabilities a plugin actually asked for."
                )
            }
        }
        items(grants.entries.toList(), key = { "g_${it.key.first}_${it.key.second}" }) { entry ->
            GrantRow(
                pluginId = entry.key.first,
                capability = entry.key.second,
                state = entry.value,
            )
        }

        item {
            SectionTitle("Recent attempts (audit)")
            if (recentDistinct.isEmpty()) {
                EmptyHint("No JS-originated tool calls in this session yet.")
            }
        }
        items(recentDistinct, key = { "a_${it.pluginId}_${it.capability}_${it.timestamp}" }) { event ->
            AuditRow(event = event)
        }

        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}

@Composable
private fun AiGateEnforceCard() {
    var enforce by remember { mutableStateOf(AiToolGate.enforce) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("AI-side gate enforcement", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (enforce) "AI tool calls are blocked unless 'ai:default' has the matching capability grant."
                        else "AI tool calls dispatch as before; audit still records every call.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = enforce,
                    onCheckedChange = { newValue ->
                        AiToolGate.enforce = newValue
                        enforce = newValue
                    },
                )
            }
        }
    }
}

@Composable
private fun Header(title: String, subtitle: String) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

@Composable
private fun GrantRow(
    pluginId: String,
    capability: JsCapabilityClass,
    state: JsPluginGate.GateState,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = pluginId,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = capability.name,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                StateChip(state)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { JsPluginGate.grant(pluginId, capability) },
                    enabled = state != JsPluginGate.GateState.GRANTED,
                ) { Text("Grant") }
                OutlinedButton(
                    onClick = { JsPluginGate.deny(pluginId, capability) },
                    enabled = state != JsPluginGate.GateState.DENIED,
                ) { Text("Deny") }
                TextButton(
                    onClick = { JsPluginGate.forget(pluginId, capability) },
                ) { Text("Forget") }
            }
        }
    }
}

@Composable
private fun AuditRow(event: JsPluginGate.AuditEvent) {
    val pluginId = event.pluginId ?: return
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = pluginId,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                StateChip(event.decision)
            }
            Text(
                text = "${event.capability.name}  ·  ${event.toolType}:${event.toolName}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = formatTimestamp(event.timestamp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { JsPluginGate.grant(pluginId, event.capability) },
                ) { Text("Grant") }
                OutlinedButton(
                    onClick = { JsPluginGate.deny(pluginId, event.capability) },
                ) { Text("Deny") }
            }
        }
    }
}

@Composable
private fun StateChip(state: JsPluginGate.GateState) {
    val (label, color, icon) = when (state) {
        JsPluginGate.GateState.GRANTED ->
            Triple("granted", MaterialTheme.colorScheme.primary, Icons.Default.Verified)
        JsPluginGate.GateState.DENIED ->
            Triple("denied", MaterialTheme.colorScheme.error, Icons.Default.Block)
        JsPluginGate.GateState.UNSET ->
            Triple("unset", MaterialTheme.colorScheme.outline, Icons.Default.Clear)
    }
    AssistChip(
        onClick = {},
        label = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null, tint = color) },
        colors = AssistChipDefaults.assistChipColors(
            labelColor = color,
            leadingIconContentColor = color,
            containerColor = Color.Transparent,
        ),
    )
}

private fun formatTimestamp(millis: Long): String {
    val fmt = SimpleDateFormat("HH:mm:ss", Locale.ROOT)
    return fmt.format(Date(millis))
}

package com.ai.assistance.operit.ui.features.decline

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.ai.assistance.operit.core.agent.decline.AgentDecline
import com.ai.assistance.operit.core.agent.decline.DeclineClass
import com.ai.assistance.operit.core.agent.decline.DeclineRegistry

/**
 * § 4.13 — surfaces AI declines as a first-class outcome.
 *
 * Mounted at the OperitApp shell. Observes [DeclineRegistry.active] and shows a Material
 * 3 dialog when a decline is unresolved. Three actions:
 *   - Abandon  — user drops the action; nothing further happens.
 *   - Rephrase — user wants to rework the request. The dialog dismisses; the chat layer
 *                is responsible for any rephrase UI.
 *   - Re-prompt — user wants a fresh turn against the same request. The dialog dismisses
 *                 and the chat layer issues a new turn.
 *
 * The dialog cannot be dismissed by tapping outside. Per `SECURITY.md` principle 8 the
 * decline is a real outcome, not a flash of a banner the user can ignore.
 */
@Composable
fun AgentDeclineOverlay() {
    val current by DeclineRegistry.active.collectAsState()
    val decline = current ?: return

    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
        icon = {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        title = { Text(titleFor(decline.classification), fontWeight = FontWeight.SemiBold) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Class: ${decline.classification.name}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = decline.reason,
                    style = MaterialTheme.typography.bodyMedium,
                )
                val alts = decline.suggestedAlternatives.orEmpty().filter { it.isNotBlank() }
                if (alts.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Suggested alternatives (from the AI):",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    for (alt in alts) {
                        Text(
                            "• $alt",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "No automatic retry. Choosing a next move below is on you.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { DeclineRegistry.acknowledgeReprompt() }) { Text("Re-prompt") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { DeclineRegistry.acknowledgeRephrase() }) { Text("Rephrase") }
                Spacer(modifier = Modifier.width(4.dp))
                TextButton(onClick = { DeclineRegistry.acknowledgeAbandon() }) { Text("Abandon") }
            }
        },
    )
}

private fun titleFor(classification: DeclineClass): String = when (classification) {
    DeclineClass.CapabilityRefusal -> "AI declined: capability"
    DeclineClass.SafetyRefusal -> "AI declined: safety"
    DeclineClass.NeedsClarification -> "AI needs clarification"
    DeclineClass.ContextLimit -> "AI declined: context limit"
    DeclineClass.Other -> "AI declined"
}

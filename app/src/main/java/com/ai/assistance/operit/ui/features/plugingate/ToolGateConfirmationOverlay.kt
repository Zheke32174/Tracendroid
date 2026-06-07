package com.ai.assistance.operit.ui.features.plugingate

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.ai.assistance.operit.core.tools.AiToolGate
import com.ai.assistance.operit.core.tools.javascript.JsPluginGate

/**
 * Modal overlay that surfaces pending tool-gate confirmations one at a time (§ 4.2).
 *
 * Mounted once at the OperitApp shell. Observes [JsPluginGate.pendingFlow] and shows a
 * Material 3 dialog for the oldest pending request. The user picks Grant / Deny / Later.
 *  - Grant: records GateState.GRANTED for that (caller, capability). Future calls pass
 *    silently. The denied call that triggered this can be retried by the agent loop.
 *  - Deny: records GateState.DENIED. Subsequent calls return the standard deny error and
 *    no longer surface as pending — the user said no.
 *  - Later: dismisses the current pending entry without recording a decision. The next
 *    same-(caller, capability) call recreates it.
 *
 * The dialog cannot be dismissed by tapping outside; the user has to make one of the
 * three choices. This prevents a "swipe away to bypass" workaround.
 */
@Composable
fun ToolGateConfirmationOverlay() {
    val pending by JsPluginGate.pendingFlow.collectAsState()
    val current = pending.minByOrNull { it.firstSeenAtMillis } ?: return

    val isAi = current.pluginId == AiToolGate.AI_PLUGIN_ID
    val callerLabel = if (isAi) "AI assistant" else "Plugin '${current.pluginId}'"

    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
        title = {
            Text(
                text = "Allow $callerLabel to use ${current.capability.name}?",
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "It just tried: ${current.toolType}:${current.toolName}",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text =
                    "Granting applies to every future ${current.capability.name} call " +
                        "from $callerLabel in this app install. You can revisit grants " +
                        "in the Plugin & AI gate screen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text =
                    "Denying remembers the decision and silently blocks future calls of " +
                        "the same kind until you forget the decision.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    JsPluginGate.grant(current.pluginId, current.capability)
                }
            ) { Text("Grant") }
        },
        dismissButton = {
            Row {
                TextButton(
                    onClick = {
                        JsPluginGate.deny(current.pluginId, current.capability)
                    }
                ) { Text("Deny") }
                Spacer(modifier = Modifier.width(4.dp))
                TextButton(
                    onClick = {
                        JsPluginGate.dismissPending(current.pluginId, current.capability)
                    }
                ) { Text("Later") }
            }
        },
    )
}

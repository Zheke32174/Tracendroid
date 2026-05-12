package com.ai.assistance.operit.ui.features.plugintrust

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.ai.assistance.operit.core.plugintrust.PluginInstallTofuRegistry

/**
 * Modal TOFU prompt for plugin install (§ 4.3 / AUDIT_PLAN § 1.1).
 *
 * Mounted at the OperitApp shell alongside the other security overlays. Observes
 * [PluginInstallTofuRegistry.active] and renders a non-dismissable Material 3 dialog
 * when a [PluginTrustChecker.Decision.NewPublisher] is awaiting a user decision.
 *
 * Two actions:
 *   - "Trust this publisher" → approves; the install flow proceeds.
 *   - "Refuse" → rejects; the install flow aborts.
 *
 * No third option, no "remind me later" — install is a synchronous user action.
 */
@Composable
fun PluginTofuPromptOverlay() {
    val pending by PluginInstallTofuRegistry.active.collectAsState()
    val current = pending ?: return
    val decision = current.decision

    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
        icon = {
            Icon(
                imageVector = Icons.Default.VerifiedUser,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        title = {
            Text(
                text = "Trust this publisher?",
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Plugin ID",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = decision.pluginId,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                )

                Text(
                    text = "Publisher",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = decision.publisherName.ifBlank { "<unnamed>" },
                    style = MaterialTheme.typography.bodyMedium,
                )

                Text(
                    text = "Key fingerprint (SHA-256)",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = decision.publisherKeyFingerprint,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text =
                    "This is the first time you're installing this plugin id. Future " +
                        "updates must come from a key with the same fingerprint, or the " +
                        "device refuses the update.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text =
                    "Trust here only binds the key. The plugin still has zero capability " +
                        "grants until you approve each one separately at first use.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { PluginInstallTofuRegistry.approveActive() }) {
                Text("Trust this publisher")
            }
        },
        dismissButton = {
            TextButton(
                onClick = { PluginInstallTofuRegistry.rejectActive() },
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) { Text("Refuse") }
        },
    )
}

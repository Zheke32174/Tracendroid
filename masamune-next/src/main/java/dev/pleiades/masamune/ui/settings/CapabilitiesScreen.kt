package dev.pleiades.masamune.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.pleiades.masamune.core.capability.Capability
import dev.pleiades.masamune.core.capability.Caller
import dev.pleiades.masamune.core.capability.CapabilityGate
import dev.pleiades.masamune.ui.components.Notice
import dev.pleiades.masamune.ui.components.NoticeTone
import dev.pleiades.masamune.ui.components.SectionCard
import dev.pleiades.masamune.ui.theme.MasamuneTheme

/**
 * The grant matrix. About → Capabilities.
 *
 * This is the gate's user interface, not a mirror of it: toggling a row here is the same call
 * the code path makes, and the Files / Shell / Chat surfaces consult the same store. Turning
 * FILE_WRITE off and then trying to delete a file produces a refusal, immediately.
 */
@Composable
fun CapabilitiesScreen() {
    val context = LocalContext.current
    val gate = remember { CapabilityGate.get(context) }
    val grants by gate.grants.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Notice(
            title = "Default deny",
            body = "Nothing is granted implicitly. On first run the caller \"user\" holds only " +
                "METADATA and FILE_READ, because a person opening a file browser is not making " +
                "a privilege request. \"ai-agent\" holds nothing at all. UNCLASSIFIED is never " +
                "grantable and is what an unrecognised operation resolves to.",
            tone = NoticeTone.INFO,
        )

        CapabilityGate.KNOWN_CALLERS.forEach { caller ->
            SectionCard(
                title = "Caller: ${caller.tag}",
                subtitle = when (caller) {
                    Caller.User -> "A person tapping a control in this app."
                    Caller.AiAgent -> "The model driving a surface. No surface in this build " +
                        "hands control to the model, so these grants are currently inert — " +
                        "they exist so the store's shape does not change when one does."
                    else -> ""
                },
            ) {
                CapabilityGate.GRANTABLE.forEach { capability ->
                    val granted = CapabilityGate.key(caller, capability) in grants
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "${capability.name} — ${capability.label}",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                capability.blurb,
                                style = MaterialTheme.typography.bodySmall,
                                color = MasamuneTheme.semantic.dim,
                            )
                            enforcementNote(capability)?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MasamuneTheme.semantic.success,
                                )
                            }
                        }
                        Switch(
                            checked = granted,
                            onCheckedChange = { on ->
                                if (on) gate.grant(caller, capability) else gate.revoke(caller, capability)
                            },
                        )
                    }
                }
            }
        }

        Notice(
            title = "Which of these are actually enforced today",
            body = "FILE_READ, FILE_WRITE, SHELL and NETWORK gate real code paths in this " +
                "build — listing, mutating, dispatching to Termux, and calling a chat " +
                "provider all call the gate first. METADATA, SYSTEM_READ, SYSTEM_WRITE, " +
                "CHAT_READ and CHAT_WRITE are declared but no code path consults them yet; " +
                "toggling them changes nothing. That is stated here rather than left to be " +
                "inferred from their presence.",
            tone = NoticeTone.WARNING,
        )
    }
}

private fun enforcementNote(capability: Capability): String? = when (capability) {
    Capability.FILE_READ -> "Enforced: every directory listing and file read."
    Capability.FILE_WRITE -> "Enforced: create, rename, delete, copy, move, save."
    Capability.SHELL -> "Enforced: every Termux RUN_COMMAND dispatch."
    Capability.NETWORK -> "Enforced: every chat provider request."
    else -> null
}

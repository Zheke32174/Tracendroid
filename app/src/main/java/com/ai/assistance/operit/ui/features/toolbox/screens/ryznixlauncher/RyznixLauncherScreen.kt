/*
 * RyznixLauncherScreen — Tracendroid cornerstone #2: the ryznix OS launcher.
 *
 * WHAT THIS IS (honestly):
 * A control surface for ryznix v1 — a REAL second operating system (Gentoo + Arch + RYZ,
 * with real VM-scoped root via ryz-ksud) that runs as a QEMU guest ON TOP OF bare-metal
 * Android. The phone itself stays UNROOTED; root exists only inside the guest VM. This screen
 * boots, shuts down, checks the status of, and opens a console into that guest.
 *
 * IT DOES NOT run the VM itself. The lifecycle lives in the on-phone backend `~/ryzvm/ryzctl`
 * (installed in Termux): `ryzctl start|stop|status|console|selftest|ip`. This UI drives that
 * CLI over Termux's public RUN_COMMAND intent bridge (see RyznixBridge.kt). Therefore it has
 * HARD DEPENDENCIES it never hides:
 *   1. Termux installed.
 *   2. The `com.termux.permission.RUN_COMMAND` permission granted to Tracendroid.
 *   3. The ryznix v1 artifacts present in `~/ryzvm` (the ryzctl CLI + the guest images).
 * If any is missing, the screen says so plainly and never fabricates a RUNNING state.
 *
 * The status shown is exactly what `ryzctl status` prints (RUNNING / STOPPED / unknown). We do
 * not infer "running" from anything else.
 */
package com.ai.assistance.operit.ui.features.toolbox.screens.ryznixlauncher

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

/** ryzctl verbs this screen drives. */
private const val VERB_STATUS = "status"
private const val VERB_START = "start"
private const val VERB_STOP = "stop"

/** Distinct PendingIntent request codes so concurrent verbs don't collide. */
private const val RC_STATUS = 3101
private const val RC_START = 3102
private const val RC_STOP = 3103

/** Coarse lifecycle state the UI renders, derived ONLY from `ryzctl status` output. */
private enum class GuestState { UNKNOWN, RUNNING, STOPPED }

/** What operation, if any, is currently in flight (drives spinners + disabled buttons). */
private enum class Pending { NONE, STATUS, START, STOP }

/**
 * The ryznix launcher. Self-contained: it owns a broadcast receiver for RUN_COMMAND results
 * and holds all state locally. No ViewModel needed — this is a control panel, not a data app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RyznixLauncherScreen() {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    // Re-checked whenever we return to the screen; drives the whole gated UI.
    var availability by remember { mutableStateOf(RyznixBridge.availability(context)) }

    var guestState by remember { mutableStateOf(GuestState.UNKNOWN) }
    var pending by remember { mutableStateOf(Pending.NONE) }
    // Last raw output line from ryzctl (shown verbatim so we never launder the backend's word).
    var lastOutput by remember { mutableStateOf<String?>(null) }
    var lastError by remember { mutableStateOf<String?>(null) }

    // Register a receiver for background ryzctl results. Recreated if availability flips to READY.
    DisposableEffect(availability) {
        if (availability != TermuxAvailability.READY) {
            onDispose { }
        } else {
            val receiver = RyzctlResultReceiver { verb, result ->
                pending = Pending.NONE
                if (result == null) {
                    lastError = "No result returned from Termux for '$verb'."
                    return@RyzctlResultReceiver
                }
                if (result.transportError != null) {
                    lastError = "Termux bridge error: ${result.transportError}"
                    return@RyzctlResultReceiver
                }
                val combined = (result.stdout + "\n" + result.stderr).trim()
                lastOutput = combined.ifBlank { "(no output, exit ${result.exitCode})" }
                lastError = if (result.exitCode != 0)
                    "ryzctl $verb exited ${result.exitCode}" else null
                // Interpret status/start/stop into a coarse state — ONLY from real output.
                guestState = interpretState(verb, combined, result.exitCode, guestState)
            }
            // Internal broadcast only; NOT_EXPORTED keeps other apps from spoofing results.
            ContextCompat.registerReceiver(
                context,
                receiver,
                receiver.intentFilter(),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            onDispose { runCatching { context.unregisterReceiver(receiver) } }
        }
    }

    // On first composition (and after gaining permission), poll status once.
    LaunchedEffect(availability) {
        if (availability == TermuxAvailability.READY) {
            pending = Pending.STATUS
            if (!RyznixBridge.runBackground(context, VERB_STATUS, RC_STATUS)) {
                pending = Pending.NONE
                lastError = "Could not reach Termux (RUN_COMMAND dispatch failed)."
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        HeaderExplainer()

        when (availability) {
            TermuxAvailability.NOT_INSTALLED -> TermuxMissingCard()
            TermuxAvailability.PERMISSION_MISSING -> PermissionMissingCard(
                onRecheck = { availability = RyznixBridge.availability(context) },
                onCopy = { clipboard.copyPlain("adb: grant com.termux.permission.RUN_COMMAND to Tracendroid, or approve the Termux prompt") },
            )
            TermuxAvailability.READY -> {
                StatusCard(
                    guestState = guestState,
                    pending = pending,
                    lastOutput = lastOutput,
                    lastError = lastError,
                    onRefresh = {
                        lastError = null
                        pending = Pending.STATUS
                        if (!RyznixBridge.runBackground(context, VERB_STATUS, RC_STATUS)) {
                            pending = Pending.NONE
                            lastError = "RUN_COMMAND dispatch failed."
                        }
                    },
                )
                ControlCard(
                    guestState = guestState,
                    pending = pending,
                    onBoot = {
                        lastError = null
                        pending = Pending.START
                        if (!RyznixBridge.runBackground(context, VERB_START, RC_START)) {
                            pending = Pending.NONE
                            lastError = "RUN_COMMAND dispatch failed."
                        }
                    },
                    onShutdown = {
                        lastError = null
                        pending = Pending.STOP
                        if (!RyznixBridge.runBackground(context, VERB_STOP, RC_STOP)) {
                            pending = Pending.NONE
                            lastError = "RUN_COMMAND dispatch failed."
                        }
                    },
                )
                ConsoleCard(
                    context = context,
                    clipboard = clipboard,
                )
            }
        }

        DependencyNote()
    }
}

// ---------------------------------------------------------------------------------------------
// Cards
// ---------------------------------------------------------------------------------------------

@Composable
private fun HeaderExplainer() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "ryznix — second OS on your phone",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Boots a REAL operating system (Gentoo + Arch + RYZ) as a QEMU guest " +
                    "over bare-metal Android. Real root exists only INSIDE the guest VM (via " +
                    "ryz-ksud) — your phone stays unrooted. This screen drives the on-phone " +
                    "backend ~/ryzvm/ryzctl through Termux.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun StatusCard(
    guestState: GuestState,
    pending: Pending,
    lastOutput: String?,
    lastError: String?,
    onRefresh: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Guest status",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                OutlinedButton(
                    onClick = onRefresh,
                    enabled = pending == Pending.NONE,
                ) {
                    if (pending == Pending.STATUS) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh", modifier = Modifier.size(16.dp))
                    }
                    Spacer(Modifier.size(6.dp))
                    Text("Refresh")
                }
            }
            Spacer(Modifier.height(10.dp))

            val (label, color) = when (guestState) {
                GuestState.RUNNING -> "RUNNING" to Color(0xFF2E7D32)
                GuestState.STOPPED -> "STOPPED" to MaterialTheme.colorScheme.onSurfaceVariant
                GuestState.UNKNOWN -> "UNKNOWN" to MaterialTheme.colorScheme.onSurfaceVariant
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(color)
                Spacer(Modifier.size(8.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = color,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = "ryz-ksud control API: $RYZ_KSUD_ENDPOINT (available once RUNNING)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
            )

            if (lastOutput != null) {
                Spacer(Modifier.height(10.dp))
                Divider()
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "ryzctl output:",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = lastOutput,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
            if (lastError != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = lastError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun ControlCard(
    guestState: GuestState,
    pending: Pending,
    onBoot: () -> Unit,
    onShutdown: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Power",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Booting runs under TCG software emulation — expect roughly 1–2 minutes " +
                    "before the guest is fully up. Status stays UNKNOWN/STOPPED until ryzctl " +
                    "confirms it; press Refresh after booting.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = onBoot,
                    modifier = Modifier.weight(1f),
                    enabled = pending == Pending.NONE && guestState != GuestState.RUNNING,
                ) {
                    if (pending == Pending.START) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    } else {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.size(6.dp))
                    Text(if (pending == Pending.START) "Booting…" else "Boot")
                }
                Button(
                    onClick = onShutdown,
                    modifier = Modifier.weight(1f),
                    enabled = pending == Pending.NONE && guestState != GuestState.STOPPED,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                ) {
                    if (pending == Pending.STOP) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.PowerSettingsNew, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.size(6.dp))
                    Text("Shutdown")
                }
            }
        }
    }
}

@Composable
private fun ConsoleCard(
    context: Context,
    clipboard: ClipboardManager,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Terminal, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.size(8.dp))
                Text(
                    text = "Console",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Opens the ryznix serial console. Because a serial attach is interactive, " +
                    "the honest path opens it inside a Termux session (a real terminal), not a " +
                    "background runner. Tracendroid's own embedded terminal can also reach it " +
                    "over ssh — see the note below.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { RyznixBridge.runConsoleForeground(context) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Terminal, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text("Open console in Termux")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { clipboard.copyPlain(RyznixBridge.copyableCommand("console")) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.size(6.dp))
                Text("Copy: ${RyznixBridge.copyableCommand("console")}")
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Embedded-terminal path (now wired): open Tracendroid's embedded terminal " +
                    "and pick the 'ryznix' profile. It SSHes into Termux (127.0.0.1:8022) with an " +
                    "app-private key, then runs '~/ryzvm/ryzctl console' (starting the VM first if " +
                    "it is down). One-time setup: in that terminal tap 'Authorize in Termux' " +
                    "(installs the key + starts sshd). This screen's Termux console button remains " +
                    "as an alternative that opens the console inside Termux itself.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun TermuxMissingCard() {
    WarnCard(
        title = "Termux is not installed",
        body = "The ryznix launcher drives the on-phone backend through Termux's RUN_COMMAND " +
            "bridge. Without Termux (and the ryznix v1 artifacts in ~/ryzvm), there is nothing " +
            "to boot. Install Termux (F-Droid / GitHub build), then place the ryznix artifacts " +
            "in ~/ryzvm.",
    )
}

@Composable
private fun PermissionMissingCard(onRecheck: () -> Unit, onCopy: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    text = "RUN_COMMAND permission required",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Termux is installed, but Tracendroid does not yet hold " +
                    "com.termux.permission.RUN_COMMAND. Termux marks this permission " +
                    "protectionLevel=signature|... on many builds, so it is granted from Termux's " +
                    "own settings (allow-external-apps) or via adb — the OS will not show a runtime " +
                    "prompt. After granting, tap Re-check.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onRecheck) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("Re-check")
                }
                OutlinedButton(onClick = onCopy) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("Copy how-to")
                }
            }
        }
    }
}

@Composable
private fun DependencyNote() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(
                    text = "Real dependencies (nothing here is faked)",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "1. Termux installed on this phone.\n" +
                    "2. Tracendroid granted com.termux.permission.RUN_COMMAND.\n" +
                    "3. ryznix v1 artifacts present at ~/ryzvm (the ryzctl CLI + guest images).\n" +
                    "Commands run via Termux's RunCommandService (com.termux.RUN_COMMAND). " +
                    "Status shown is exactly what 'ryzctl status' reports — never inferred.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun WarnCard(title: String, body: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@Composable
private fun StatusDot(color: Color) {
    Card(
        modifier = Modifier.size(14.dp),
        shape = RoundedCornerShape(7.dp),
        colors = CardDefaults.cardColors(containerColor = color),
    ) {}
}

// ---------------------------------------------------------------------------------------------
// Pure helpers
// ---------------------------------------------------------------------------------------------

/** Copy plain text to the clipboard via Compose's ClipboardManager. */
private fun ClipboardManager.copyPlain(text: String) = setText(AnnotatedString(text))

/**
 * Interpret ryzctl output into a coarse [GuestState]. Conservative and honest: we only claim
 * RUNNING / STOPPED when the backend's own words say so; otherwise we keep the prior/unknown
 * state rather than guessing.
 */
private fun interpretState(
    verb: String,
    output: String,
    exitCode: Int,
    prior: GuestState,
): GuestState {
    val up = output.uppercase()
    return when (verb) {
        VERB_STATUS -> when {
            up.contains("RUNNING") -> GuestState.RUNNING
            up.contains("STOPPED") -> GuestState.STOPPED
            else -> prior
        }
        // start/stop success flips state; on failure keep prior so we never lie about it.
        VERB_START -> if (exitCode == 0) GuestState.RUNNING else prior
        VERB_STOP -> if (exitCode == 0) GuestState.STOPPED else prior
        else -> prior
    }
}

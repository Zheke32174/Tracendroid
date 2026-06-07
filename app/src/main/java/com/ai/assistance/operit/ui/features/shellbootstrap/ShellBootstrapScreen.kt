package com.ai.assistance.operit.ui.features.shellbootstrap

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.shell.ShellBootstrapManager
import com.ai.assistance.operit.shell.ShellBootstrapState
import com.ai.assistance.operit.shell.launcher.ShellForegroundService
import kotlinx.coroutines.launch

/**
 * Bootstrap UI for the shell rebuild rootfs install flow (PR 2/N, sub-commit C).
 *
 * Observes [ShellBootstrapManager.state] and renders one panel per state. Confirmation,
 * progress, success, and failure are each terminal panels with no "skip" path — per
 * docs/SHELL_REBUILD.md and AGENTS.md errors are surfaced verbatim.
 */
@Composable
fun ShellBootstrapScreen(
    onClose: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val manager = remember { ShellBootstrapManager(context.applicationContext) }
    val state by manager.state.collectAsState()

    LaunchedEffect(Unit) {
        manager.inspectAndPropose()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        StateHeader(state)
        StateBody(state)

        when (val s = state) {
            is ShellBootstrapState.AwaitingConfirmation -> {
                Button(
                    onClick = {
                        coroutineScope.launch { manager.runBootstrap() }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Download and install")
                }
                if (onClose != null) {
                    OutlinedButton(
                        onClick = onClose,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Not now")
                    }
                }
            }
            is ShellBootstrapState.Installed,
            is ShellBootstrapState.Ready -> {
                Button(
                    onClick = { ShellForegroundService.start(context) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Start shell session")
                }
                OutlinedButton(
                    onClick = { ShellForegroundService.stop(context) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Halt shell session")
                }
                if (onClose != null) {
                    TextButton(
                        onClick = onClose,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Done")
                    }
                }
            }
            is ShellBootstrapState.Failed -> {
                if (onClose != null) {
                    OutlinedButton(
                        onClick = onClose,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Close")
                    }
                }
            }
            else -> Unit
        }
    }
}

@Composable
private fun StateHeader(state: ShellBootstrapState) {
    val (title, subtitle, icon) = when (state) {
        ShellBootstrapState.Idle ->
            Triple("Shell environment", "Initializing…", Icons.Default.HourglassEmpty)
        ShellBootstrapState.Inspecting ->
            Triple("Shell environment", "Checking installed rootfs…", Icons.Default.HourglassEmpty)
        is ShellBootstrapState.Ready ->
            Triple("Shell environment installed", state.version, Icons.Default.CheckCircle)
        is ShellBootstrapState.AwaitingConfirmation ->
            Triple("Shell environment needs setup", state.expectedVersion, Icons.Default.Download)
        is ShellBootstrapState.Downloading ->
            Triple("Downloading rootfs", "From the project release", Icons.Default.Download)
        ShellBootstrapState.VerifyingDigest ->
            Triple("Verifying download", "SHA-256 digest", Icons.Default.Storage)
        ShellBootstrapState.VerifyingSignature ->
            Triple("Verifying signature", "Ed25519 release key", Icons.Default.Storage)
        is ShellBootstrapState.Extracting ->
            Triple("Extracting rootfs", "Into app-private storage", Icons.Default.Storage)
        is ShellBootstrapState.Installed ->
            Triple("Shell environment installed", state.version, Icons.Default.CheckCircle)
        is ShellBootstrapState.Failed ->
            Triple("Bootstrap failed", state.phase.name.lowercase(), Icons.Default.ErrorOutline)
    }
    HeaderRow(title, subtitle, icon)
}

@Composable
private fun HeaderRow(title: String, subtitle: String, icon: ImageVector) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StateBody(state: ShellBootstrapState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (state) {
                ShellBootstrapState.Idle,
                ShellBootstrapState.Inspecting -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text("Looking for an installed rootfs at the expected version.")
                }
                is ShellBootstrapState.Ready -> {
                    Text("Version: ${state.version}", fontFamily = FontFamily.Monospace)
                    Text("SHA-256: ${state.sha256.take(16)}…", fontFamily = FontFamily.Monospace)
                    Text(
                        "Nothing to download — the on-disk rootfs matches this build's pin.",
                    )
                }
                is ShellBootstrapState.AwaitingConfirmation -> {
                    Text("This build expects rootfs version:")
                    Text(state.expectedVersion, fontFamily = FontFamily.Monospace)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Source:")
                    Text(state.artifactUrl, fontFamily = FontFamily.Monospace)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Download proceeds only after you confirm. The artifact is " +
                            "SHA-256-pinned and Ed25519-signed; mismatches abort the install."
                    )
                }
                is ShellBootstrapState.Downloading -> {
                    val total = state.totalBytes
                    if (total != null && total > 0) {
                        LinearProgressIndicator(
                            progress = { (state.bytesDownloaded.toFloat() / total).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text("${humanBytes(state.bytesDownloaded)} / ${humanBytes(total)}")
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text("${humanBytes(state.bytesDownloaded)} downloaded")
                    }
                }
                ShellBootstrapState.VerifyingDigest -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text("Checking SHA-256 against this build's pin.")
                }
                ShellBootstrapState.VerifyingSignature -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text("Verifying detached Ed25519 signature.")
                }
                is ShellBootstrapState.Extracting -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text("Entries processed: ${state.entriesProcessed}")
                    Text("Bytes written: ${humanBytes(state.bytesProcessed)}")
                }
                is ShellBootstrapState.Installed -> {
                    Text("Version: ${state.version}", fontFamily = FontFamily.Monospace)
                    Text("SHA-256: ${state.sha256.take(16)}…", fontFamily = FontFamily.Monospace)
                    Text(
                        "Rootfs installed. The proot launcher uses this rootfs the next " +
                            "time a shell session opens."
                    )
                }
                is ShellBootstrapState.Failed -> {
                    Text(
                        text = "Phase: ${state.phase.name.lowercase()}",
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(text = state.reason)
                    Text(
                        text =
                        "Bootstrap stopped. No silent retry, no fallback path. " +
                            "See docs/SHELL_REBUILD.md if this is unexpected.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun humanBytes(bytes: Long): String {
    val units = listOf("B", "KiB", "MiB", "GiB")
    var value = bytes.toDouble()
    var unitIndex = 0
    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex++
    }
    return if (unitIndex == 0) "${bytes} ${units[unitIndex]}"
    else String.format("%.2f %s", value, units[unitIndex])
}

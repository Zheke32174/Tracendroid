package dev.pleiades.masamune.ui.shell

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.pleiades.masamune.shell.TermuxContract
import dev.pleiades.masamune.shell.TermuxShellBackend
import dev.pleiades.masamune.ui.components.Notice
import dev.pleiades.masamune.ui.components.NoticeTone
import dev.pleiades.masamune.ui.masamuneViewModel
import dev.pleiades.masamune.ui.theme.MasamuneTheme

/**
 * The Shell surface. Bottom nav → Shell.
 *
 * The chosen design is named on screen, always, so nobody has to guess what is behind it:
 * commands are handed to an installed Termux over `com.termux.RUN_COMMAND` and run as
 * `bash -c`. If Termux is absent, this screen says exactly that and stops — there is no
 * installer, no bundled APK, no "set up Termux first" wizard.
 */
@Composable
fun ShellScreen(onOpenCapabilities: () -> Unit) {
    val vm = masamuneViewModel { ctx -> ShellViewModel(ctx) }
    val state by vm.state.collectAsState()
    var command by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { vm.refreshAvailability() }

    LaunchedEffect(Unit) { vm.refreshAvailability() }
    LaunchedEffect(state.transcript.size) {
        if (state.transcript.isNotEmpty()) listState.animateScrollToItem(state.transcript.size - 1)
    }

    Column(modifier = Modifier.fillMaxSize()) {

        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Design: ${vm.designName}", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Commands run as `${TermuxContract.BASH} -c \"<line>\"` inside an installed " +
                        "Termux, via its public RunCommandService contract. Output comes back " +
                        "on a PendingIntent this app owns. No terminal is bundled and no PTY " +
                        "is emulated — a real PTY needs an NDK build and every native source " +
                        "tree in this repository is empty.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MasamuneTheme.semantic.dim,
                )
            }
        }

        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (state.availability) {
                TermuxShellBackend.Availability.NotInstalled -> Notice(
                    title = "No shell backend on this device",
                    body = "${TermuxContract.PACKAGE} is not installed, so there is nothing to " +
                        "drive. This app will not install it for you and does not bundle a copy. " +
                        "Everything on this screen below is inert until a Termux is present.",
                    tone = NoticeTone.BLOCKED,
                    actionLabel = "Re-check",
                    onAction = { vm.refreshAvailability() },
                )
                TermuxShellBackend.Availability.PermissionNotGranted -> Notice(
                    title = "Termux is installed; this app cannot call it yet",
                    body = "${TermuxContract.PERMISSION} has not been granted to Masamune. " +
                        "That is an Android runtime permission declared by Termux itself.",
                    tone = NoticeTone.WARNING,
                    actionLabel = "Request permission",
                    onAction = { permissionLauncher.launch(TermuxContract.PERMISSION) },
                )
                TermuxShellBackend.Availability.Ready -> Notice(
                    title = "Backend ready",
                    body = "Termux is installed and ${TermuxContract.PERMISSION} is granted. " +
                        "Note that Termux additionally requires allow-external-apps=true in its " +
                        "own termux.properties; if it is off, Termux's refusal is printed " +
                        "verbatim in the transcript below rather than being interpreted here.",
                    tone = NoticeTone.SUCCESS,
                )
            }

            if (!state.capabilityGranted) {
                Notice(
                    title = "SHELL capability not granted",
                    body = "The capability gate denies shell dispatch for caller \"user\" by " +
                        "default. Nothing runs until this is granted.",
                    tone = NoticeTone.BLOCKED,
                    actionLabel = "Grant SHELL to \"user\"",
                    onAction = { vm.grantShellCapability() },
                )
                TextButton(onClick = onOpenCapabilities) { Text("Open the full capability matrix") }
            }

            state.gateMessage?.let {
                Notice(title = "Refused", body = it, tone = NoticeTone.ERROR)
            }

            OutlinedTextField(
                value = state.workdir,
                onValueChange = { vm.setWorkdir(it) },
                label = { Text("Working directory (inside Termux)") },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        if (state.running) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(state.transcript) { entry -> TranscriptBlock(entry) }
        }

        if (state.transcript.isNotEmpty()) {
            TextButton(
                onClick = { vm.clearTranscript() },
                modifier = Modifier.padding(horizontal = 12.dp),
            ) { Text("Clear transcript") }
        }

        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = command,
                    onValueChange = { command = it },
                    modifier = Modifier.weight(1f),
                    label = { Text("Command") },
                    singleLine = true,
                    enabled = !state.running,
                    textStyle = MaterialTheme.typography.bodyMedium
                        .copy(fontFamily = FontFamily.Monospace),
                )
                if (state.running) {
                    CircularProgressIndicator(modifier = Modifier.padding(8.dp))
                } else {
                    IconButton(
                        onClick = {
                            vm.run(command)
                            command = ""
                        },
                        enabled = command.isNotBlank(),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Run")
                    }
                }
            }
        }
    }
}

@Composable
private fun TranscriptBlock(entry: ShellTranscriptEntry) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                "$ ${entry.command}",
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                "workdir ${entry.workdir}",
                style = MaterialTheme.typography.labelSmall,
                color = MasamuneTheme.semantic.dim,
            )
            if (entry.failure != null) {
                Text(
                    entry.failure,
                    style = MaterialTheme.typography.bodySmall
                        .copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                if (entry.stdout.isNotBlank()) {
                    SelectionContainer {
                        Text(
                            entry.stdout.trimEnd(),
                            style = MaterialTheme.typography.bodySmall
                                .copy(fontFamily = FontFamily.Monospace),
                        )
                    }
                }
                if (entry.stderr.isNotBlank()) {
                    SelectionContainer {
                        Text(
                            entry.stderr.trimEnd(),
                            style = MaterialTheme.typography.bodySmall
                                .copy(fontFamily = FontFamily.Monospace),
                            color = MasamuneTheme.semantic.warning,
                        )
                    }
                }
                if (entry.stdout.isBlank() && entry.stderr.isBlank()) {
                    Text(
                        "(no output)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MasamuneTheme.semantic.dim,
                    )
                }
                Text(
                    "exit ${entry.exitCode}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (entry.exitCode == 0) {
                        MasamuneTheme.semantic.success
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
        }
    }
}

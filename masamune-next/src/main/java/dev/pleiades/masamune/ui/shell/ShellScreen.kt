package dev.pleiades.masamune.ui.shell

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.pleiades.masamune.R
import dev.pleiades.masamune.shell.TermuxContract
import dev.pleiades.masamune.shell.TermuxShellBackend
import dev.pleiades.masamune.ui.components.Notice
import dev.pleiades.masamune.ui.components.NoticeTone
import dev.pleiades.masamune.ui.masamuneViewModel
import dev.pleiades.masamune.ui.theme.MasamuneTheme
import kotlinx.coroutines.launch

/**
 * The Terminal surface (DONOR-SURFACES §4, donor: Termux). Bottom nav → Shell.
 *
 * The design is named on screen, always: commands are handed to an installed Termux over
 * `com.termux.RUN_COMMAND` and run as `bash -c`, one-shot and in the background. There is no PTY,
 * no bundled terminal and no rootfs — each would need native code and every native tree in this
 * repository is empty. This screen composes the donor's Termux surfaces over that one contract:
 * a session drawer of named run-groups, a non-blocking background-job registry, an Environments
 * panel of `command -v`/`pkg`/`proot-distro` probes, and the terminal context menu — with every
 * capability the contract cannot back shown as a gated control that names what is missing.
 */
@Composable
fun ShellScreen(onOpenCapabilities: () -> Unit) {
    val vm = masamuneViewModel { ctx -> ShellViewModel(ctx) }
    val state by vm.state.collectAsState()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { vm.refreshAvailability() }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            SessionDrawer(
                sessions = state.sessions,
                activeSessionId = state.activeSessionId,
                onSelect = { vm.setActiveSession(it); scope.launch { drawerState.close() } },
                onNewSession = { vm.newSession(); scope.launch { drawerState.close() } },
                onNewFailsafe = { vm.newFailsafeSession(); scope.launch { drawerState.close() } },
                onRename = vm::renameSession,
                onKill = vm::killSession,
            )
        },
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopBar(
                state = state,
                onOpenDrawer = { scope.launch { drawerState.open() } },
                onOpenEnvironments = { vm.openPanel(ShellPanel.ENVIRONMENTS) },
                onOpenJobs = { vm.openPanel(ShellPanel.JOBS) },
                onReset = vm::clearTranscript,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            when (state.panel) {
                ShellPanel.TERMINAL -> TerminalPane(state, vm, onOpenCapabilities)
                ShellPanel.ENVIRONMENTS -> PanelFrame(
                    title = stringResource(R.string.terminal_environments_title),
                    onClose = vm::closePanel,
                ) { EnvironmentsPanel(vm, state.env) }
                ShellPanel.JOBS -> PanelFrame(
                    title = stringResource(R.string.terminal_jobs_title),
                    onClose = vm::closePanel,
                ) {
                    JobsPanel(
                        jobs = state.jobs,
                        onDismissJob = vm::dismissJob,
                        onClearFinished = vm::clearFinishedJobs,
                    )
                }
            }
        }
    }
}

@Composable
private fun TopBar(
    state: ShellUiState,
    onOpenDrawer: () -> Unit,
    onOpenEnvironments: () -> Unit,
    onOpenJobs: () -> Unit,
    onReset: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var keepScreenOn by remember { mutableStateOf(false) }
    val view = LocalView.current
    LaunchedEffect(keepScreenOn) { view.keepScreenOn = keepScreenOn }

    val active = state.activeSession
    val transcriptText = remember(active?.id, active?.transcript) { transcriptToText(active) }

    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onOpenDrawer) {
                Icon(Icons.Filled.Menu, contentDescription = stringResource(R.string.terminal_sessions_title))
            }
            Text(
                active?.name ?: "session",
                style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onOpenEnvironments) {
                Icon(Icons.Filled.Layers, contentDescription = stringResource(R.string.terminal_environments_title))
            }
            if (state.runningJobCount > 0) {
                Text(
                    "${state.runningJobCount}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            IconButton(onClick = onOpenJobs) {
                Icon(Icons.Filled.PlayCircle, contentDescription = stringResource(R.string.terminal_jobs_title))
            }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.terminal_menu_title))
                }
                TerminalContextMenu(
                    expanded = menuExpanded,
                    onDismiss = { menuExpanded = false },
                    transcriptText = transcriptText,
                    keepScreenOn = keepScreenOn,
                    onToggleKeepScreenOn = { keepScreenOn = it },
                    onReset = onReset,
                )
            }
        }
    }
}

@Composable
private fun PanelFrame(title: String, onClose: () -> Unit, content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = MasamuneTheme.spacing.md, vertical = MasamuneTheme.spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onClose) {
                Icon(Icons.Filled.Terminal, contentDescription = null)
                Text(stringResource(R.string.terminal_close))
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        content()
    }
}

@Composable
private fun TerminalPane(state: ShellUiState, vm: ShellViewModel, onOpenCapabilities: () -> Unit) {
    var command by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val active = state.activeSession
    val transcript = active?.transcript ?: emptyList()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { vm.refreshAvailability() }

    LaunchedEffect(transcript.size) {
        if (transcript.isNotEmpty()) listState.animateScrollToItem(transcript.size - 1)
    }

    Column(modifier = Modifier.fillMaxSize()) {

        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Design: ${vm.designName}", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Commands run as `${TermuxContract.BASH} -c \"<line>\"` inside an installed Termux, " +
                        "one-shot and in the background. No terminal is bundled and no PTY is emulated.",
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
                    body = "${TermuxContract.PACKAGE} is not installed, so there is nothing to drive. " +
                        "This app will not install it and does not bundle a copy. Everything below is " +
                        "inert until a Termux is present.",
                    tone = NoticeTone.BLOCKED,
                    actionLabel = "Re-check",
                    onAction = { vm.refreshAvailability() },
                )
                TermuxShellBackend.Availability.PermissionNotGranted -> Notice(
                    title = "Termux is installed; this app cannot call it yet",
                    body = "${TermuxContract.PERMISSION} has not been granted to Masamune.",
                    tone = NoticeTone.WARNING,
                    actionLabel = "Request permission",
                    onAction = { permissionLauncher.launch(TermuxContract.PERMISSION) },
                )
                TermuxShellBackend.Availability.Ready -> Notice(
                    title = "Backend ready",
                    body = "Termux is installed and ${TermuxContract.PERMISSION} is granted. If " +
                        "allow-external-apps=true is off in termux.properties, Termux's refusal is " +
                        "printed verbatim below rather than interpreted here.",
                    tone = NoticeTone.SUCCESS,
                )
            }

            if (!state.capabilityGranted) {
                Notice(
                    title = "SHELL capability not granted",
                    body = "The capability gate denies shell dispatch for caller \"user\" by default. " +
                        "Nothing runs until this is granted.",
                    tone = NoticeTone.BLOCKED,
                    actionLabel = "Grant SHELL to \"user\"",
                    onAction = { vm.grantShellCapability() },
                )
                TextButton(onClick = onOpenCapabilities) { Text("Open the full capability matrix") }
            }

            state.gateMessage?.let { Notice(title = "Refused", body = it, tone = NoticeTone.ERROR) }

            OutlinedTextField(
                value = active?.workdir ?: TermuxContract.HOME,
                onValueChange = { vm.setWorkdir(it) },
                label = { Text("Working directory (inside Termux)") },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        if (state.runningJobCount > 0) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(transcript, key = { it.id }) { entry -> TranscriptBlock(entry) }
        }

        if (transcript.isNotEmpty()) {
            TextButton(
                onClick = { vm.clearTranscript() },
                modifier = Modifier.padding(horizontal = 12.dp),
            ) { Text(stringResource(R.string.terminal_action_reset)) }
        }

        ExtraKeysToolbar(onInsert = { command += it })

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
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                )
                IconButton(
                    onClick = { vm.run(command); command = "" },
                    enabled = command.isNotBlank(),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Run")
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "$ ${entry.command}",
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                if (entry.running) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(start = 6.dp).size(16.dp),
                        strokeWidth = 2.dp,
                    )
                }
            }
            Text(
                "workdir ${entry.workdir}${if (entry.failsafe) " · failsafe" else ""}",
                style = MaterialTheme.typography.labelSmall,
                color = MasamuneTheme.semantic.dim,
            )
            when {
                entry.running -> Text(
                    stringResource(R.string.terminal_running),
                    style = MaterialTheme.typography.labelSmall,
                    color = MasamuneTheme.semantic.dim,
                )
                entry.failure != null -> Text(
                    entry.failure,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.error,
                )
                else -> {
                    if (entry.stdout.isNotBlank()) {
                        SelectionContainer {
                            Text(
                                entry.stdout.trimEnd(),
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            )
                        }
                    }
                    if (entry.stderr.isNotBlank()) {
                        SelectionContainer {
                            Text(
                                entry.stderr.trimEnd(),
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
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
                        color = if (entry.exitCode == 0) MasamuneTheme.semantic.success else MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

/** Renders a session transcript to plain text for Share / Copy / Report / URL-scan. */
private fun transcriptToText(session: ShellSession?): String {
    if (session == null) return ""
    return session.transcript.joinToString("\n\n") { e ->
        buildString {
            append("$ ").append(e.command).append('\n')
            when {
                e.running -> append("(running)")
                e.failure != null -> append(e.failure)
                else -> {
                    if (e.stdout.isNotBlank()) append(e.stdout.trimEnd()).append('\n')
                    if (e.stderr.isNotBlank()) append(e.stderr.trimEnd()).append('\n')
                    append("exit ").append(e.exitCode)
                }
            }
        }
    }
}

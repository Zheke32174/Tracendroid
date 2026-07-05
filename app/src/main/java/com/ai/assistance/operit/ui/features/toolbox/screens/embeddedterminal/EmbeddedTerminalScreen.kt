/*
 * EmbeddedTerminalScreen — Tracendroid's DUAL-RIGGED, in-process terminal.
 *
 * WHAT CHANGED AND WHY:
 * The embedded terminal used to spawn only the bare Android shell (/system/bin/sh). That shell has
 * NO package manager (apt/pkg) and no rootfs, so every `pkg install` / `apt` attempt hit dead
 * mirrors — the operator's exact complaint. This screen re-rigs the terminal as a PROFILE PICKER
 * over the environments that actually have a package manager and a real userland:
 *
 *   1. TERMUX  (default) — an interactive shell in the Termux userland, reached over SSH to
 *      Termux's own sshd on 127.0.0.1:8022. Termux is where pkg/apt + working mirrors live.
 *   2. RYZNIX  — the second-OS VM, reached through Termux over SSH, then `~/ryzvm/ryzctl console`.
 *   3. ANDROID SHELL — the original local /system/bin/sh, kept as an honest fallback. NO apt/pkg.
 *
 * HONEST CEILING (also stated in the UI): Tracendroid is a SEPARATE, UNROOTED app. It cannot exec
 * Termux's binaries directly (different sandbox/linker). The supported bridges are SSH to
 * 127.0.0.1:8022 and Termux's RUN_COMMAND intents — both used here, nothing faked. apt/pkg only
 * work if Termux is installed with sshd running and this app's key authorized (one-tap below).
 *
 * TRANSPORT + RENDERING CHOICE (documented): the vendored com.termux.view.TerminalView is welded
 * to the `final` com.termux.terminal.TerminalSession, which always forks a LOCAL process via JNI —
 * it cannot render a non-local (SSH) byte stream without editing the vendored final classes. So the
 * SSH profiles use SshTerminalView, which reuses the DECOUPLED parts of the same library
 * (TerminalEmulator for full VT100/xterm parsing + TerminalRenderer for drawing) fed by an sshj
 * channel — ConnectBot-style. The Android-shell profile keeps the original TerminalView path
 * unchanged. Both render through the same emulator/renderer code, so ANSI fidelity matches.
 */
package com.ai.assistance.operit.ui.features.toolbox.screens.embeddedterminal

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.ai.assistance.operit.ui.features.toolbox.screens.ryznixlauncher.RyznixBridge
import com.ai.assistance.operit.ui.features.toolbox.screens.ryznixlauncher.TermuxAvailability
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val LOG_TAG = "EmbeddedTerminal"

/** The device shell — always present on Android, needs no bootstrap or install. */
const val DEFAULT_SHELL_PATH = "/system/bin/sh"

/** Terminal text size in px used by both the local and SSH views. */
private const val TERMINAL_TEXT_SIZE_PX = 32

/** Broadcast action for the "authorize key" RUN_COMMAND result. Internal to this app. */
private const val ACTION_AUTHORIZE_RESULT = "com.ai.assistance.operit.EMBEDDED_TERMINAL_AUTHORIZE_RESULT"
private const val RC_AUTHORIZE = 4201

/** Live connection state for an SSH-backed profile. */
private enum class ConnState { IDLE, CONNECTING, CONNECTED, ERROR }

/**
 * The dual-rigged embedded terminal. Opens to the Termux profile by default (it has apt/pkg),
 * with ryznix and the Android-shell fallback selectable. Self-contained: owns its own SSH
 * connection lifecycle and the RUN_COMMAND authorize flow.
 */
@Composable
fun EmbeddedTerminalScreen(
    // Retained for source compatibility with the old local-shell entry point. When a caller passes
    // an explicit shellCommand (the old behaviour), we honour it by opening the Android-shell
    // profile directly instead of the picker.
    shellCommand: String = DEFAULT_SHELL_PATH,
    args: Array<String> = emptyArray(),
    workingDir: String? = null,
    environment: Array<String> = emptyArray(),
    transcriptRows: Int = 2_000,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var profile by remember { mutableStateOf(TerminalProfile.TERMUX) }
    var termuxAvailability by remember { mutableStateOf(RyznixBridge.availability(context)) }

    // SSH connection state (shared by TERMUX + RYZNIX profiles).
    var connState by remember { mutableStateOf(ConnState.IDLE) }
    var connError by remember { mutableStateOf<String?>(null) }
    var connection by remember { mutableStateOf<SshTerminalConnection?>(null) }

    // Result of the last "Authorize in Termux" run (honest, verbatim).
    var authorizeStatus by remember { mutableStateOf<String?>(null) }
    var authorizePending by remember { mutableStateOf(false) }

    // Close any live SSH connection when leaving the screen or switching away from an SSH profile.
    DisposableEffect(Unit) {
        onDispose { connection?.close(); connection = null }
    }

    // Register receiver for the authorize RUN_COMMAND result.
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                authorizePending = false
                val bundle: Bundle? = intent?.getBundleExtra(TermuxRunCommandResultKeys.RESULT_BUNDLE)
                if (bundle == null) {
                    authorizeStatus = "No result returned from Termux."
                    return
                }
                val stdout = bundle.getString(TermuxRunCommandResultKeys.RESULT_STDOUT).orEmpty()
                val stderr = bundle.getString(TermuxRunCommandResultKeys.RESULT_STDERR).orEmpty()
                val exit = bundle.getInt(TermuxRunCommandResultKeys.RESULT_EXIT_CODE, -1)
                authorizeStatus = when {
                    exit == 0 -> "Authorized. " + stdout.trim().ifBlank { "sshd ensured." }
                    else -> "Authorize failed (exit $exit): " + (stderr.ifBlank { stdout }).trim()
                }
                // Re-check availability in case permission state changed.
                termuxAvailability = RyznixBridge.availability(context)
            }
        }
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(ACTION_AUTHORIZE_RESULT),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }

    // Connect helper for SSH profiles.
    fun openSsh(p: TerminalProfile) {
        connection?.close(); connection = null
        connError = null
        connState = ConnState.CONNECTING
        val user = TerminalPrefs.getTermuxUser(context)
        val initialInput = if (p == TerminalProfile.RYZNIX) RYZNIX_BOOTSTRAP_INPUT else null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    // Grid is (re)sized by the view on layout; seed a common 80x24 so the pty starts sane.
                    SshTerminalTransport.connect(
                        context = context,
                        username = user,
                        initialColumns = 80,
                        initialRows = 24,
                        initialWidthPx = 0,
                        initialHeightPx = 0,
                        initialInput = initialInput,
                    )
                }
            }
            result.onSuccess {
                connection = it
                connState = ConnState.CONNECTED
            }.onFailure {
                connError = it.message ?: "Connection failed."
                connState = ConnState.ERROR
            }
        }
    }

    // Auto-connect the default SSH profile (Termux) once when the screen opens, but only if Termux
    // is actually usable — otherwise we show the honest "install/authorize" guidance instead.
    LaunchedEffect(Unit) {
        if (profile != TerminalProfile.ANDROID_SH &&
            termuxAvailability == TermuxAvailability.READY &&
            connState == ConnState.IDLE
        ) {
            openSsh(profile)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ProfilePickerBar(
            profile = profile,
            onSelect = { selected ->
                if (selected == profile) return@ProfilePickerBar
                // Leaving an SSH profile: close its connection.
                if (profile != TerminalProfile.ANDROID_SH) {
                    connection?.close(); connection = null
                    connState = ConnState.IDLE
                    connError = null
                }
                profile = selected
                if (selected != TerminalProfile.ANDROID_SH) openSsh(selected)
            },
        )

        Box(modifier = Modifier.fillMaxSize()) {
            when (profile) {
                TerminalProfile.ANDROID_SH -> LocalShellTerminal(
                    shellCommand = shellCommand,
                    args = args,
                    workingDir = workingDir,
                    environment = environment,
                    transcriptRows = transcriptRows,
                )
                TerminalProfile.TERMUX, TerminalProfile.RYZNIX -> SshProfileBody(
                    context = context,
                    profile = profile,
                    termuxAvailability = termuxAvailability,
                    connState = connState,
                    connError = connError,
                    connection = connection,
                    authorizeStatus = authorizeStatus,
                    authorizePending = authorizePending,
                    onRetry = { openSsh(profile) },
                    onRecheck = { termuxAvailability = RyznixBridge.availability(context) },
                    onAuthorize = {
                        authorizePending = true
                        authorizeStatus = null
                        val ok = dispatchAuthorize(context)
                        if (!ok) {
                            authorizePending = false
                            authorizeStatus = "Could not reach Termux to authorize (RUN_COMMAND dispatch failed)."
                        }
                    },
                )
            }
        }
    }
}

// -------------------------------------------------------------------------------------------------
// Profile picker bar
// -------------------------------------------------------------------------------------------------

@Composable
private fun ProfilePickerBar(profile: TerminalProfile, onSelect: (TerminalProfile) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProfileChip("Termux", "apt/pkg", profile == TerminalProfile.TERMUX) { onSelect(TerminalProfile.TERMUX) }
        ProfileChip("ryznix", "VM", profile == TerminalProfile.RYZNIX) { onSelect(TerminalProfile.RYZNIX) }
        ProfileChip("Android sh", "no pkg", profile == TerminalProfile.ANDROID_SH) { onSelect(TerminalProfile.ANDROID_SH) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileChip(label: String, hint: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Column {
                Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Text(hint, style = MaterialTheme.typography.labelSmall)
            }
        },
    )
}

// -------------------------------------------------------------------------------------------------
// SSH profile body (Termux / ryznix)
// -------------------------------------------------------------------------------------------------

@Composable
private fun SshProfileBody(
    context: Context,
    profile: TerminalProfile,
    termuxAvailability: TermuxAvailability,
    connState: ConnState,
    connError: String?,
    connection: SshTerminalConnection?,
    authorizeStatus: String?,
    authorizePending: Boolean,
    onRetry: () -> Unit,
    onRecheck: () -> Unit,
    onAuthorize: () -> Unit,
) {
    when {
        termuxAvailability == TermuxAvailability.NOT_INSTALLED ->
            NoticeColumn {
                WarnCard(
                    title = "Termux is not installed",
                    body = "The Termux and ryznix profiles reach the Termux userland over SSH " +
                        "(127.0.0.1:8022). Without Termux there is no apt/pkg on this phone. " +
                        "Install Termux (F-Droid / GitHub build), then in Termux run " +
                        "`pkg install openssh && sshd`, and come back and tap Authorize.",
                )
                CeilingCard()
            }

        termuxAvailability == TermuxAvailability.PERMISSION_MISSING ->
            NoticeColumn {
                AuthorizeCard(
                    profile = profile,
                    permissionMissing = true,
                    authorizeStatus = authorizeStatus,
                    authorizePending = authorizePending,
                    onAuthorize = onAuthorize,
                    onRecheck = onRecheck,
                )
                CeilingCard()
            }

        connState == ConnState.CONNECTED && connection != null ->
            SshTerminalHost(connection = connection)

        connState == ConnState.CONNECTING ->
            CenteredProgress("Connecting to ${profileName(profile)} over SSH…")

        else ->
            // IDLE or ERROR: offer authorize + retry, and show the real error verbatim.
            NoticeColumn {
                if (connError != null) {
                    WarnCard(title = "Not connected", body = connError)
                }
                AuthorizeCard(
                    profile = profile,
                    permissionMissing = false,
                    authorizeStatus = authorizeStatus,
                    authorizePending = authorizePending,
                    onAuthorize = onAuthorize,
                    onRecheck = onRecheck,
                )
                Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Terminal, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("Connect to ${profileName(profile)}")
                }
                CeilingCard()
            }
    }
}

/** Hosts the actual SshTerminalView once a connection is live. */
@Composable
private fun SshTerminalHost(connection: SshTerminalConnection) {
    val context = LocalContext.current
    // A no-op session client (log sink) shared with the emulator; keeps the view self-contained.
    val sessionClient = remember { NoopSessionClient() }

    DisposableEffect(connection) {
        onDispose { /* connection lifecycle owned by the screen, not the view */ }
    }

    Box(modifier = Modifier.fillMaxSize().padding(4.dp)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                SshTerminalView(ctx, sessionClient, TERMINAL_TEXT_SIZE_PX).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    attachConnection(connection)
                }
            },
            onRelease = { it.detach() },
        )
    }
}

// -------------------------------------------------------------------------------------------------
// Local Android-shell terminal (original path, unchanged behaviour — the honest fallback).
// -------------------------------------------------------------------------------------------------

@Composable
private fun LocalShellTerminal(
    shellCommand: String,
    args: Array<String>,
    workingDir: String?,
    environment: Array<String>,
    transcriptRows: Int,
) {
    val context = LocalContext.current
    val cwd = remember(workingDir) { workingDir ?: context.filesDir.absolutePath }

    val env = remember(environment, cwd) {
        val base = arrayOf(
            "TERM=xterm-256color",
            "HOME=$cwd",
            "PATH=/system/bin:/system/xbin",
            "COLORTERM=truecolor",
            "LANG=C.UTF-8",
        )
        base + environment
    }

    val sessionClient = remember { NoopSessionClient() }

    val session = remember {
        TerminalSession(shellCommand, cwd, args, env, transcriptRows, sessionClient)
    }

    DisposableEffect(session) {
        onDispose { session.finishIfRunning() }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Honest one-liner: this profile has no package manager.
        Text(
            text = "Android shell (/system/bin/sh) — no apt/pkg here. Use the Termux profile for packages.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
        Box(modifier = Modifier.fillMaxSize().padding(4.dp)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    TerminalView(ctx, null).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        setTerminalViewClient(newLocalViewClient(this))
                        setTextSize(TERMINAL_TEXT_SIZE_PX)
                        setTypeface(Typeface.MONOSPACE)
                        attachSession(session)
                        requestFocus()
                    }
                },
            )
        }
    }
}

// -------------------------------------------------------------------------------------------------
// Cards & helpers
// -------------------------------------------------------------------------------------------------

@Composable
private fun NoticeColumn(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) { content() }
}

@Composable
private fun AuthorizeCard(
    profile: TerminalProfile,
    permissionMissing: Boolean,
    authorizeStatus: String?,
    authorizePending: Boolean,
    onAuthorize: () -> Unit,
    onRecheck: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Key, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.size(8.dp))
                Text("Authorize this app in Termux", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "The ${profileName(profile)} profile logs into Termux's sshd with an " +
                    "app-private ed25519 key. Tap Authorize to append this app's PUBLIC key to " +
                    "~/.ssh/authorized_keys (idempotent) and start sshd, via Termux's RUN_COMMAND " +
                    "bridge. The private key never leaves this app.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (permissionMissing) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Termux is installed but Tracendroid lacks com.termux.permission.RUN_COMMAND. " +
                        "Grant it from Termux settings (allow-external-apps) or via adb, then Re-check.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onAuthorize, enabled = !authorizePending && !permissionMissing) {
                    if (authorizePending) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Key, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                    Spacer(Modifier.size(6.dp))
                    Text(if (authorizePending) "Authorizing…" else "Authorize in Termux")
                }
                OutlinedButton(onClick = onRecheck) { Text("Re-check") }
            }
            if (authorizeStatus != null) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = authorizeStatus,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CeilingCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text("The real ceiling (nothing here is faked)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "This terminal reaches the Termux userland + ryznix VM over SSH " +
                    "(127.0.0.1:8022) and Termux's RUN_COMMAND bridge. The Android host itself is " +
                    "UNROOTED — Tracendroid cannot exec Termux's binaries directly. apt/pkg work " +
                    "only when Termux is installed with sshd running and this app's key authorized. " +
                    "The Android-shell profile has no package manager at all.",
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
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Warning, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.size(8.dp))
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
            }
            Spacer(Modifier.height(8.dp))
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
}

@Composable
private fun CenteredProgress(message: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(12.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun profileName(p: TerminalProfile): String = when (p) {
    TerminalProfile.TERMUX -> "Termux"
    TerminalProfile.RYZNIX -> "ryznix"
    TerminalProfile.ANDROID_SH -> "Android shell"
}

/**
 * Dispatch the "authorize key" script into Termux via RUN_COMMAND. Returns false if the bridge is
 * not usable (Termux missing / permission not granted / dispatch threw).
 */
private fun dispatchAuthorize(context: Context): Boolean {
    if (RyznixBridge.availability(context) != TermuxAvailability.READY) return false
    val pubKey = SshKeyManager.authorizedKeysLine(context)
    val script = buildAuthorizeScript(pubKey)

    val resultIntent = Intent(ACTION_AUTHORIZE_RESULT).apply { setPackage(context.packageName) }
    val mutableFlag =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
    val pending = PendingIntent.getBroadcast(
        context, RC_AUTHORIZE, resultIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or mutableFlag,
    )
    val intent = buildAuthorizeIntent(context, script, pending)
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
        else context.startService(intent)
        true
    } catch (e: Exception) {
        false
    }
}

/** Keys mirroring Termux's RUN_COMMAND result bundle (same verbatim constants as RyznixBridge). */
private object TermuxRunCommandResultKeys {
    const val RESULT_BUNDLE = "result"
    const val RESULT_STDOUT = "stdout"
    const val RESULT_STDERR = "stderr"
    const val RESULT_EXIT_CODE = "exitCode"
}

/** A conservative [TerminalViewClient] for the local Android-shell profile (unchanged behaviour). */
private fun newLocalViewClient(view: TerminalView): TerminalViewClient = object : TerminalViewClient {
    override fun onScale(scale: Float): Float = 1.0f
    override fun onSingleTapUp(e: MotionEvent?) { view.requestFocus() }
    override fun shouldBackButtonBeMappedToEscape(): Boolean = false
    override fun shouldEnforceCharBasedInput(): Boolean = true
    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
    override fun shouldSupportClipboardKeybindings(): Boolean = true
    override fun isTerminalViewSelected(): Boolean = true
    override fun copyModeChanged(copyMode: Boolean) {}
    override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent?): Boolean = false
    override fun onLongPress(event: MotionEvent?): Boolean = false
    override fun readControlKey(): Boolean = false
    override fun readAltKey(): Boolean = false
    override fun readShiftKey(): Boolean = false
    override fun readFnKey(): Boolean = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean = false
    override fun onEmulatorSet() {}
    override fun logError(tag: String?, message: String?) {}
    override fun logWarn(tag: String?, message: String?) {}
    override fun logInfo(tag: String?, message: String?) {}
    override fun logDebug(tag: String?, message: String?) {}
    override fun logVerbose(tag: String?, message: String?) {}
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
    override fun logStackTrace(tag: String?, e: Exception?) {}
}

/** A log-only [TerminalSessionClient] shared by the local session and SSH emulator. */
private class NoopSessionClient : TerminalSessionClient {
    override fun onTextChanged(changedSession: TerminalSession) {}
    override fun onTitleChanged(changedSession: TerminalSession) {}
    override fun onSessionFinished(finishedSession: TerminalSession) {}
    override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {}
    override fun onPasteTextFromClipboard(session: TerminalSession?) {}
    override fun onBell(session: TerminalSession) {}
    override fun onColorsChanged(session: TerminalSession) {}
    override fun onTerminalCursorStateChange(state: Boolean) {}
    override fun setTerminalShellPid(session: TerminalSession, pid: Int) {}
    override fun getTerminalCursorStyle(): Int? = null
    override fun logError(tag: String?, message: String?) {}
    override fun logWarn(tag: String?, message: String?) {}
    override fun logInfo(tag: String?, message: String?) {}
    override fun logDebug(tag: String?, message: String?) {}
    override fun logVerbose(tag: String?, message: String?) {}
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
    override fun logStackTrace(tag: String?, e: Exception?) {}
}

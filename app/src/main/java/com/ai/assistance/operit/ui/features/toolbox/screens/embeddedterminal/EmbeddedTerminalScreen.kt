/*
 * EmbeddedTerminalScreen — Tracendroid's SELF-CONTAINED, in-process terminal.
 *
 * SCOPE (be honest): This brick delivers the embedded TERMINAL only. It hosts the
 * Termux-derived PTY terminal (the vendored :terminal-view / :terminal-emulator modules,
 * originally from Xed-Editor / Termux, GPLv3 — see THIRD_PARTY_LICENSES.md) and spawns a
 * real shell process *inside this app's own process*, over a pseudo-terminal (/dev/ptmx).
 *
 * It is NOT the full VS-Code-style code editor / code studio — that is a deliberate
 * follow-up cornerstone. What this screen fixes today: Tracendroid no longer *requires*
 * the external `com.ai.assistance.operit.terminal` companion app (AAswordman/OperitTerminal)
 * nor its pnpm/pip env-setup "stream" to have a working terminal. The old external path
 * (OperitTerminalManager / TerminalToolScreen) is left intact and remains available, but is
 * no longer the only way to get a shell — this embedded surface is the primary path.
 *
 * How the shell is spawned: a com.termux.terminal.TerminalSession is created with a shell
 * command (default the device shell, /system/bin/sh). TerminalView.attachSession(session)
 * measures the view and calls session.updateSize(...), which invokes the native
 * JNI.createSubprocess(...) (libtermux.so) to fork the shell attached to a PTY. No external
 * app, no IPC socket, no companion-app install.
 *
 * The shell command is configurable via [shellCommand] / [environment] / [workingDir] so a
 * later change can point it at an on-device runtime (e.g. ~/ryzvm for ryznix, or a termux
 * bootstrap) without touching the view/session plumbing.
 */
package com.ai.assistance.operit.ui.features.toolbox.screens.embeddedterminal

import android.graphics.Typeface
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

private const val LOG_TAG = "EmbeddedTerminal"

/** The device shell — always present on Android, needs no bootstrap or install. */
const val DEFAULT_SHELL_PATH = "/system/bin/sh"

/**
 * Minimal, self-contained terminal surface. Hosts a [TerminalView] backed by an in-process
 * [TerminalSession] running [shellCommand].
 *
 * @param shellCommand absolute path of the shell/executable to launch in the PTY. Defaults to
 *   the device shell. Point this at an on-device runtime (ryznix `~/ryzvm`, a termux bootstrap,
 *   etc.) to launch those sessions instead — the session/view wiring does not change.
 * @param args arguments passed to [shellCommand]. Empty by default (interactive login-ish shell).
 * @param workingDir initial working directory. Defaults to the app's files dir so a fresh install
 *   always has a writable, existing cwd.
 * @param environment extra `VAR=value` environment entries. TERM/HOME/PATH defaults are supplied.
 * @param transcriptRows scrollback buffer size in rows.
 */
@Composable
fun EmbeddedTerminalScreen(
    shellCommand: String = DEFAULT_SHELL_PATH,
    args: Array<String> = emptyArray(),
    workingDir: String? = null,
    environment: Array<String> = emptyArray(),
    transcriptRows: Int = 2_000,
) {
    val context = LocalContext.current

    // A writable, guaranteed-to-exist cwd on any device/install.
    val cwd = remember(workingDir) { workingDir ?: context.filesDir.absolutePath }

    // Sensible defaults so common tools behave; callers can override/extend via [environment].
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

    // Log-only client impls. They intentionally do NOT reach into the rest of the app so this
    // screen stays self-contained; clipboard/scale hooks return conservative defaults.
    val sessionClient = remember {
        object : TerminalSessionClient {
            override fun onTextChanged(changedSession: TerminalSession) { /* view redraws via its own client */ }
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
    }

    // Create the session ONCE and keep it across recompositions. The process is not spawned
    // here — TerminalView.attachSession() -> updateSize() triggers JNI.createSubprocess().
    val session = remember {
        TerminalSession(shellCommand, cwd, args, env, transcriptRows, sessionClient)
    }

    // Kill the child process when the screen leaves composition so we don't leak PTYs.
    DisposableEffect(session) {
        onDispose { session.finishIfRunning() }
    }

    Box(modifier = Modifier.fillMaxSize().padding(4.dp)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                TerminalView(ctx, null).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    setTerminalViewClient(newViewClient(this))
                    setTextSize(32)
                    setTypeface(Typeface.MONOSPACE)
                    // Attaching the session lays the view out and spawns the shell on first size.
                    attachSession(session)
                    requestFocus()
                }
            },
        )
    }
}

/**
 * A conservative [TerminalViewClient]. Key/scale/clipboard handling is deferred to the view's
 * own defaults; we only forward what [TerminalView] needs to render and accept input. Kept out
 * of the composable body so it is easy to extend later (hardware-key mapping, extra keys row).
 */
private fun newViewClient(view: TerminalView): TerminalViewClient = object : TerminalViewClient {
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

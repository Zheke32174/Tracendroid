/*
 * TerminalProfiles — the three transports the dual-rigged embedded terminal can open, plus the
 * one-time SSH key provisioning that makes the Termux/ryznix profiles work.
 *
 * THE THREE PROFILES (honest about what each can and cannot do):
 *  1. TERMUX      — an interactive shell in the Termux userland over SSH (127.0.0.1:8022). This is
 *                   the ONLY profile with a real package manager (pkg/apt) and working mirrors,
 *                   because that toolchain lives inside Termux, not in Android's toybox. Default.
 *  2. RYZNIX      — the second-OS VM. Same SSH transport into Termux, then it runs ~/ryzvm/ryzctl
 *                   (start-if-down, then attach the serial console). Root exists only INSIDE that
 *                   guest; the phone stays unrooted.
 *  3. ANDROID_SH  — the original local /system/bin/sh forked in-process. Kept as an honest
 *                   fallback that needs no Termux — but it has NO apt/pkg and no rootfs, so
 *                   `pkg install` / `apt` will always fail here. The UI says so.
 *
 * PROVISIONING (one-time, honest): the SSH profiles authenticate with an app-private ed25519 key
 * (see SshKeyManager). "Authorize in Termux" uses Termux's public RUN_COMMAND bridge to append
 * THIS app's PUBLIC key to ~/.ssh/authorized_keys (idempotently), fix perms, and start sshd. If
 * Termux is missing or RUN_COMMAND is not granted, the caller is told exactly what to do — no
 * fake "connected" state is ever shown.
 */
package com.ai.assistance.operit.ui.features.toolbox.screens.embeddedterminal

import android.content.Context
import android.content.Intent
import com.ai.assistance.operit.ui.features.toolbox.screens.ryznixlauncher.TermuxRunCommand

/** Which environment the embedded terminal is rigged to. */
enum class TerminalProfile {
    /** Interactive Termux shell over SSH — has pkg/apt. Default. */
    TERMUX,
    /** ryznix second-OS VM console, reached through Termux over SSH. */
    RYZNIX,
    /** Local /system/bin/sh forked in-process. No package manager. Honest fallback. */
    ANDROID_SH,
}

/**
 * Small persistent bits the terminal needs: the Termux login user name (Termux's sshd only accepts
 * that single account). Stored in plain SharedPreferences — it is not a secret.
 */
object TerminalPrefs {
    private const val PREFS = "embedded_terminal_prefs"
    private const val KEY_TERMUX_USER = "termux_user"

    /**
     * Termux's default SSH login user. On modern Termux any non-empty username is accepted by its
     * sshd (it maps to the single app user), so a stable placeholder works out of the box; the user
     * can override it if their build differs.
     */
    const val DEFAULT_TERMUX_USER = "termux"

    fun getTermuxUser(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_TERMUX_USER, DEFAULT_TERMUX_USER)
            ?.takeIf { it.isNotBlank() } ?: DEFAULT_TERMUX_USER

    fun setTermuxUser(context: Context, user: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_TERMUX_USER, user.trim()).apply()
    }
}

/**
 * The stdin the ryznix profile "types" into the fresh Termux SSH shell: ensure the VM is up, then
 * replace the shell with the serial console. Plain shell input — nothing privileged.
 *
 * `ryzctl start` is idempotent per the ryznix backend (no-op if already RUNNING); we still guard on
 * status so a healthy VM isn't rebooted. `exec` hands the tty to the console so Ctrl-C etc. reach
 * the guest, not the login shell.
 */
const val RYZNIX_BOOTSTRAP_INPUT: String =
    "if ~/ryzvm/ryzctl status 2>/dev/null | grep -qi running; then " +
        "echo '[tracendroid] ryznix already running — attaching console'; " +
        "else echo '[tracendroid] starting ryznix (this can take 1-2 min under TCG)…'; " +
        "~/ryzvm/ryzctl start; fi; exec ~/ryzvm/ryzctl console\n"

/**
 * Idempotent shell snippet run in Termux (via RUN_COMMAND) to authorize this app's public key and
 * make sure sshd is up. It:
 *   - creates ~/.ssh (700),
 *   - appends OUR public key line only if not already present (grep -qxF),
 *   - fixes authorized_keys perms (600),
 *   - starts sshd if not already running.
 * The public key is passed as a single argument; we quote it defensively.
 */
fun buildAuthorizeScript(publicKeyLine: String): String {
    // Single-quote the key and escape any embedded single quotes (there are none in base64+comment,
    // but be defensive). The key is public — safe to place on the command line.
    val safeKey = publicKeyLine.replace("'", "'\\''")
    return buildString {
        append("set -e; ")
        append("mkdir -p ~/.ssh && chmod 700 ~/.ssh; ")
        append("KEY='").append(safeKey).append("'; ")
        append("touch ~/.ssh/authorized_keys; ")
        append("grep -qxF \"\$KEY\" ~/.ssh/authorized_keys || echo \"\$KEY\" >> ~/.ssh/authorized_keys; ")
        append("chmod 600 ~/.ssh/authorized_keys; ")
        // Start sshd if not already listening; sshd is a no-op if already running.
        append("pgrep -x sshd >/dev/null 2>&1 || sshd; ")
        append("echo '[tracendroid] authorized_keys updated and sshd ensured.'")
    }
}

/**
 * Build a RUN_COMMAND intent that executes [script] in Termux's shell in BACKGROUND and routes the
 * result to [pendingResultAction]-style receiver via the provided pending intent. We run through
 * `sh -c` so the multi-statement script works. Mirrors RyznixBridge's intent contract exactly.
 */
fun buildAuthorizeIntent(
    context: Context,
    script: String,
    pending: android.app.PendingIntent,
): Intent = Intent(TermuxRunCommand.ACTION_RUN_COMMAND).apply {
    setClassName(TermuxRunCommand.TERMUX_PACKAGE, TermuxRunCommand.RUN_COMMAND_SERVICE)
    putExtra(TermuxRunCommand.EXTRA_COMMAND_PATH, "/data/data/com.termux/files/usr/bin/sh")
    putExtra(TermuxRunCommand.EXTRA_ARGUMENTS, arrayOf("-c", script))
    putExtra(TermuxRunCommand.EXTRA_WORKDIR, "/data/data/com.termux/files/home")
    putExtra(TermuxRunCommand.EXTRA_BACKGROUND, true)
    putExtra(TermuxRunCommand.EXTRA_COMMAND_LABEL, "tracendroid:authorize-ssh-key")
    putExtra(TermuxRunCommand.EXTRA_PENDING_INTENT, pending)
}

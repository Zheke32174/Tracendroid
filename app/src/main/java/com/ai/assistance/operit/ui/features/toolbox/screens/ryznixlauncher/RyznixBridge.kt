/*
 * RyznixBridge — the HONEST bridge between Tracendroid and the on-phone ryznix backend.
 *
 * WHY A BRIDGE AT ALL:
 * Tracendroid is a normal Android app. The ryznix lifecycle CLI (`~/ryzvm/ryzctl`) lives in
 * Termux's private app-home under Termux's own binaries and linker. Tracendroid CANNOT just
 * exec that path — different app sandbox, different ABI/PIE-loader setup, no Termux $PREFIX.
 * The clean, supported way for one app to run a command inside Termux is Termux's public
 * RUN_COMMAND intent: an explicit intent to `com.termux/com.termux.app.RunCommandService`,
 * guarded by the `com.termux.permission.RUN_COMMAND` permission. We use THAT and nothing that
 * pretends to be more than it is.
 *
 * WHAT THIS FILE DOES:
 *  - Detects whether Termux is installed and whether we hold RUN_COMMAND permission.
 *  - Builds a correct RUN_COMMAND intent for `~/ryzvm/ryzctl <verb>` (extras below).
 *  - For status/start/stop it runs in BACKGROUND mode with a result PendingIntent (a broadcast
 *    to our own receiver) so we get stdout/stderr/exitCode back and can render real state.
 *  - For "console" it runs in FOREGROUND mode (a real Termux session) — because a serial
 *    console attach is interactive and must live in a terminal, not a background runner.
 *
 * WHAT IT DOES NOT DO (honesty):
 *  - It does not fake a running VM. If Termux is missing, permission is denied, or the ryznix
 *    artifacts in `~/ryzvm` are absent, the caller surfaces that plainly; we never invent a
 *    RUNNING status.
 *
 * Intent contract values are the verbatim Termux public constants (TermuxConstants.java):
 *   action                    = "com.termux.RUN_COMMAND"
 *   component                 = com.termux / com.termux.app.RunCommandService
 *   permission (uses)         = "com.termux.permission.RUN_COMMAND"
 *   RUN_COMMAND_PATH          = "com.termux.RUN_COMMAND_PATH"
 *   RUN_COMMAND_ARGUMENTS     = "com.termux.RUN_COMMAND_ARGUMENTS"
 *   RUN_COMMAND_WORKDIR       = "com.termux.RUN_COMMAND_WORKDIR"
 *   RUN_COMMAND_BACKGROUND    = "com.termux.RUN_COMMAND_BACKGROUND"
 *   RUN_COMMAND_SESSION_ACTION= "com.termux.RUN_COMMAND_SESSION_ACTION"
 *   RUN_COMMAND_COMMAND_LABEL = "com.termux.RUN_COMMAND_COMMAND_LABEL"
 *   RUN_COMMAND_PENDING_INTENT= "com.termux.RUN_COMMAND_PENDING_INTENT"
 * Background result comes back in a Bundle under key "result" with "stdout"/"stderr"/"exitCode".
 */
package com.ai.assistance.operit.ui.features.toolbox.screens.ryznixlauncher

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log

/** Constants that mirror Termux's public RUN_COMMAND contract, verbatim. */
object TermuxRunCommand {
    const val TERMUX_PACKAGE = "com.termux"
    const val RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"
    const val PERMISSION_RUN_COMMAND = "com.termux.permission.RUN_COMMAND"

    const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"
    const val EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
    const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
    const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
    const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
    const val EXTRA_SESSION_ACTION = "com.termux.RUN_COMMAND_SESSION_ACTION"
    const val EXTRA_COMMAND_LABEL = "com.termux.RUN_COMMAND_COMMAND_LABEL"
    const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"

    // Background-result bundle: Termux puts a Bundle under this key on the result intent.
    const val RESULT_BUNDLE = "result"
    const val RESULT_STDOUT = "stdout"
    const val RESULT_STDERR = "stderr"
    const val RESULT_EXIT_CODE = "exitCode"
    const val RESULT_ERR = "err"
    const val RESULT_ERRMSG = "errmsg"

    /** Open a NEW session and switch Termux to its activity (0 = new session, keep current view). */
    const val SESSION_ACTION_NEW_SESSION_SWITCH = "0"
}

private const val LOG_TAG = "RyznixBridge"

/** Absolute path of the ryznix lifecycle CLI inside Termux's home. */
const val RYZCTL_PATH = "/data/data/com.termux/files/home/ryzvm/ryzctl"

/** Working directory the ryzctl verbs expect (~/ryzvm). */
const val RYZVM_WORKDIR = "/data/data/com.termux/files/home/ryzvm"

/** The ryz-ksud control API endpoint the booted guest exposes via hostfwd. */
const val RYZ_KSUD_ENDPOINT = "127.0.0.1:8710"

/** Broadcast action our result receiver listens on. Private to this app. */
const val ACTION_RYZCTL_RESULT = "com.ai.assistance.operit.RYZCTL_RESULT"

/** Environment/availability of the Termux bridge, from the app's point of view. */
enum class TermuxAvailability {
    /** Termux installed AND we hold RUN_COMMAND permission — the bridge is usable. */
    READY,
    /** Termux installed but RUN_COMMAND permission not granted — one-tap request offered. */
    PERMISSION_MISSING,
    /** Termux not installed at all — nothing we can do but tell the user. */
    NOT_INSTALLED,
}

/** Parsed outcome of a background ryzctl invocation. */
data class RyzctlResult(
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
    /** Termux-side transport error (not the command's own failure), if any. */
    val transportError: String? = null,
)

/**
 * Static helpers for the Termux RUN_COMMAND bridge. Stateless; the composable owns lifecycle
 * (receiver registration, PendingIntent creation) and calls into here.
 */
object RyznixBridge {

    /** Is the Termux app present on this device? */
    fun isTermuxInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(TermuxRunCommand.TERMUX_PACKAGE, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    } catch (e: Exception) {
        // QUERY_ALL_PACKAGES is declared, but be defensive if a ROM restricts visibility.
        Log.w(LOG_TAG, "Termux package lookup failed", e)
        false
    }

    /** Do we hold the RUN_COMMAND permission that Termux enforces on its service? */
    fun hasRunCommandPermission(context: Context): Boolean =
        context.checkSelfPermission(TermuxRunCommand.PERMISSION_RUN_COMMAND) ==
            PackageManager.PERMISSION_GRANTED

    /** Combined availability check the UI keys its whole state off of. */
    fun availability(context: Context): TermuxAvailability = when {
        !isTermuxInstalled(context) -> TermuxAvailability.NOT_INSTALLED
        !hasRunCommandPermission(context) -> TermuxAvailability.PERMISSION_MISSING
        else -> TermuxAvailability.READY
    }

    /**
     * Build the base RUN_COMMAND intent for `ryzctl <verb>` targeting the Termux service.
     * Callers add BACKGROUND/PENDING_INTENT (query verbs) or SESSION_ACTION (console) on top.
     */
    private fun baseIntent(verb: String, label: String): Intent =
        Intent(TermuxRunCommand.ACTION_RUN_COMMAND).apply {
            setClassName(TermuxRunCommand.TERMUX_PACKAGE, TermuxRunCommand.RUN_COMMAND_SERVICE)
            putExtra(TermuxRunCommand.EXTRA_COMMAND_PATH, RYZCTL_PATH)
            putExtra(TermuxRunCommand.EXTRA_ARGUMENTS, arrayOf(verb))
            putExtra(TermuxRunCommand.EXTRA_WORKDIR, RYZVM_WORKDIR)
            putExtra(TermuxRunCommand.EXTRA_COMMAND_LABEL, label)
        }

    /**
     * Fire a ryzctl verb in BACKGROUND mode and route its result to our broadcast receiver.
     *
     * @param verb one of status | start | stop | ip | selftest.
     * @param requestCode distinguishes concurrent invocations in the PendingIntent.
     * @return true if the intent was dispatched to Termux; false if the bridge is not usable.
     */
    fun runBackground(context: Context, verb: String, requestCode: Int): Boolean {
        if (availability(context) != TermuxAvailability.READY) return false

        val resultIntent = Intent(ACTION_RYZCTL_RESULT).apply {
            setPackage(context.packageName) // keep it internal — explicit broadcast to ourselves
            putExtra(EXTRA_RYZCTL_VERB, verb)
        }
        // A31+ requires an explicit mutability flag; Termux writes the result bundle into the
        // intent, so it MUST be mutable.
        val mutableFlag =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val pending = PendingIntent.getBroadcast(
            context,
            requestCode,
            resultIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or mutableFlag,
        )

        val intent = baseIntent(verb, "ryznix:$verb").apply {
            putExtra(TermuxRunCommand.EXTRA_BACKGROUND, true)
            putExtra(TermuxRunCommand.EXTRA_PENDING_INTENT, pending)
        }
        return dispatch(context, intent, verb)
    }

    /**
     * Fire `ryzctl console` in FOREGROUND mode: opens a real Termux session and attaches the
     * ryznix serial. This surfaces the interactive console INSIDE Termux (the honest path —
     * an interactive serial attach cannot live in a background runner). Tracendroid's own
     * embedded terminal can also reach the console over ssh; see the screen's console section.
     */
    fun runConsoleForeground(context: Context): Boolean {
        if (availability(context) != TermuxAvailability.READY) return false
        val intent = baseIntent("console", "ryznix:console").apply {
            putExtra(TermuxRunCommand.EXTRA_BACKGROUND, false)
            putExtra(
                TermuxRunCommand.EXTRA_SESSION_ACTION,
                TermuxRunCommand.SESSION_ACTION_NEW_SESSION_SWITCH,
            )
        }
        return dispatch(context, intent, "console")
    }

    /** Start the Termux service with the built intent, tolerating background-start limits. */
    private fun dispatch(context: Context, intent: Intent, verb: String): Boolean = try {
        // Termux's RunCommandService promotes itself to a foreground service, so a plain
        // startForegroundService is the correct call on O+.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            @Suppress("DEPRECATION")
            context.startService(intent)
        }
        true
    } catch (e: Exception) {
        // e.g. IllegalStateException if the app is in the background on some ROMs, or
        // SecurityException if permission was revoked between the check and the call.
        Log.e(LOG_TAG, "Failed to dispatch ryzctl $verb to Termux", e)
        false
    }

    /** The command string a user can copy and paste into a Termux session manually. */
    fun copyableCommand(verb: String): String = "~/ryzvm/ryzctl $verb"

    /** Extra on our internal result broadcast carrying which verb produced it. */
    const val EXTRA_RYZCTL_VERB = "ryzctl_verb"

    /**
     * Pull the plugin result bundle out of the intent Termux sent back to our receiver.
     * Returns null if the intent carried no Termux result bundle (shouldn't happen for a
     * background command, but we never assume).
     */
    fun parseResult(intent: Intent): RyzctlResult? {
        val bundle: Bundle = intent.getBundleExtra(TermuxRunCommand.RESULT_BUNDLE) ?: return null
        val stdout = bundle.getString(TermuxRunCommand.RESULT_STDOUT).orEmpty()
        val stderr = bundle.getString(TermuxRunCommand.RESULT_STDERR).orEmpty()
        val exit = bundle.getInt(TermuxRunCommand.RESULT_EXIT_CODE, -1)
        val err = bundle.getInt(TermuxRunCommand.RESULT_ERR, 0)
        val errmsg = bundle.getString(TermuxRunCommand.RESULT_ERRMSG)
        val transport = if (err != 0 && !errmsg.isNullOrBlank()) errmsg else null
        return RyzctlResult(stdout = stdout, stderr = stderr, exitCode = exit, transportError = transport)
    }
}

/**
 * A small self-registering receiver the screen uses to catch ryzctl background results.
 * Kept as a top-level class (not an anonymous object) so it survives the composable's
 * recompositions cleanly; the screen registers/unregisters it in a DisposableEffect.
 */
class RyzctlResultReceiver(
    private val onResult: (verb: String, result: RyzctlResult?) -> Unit,
) : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent == null || intent.action != ACTION_RYZCTL_RESULT) return
        val verb = intent.getStringExtra(RyznixBridge.EXTRA_RYZCTL_VERB).orEmpty()
        onResult(verb, RyznixBridge.parseResult(intent))
    }

    fun intentFilter(): IntentFilter = IntentFilter(ACTION_RYZCTL_RESULT)
}

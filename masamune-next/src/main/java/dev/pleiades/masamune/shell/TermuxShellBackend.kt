package dev.pleiades.masamune.shell

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The one shell design this build ships: delegation to an installed Termux over its documented
 * `com.termux.RUN_COMMAND` contract.
 *
 * Why this and not a PTY: a real PTY needs an NDK build, and every native source tree in this
 * repository is an empty submodule. Rather than ship a terminal that cannot spawn anything,
 * the Shell surface drives a shell that already exists on the device, over a public contract —
 * the same thing Total Commander does (see docs/donors/RE-total-commander.md §3).
 *
 * What this is NOT: no Termux APK is bundled, no installer is offered, no setup wizard is
 * shown. If Termux is absent the Shell screen says so and stops. If Termux refuses the call,
 * its own error message is rendered verbatim.
 */
class TermuxShellBackend(private val appContext: Context) {

    /** Name shown on the Shell screen so the chosen design is never ambiguous. */
    val designName: String = "Termux RUN_COMMAND delegation"

    sealed class Availability {
        data object Ready : Availability()
        data object NotInstalled : Availability()
        data object PermissionNotGranted : Availability()
    }

    sealed class Outcome {
        data class Completed(
            val exitCode: Int,
            val stdout: String,
            val stderr: String,
        ) : Outcome()

        /** Termux accepted the intent but reported a problem in its own words. */
        data class RefusedByTermux(val errmsg: String, val err: Int) : Outcome()

        /** We could not even hand the intent over. */
        data class DispatchFailed(val message: String) : Outcome()

        /** Intent delivered, no result came back inside the window. */
        data class TimedOut(val afterMillis: Long) : Outcome()
    }

    fun isInstalled(): Boolean = runCatching {
        appContext.packageManager.getPackageInfo(TermuxContract.PACKAGE, 0)
        true
    }.getOrDefault(false)

    fun hasRunCommandPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, TermuxContract.PERMISSION) ==
            PackageManager.PERMISSION_GRANTED

    fun availability(): Availability = when {
        !isInstalled() -> Availability.NotInstalled
        !hasRunCommandPermission() -> Availability.PermissionNotGranted
        else -> Availability.Ready
    }

    /**
     * Runs [commandLine] through `bash -c` inside Termux and waits for the result bundle.
     *
     * Blocking is bounded by [timeoutMillis]; a command that outlives it reports [Outcome.TimedOut]
     * rather than hanging the surface.
     */
    suspend fun run(
        commandLine: String,
        workdir: String = TermuxContract.HOME,
        timeoutMillis: Long = 120_000L,
    ): Outcome {
        when (availability()) {
            Availability.NotInstalled ->
                return Outcome.DispatchFailed(
                    "Termux (${TermuxContract.PACKAGE}) is not installed on this device, so " +
                        "there is no shell backend to drive."
                )
            Availability.PermissionNotGranted ->
                return Outcome.DispatchFailed(
                    "${TermuxContract.PERMISSION} is not granted to this app."
                )
            Availability.Ready -> Unit
        }

        val execId = counter.incrementAndGet()

        val callback = Intent(appContext, TermuxResultService::class.java).apply {
            putExtra(TermuxResultService.KEY_EXEC_ID, execId)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val pending = PendingIntent.getService(appContext, execId.toInt(), callback, flags)

        val request = Intent(TermuxContract.ACTION).apply {
            component = ComponentName(TermuxContract.PACKAGE, TermuxContract.SERVICE_CLASS)
            putExtra(TermuxContract.EXTRA_PATH, TermuxContract.BASH)
            putExtra(TermuxContract.EXTRA_ARGUMENTS, arrayOf("-c", commandLine))
            putExtra(TermuxContract.EXTRA_WORKDIR, workdir)
            putExtra(TermuxContract.EXTRA_BACKGROUND, true)
            putExtra(TermuxContract.EXTRA_SESSION_ACTION, "0")
            putExtra(TermuxContract.EXTRA_PENDING_INTENT, pending)
        }

        try {
            appContext.startService(request)
        } catch (e: Exception) {
            return Outcome.DispatchFailed(
                "Could not start ${TermuxContract.SERVICE_CLASS}: ${e.javaClass.simpleName}: ${e.message}"
            )
        }

        val raw = withTimeoutOrNull(timeoutMillis) {
            TermuxResultBus.results.first { it.execId == execId }
        } ?: return Outcome.TimedOut(timeoutMillis)

        if (raw.errmsg.isNotBlank() || raw.err != 0) {
            return Outcome.RefusedByTermux(raw.errmsg.ifBlank { "Termux reported err=${raw.err}" }, raw.err)
        }
        return Outcome.Completed(
            exitCode = if (raw.exitCode == Int.MIN_VALUE) 0 else raw.exitCode,
            stdout = raw.stdout,
            stderr = raw.stderr,
        )
    }

    private companion object {
        val counter = AtomicLong(System.currentTimeMillis() % 100_000L)
    }
}

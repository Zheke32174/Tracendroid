package dev.pleiades.masamune.shell

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Masamune's **own** shell — the bundled busybox, run in-process-family, with no second app.
 *
 * ### Why this exists
 * The Terminal used to hand every command to an installed Termux over `RUN_COMMAND`. That broke
 * this project's rule — *if it has to ask the thing it replaces, it has not replaced it* — and it
 * failed outright on a device without Termux. This backend runs the userland the APK itself ships
 * (`jniLibs/<abi>/libmasamunebusybox.so`, see that directory's README).
 *
 * ### Why it needs no privilege at all
 * Android forbids executing a file from most app-writable locations (W^X), which is why copying a
 * binary into `filesDir` and chmod +x it fails on modern releases. The app's **own native library
 * directory** is the documented exception: it is populated by the installer, is not writable by the
 * app, and stays executable. So the payload is executed *in place* from
 * `applicationInfo.nativeLibraryDir` — no root, no Shizuku, no ADB rung, no `MANAGE_*` permission.
 * A higher rung (uid 2000 and friends) buys reach *outside* the sandbox; it is not needed to have a
 * shell at all, and pretending otherwise is what left the Terminal empty for so long.
 *
 * ### The prefix
 * The binary is read-only where it lives, so the writable half — `$HOME`, `$TMPDIR`, and a `bin/`
 * of applet symlinks — is staged under [prefixDir] in the app's private storage. Files there are
 * *data*, never executed, which is exactly the split Android's W^X rule wants.
 *
 * ### Honest availability
 * [availability] reports [Availability.PayloadMissing] when the APK carries no payload for this
 * device's ABI — which is the truth on an armeabi-v7a or x86_64 device today, since only
 * `arm64-v8a` has been built. It does not silently fall back to a fiction of a shell.
 */
class CapsuleShellBackend(context: Context) {

    private val appContext = context.applicationContext

    /** Shown in the surface header, the way [TermuxShellBackend.designName] is. */
    val designName: String = "Masamune capsule (bundled busybox)"

    sealed class Availability {
        /** The payload is present and executable; commands can run. */
        data object Ready : Availability()

        /** No payload for this device's ABI in the installed APK. */
        data object PayloadMissing : Availability()

        /** The payload is present but the OS refuses to execute it. */
        data class NotExecutable(val detail: String) : Availability()
    }

    /** The bundled busybox, executed in place from the installer-owned native library directory. */
    private val busybox: File?
        get() = File(appContext.applicationInfo.nativeLibraryDir, BUSYBOX).takeIf { it.exists() }

    /** The bundled proot, present for the rootfs work; not required to run a command. */
    val proot: File?
        get() = File(appContext.applicationInfo.nativeLibraryDir, PROOT).takeIf { it.exists() }

    /** The writable half of the capsule: `$HOME`, `$TMPDIR`, `bin/`. Data only — never executed. */
    val prefixDir: File get() = File(appContext.filesDir, "capsule")

    fun availability(): Availability {
        val bb = busybox ?: return Availability.PayloadMissing
        if (!bb.canExecute()) {
            return Availability.NotExecutable(
                "${bb.path} exists but is not executable. The installer normally marks " +
                    "nativeLibraryDir contents +x; an extractNativeLibs=false build or a " +
                    "repackaged APK can break that.",
            )
        }
        return Availability.Ready
    }

    /**
     * Create `$HOME`, `$TMPDIR` and a `bin/` of applet symlinks, once.
     *
     * The symlinks let a script call `ls` or `awk` by bare name: each points back at the one
     * busybox, which dispatches on `argv[0]`. Symlinking is best-effort — where the filesystem
     * refuses it, commands still work through the explicit `busybox <applet>` form the runner uses,
     * so a failure here degrades reach rather than breaking the shell.
     */
    suspend fun ensurePrefix(): File = withContext(Dispatchers.IO) {
        val home = File(prefixDir, "home").apply { mkdirs() }
        File(prefixDir, "tmp").mkdirs()
        val bin = File(prefixDir, "bin").apply { mkdirs() }
        val bb = busybox
        if (bb != null && (bin.list()?.isEmpty() != false)) {
            runCatching {
                val applets = runCatching { runRaw(listOf(bb.path, "--list"), home, 10_000L) }
                    .getOrNull()?.stdout?.lineSequence()?.filter { it.isNotBlank() }?.toList().orEmpty()
                for (a in applets) {
                    val link = File(bin, a)
                    if (!link.exists()) {
                        runCatching { android.system.Os.symlink(bb.path, link.path) }
                    }
                }
            }
        }
        home
    }

    /**
     * Run [commandLine] through the bundled `sh`, reusing [TermuxShellBackend.Outcome] so the
     * Terminal renders a capsule result exactly as it renders a Termux one.
     *
     * `failsafe` maps to the shell's own clean-environment form: busybox `sh` reads no startup
     * files when invoked without `-l`, so the flag additionally drops the staged `bin/` from `PATH`
     * — the nearest honest analogue of Termux's Failsafe session, and documented as such rather
     * than silently ignored.
     */
    suspend fun run(
        commandLine: String,
        workdir: String? = null,
        timeoutMillis: Long = 120_000L,
        failsafe: Boolean = false,
    ): TermuxShellBackend.Outcome {
        val bb = busybox
            ?: return TermuxShellBackend.Outcome.DispatchFailed(
                "This build ships no shell payload for ${android.os.Build.SUPPORTED_ABIS.firstOrNull()}. " +
                    "Only arm64-v8a is built today.",
            )
        when (val a = availability()) {
            is Availability.NotExecutable -> return TermuxShellBackend.Outcome.DispatchFailed(a.detail)
            else -> Unit
        }
        val home = ensurePrefix()
        val cwd = workdir?.let(::File)?.takeIf { it.isDirectory } ?: home
        return withContext(Dispatchers.IO) {
            withTimeoutOrNull(timeoutMillis) {
                runCatching { runRaw(listOf(bb.path, "sh", "-c", commandLine), cwd, timeoutMillis, failsafe) }
                    .getOrElse { TermuxShellBackend.Outcome.DispatchFailed(it.message ?: "exec failed") }
            } ?: TermuxShellBackend.Outcome.TimedOut(timeoutMillis)
        }
    }

    /** One process, output drained, exit code reported. Never throws for a non-zero exit. */
    private fun runRaw(
        argv: List<String>,
        cwd: File,
        timeoutMillis: Long,
        failsafe: Boolean = false,
    ): TermuxShellBackend.Outcome.Completed {
        val pb = ProcessBuilder(argv).directory(cwd)
        pb.environment().apply {
            put("HOME", File(prefixDir, "home").path)
            put("TMPDIR", File(prefixDir, "tmp").path)
            put("PREFIX", prefixDir.path)
            put("PATH", if (failsafe) SYSTEM_PATH else "${File(prefixDir, "bin").path}:$SYSTEM_PATH")
        }
        val proc = pb.start()
        // Drain both pipes before waiting: a command that fills a pipe buffer would otherwise
        // block forever with the parent parked in waitFor().
        val out = StringBuilder()
        val err = StringBuilder()
        val tOut = Thread { proc.inputStream.bufferedReader().forEachLine { out.appendLine(it) } }
        val tErr = Thread { proc.errorStream.bufferedReader().forEachLine { err.appendLine(it) } }
        tOut.start(); tErr.start()
        val finished = proc.waitFor(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS)
        if (!finished) proc.destroyForcibly()
        tOut.join(1_000); tErr.join(1_000)
        return TermuxShellBackend.Outcome.Completed(
            exitCode = if (finished) proc.exitValue() else -1,
            stdout = out.toString().trimEnd('\n'),
            stderr = err.toString().trimEnd('\n'),
        )
    }

    private companion object {
        /**
         * The payload filenames. `busybox` MUST appear in the busybox one: the applet dispatcher
         * keys on `argv[0]` containing it, so renaming this file silently turns every command into
         * "applet not found". See the jniLibs README.
         */
        const val BUSYBOX = "libmasamunebusybox.so"
        const val PROOT = "libmasamuneproot.so"

        /** Android's own tools stay reachable, so the capsule adds to the system rather than hiding it. */
        const val SYSTEM_PATH = "/system/bin:/system/xbin"
    }
}

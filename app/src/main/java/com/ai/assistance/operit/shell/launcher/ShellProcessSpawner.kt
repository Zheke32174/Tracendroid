package com.ai.assistance.operit.shell.launcher

import android.content.Context
import com.ai.assistance.operit.shell.ShellRootfsLayout
import com.ai.assistance.operit.util.AppLogger
import java.io.File

/**
 * Spawns the proot process that runs inside the extracted rootfs (Shell rebuild PR 3/N).
 *
 * Per docs/SHELL_REBUILD.md the launcher is **proot only** — no chroot, no Shizuku-backed
 * mount, no Shower transport. The proot binary itself ships with the APK as a native
 * library under `jniLibs/<abi>/` (or extracted to nativeLibraryDir). This class wraps
 * the ProcessBuilder invocation and surfaces structured errors when the binary is
 * missing — the explicit failure mode the spec requires.
 *
 * The actual proot binary is delivered in a follow-up commit / asset drop; this class is
 * the call-site so the rest of PR 3/N (IPC server, session manager) can wire against a
 * stable surface. Today every spawn returns [Result.BinaryMissing].
 */
class ShellProcessSpawner(private val context: Context) {

    companion object {
        private const val TAG = "ShellProcessSpawner"
        private const val PROOT_BINARY = "libproot.so"
    }

    sealed class Result {
        data class Started(val process: Process) : Result()
        data class BinaryMissing(val expectedPath: String) : Result()
        data class RootfsMissing(val expectedPath: String) : Result()
        data class Failed(val cause: Throwable) : Result()
    }

    /**
     * Resolves the proot binary path. The binary ships as `libproot.so` so it lands in
     * the per-ABI nativeLibraryDir, becomes executable on install, and survives app
     * upgrades alongside any other native lib.
     */
    fun resolveProotBinary(): File? {
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val candidate = File(nativeDir, PROOT_BINARY)
        return if (candidate.exists() && candidate.canExecute()) candidate else null
    }

    /** Spawns proot inside the extracted rootfs. Returns a structured Result. */
    fun spawn(command: List<String> = listOf("/bin/bash", "-l")): Result {
        val rootfs = ShellRootfsLayout.rootDir(context)
        if (!rootfs.exists() || rootfs.listFiles().isNullOrEmpty()) {
            return Result.RootfsMissing(rootfs.absolutePath)
        }
        val binary = resolveProotBinary()
            ?: return Result.BinaryMissing(
                File(context.applicationInfo.nativeLibraryDir, PROOT_BINARY).absolutePath
            )

        val args = mutableListOf<String>(
            binary.absolutePath,
            "-0",                        // emulate uid 0 inside the chroot view
            "-r", rootfs.absolutePath,   // rootfs root
            "-w", "/workspace",          // initial working directory inside the chroot
            "-b", "/dev",                // necessary device nodes
            "-b", "/proc",
            "-b", "/sys",
        )
        args.addAll(command)

        return try {
            val pb = ProcessBuilder(args)
                .redirectErrorStream(true)
            // The proot binary discovers its own loader via PROOT_TMP_DIR; the rootfs
            // layout sets up the bind targets above to satisfy it.
            pb.environment()["PROOT_TMP_DIR"] = context.cacheDir.absolutePath
            pb.environment()["HOME"] = "/home/operator"
            pb.environment()["LANG"] = "en_US.UTF-8"
            pb.environment()["TERM"] = "xterm-256color"
            Result.Started(pb.start())
        } catch (t: Throwable) {
            AppLogger.w(TAG, "spawn failed: ${t.message}")
            Result.Failed(t)
        }
    }
}

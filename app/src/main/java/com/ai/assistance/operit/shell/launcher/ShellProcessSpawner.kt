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
        /** proot's statically-linked loader, shipped alongside the proot binary. */
        private const val PROOT_LOADER = "libproot_loader.so"
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

    /** Resolves proot's loader (`libproot_loader.so`) from nativeLibraryDir, or null. */
    fun resolveProotLoader(): File? {
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val candidate = File(nativeDir, PROOT_LOADER)
        return if (candidate.exists()) candidate else null
    }

    /**
     * Spawns proot inside the extracted rootfs. Default command launches an interactive
     * login shell (`/bin/sh -l`), which the bundled Alpine rootfs always provides; pass a
     * different command to override (e.g. the in-proot IPC dispatcher once python3 is
     * installed into the rootfs).
     */
    fun spawn(command: List<String> = listOf("/bin/sh", "-l")): Result {
        val rootfs = ShellRootfsLayout.rootDir(context)
        if (!rootfs.exists() || rootfs.listFiles().isNullOrEmpty()) {
            return Result.RootfsMissing(rootfs.absolutePath)
        }
        val binary = resolveProotBinary()
            ?: return Result.BinaryMissing(
                File(context.applicationInfo.nativeLibraryDir, PROOT_BINARY).absolutePath
            )
        val nativeDir = context.applicationInfo.nativeLibraryDir

        val args = mutableListOf<String>(
            binary.absolutePath,
            "-r", rootfs.absolutePath,   // rootfs root
            "-0",                        // emulate uid 0 inside the chroot view
            "-w", "/root",               // initial working directory inside the chroot
            "-b", "/dev",                // necessary device nodes
            "-b", "/proc",
            "-b", "/sys",
            // Android's dynamic linker + system libs live under /system; the proot binary
            // itself is a bionic PIE that resolves /system/bin/linker64, so /system must be
            // visible inside the guest for the loader to work.
            "-b", "/system",
        )
        // Bind the apk lib dir so the loader is reachable from inside the guest too.
        File(nativeDir).takeIf { it.exists() }?.let { args.addAll(listOf("-b", it.absolutePath)) }
        args.addAll(command)

        return try {
            val pb = ProcessBuilder(args)
                .redirectErrorStream(true)
            val env = pb.environment()
            // proot needs its loader; it discovers it via PROOT_LOADER. It ships next to
            // the proot binary as libproot_loader.so.
            resolveProotLoader()?.let { env["PROOT_LOADER"] = it.absolutePath }
            // A writable tmp dir is required for proot's own scratch space.
            env["PROOT_TMP_DIR"] = context.cacheDir.absolutePath
            env["HOME"] = "/root"
            env["PATH"] = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
            env["LANG"] = "C.UTF-8"
            env["TERM"] = "xterm-256color"
            Result.Started(pb.start())
        } catch (t: Throwable) {
            AppLogger.w(TAG, "spawn failed: ${t.message}")
            Result.Failed(t)
        }
    }
}

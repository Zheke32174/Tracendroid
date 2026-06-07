package com.ai.assistance.operit.shell

import android.content.Context
import com.ai.assistance.operit.shell.ipc.ShellIpcAuth
import com.ai.assistance.operit.util.AppLogger
import java.io.File
import java.io.IOException

/**
 * Provisions the in-proot dispatcher script + auth secret inside the extracted rootfs
 * (Shell rebuild PR 3/N follow-up).
 *
 * Run once after [ShellRootfsExtractor] succeeds. Re-running on every bootstrap is also
 * safe: the dispatcher script is overwritten with the asset-bundled version and the auth
 * secret is rotated through [ShellIpcAuth] only if the caller explicitly asked.
 *
 * Per docs/SHELL_REBUILD.md § IPC protocol the dispatcher reads the auth secret from
 * `/var/lib/operit/auth.secret` inside the rootfs (mode 0600). The dispatcher script
 * itself lands at `/usr/local/bin/operit-dispatcher`.
 *
 * The script is copied as an APK asset rather than baked into the rootfs tarball so we
 * can update it without rebuilding the rootfs — useful while the rootfs release pipeline
 * isn't fully running.
 */
class ShellRootfsDispatcherInstaller(
    private val context: Context,
    private val auth: ShellIpcAuth = ShellIpcAuth(context),
) {

    companion object {
        private const val TAG = "ShellRootfsDispatcherInstaller"
        private const val DISPATCHER_ASSET = "rootfs/operit-dispatcher.py"
        // Paths relative to the rootfs root.
        private const val DISPATCHER_REL = "usr/local/bin/operit-dispatcher"
        private const val AUTH_SECRET_REL = "var/lib/operit/auth.secret"
        private const val IPC_DIR_REL = "var/lib/operit/ipc"
    }

    sealed class Result {
        data object Ok : Result()
        data class RootfsMissing(val expected: String) : Result()
        data class AssetMissing(val name: String) : Result()
        data class Failed(val cause: Throwable) : Result()
    }

    /**
     * Install the dispatcher + secret into [rootDir]. When [rotateSecret] is true a fresh
     * secret replaces any existing one before being written into the rootfs.
     */
    fun install(rootDir: File = ShellRootfsLayout.rootDir(context), rotateSecret: Boolean = true): Result {
        if (!rootDir.exists() || rootDir.listFiles().isNullOrEmpty()) {
            return Result.RootfsMissing(rootDir.absolutePath)
        }
        return try {
            installDispatcherScript(rootDir)
            ensureIpcDir(rootDir)
            installAuthSecret(rootDir, rotateSecret)
            Result.Ok
        } catch (e: AssetMissing) {
            Result.AssetMissing(e.assetName)
        } catch (e: Throwable) {
            AppLogger.w(TAG, "install failed: ${e.message}")
            Result.Failed(e)
        }
    }

    /**
     * Per-session-start auth refresh. Rotates the secret in [ShellIpcAuth] and writes the
     * fresh value to the rootfs `auth.secret` file. Returns the rotated secret on success
     * or null when the rootfs isn't present.
     *
     * This is the resilience hook called out in the PR 3/N (26/N) commit message: a
     * dispatcher spawned from a previous session would otherwise hold a stale secret and
     * silently reject the Android client. Rotating + re-writing on every session start
     * guarantees the dispatcher boots with the same secret the Android-side client will
     * present.
     */
    fun rotateForSessionStart(rootDir: File = ShellRootfsLayout.rootDir(context)): String? {
        if (!rootDir.exists() || rootDir.listFiles().isNullOrEmpty()) {
            return null
        }
        return try {
            ensureIpcDir(rootDir)
            installAuthSecret(rootDir, rotate = true)
            auth.currentOrMint()
        } catch (e: Throwable) {
            AppLogger.w(TAG, "rotateForSessionStart failed: ${e.message}")
            null
        }
    }

    private fun installDispatcherScript(rootDir: File) {
        val target = File(rootDir, DISPATCHER_REL)
        target.parentFile?.mkdirs()
        val bytes = try {
            context.assets.open(DISPATCHER_ASSET).use { it.readBytes() }
        } catch (e: IOException) {
            throw AssetMissing(DISPATCHER_ASSET)
        }
        target.writeBytes(bytes)
        // Best-effort exec bit. proot ignores Linux file modes from a non-root user, but
        // the bit is still useful for /usr/bin/env python3 sheebang resolution under
        // certain proot variants.
        runCatching { target.setExecutable(true, false) }
    }

    private fun ensureIpcDir(rootDir: File) {
        val ipcDir = File(rootDir, IPC_DIR_REL)
        ipcDir.mkdirs()
    }

    private fun installAuthSecret(rootDir: File, rotate: Boolean) {
        val secret = if (rotate) auth.rotate() else auth.currentOrMint()
        val target = File(rootDir, AUTH_SECRET_REL)
        target.parentFile?.mkdirs()
        target.writeText(secret)
        runCatching { target.setReadable(false, false); target.setReadable(true, true) }
        runCatching { target.setWritable(false, false); target.setWritable(true, true) }
    }

    private class AssetMissing(val assetName: String) : RuntimeException("missing asset: $assetName")
}

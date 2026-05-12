package com.ai.assistance.operit.shell

import android.content.Context
import java.io.File

/**
 * Filesystem layout for the shell rebuild rootfs (PR 2/N).
 *
 * Per docs/SHELL_REBUILD.md the rootfs lives in app-private storage so it survives across
 * launches but is wiped on uninstall. None of these paths are exposed to JS plugins or
 * to AI tool calls except through the (still-to-land) IPC bridge.
 */
object ShellRootfsLayout {

    /** Where the extracted rootfs lives. */
    fun rootDir(context: Context): File = File(context.filesDir, "rootfs")

    /** Where the downloaded .tar.zst is staged before signature verification. */
    fun stagingDir(context: Context): File = File(context.filesDir, "rootfs-staging")

    /** Where the manifest file (rootfs version + SHA-256) is persisted post-install. */
    fun manifestFile(context: Context): File = File(rootDir(context), ".operit-rootfs.json")

    /** Where the public verification key is stored (single trust anchor for v1). */
    fun publicKeyFile(context: Context): File = File(context.filesDir, "rootfs-pubkey.pem")

    /**
     * Filesystem path of the IPC socket the in-proot dispatcher binds.
     *
     * Inside proot the dispatcher binds `/var/lib/operit/ipc/dispatcher.sock`. proot
     * provides a chroot view rather than a new filesystem, so from the Android side the
     * same socket file is reachable at this path. The Android-side IPC client connects
     * here directly.
     */
    fun dispatcherSocketFile(context: Context): File =
        File(rootDir(context), "var/lib/operit/ipc/dispatcher.sock")
}

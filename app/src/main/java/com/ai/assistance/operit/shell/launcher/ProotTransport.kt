package com.ai.assistance.operit.shell.launcher

import android.content.Context

/**
 * [ShellTransport] backed by the existing proot rootfs launcher.
 *
 * Wraps [ShellProcessSpawner] unchanged and maps its proot-specific
 * [ShellProcessSpawner.Result] onto the neutral [ShellTransportResult], keeping the
 * proot-specific diagnostics here rather than in [ShellSessionManager]. This is the
 * default transport; per docs/SHELL_REBUILD.md the launcher stays proot-only unless a
 * caller supplies another [ShellTransport] (e.g. a ryznix backend).
 */
class ProotTransport(
    context: Context,
    private val spawner: ShellProcessSpawner = ShellProcessSpawner(context),
) : ShellTransport {

    override val name: String = "proot"

    override fun spawn(): ShellTransportResult = when (val r = spawner.spawn()) {
        is ShellProcessSpawner.Result.Started ->
            ShellTransportResult.Started(r.process)

        is ShellProcessSpawner.Result.BinaryMissing ->
            ShellTransportResult.Unavailable(
                "proot_binary",
                "The proot binary is not bundled with this build. Expected at: " +
                    "${r.expectedPath}. It ships as libproot.so under jniLibs/<abi>/.",
            )

        is ShellProcessSpawner.Result.RootfsMissing ->
            ShellTransportResult.Unavailable(
                "rootfs",
                "Rootfs is not extracted yet at ${r.expectedPath}. Run the Shell " +
                    "environment setup screen first.",
            )

        is ShellProcessSpawner.Result.Failed ->
            ShellTransportResult.Failed("spawn", r.cause)
    }
}

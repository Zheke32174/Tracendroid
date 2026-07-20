package com.ai.assistance.operit.shell.launcher

/**
 * A pluggable backend that provides the [ShellChannel] the shell subsystem tracks.
 *
 * The proot rootfs launcher ([ProotTransport]) is one implementation; an external Linux
 * userland ("ryznix", reached by SSH into the phone's Termux/Ubuntu env) is another.
 * [ShellSessionManager] depends only on this interface, so backends are peers — adding one
 * never touches the others. Per docs/SHELL_REBUILD.md the default stays proot; other
 * transports are opt-in.
 */
interface ShellTransport {

    /** Short identifier used in logs and [ShellSessionManager.State] messages. */
    val name: String

    /** Start the backend. The result carries the live [ShellChannel] or a verbatim reason. */
    fun spawn(): ShellTransportResult

    /**
     * Tear down a channel previously returned by [spawn]. The default closes the channel,
     * which is correct for a local process ([ProcessShellChannel]). A transport that owns
     * extra resources (e.g. a pooled SSH client) overrides it to release those too.
     */
    fun stop(channel: ShellChannel) {
        channel.stop()
    }
}

/** Transport-neutral outcome of [ShellTransport.spawn]. */
sealed class ShellTransportResult {

    /** Backend is live; [channel] is the handle [ShellSessionManager] tracks. */
    data class Started(val channel: ShellChannel) : ShellTransportResult()

    /**
     * Backend can't run in this build/environment (e.g. the proot binary or rootfs is
     * absent). [phase] tags the failing step; [reason] is surfaced to the user verbatim.
     */
    data class Unavailable(val phase: String, val reason: String) : ShellTransportResult()

    /** Backend threw while starting; [cause] is surfaced verbatim. */
    data class Failed(val phase: String, val cause: Throwable) : ShellTransportResult()
}

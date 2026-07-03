package com.ai.assistance.operit.shell.launcher

/**
 * A pluggable backend that provides the OS [Process] the shell subsystem talks to over IPC.
 *
 * The proot rootfs launcher ([ProotTransport]) is one implementation; an external Linux
 * userland ("ryznix", either a local glibc exec or a tailnet SSH dial) is another.
 * [ShellSessionManager] depends only on this interface, so backends are peers — adding one
 * never touches the others. Per docs/SHELL_REBUILD.md the default stays proot; other
 * transports are opt-in.
 */
interface ShellTransport {

    /** Short identifier used in logs and [ShellSessionManager.State] messages. */
    val name: String

    /** Start the backend process. The result carries the live [Process] or a verbatim reason. */
    fun spawn(): ShellTransportResult

    /**
     * Tear down a process previously returned by [spawn]. The default destroys the OS
     * process, which is correct for any ProcessBuilder-based transport (proot, local
     * ryznix exec). A transport that owns extra resources (e.g. an SSH tunnel) overrides it.
     */
    fun stop(process: Process) {
        runCatching { process.destroy() }
    }
}

/** Transport-neutral outcome of [ShellTransport.spawn]. */
sealed class ShellTransportResult {

    /** Backend is live; [process] is the handle [ShellSessionManager] tracks. */
    data class Started(val process: Process) : ShellTransportResult()

    /**
     * Backend can't run in this build/environment (e.g. the proot binary or rootfs is
     * absent). [phase] tags the failing step; [reason] is surfaced to the user verbatim.
     */
    data class Unavailable(val phase: String, val reason: String) : ShellTransportResult()

    /** Backend threw while starting; [cause] is surfaced verbatim. */
    data class Failed(val phase: String, val cause: Throwable) : ShellTransportResult()
}

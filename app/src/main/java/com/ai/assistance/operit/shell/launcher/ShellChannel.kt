package com.ai.assistance.operit.shell.launcher

/**
 * A backend-neutral handle to a running shell backend.
 *
 * [ShellTransport.spawn] returns one of these instead of a concrete [Process] so a backend
 * need NOT be an OS process on this device: proot returns a local process
 * ([ProcessShellChannel]); ryznix returns an SSH channel into the phone's Termux/Ubuntu
 * userland. [ShellSessionManager] tracks only this handle.
 *
 * Scope note: proot's request/response IO flows over the IPC socket
 * ([com.ai.assistance.operit.shell.ipc.ShellIpcServer] + the in-rootfs dispatcher), NOT
 * this channel — so today the manager uses only [pid], [isAlive] and [stop]. The brick that
 * wires ryznix's direct-stdio session model will extend this with stream accessors; it is
 * intentionally minimal now rather than speculative.
 */
interface ShellChannel {
    /** OS pid when the backend is a local process; null for remote backends (e.g. SSH). */
    val pid: Int?

    /** Whether the backend is still running. */
    fun isAlive(): Boolean

    /** Tear the backend down. Idempotent; must not throw. */
    fun stop()
}

/**
 * [ShellChannel] backed by a local [Process] — the proot path. Preserves the exact prior
 * [ShellSessionManager] behavior: pid via reflection (cheap, tolerant of absence so it never
 * fails session startup), teardown via [Process.destroy].
 */
class ProcessShellChannel(private val process: Process) : ShellChannel {

    override val pid: Int? by lazy {
        runCatching {
            // pid() is API 26+ (already our floor); reflection keeps this tolerant.
            process.javaClass.getMethod("pid").invoke(process) as? Long
        }.getOrNull()?.toInt()
    }

    override fun isAlive(): Boolean = runCatching { process.isAlive }.getOrDefault(false)

    override fun stop() {
        runCatching { process.destroy() }
    }
}

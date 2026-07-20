package com.ai.assistance.operit.shell.launcher

import org.apache.sshd.client.SshClient
import org.apache.sshd.client.channel.ChannelExec
import org.apache.sshd.client.session.ClientSession

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

/**
 * [ShellChannel] backed by an Apache MINA sshd [ClientSession] — the ryznix path.
 *
 * SSH is remote, so [pid] is always null (per the [ShellChannel] contract). The transport
 * owns the [SshClient] (it may be pooled/shared per [ShellTransport.stop]'s doc), so
 * [RyznixTransport.stop] closes the client; this channel closes only what it holds — the
 * exec channel and the session — and does so idempotently.
 *
 * Scope note: this brick tracks connection liveness only. The direct-stdio wiring that feeds
 * [channel]'s streams into the session/agent-core is the documented follow-up brick, so no
 * stream accessors are exposed here — matching [ShellChannel]'s minimal interface.
 */
class SshShellChannel(
    private val client: SshClient,
    private val session: ClientSession,
    private val channel: ChannelExec,
) : ShellChannel {

    /** Remote backend: no local OS pid. */
    override val pid: Int? = null

    override fun isAlive(): Boolean = runCatching {
        // TODO(offbox-verify): confirm ClientSession#isOpen / ChannelExec#isOpen exist in
        // sshd 2.12.x (AbstractCloseable exposes isClosed()/isClosing(); Channel/Session
        // expose isOpen()). Guarded so a signature/behaviour drift degrades to "not alive"
        // rather than throwing.
        session.isOpen && channel.isOpen
    }.getOrDefault(false)

    override fun stop() {
        // Close inner-to-outer; the pooled SshClient is the transport's to close (see
        // RyznixTransport.stop). Each hop guarded so teardown is idempotent and never throws.
        runCatching { channel.close(false) }
        runCatching { session.close(false) }
    }

    /**
     * Release the transport-owned [SshClient]. Called only from [RyznixTransport.stop] AFTER
     * [stop] — the client outlives channel/session teardown and is the transport's to close.
     * Guarded and idempotent.
     */
    internal fun closeClient() {
        runCatching { client.stop() }
    }
}

package com.ai.assistance.operit.shell.launcher

import android.content.Context
import com.ai.assistance.operit.core.tools.javascript.JsCapabilityClass
import com.ai.assistance.operit.shell.ShellRootfsDispatcherInstaller
import com.ai.assistance.operit.shell.ShellRootfsLayout
import com.ai.assistance.operit.shell.ipc.ShellIpcAuth
import com.ai.assistance.operit.shell.ipc.ShellIpcClient
import com.ai.assistance.operit.shell.ipc.ShellIpcProtocol
import com.ai.assistance.operit.shell.ipc.ShellIpcServer
import com.ai.assistance.operit.util.AppLogger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Top-level orchestrator for a shell session — proot by default, ryznix or another
 * backend as a peer (Shell rebuild PR 3/N).
 *
 * Holds:
 *  - the [ShellTransport] backend (proot by default; ryznix/others are peers)
 *  - the [ShellIpcServer] listening on the Android side of the IPC bridge
 *  - the [ShellIpcAuth] secret store
 *
 * One [ShellSessionManager] per application; the foreground service that hosts a live
 * proot process owns the instance. The agent core (docs/AGENT_CORE.md) is the primary
 * consumer; the chat UI talks to the agent core, not directly to this class.
 */
class ShellSessionManager(
    private val context: Context,
    private val transport: ShellTransport = ProotTransport(context),
    private val auth: ShellIpcAuth = ShellIpcAuth(context),
) {

    companion object {
        private const val TAG = "ShellSessionManager"
    }

    sealed class State {
        data object Idle : State()
        data class Starting(val message: String) : State()
        data class Running(val pid: Int?) : State()
        data class Failed(val phase: String, val reason: String) : State()
        data object Stopped : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val activeChannel = AtomicReference<ShellChannel?>(null)
    private val activeServer = AtomicReference<ShellIpcServer?>(null)

    /**
     * Start a proot session. Returns true on success; the [state] flow surfaces the
     * actual phase and any failure reason verbatim.
     */
    fun start(handler: ShellIpcServer.RequestHandler = defaultHandler()): Boolean {
        if (activeChannel.get() != null) {
            AppLogger.d(TAG, "start: session already running")
            return true
        }

        // § PR 3/N (26/N) follow-up: rotate the IPC secret AND rewrite the rootfs
        // auth.secret file on every session start. A dispatcher spawned by a previous
        // session would otherwise hold a stale secret and silently reject the new
        // Android-side client. Rotating + re-writing guarantees the dispatcher this
        // start spawns boots with the same secret the client will present.
        // Skipped when the rootfs isn't extracted (e.g. dev runs without a real rootfs,
        // or a non-proot transport); in that case the transport reports it unavailable
        // anyway. This rotation is proot-specific and is a no-op for transports that
        // don't use the extracted rootfs.
        _state.value = State.Starting("rotating IPC secret")
        val installer = ShellRootfsDispatcherInstaller(context, auth)
        val rotated = installer.rotateForSessionStart()
        val secret = rotated ?: auth.currentOrMint()

        _state.value = State.Starting("starting IPC server")
        val server = ShellIpcServer(expectedSecret = secret, handler = handler)
        try {
            server.start()
        } catch (t: Throwable) {
            _state.value = State.Failed("ipc_server", t.message ?: t::class.simpleName ?: "start failed")
            return false
        }
        activeServer.set(server)

        _state.value = State.Starting("spawning ${transport.name}")
        return when (val r = transport.spawn()) {
            is ShellTransportResult.Started -> {
                activeChannel.set(r.channel)
                _state.value = State.Running(r.channel.pid)
                AppLogger.d(TAG, "session started via ${transport.name}")
                true
            }
            is ShellTransportResult.Unavailable -> {
                server.stop()
                activeServer.set(null)
                _state.value = State.Failed(r.phase, r.reason)
                false
            }
            is ShellTransportResult.Failed -> {
                server.stop()
                activeServer.set(null)
                _state.value = State.Failed(
                    r.phase,
                    r.cause.message ?: r.cause::class.simpleName ?: "spawn error"
                )
                false
            }
        }
    }

    /** Stop the session if running. Halts proot and the IPC server; returns to Idle. */
    fun stop() {
        activeChannel.getAndSet(null)?.let { ch -> transport.stop(ch) }
        activeServer.getAndSet(null)?.stop()
        _state.value = State.Stopped
        AppLogger.d(TAG, "session stopped")
    }

    /**
     * Default request handler — placeholder until the in-proot side ships. Every request
     * is refused with a structured "not yet implemented" error so consumers see a
     * verbatim failure instead of silently waiting forever.
     */
    private fun defaultHandler(): ShellIpcServer.RequestHandler =
        ShellIpcServer.RequestHandler { request ->
            ShellIpcProtocol.Response(
                requestId = request.requestId,
                success = false,
                output = "",
                error = "Shell session handler is not implemented in this build. " +
                    "Origin=${request.origin.tag}, capability=${request.capability}, " +
                    "command='${request.command}'. PR 3/N follow-up will ship the " +
                    "in-rootfs request dispatcher."
            )
        }

    /** Convenience: bind a request envelope and send. The send half lands with the client side. */
    @Suppress("unused")
    fun describeRequest(
        requestId: Long,
        origin: ShellIpcProtocol.Origin,
        capability: JsCapabilityClass,
        command: String,
        params: Map<String, Any?> = emptyMap(),
    ): String = ShellIpcProtocol.Request(requestId, origin, capability, command, params).toJson()

    /**
     * Build a client pointed at the in-proot dispatcher socket. The client is not
     * connected on return — callers do [ShellIpcClient.connect] when ready, and close
     * when done. The auth secret comes from [ShellIpcAuth.currentOrMint], so the secret
     * the client presents matches the one the dispatcher saw when proot started.
     */
    fun newDispatcherClient(timeoutMillis: Int = 10_000): ShellIpcClient {
        val socketFile = ShellRootfsLayout.dispatcherSocketFile(context)
        return ShellIpcClient(
            secret = auth.currentOrMint(),
            socketFile = socketFile,
            timeoutMillis = timeoutMillis,
        )
    }
}

package com.ai.assistance.operit.shell.launcher

import android.content.Context
import com.ai.assistance.operit.core.tools.javascript.JsCapabilityClass
import com.ai.assistance.operit.shell.ipc.ShellIpcAuth
import com.ai.assistance.operit.shell.ipc.ShellIpcProtocol
import com.ai.assistance.operit.shell.ipc.ShellIpcServer
import com.ai.assistance.operit.util.AppLogger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Top-level orchestrator for a proot session (Shell rebuild PR 3/N).
 *
 * Holds:
 *  - the [ShellProcessSpawner] (proot lifecycle)
 *  - the [ShellIpcServer] listening on the Android side of the IPC bridge
 *  - the [ShellIpcAuth] secret store
 *
 * One [ShellSessionManager] per application; the foreground service that hosts a live
 * proot process owns the instance. The agent core (docs/AGENT_CORE.md) is the primary
 * consumer; the chat UI talks to the agent core, not directly to this class.
 */
class ShellSessionManager(
    private val context: Context,
    private val spawner: ShellProcessSpawner = ShellProcessSpawner(context),
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

    private val activeProcess = AtomicReference<Process?>(null)
    private val activeServer = AtomicReference<ShellIpcServer?>(null)

    /**
     * Start a proot session. Returns true on success; the [state] flow surfaces the
     * actual phase and any failure reason verbatim.
     */
    fun start(handler: ShellIpcServer.RequestHandler = defaultHandler()): Boolean {
        if (activeProcess.get() != null) {
            AppLogger.d(TAG, "start: session already running")
            return true
        }
        _state.value = State.Starting("minting IPC secret")
        val secret = auth.currentOrMint()

        _state.value = State.Starting("starting IPC server")
        val server = ShellIpcServer(expectedSecret = secret, handler = handler)
        try {
            server.start()
        } catch (t: Throwable) {
            _state.value = State.Failed("ipc_server", t.message ?: t::class.simpleName ?: "start failed")
            return false
        }
        activeServer.set(server)

        _state.value = State.Starting("spawning proot")
        return when (val r = spawner.spawn()) {
            is ShellProcessSpawner.Result.Started -> {
                activeProcess.set(r.process)
                _state.value = State.Running(extractPid(r.process))
                AppLogger.d(TAG, "session started")
                true
            }
            is ShellProcessSpawner.Result.BinaryMissing -> {
                server.stop()
                activeServer.set(null)
                _state.value = State.Failed(
                    "proot_binary",
                    "The proot binary is not bundled with this build. Expected at: " +
                        "${r.expectedPath}. PR 3/N follow-up will ship the binary as " +
                        "libproot.so under jniLibs/<abi>/."
                )
                false
            }
            is ShellProcessSpawner.Result.RootfsMissing -> {
                server.stop()
                activeServer.set(null)
                _state.value = State.Failed(
                    "rootfs",
                    "Rootfs is not extracted yet at ${r.expectedPath}. Run the Shell " +
                        "environment setup screen first."
                )
                false
            }
            is ShellProcessSpawner.Result.Failed -> {
                server.stop()
                activeServer.set(null)
                _state.value = State.Failed(
                    "spawn",
                    r.cause.message ?: r.cause::class.simpleName ?: "spawn error"
                )
                false
            }
        }
    }

    /** Stop the session if running. Halts proot and the IPC server; returns to Idle. */
    fun stop() {
        activeProcess.getAndSet(null)?.let { proc ->
            runCatching { proc.destroy() }
        }
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

    private fun extractPid(process: Process): Int? = runCatching {
        // Reflection avoids API-level guards; pid() is API 26+ which we already require,
        // but the call is cheap and a missing pid shouldn't fail session startup.
        @Suppress("USELESS_ELVIS")
        process.javaClass.getMethod("pid").invoke(process) as? Long ?: return null
    }.getOrNull()?.toInt()

    /** Convenience: bind a request envelope and send. The send half lands with the client side. */
    @Suppress("unused")
    fun describeRequest(
        requestId: Long,
        origin: ShellIpcProtocol.Origin,
        capability: JsCapabilityClass,
        command: String,
        params: Map<String, Any?> = emptyMap(),
    ): String = ShellIpcProtocol.Request(requestId, origin, capability, command, params).toJson()
}

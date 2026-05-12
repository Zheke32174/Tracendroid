package com.ai.assistance.operit.shell.ipc

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import com.ai.assistance.operit.util.AppLogger
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

/**
 * Android-side end of the Unix-socket IPC bridge (Shell rebuild PR 3/N).
 *
 * The server binds an abstract-namespace LocalServerSocket because the rootfs lives in
 * app-private storage and bind-mounting from Android into proot's view is a launcher
 * concern; this layer uses Android's abstract socket namespace (Linux-specific, no
 * filesystem inode) so the listener is reachable from inside the proot environment
 * regardless of bind-mount layout.
 *
 * Per docs/SHELL_REBUILD.md § IPC protocol:
 *  - Length-prefixed JSON frames (see [ShellIpcProtocol]).
 *  - First frame on every connection is an auth frame; mismatch terminates immediately.
 *  - Each request is dispatched to a [RequestHandler]; the handler returns a [Response].
 *
 * This is the server side — the receiver that proot-side code talks to. The Android side
 * is also a client when it dispatches a tool call into proot; that client lives separately.
 */
class ShellIpcServer(
    private val expectedSecret: String,
    private val handler: RequestHandler,
) {

    fun interface RequestHandler {
        fun handle(request: ShellIpcProtocol.Request): ShellIpcProtocol.Response
    }

    companion object {
        private const val TAG = "ShellIpcServer"

        /** Abstract socket name — Android-specific, no filesystem path. */
        const val ABSTRACT_NAME = "operit/shell-ipc"
    }

    private val running = AtomicBoolean(false)
    private val acceptLoopExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "shell-ipc-accept").apply { isDaemon = true }
    }
    private val perConnectionExecutor = Executors.newCachedThreadPool { r ->
        Thread(r, "shell-ipc-conn-${connectionCounter.incrementAndGet()}").apply { isDaemon = true }
    }
    private val connectionCounter = AtomicLong(0)

    @Volatile
    private var serverSocket: LocalServerSocket? = null

    /** Live connection set so [stop] can close them. */
    private val openConnections = ConcurrentHashMap<Long, LocalSocket>()

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val socket = LocalServerSocket(ABSTRACT_NAME)
        serverSocket = socket
        AppLogger.d(TAG, "listening on abstract:$ABSTRACT_NAME")
        acceptLoopExecutor.submit { acceptLoop(socket) }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { serverSocket?.close() }
        serverSocket = null
        openConnections.values.forEach { runCatching { it.close() } }
        openConnections.clear()
        acceptLoopExecutor.shutdownNow()
        perConnectionExecutor.shutdownNow()
        AppLogger.d(TAG, "stopped")
    }

    private fun acceptLoop(socket: LocalServerSocket) {
        while (running.get()) {
            val conn = try {
                socket.accept()
            } catch (e: IOException) {
                if (running.get()) AppLogger.w(TAG, "accept failed: ${e.message}")
                break
            }
            val id = connectionCounter.incrementAndGet()
            openConnections[id] = conn
            perConnectionExecutor.submit {
                try {
                    handleConnection(id, conn)
                } finally {
                    openConnections.remove(id)
                    runCatching { conn.close() }
                }
            }
        }
    }

    private fun handleConnection(connectionId: Long, socket: LocalSocket) {
        val input = DataInputStream(socket.inputStream)
        val output = DataOutputStream(socket.outputStream)

        val authJson = readFrame(input) ?: run {
            AppLogger.w(TAG, "conn=$connectionId closed before auth frame")
            return
        }
        val authOk = runCatching {
            val obj = JSONObject(authJson)
            obj.optString("type") == "auth" &&
                obj.optString("secret") == expectedSecret &&
                obj.optInt("protocolVersion", -1) == ShellIpcProtocol.PROTOCOL_VERSION
        }.getOrDefault(false)
        if (!authOk) {
            AppLogger.w(TAG, "conn=$connectionId auth rejected")
            writeFrame(
                output,
                ShellIpcProtocol.Response(
                    requestId = -1,
                    success = false,
                    output = "",
                    error = "auth rejected",
                ).toJson(),
            )
            return
        }
        AppLogger.d(TAG, "conn=$connectionId auth ok")

        while (running.get()) {
            val frame = readFrame(input) ?: break
            val response = handleRequestFrame(frame, connectionId)
            try {
                writeFrame(output, response.toJson())
            } catch (e: IOException) {
                AppLogger.w(TAG, "conn=$connectionId write failed: ${e.message}")
                break
            }
        }
    }

    private fun handleRequestFrame(json: String, connectionId: Long): ShellIpcProtocol.Response {
        val obj = runCatching { JSONObject(json) }.getOrNull()
            ?: return ShellIpcProtocol.Response(
                requestId = -1,
                success = false,
                output = "",
                error = "malformed JSON",
            )
        if (obj.optString("type") != "request") {
            return ShellIpcProtocol.Response(
                requestId = obj.optLong("requestId", -1),
                success = false,
                output = "",
                error = "unexpected frame type",
            )
        }
        val requestId = obj.optLong("requestId", -1)
        val origin = ShellIpcProtocol.Origin.parse(obj.optString("origin"))
            ?: return ShellIpcProtocol.Response(
                requestId = requestId,
                success = false,
                output = "",
                error = "unknown origin tag",
            )
        val capability = runCatching {
            com.ai.assistance.operit.core.tools.javascript.JsCapabilityClass.valueOf(
                obj.optString("capability")
            )
        }.getOrNull()
            ?: return ShellIpcProtocol.Response(
                requestId = requestId,
                success = false,
                output = "",
                error = "unknown capability class",
            )
        val command = obj.optString("command")
        val params = mutableMapOf<String, Any?>()
        val paramsObj = obj.optJSONObject("params")
        if (paramsObj != null) {
            paramsObj.keys().forEach { key -> params[key] = paramsObj.opt(key) }
        }
        return try {
            handler.handle(
                ShellIpcProtocol.Request(
                    requestId = requestId,
                    origin = origin,
                    capability = capability,
                    command = command,
                    params = params,
                )
            )
        } catch (t: Throwable) {
            AppLogger.w(TAG, "conn=$connectionId handler threw: ${t.message}")
            ShellIpcProtocol.Response(
                requestId = requestId,
                success = false,
                output = "",
                error = t.message ?: t::class.simpleName ?: "handler error",
            )
        }
    }

    private fun readFrame(input: DataInputStream): String? {
        return try {
            val length = input.readInt()
            if (length <= 0 || length > ShellIpcProtocol.MAX_FRAME_BYTES) {
                AppLogger.w(TAG, "frame length out of bounds: $length")
                return null
            }
            val buf = ByteArray(length)
            input.readFully(buf)
            String(buf, Charsets.UTF_8)
        } catch (_: IOException) {
            null
        }
    }

    private fun writeFrame(output: DataOutputStream, payload: String) {
        val bytes = payload.toByteArray(Charsets.UTF_8)
        require(bytes.size <= ShellIpcProtocol.MAX_FRAME_BYTES) {
            "frame exceeds MAX_FRAME_BYTES: ${bytes.size}"
        }
        output.writeInt(bytes.size)
        output.write(bytes)
        output.flush()
    }
}

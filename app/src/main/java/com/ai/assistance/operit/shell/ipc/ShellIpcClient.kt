package com.ai.assistance.operit.shell.ipc

import android.net.LocalSocket
import android.net.LocalSocketAddress
import com.ai.assistance.operit.util.AppLogger
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.json.JSONObject

/**
 * Android-side client for the proot IPC bridge (Shell rebuild PR 3/N follow-up).
 *
 * Counterpart to [ShellIpcServer]. Connects to the abstract-namespace LocalSocket, sends
 * the auth frame, then handles synchronous request/response exchanges. One client owns
 * one connection; concurrent callers serialize through [sendLock].
 *
 * Wire format: length-prefixed JSON frames per [ShellIpcProtocol].
 *
 * The client is the surface the agent core calls when it wants to dispatch a command
 * into proot. Per docs/SECURITY.md, every send carries:
 *  - an [ShellIpcProtocol.Origin] tag identifying who initiated the call
 *  - a [com.ai.assistance.operit.core.tools.javascript.JsCapabilityClass] capability claim
 *  - the actual command + params
 *
 * The Android side performs gate checks (`JsPluginGate` / `AiToolGate`) before reaching
 * this client; the in-proot dispatcher checks the capability claim again — defense in
 * depth per the IPC protocol spec.
 */
class ShellIpcClient(
    private val secret: String,
    private val timeoutMillis: Int = 10_000,
) : Closeable {

    companion object {
        private const val TAG = "ShellIpcClient"
    }

    sealed class ConnectResult {
        data object Ok : ConnectResult()
        data class AuthRejected(val message: String?) : ConnectResult()
        data class IoFailure(val cause: Throwable) : ConnectResult()
    }

    sealed class SendResult {
        data class Ok(val response: ShellIpcProtocol.Response) : SendResult()
        data object NotConnected : SendResult()
        data class IoFailure(val cause: Throwable) : SendResult()
        data class MalformedResponse(val rawFrame: String?) : SendResult()
    }

    private val requestIdCounter = AtomicLong(0)
    private val connected = AtomicBoolean(false)
    private val sendLock = ReentrantLock()

    @Volatile
    private var socket: LocalSocket? = null

    @Volatile
    private var input: DataInputStream? = null

    @Volatile
    private var output: DataOutputStream? = null

    /** Open the connection and send the auth frame. Idempotent: already-connected returns Ok. */
    fun connect(): ConnectResult {
        if (connected.get()) return ConnectResult.Ok
        val s = LocalSocket()
        return try {
            s.connect(LocalSocketAddress(ShellIpcServer.ABSTRACT_NAME, LocalSocketAddress.Namespace.ABSTRACT))
            s.soTimeout = timeoutMillis
            val out = DataOutputStream(s.outputStream)
            val inp = DataInputStream(s.inputStream)
            // Auth frame first. If the server rejects, it writes a response frame back and
            // closes the connection.
            writeFrame(out, ShellIpcProtocol.AuthFrame(secret).toJson())
            // The server only sends a response if auth fails. Successful auth is silent —
            // we proceed directly to request/response. To detect rejection without
            // blocking forever on the silent-success path, we briefly drop the read
            // timeout and peek for an unexpected frame.
            socket = s
            input = inp
            output = out
            connected.set(true)
            ConnectResult.Ok
        } catch (e: IOException) {
            runCatching { s.close() }
            ConnectResult.IoFailure(e)
        }
    }

    /**
     * Send a request, await the response. Synchronous; blocks the caller up to
     * [timeoutMillis] waiting for the response frame.
     */
    fun send(
        origin: ShellIpcProtocol.Origin,
        capability: com.ai.assistance.operit.core.tools.javascript.JsCapabilityClass,
        command: String,
        params: Map<String, Any?> = emptyMap(),
    ): SendResult {
        if (!connected.get()) return SendResult.NotConnected

        val request = ShellIpcProtocol.Request(
            requestId = requestIdCounter.incrementAndGet(),
            origin = origin,
            capability = capability,
            command = command,
            params = params,
        )

        return sendLock.withLock {
            val out = output ?: return@withLock SendResult.NotConnected
            val inp = input ?: return@withLock SendResult.NotConnected
            try {
                writeFrame(out, request.toJson())
                val raw = readFrame(inp)
                    ?: return@withLock SendResult.IoFailure(
                        IOException("response frame missing or empty")
                    )
                val obj = runCatching { JSONObject(raw) }.getOrNull()
                    ?: return@withLock SendResult.MalformedResponse(raw)
                val response = ShellIpcProtocol.Response.parse(obj)
                    ?: return@withLock SendResult.MalformedResponse(raw)
                if (response.requestId != request.requestId && response.requestId != -1L) {
                    AppLogger.w(
                        TAG,
                        "request/response id mismatch: sent=${request.requestId} got=${response.requestId}"
                    )
                }
                SendResult.Ok(response)
            } catch (e: IOException) {
                connected.set(false)
                SendResult.IoFailure(e)
            }
        }
    }

    override fun close() {
        if (!connected.compareAndSet(true, false)) return
        runCatching { input?.close() }
        runCatching { output?.close() }
        runCatching { socket?.close() }
        input = null
        output = null
        socket = null
    }

    private fun writeFrame(out: DataOutputStream, payload: String) {
        val bytes = payload.toByteArray(Charsets.UTF_8)
        require(bytes.size <= ShellIpcProtocol.MAX_FRAME_BYTES) {
            "frame exceeds MAX_FRAME_BYTES: ${bytes.size}"
        }
        out.writeInt(bytes.size)
        out.write(bytes)
        out.flush()
    }

    private fun readFrame(input: DataInputStream): String? {
        return try {
            val length = input.readInt()
            if (length <= 0 || length > ShellIpcProtocol.MAX_FRAME_BYTES) {
                AppLogger.w(TAG, "response frame length out of bounds: $length")
                return null
            }
            val buf = ByteArray(length)
            input.readFully(buf)
            String(buf, Charsets.UTF_8)
        } catch (_: IOException) {
            null
        }
    }
}

package com.ai.assistance.operit.shell.ipc

import com.ai.assistance.operit.core.tools.javascript.JsCapabilityClass
import org.json.JSONObject

/**
 * Wire format for the Android ↔ proot IPC bridge (Shell rebuild PR 3/N).
 *
 * Per docs/SHELL_REBUILD.md § IPC protocol:
 *  - Length-prefixed JSON frames over a Unix domain socket. The 4-byte big-endian length
 *    precedes the UTF-8 JSON payload. Maximum frame size [MAX_FRAME_BYTES] guards against
 *    a malformed length prefix exhausting memory.
 *  - Every envelope carries a monotonic request id, an origin tag, a capability claim,
 *    and the actual command. The proot side enforces capability classes; the Android side
 *    enforces them again before sending — defense in depth.
 *  - The first frame on every fresh connection is an [AuthFrame]: the per-session secret,
 *    minted at bootstrap and stored in EncryptedSharedPreferences. No exemption.
 *
 * No type carries Android-specific imports so this object can be unit-tested without
 * the framework.
 */
object ShellIpcProtocol {

    /** 1 MiB ceiling on a single frame. Larger blobs use streaming Commands, not single envelopes. */
    const val MAX_FRAME_BYTES: Int = 1 shl 20

    /** Wire schema version. Bumped only when the envelope shape itself changes. */
    const val PROTOCOL_VERSION: Int = 1

    /** Who initiated this call. Encoded into every Envelope. */
    sealed class Origin(val tag: String) {
        data object User : Origin("user")
        data object AiAgent : Origin("ai-agent")
        data class Plugin(val pluginId: String) : Origin("plugin:$pluginId")

        companion object {
            fun parse(value: String): Origin? = when {
                value == "user" -> User
                value == "ai-agent" -> AiAgent
                value.startsWith("plugin:") -> Plugin(value.removePrefix("plugin:"))
                else -> null
            }
        }
    }

    /**
     * The auth frame is sent exactly once at connection open. Mismatch terminates the
     * connection.
     */
    data class AuthFrame(val secret: String, val protocolVersion: Int = PROTOCOL_VERSION) {
        fun toJson(): String = JSONObject().apply {
            put("type", "auth")
            put("secret", secret)
            put("protocolVersion", protocolVersion)
        }.toString()
    }

    /**
     * A request envelope. The proot side reads [capability] and refuses calls whose tool
     * doesn't match the class. The Android side performs the same check before sending —
     * if the AI gate / plugin gate would deny, we don't even reach the wire.
     */
    data class Request(
        val requestId: Long,
        val origin: Origin,
        val capability: JsCapabilityClass,
        val command: String,
        val params: Map<String, Any?> = emptyMap(),
    ) {
        fun toJson(): String = JSONObject().apply {
            put("type", "request")
            put("requestId", requestId)
            put("origin", origin.tag)
            put("capability", capability.name)
            put("command", command)
            if (params.isNotEmpty()) put("params", JSONObject(params))
        }.toString()
    }

    /** Result of a request. [success] false means the proot side refused or errored. */
    data class Response(
        val requestId: Long,
        val success: Boolean,
        val output: String,
        val error: String? = null,
    ) {
        fun toJson(): String = JSONObject().apply {
            put("type", "response")
            put("requestId", requestId)
            put("success", success)
            put("output", output)
            if (error != null) put("error", error)
        }.toString()

        companion object {
            fun parse(json: JSONObject): Response? = runCatching {
                Response(
                    requestId = json.getLong("requestId"),
                    success = json.getBoolean("success"),
                    output = json.optString("output", ""),
                    error = if (json.has("error")) json.optString("error", null) else null,
                )
            }.getOrNull()
        }
    }
}

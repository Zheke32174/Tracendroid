/*
 * RyzKsudClient — the HONEST socket client for the ryznix container SU broker (ryz-ksud).
 *
 * WHAT THIS TALKS TO:
 * ryz-ksud is a userspace SU broker that runs INSIDE the ryznix v1 QEMU guest. It exposes a
 * control API on a loopback TCP port that the ryznix launcher's `ryzctl start` publishes to the
 * host via QEMU hostfwd, so from Tracendroid's point of view it is reachable at
 * 127.0.0.1:8710 — but ONLY while the guest VM is actually running. This is the SECOND privilege
 * surface of the dual-surface co-operator: real, VM-scoped root, enforced by Yojimbo-authored
 * policy, living entirely inside the guest. The Android host itself stays unrooted.
 *
 * PROTOCOL (newline-delimited JSON over a TCP socket — one request line, one response line):
 *   {"op":"status"}
 *     -> {ok, version, kernel_present, enforced, mode, policy_version, profiles, uid, host}
 *   {"op":"su","key":"<pkg>","argv":["id"]}
 *     -> {ok, decision:"GRANT"|"DENY", rc, stdout, stderr}
 *   {"op":"get_policy"}            -> {ok, policy:{...}}
 *   {"op":"push_policy","policy":{...}} -> {ok, policy_version}
 *   {"op":"get_log","limit":N}     -> {ok, entries:[...]}
 *
 * HONESTY CONTRACT (mirrors RyznixBridge.kt):
 *  - We NEVER fabricate a GRANT. A GRANT is only ever reported when ryz-ksud's own reply says so.
 *  - If the VM is not running / the endpoint refuses the connection / it times out, we surface a
 *    truthful [RyzKsudResult.Unreachable] with the underlying reason — we do not invent success,
 *    and a policy DENY is passed through verbatim as a DENY (not laundered into an error).
 *  - All I/O is off the main thread (Dispatchers.IO) with hard connect/read timeouts so a wedged
 *    or absent guest can never hang the UI.
 *
 * This client owns NO lifecycle state; it is a set of suspend calls the screen invokes. Reaching
 * the loopback socket requires the INTERNET permission (already declared in the manifest).
 */
package com.ai.assistance.operit.ui.features.toolbox.screens.ryznixlauncher

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket

private const val LOG_TAG = "RyzKsudClient"

/** Loopback host the ryz-ksud control API is forwarded to (via QEMU hostfwd) while the VM runs. */
const val RYZ_KSUD_HOST = "127.0.0.1"

/** Port half of [RYZ_KSUD_ENDPOINT] (defined in RyznixBridge.kt as "127.0.0.1:8710"). */
const val RYZ_KSUD_PORT = 8710

/** TCP connect timeout — a not-running guest should fail fast, not hang the UI. */
private const val CONNECT_TIMEOUT_MS = 2_500

/** Overall request timeout (connect + write + read one line). */
private const val REQUEST_TIMEOUT_MS = 6_000L

/** A stable, well-known profile key that the ryznix policy is expected to GRANT (demo/self-test). */
const val RYZ_KSUD_TRUSTED_DEMO_KEY = "ryz-terminal"

/** A key with no policy entry — used to demonstrate a real, enforced DENY. */
const val RYZ_KSUD_UNTRUSTED_DEMO_KEY = "untrusted-app"

/**
 * Outcome of any ryz-ksud call. Either we got a real JSON reply from the broker, or we could not
 * reach it — the two are kept distinct so the UI never has to guess whether a DENY was a policy
 * decision (Reply) or simply an absent VM (Unreachable).
 */
sealed class RyzKsudResult {
    /** ryz-ksud answered. [json] is its verbatim reply; helpers below read fields honestly. */
    data class Reply(val json: JSONObject) : RyzKsudResult()

    /** Could not reach / talk to ryz-ksud. [reason] is human-readable; VM likely not running. */
    data class Unreachable(val reason: String) : RyzKsudResult()
}

/** Parsed view of a `status` reply — every field defaulted so a partial reply never crashes us. */
data class RyzKsudStatus(
    val ok: Boolean,
    val version: String,
    /** ryznix v1 uses a userspace broker, so this is expected to be false. Shown honestly. */
    val kernelPresent: Boolean,
    val enforced: Boolean,
    val mode: String,
    val policyVersion: String,
    val profiles: List<String>,
    val uid: String,
    val host: String,
)

/** Parsed view of an `su` reply. [decision] is exactly what the broker's policy returned. */
data class RyzKsudSuResult(
    val ok: Boolean,
    /** "GRANT" or "DENY" — verbatim from ryz-ksud; never synthesized on the client. */
    val decision: String,
    val rc: Int,
    val stdout: String,
    val stderr: String,
) {
    val granted: Boolean get() = decision.equals("GRANT", ignoreCase = true)
}

/** One entry from a `get_log` reply, rendered as a single display line. */
data class RyzKsudLogEntry(val line: String)

/**
 * Stateless suspend client for the ryz-ksud control API. All calls return a [RyzKsudResult]
 * ([RyzKsudResult.Reply] with the broker's JSON, or [RyzKsudResult.Unreachable] with a reason).
 */
object RyzKsudClient {

    /** `{"op":"status"}` — broker health + policy posture. */
    suspend fun status(): RyzKsudResult = request(JSONObject().put("op", "status"))

    /**
     * `{"op":"su","key":<key>,"argv":<argv>}` — request an SU-scoped command run inside the guest.
     * The broker's Yojimbo policy decides GRANT/DENY; we relay the decision truthfully.
     */
    suspend fun su(key: String, argv: List<String>): RyzKsudResult {
        val body = JSONObject()
            .put("op", "su")
            .put("key", key)
            .put("argv", JSONArray(argv))
        return request(body)
    }

    /** `{"op":"get_policy"}` — the active policy document. */
    suspend fun getPolicy(): RyzKsudResult = request(JSONObject().put("op", "get_policy"))

    /** `{"op":"push_policy","policy":<policy>}` — replace the active policy (mutating). */
    suspend fun pushPolicy(policy: JSONObject): RyzKsudResult =
        request(JSONObject().put("op", "push_policy").put("policy", policy))

    /** `{"op":"get_log","limit":<limit>}` — recent SU decision log lines. */
    suspend fun getLog(limit: Int): RyzKsudResult =
        request(JSONObject().put("op", "get_log").put("limit", limit))

    // --- parsing helpers (pure; never throw — a malformed reply degrades to safe defaults) ------

    /** Interpret a `status` [RyzKsudResult] into a [RyzKsudStatus], or null if unreachable/bad. */
    fun parseStatus(result: RyzKsudResult): RyzKsudStatus? {
        val json = (result as? RyzKsudResult.Reply)?.json ?: return null
        return RyzKsudStatus(
            ok = json.optBoolean("ok", false),
            version = json.optString("version", "unknown"),
            kernelPresent = json.optBoolean("kernel_present", false),
            enforced = json.optBoolean("enforced", false),
            mode = json.optString("mode", "unknown"),
            policyVersion = json.optString("policy_version", "unknown"),
            profiles = json.optJSONArray("profiles").toStringList(),
            uid = json.opt("uid")?.toString() ?: "unknown",
            host = json.optString("host", "unknown"),
        )
    }

    /** Interpret an `su` [RyzKsudResult] into a [RyzKsudSuResult], or null if unreachable/bad. */
    fun parseSu(result: RyzKsudResult): RyzKsudSuResult? {
        val json = (result as? RyzKsudResult.Reply)?.json ?: return null
        // If the broker omitted a decision, treat the absence as DENY — never as GRANT.
        val decision = json.optString("decision", "DENY").ifBlank { "DENY" }
        return RyzKsudSuResult(
            ok = json.optBoolean("ok", false),
            decision = decision,
            rc = json.optInt("rc", -1),
            stdout = json.optString("stdout", ""),
            stderr = json.optString("stderr", ""),
        )
    }

    /** Interpret a `get_log` reply into display lines. Tolerates entries as strings or objects. */
    fun parseLog(result: RyzKsudResult): List<RyzKsudLogEntry>? {
        val json = (result as? RyzKsudResult.Reply)?.json ?: return null
        val arr = json.optJSONArray("entries") ?: json.optJSONArray("log") ?: return emptyList()
        val out = ArrayList<RyzKsudLogEntry>(arr.length())
        for (i in 0 until arr.length()) {
            val line = when (val v = arr.opt(i)) {
                is JSONObject -> v.toString()
                null -> continue
                else -> v.toString()
            }
            out.add(RyzKsudLogEntry(line))
        }
        return out
    }

    // --- transport ------------------------------------------------------------------------------

    /**
     * Open a fresh connection, write one request line, read one reply line, close. One socket per
     * call keeps state simple and matches the broker's line-oriented, request/response model.
     */
    private suspend fun request(body: JSONObject): RyzKsudResult = withContext(Dispatchers.IO) {
        try {
            withTimeout(REQUEST_TIMEOUT_MS) {
                Socket().use { socket ->
                    try {
                        socket.connect(InetSocketAddress(RYZ_KSUD_HOST, RYZ_KSUD_PORT), CONNECT_TIMEOUT_MS)
                    } catch (e: IOException) {
                        // Connection refused / no route == the guest VM is almost certainly not up.
                        return@withTimeout RyzKsudResult.Unreachable(
                            "ryz-ksud not reachable at $RYZ_KSUD_HOST:$RYZ_KSUD_PORT " +
                                "(is the ryznix VM running?): ${e.message ?: "connection failed"}"
                        )
                    }
                    // Read timeout guards a half-open / silent broker.
                    socket.soTimeout = REQUEST_TIMEOUT_MS.toInt()

                    val writer = OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8)
                    writer.write(body.toString())
                    writer.write("\n")
                    writer.flush()

                    val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                    val replyLine = reader.readLine()
                        ?: return@withTimeout RyzKsudResult.Unreachable(
                            "ryz-ksud closed the connection without replying"
                        )

                    try {
                        RyzKsudResult.Reply(JSONObject(replyLine))
                    } catch (e: JSONException) {
                        RyzKsudResult.Unreachable(
                            "ryz-ksud reply was not valid JSON: ${replyLine.take(200)}"
                        )
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            RyzKsudResult.Unreachable("ryz-ksud timed out after ${REQUEST_TIMEOUT_MS}ms")
        } catch (e: IOException) {
            Log.w(LOG_TAG, "ryz-ksud request failed", e)
            RyzKsudResult.Unreachable("ryz-ksud I/O error: ${e.message ?: "unknown"}")
        } catch (e: Exception) {
            // Defensive: never let a transport surprise crash the caller — surface it honestly.
            Log.w(LOG_TAG, "ryz-ksud unexpected error", e)
            RyzKsudResult.Unreachable("ryz-ksud unexpected error: ${e.message ?: e.javaClass.simpleName}")
        }
    }
}

/** Read a possibly-null JSONArray of strings into a Kotlin list (empty on null / non-strings). */
private fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    val out = ArrayList<String>(length())
    for (i in 0 until length()) {
        opt(i)?.let { out.add(it.toString()) }
    }
    return out
}

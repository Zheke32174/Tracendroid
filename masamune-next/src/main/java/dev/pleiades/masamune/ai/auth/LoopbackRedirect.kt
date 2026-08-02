package dev.pleiades.masamune.ai.auth

import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URLDecoder

/**
 * The redirect a terminal CLI uses: a one-shot HTTP listener on loopback.
 *
 * `claude`, `gh`, `gcloud`, `flyctl` all sign you in the same way — bind a socket on 127.0.0.1,
 * open your browser at the provider's authorize URL with `redirect_uri=http://127.0.0.1:<port>/…`,
 * and catch the code when the provider redirects back. This is that, in-app, so the button says
 * "Sign in" and the browser does the rest.
 *
 * ### Why this exists alongside the custom scheme
 * [OAuthRedirect] registers `masamune://oauth/callback`. That works, but it is the *less* portable
 * half of RFC 8252: a provider decides which redirect forms a client may register, and for the
 * "native"/"desktop" client type — the type a CLI registers, and the only type many providers offer
 * without a client secret — **loopback is the form that is accepted**, sometimes the only one.
 * Google, for instance, documents `http://127.0.0.1:<port>` for installed apps. Offering only a
 * custom scheme is what forces a user to hunt for a client type that allows it.
 *
 * ### The literal-IP rule, which is easy to get wrong
 * RFC 8252 §7.3 says use the **IP literal** `127.0.0.1`, not the name `localhost`: `localhost`
 * resolves through the host's resolver and can be pointed elsewhere, and providers increasingly
 * reject it outright. So [redirectUri] is built from the literal, always.
 *
 * ### The port is ephemeral, and that is deliberate
 * Binding port 0 lets the OS pick a free port, which is what RFC 8252 tells native apps to do
 * ("the client SHOULD use a dynamic port") and avoids failing because something else holds a fixed
 * one. Providers are required to allow any port for a loopback redirect. The registered redirect is
 * therefore `http://127.0.0.1` with the path — the port varies per run.
 *
 * ### Scope of exposure
 * It binds to the loopback address explicitly, so nothing off-device can reach it; it accepts
 * exactly **one** request and then closes; and it is only alive for the seconds between tapping
 * Sign in and the browser coming back. [close] is safe to call twice, so an abandoned sign-in
 * releases the port immediately rather than lingering.
 */
class LoopbackRedirect private constructor(private val server: ServerSocket) : Closeable {

    /** The port the OS picked. Part of the redirect URI the authorize request must carry. */
    val port: Int get() = server.localPort

    /** The exact `redirect_uri` to send in the authorize request, and to expect back. */
    val redirectUri: String get() = "http://$LOOPBACK_IP:$port$PATH"

    /**
     * Block until the browser hits the redirect, and return it as a [Uri] whose query carries
     * `code`/`state` — or `error` when the user declined, which is a real answer and not a failure
     * to hide. Returns null if the wait times out or the socket is closed first.
     *
     * The reply written back is a tiny page telling the user to return to the app: the browser tab
     * is left showing something deliberate rather than a connection error, which is exactly what a
     * CLI does when it prints "you may close this window".
     */
    fun awaitRedirect(timeoutMillis: Int = 300_000): Redirect? {
        server.soTimeout = timeoutMillis
        return runCatching {
            server.accept().use { socket ->
                val reader = socket.getInputStream().bufferedReader()
                val requestLine = reader.readLine() ?: return null
                // "GET /callback?code=…&state=… HTTP/1.1"
                val target = requestLine.split(' ').getOrNull(1) ?: return null
                socket.getOutputStream().write(responseBytes())
                socket.getOutputStream().flush()
                Redirect(target, parseQuery(target))
            }
        }.getOrNull()
    }

    /**
     * What came back, as plain data.
     *
     * Deliberately **not** an `android.net.Uri`: this receiver is pure JVM (a socket and a query
     * string), and depending on a framework type would make it untestable off-device — in a local
     * unit test `Uri.parse` is an unmocked stub that throws, which is exactly how the first version
     * of this class failed its own tests. Parsing its own query keeps the whole handshake provable
     * on the JVM.
     */
    data class Redirect(val target: String, val params: Map<String, String>) {
        /** The authorization code, present on success. */
        val code: String? get() = params["code"]

        /** The `state` echoed back — the caller MUST compare it to the one it sent (CSRF). */
        val state: String? get() = params["state"]

        /** The provider's error code (e.g. `access_denied`) when the user declined. */
        val error: String? get() = params["error"]

        /** The provider's human-readable reason, to show verbatim instead of inventing one. */
        val errorDescription: String? get() = params["error_description"]
    }

    override fun close() {
        runCatching { server.close() }
    }

    companion object {
        /** RFC 8252 §7.3: the IP literal, never the name `localhost`. */
        const val LOOPBACK_IP = "127.0.0.1"
        const val PATH = "/callback"

        /** Bind loopback on an OS-chosen port. Returns null when no port can be bound. */
        fun open(): LoopbackRedirect? = runCatching {
            LoopbackRedirect(ServerSocket(0, 1, InetAddress.getByName(LOOPBACK_IP)))
        }.getOrNull()

        /**
         * Parse the query of a request target (`/callback?code=…&state=…`), decoding
         * percent-escapes so a reason like `User%20declined` can be shown verbatim.
         */
        fun parseQuery(target: String): Map<String, String> {
            val q = target.substringAfter('?', "").substringBefore('#')
            if (q.isEmpty()) return emptyMap()
            val out = LinkedHashMap<String, String>()
            for (pair in q.split('&')) {
                if (pair.isEmpty()) continue
                val k = decode(pair.substringBefore('='))
                val v = if ('=' in pair) decode(pair.substringAfter('=')) else ""
                if (k.isNotEmpty()) out[k] = v
            }
            return out
        }

        private fun decode(s: String): String =
            runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

        private fun responseBytes(): ByteArray {
            val body = """
                <!doctype html><meta charset="utf-8">
                <title>Signed in</title>
                <body style="font:16px system-ui;padding:3rem;text-align:center">
                <h2>Signed in</h2><p>You can close this tab and return to Masamune.</p>
            """.trimIndent()
            return buildString {
                append("HTTP/1.1 200 OK\r\n")
                append("Content-Type: text/html; charset=utf-8\r\n")
                append("Content-Length: ${body.toByteArray().size}\r\n")
                append("Connection: close\r\n\r\n")
                append(body)
            }.toByteArray()
        }
    }
}

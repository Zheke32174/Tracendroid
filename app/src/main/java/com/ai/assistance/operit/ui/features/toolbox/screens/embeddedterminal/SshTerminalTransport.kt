/*
 * SshTerminalTransport — the SSH byte pipe that backs the Termux and ryznix terminal profiles.
 *
 * HONEST CEILING (read this):
 * Tracendroid is a separate, UNROOTED Android app. It cannot exec Termux's binaries directly
 * (different sandbox, different linker/$PREFIX). The supported way to get an interactive shell in
 * the Termux userland — which is the ONLY environment on the phone with a real package manager
 * (pkg/apt) and working mirrors — is to SSH into Termux's own sshd, which listens on
 * 127.0.0.1:8022 by default. That is what this class does, using the pure-JVM sshj client
 * (checksummed on Maven Central; no bundled ssh/dropbear binary blob). If sshd is not running,
 * or our public key has not been authorized, the connection fails and the REAL error is surfaced
 * — we never fake a connected shell.
 *
 * TRANSPORT ONLY: this class owns the socket, auth, PTY channel, and the two byte streams. It
 * knows nothing about ANSI rendering — SshTerminalView pumps these streams into a reused
 * com.termux.terminal.TerminalEmulator for full interactive rendering (vim, apt progress, etc.).
 *
 * HOST-KEY POLICY: the peer is always loopback (127.0.0.1) — the SSH host key belongs to the
 * phone's own Termux sshd and rotates whenever the user reinstalls Termux or regenerates host
 * keys. Pinning it would create constant false "host key changed" failures for a same-device
 * loopback peer that no network attacker can reach (there is no MITM position on loopback). We
 * therefore accept the loopback host key (PromiscuousVerifier) and say so plainly. This decision
 * is scoped to 127.0.0.1 by construction — the connect target is hard-coded to loopback.
 */
package com.ai.assistance.operit.ui.features.toolbox.screens.embeddedterminal

import android.content.Context
import android.util.Log
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import net.schmizz.sshj.userauth.keyprovider.KeyPairWrapper
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

private const val LOG_TAG = "SshTerminalTransport"

/** Termux's default sshd loopback endpoint. Hard-coded to loopback by design (see host-key note). */
const val TERMUX_SSH_HOST = "127.0.0.1"
const val TERMUX_SSH_PORT = 8022

/** A live SSH-backed PTY: the two byte streams plus the resize + close handles the view drives. */
class SshTerminalConnection internal constructor(
    private val client: SSHClient,
    private val session: Session,
    private val shell: Session.Shell,
) {
    /** Bytes coming FROM the remote shell — feed these into the terminal emulator. */
    val input: InputStream get() = shell.inputStream

    /** Bytes going TO the remote shell — write user keystrokes here. */
    val output: OutputStream get() = shell.outputStream

    /** Tell the remote pty the on-screen grid changed size (keeps `stty`/curses apps correct). */
    fun resize(columns: Int, rows: Int, widthPx: Int, heightPx: Int) {
        runCatching { shell.changeWindowDimensions(columns, rows, widthPx, heightPx) }
            .onFailure { Log.w(LOG_TAG, "PTY resize failed", it) }
    }

    /** True while the channel is open and the remote shell has not exited. */
    fun isOpen(): Boolean = runCatching { shell.isOpen && client.isConnected }.getOrDefault(false)

    /** Tear everything down. Safe to call more than once. */
    fun close() {
        runCatching { shell.close() }
        runCatching { session.close() }
        runCatching { client.disconnect() }
        runCatching { client.close() }
    }
}

/** Thrown with a human-honest message when a connection cannot be established. */
class SshTerminalException(message: String, cause: Throwable? = null) : Exception(message, cause)

object SshTerminalTransport {

    /**
     * Open an interactive PTY shell in the Termux userland over SSH.
     *
     * @param username the Termux login user (its Linux uid's name, e.g. `u0_aNNN`). Termux's sshd
     *   only accepts THIS single account; the caller passes what the user configured.
     * @param initialColumns/initialRows the current on-screen grid so the remote pty starts sized.
     * @param initialInput if non-blank, these bytes are written to the shell's stdin right after it
     *   starts — i.e. as if the user typed them. The ryznix profile uses this to run
     *   `~/ryzvm/ryzctl` (start-if-down, then attach the console). It is ordinary shell input, not a
     *   privileged side channel.
     *
     * @throws SshTerminalException with a real, user-facing reason on any failure.
     */
    fun connect(
        context: Context,
        username: String,
        initialColumns: Int,
        initialRows: Int,
        initialWidthPx: Int,
        initialHeightPx: Int,
        initialInput: String? = null,
    ): SshTerminalConnection {
        val client = SSHClient()
        // Loopback-only peer; see the host-key policy note at the top of this file.
        client.addHostKeyVerifier(PromiscuousVerifier())
        client.connectTimeout = TimeUnit.SECONDS.toMillis(8).toInt()
        client.timeout = 0 // no read timeout: interactive shells idle for long stretches.

        try {
            client.connect(TERMUX_SSH_HOST, TERMUX_SSH_PORT)
        } catch (e: Exception) {
            runCatching { client.close() }
            throw SshTerminalException(
                "Could not reach Termux sshd at $TERMUX_SSH_HOST:$TERMUX_SSH_PORT. " +
                    "Is Termux running with sshd started? (in Termux: `pkg install openssh && sshd`).",
                e,
            )
        }

        try {
            val keyProvider = KeyPairWrapper(SshKeyManager.ensureKeyPair(context))
            client.authPublickey(username, keyProvider)
        } catch (e: Exception) {
            runCatching { client.disconnect(); client.close() }
            throw SshTerminalException(
                "SSH key auth failed for user '$username'. Tap \"Authorize in Termux\" to install " +
                    "this app's public key, and confirm the Termux user name is correct.",
                e,
            )
        }

        try {
            val session = client.startSession()
            session.allocatePTY(
                "xterm-256color",
                initialColumns.coerceAtLeast(1),
                initialRows.coerceAtLeast(1),
                initialWidthPx.coerceAtLeast(0),
                initialHeightPx.coerceAtLeast(0),
                emptyMap(),
            )
            // Always an interactive login shell (single Session.Shell type for both profiles).
            // Termux's sshd starts the user's login shell with pkg/apt on PATH already.
            val shell = session.startShell()

            // For the ryznix profile, "type" the ryzctl bootstrap line into the fresh shell. This
            // is ordinary stdin — exactly what a user would type — not a privileged channel.
            if (!initialInput.isNullOrBlank()) {
                runCatching {
                    shell.outputStream.write(initialInput.toByteArray(Charsets.UTF_8))
                    shell.outputStream.flush()
                }.onFailure { Log.w(LOG_TAG, "Failed to send initial ryznix input", it) }
            }
            return SshTerminalConnection(client, session, shell)
        } catch (e: Exception) {
            runCatching { client.disconnect(); client.close() }
            throw SshTerminalException("Failed to start remote shell: ${e.message}", e)
        }
    }
}

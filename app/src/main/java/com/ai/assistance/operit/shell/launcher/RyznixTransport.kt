package com.ai.assistance.operit.shell.launcher

import android.content.Context
import com.ai.assistance.operit.util.AppLogger
import java.io.File
import java.time.Duration
import org.apache.sshd.client.SshClient
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier
import org.apache.sshd.common.keyprovider.FileKeyPairProvider

/**
 * [ShellTransport] backed by SSH into the phone's own "ryznix" Termux/Ubuntu userland.
 *
 * Unlike [ProotTransport] (a local proot process on this device), ryznix dials a loopback
 * SSH server (Termux's `sshd`, conventionally on 127.0.0.1:8022) and runs a shell there.
 * [ShellSessionManager] depends only on [ShellTransport], so this plugs in with zero manager
 * edits and proot stays the default.
 *
 * Scope (locked): this brick does transport + channel lifecycle + connection ONLY. proot's
 * request/response IO flows over an IPC socket; ryznix's IO is direct SSH stdio, and wiring
 * that stdio into the session/agent-core is the documented FOLLOW-UP brick — so [spawn] only
 * opens/validates the connection and hands back an [SshShellChannel] that tracks liveness.
 *
 * Key provisioning is ALSO a later brick: this transport only HOLDS an SSH identity. When no
 * key is present it returns [ShellTransportResult.Unavailable] rather than generating one.
 *
 * NOTE: The entire MINA-sshd-on-Android path is unverified here (cannot compile/run). Every
 * uncertain MINA symbol is marked // TODO(offbox-verify).
 */
class RyznixTransport(
    context: Context,
    private val host: String = "127.0.0.1",
    private val port: Int = 8022,
    private val username: String = "ryznix",
    /**
     * Private-key identity used to auth to Termux's authorized_keys. Defaults to a stable
     * per-app path; brick 3 provisions the key here. `null`/absent ⇒ [spawn] reports
     * Unavailable (never invents key material).
     */
    private val keyPath: File? = File(File(context.filesDir, "ssh"), "ryznix_id_ed25519"),
    /**
     * Remote entry command. Prefer the full ryznix Ubuntu env; fall back to a plain Termux
     * login shell when the Ubuntu entry is absent. Implemented as a single POSIX one-liner so
     * the fallback is decided on-device without a second round-trip.
     * TODO(offbox-verify): confirm superlinux.sh path + the Termux bash absolute path on the
     * actual handset; these mirror the ryznix conventions but were not observed on a device.
     */
    private val entryCommand: String =
        "if [ -x \"\$HOME/ryznix/superlinux.sh\" ]; then exec \"\$HOME/ryznix/superlinux.sh\"; " +
            "else exec /data/data/com.termux/files/usr/bin/bash -l; fi",
    /** Injectable for tests; mirrors the OkHttpClient() default-arg pattern in ModelOAuthClient. */
    private val clientFactory: () -> SshClient = { SshClient.setUpDefaultClient() },
) : ShellTransport {

    override val name: String = "ryznix"

    override fun spawn(): ShellTransportResult {
        val key = keyPath
        if (key == null || !key.exists()) {
            AppLogger.w(TAG, "No SSH identity at ${key?.absolutePath ?: "<null>"}; ryznix unavailable")
            return ShellTransportResult.Unavailable(
                "ryznix_key",
                "No SSH identity provisioned for ryznix yet (expected at " +
                    "${key?.absolutePath ?: "<app files>/ssh/ryznix_id_ed25519"}). Provision an " +
                    "SSH key into Termux authorized_keys first (key-provisioning brick).",
            )
        }

        var client: SshClient? = null
        return try {
            val c = clientFactory().also { client = it }
            // Loopback 127.0.0.1:8022 has no stable known_hosts; accept the host key like the
            // linux_ssh convention's StrictHostKeyChecking=no.
            // TODO(offbox-verify): AcceptAllServerKeyVerifier.INSTANCE constant + package in 2.12.x.
            c.serverKeyVerifier = AcceptAllServerKeyVerifier.INSTANCE
            c.start()

            // connect -> verify -> session.
            // TODO(offbox-verify): ConnectFuture#verify(Duration) overload and that it returns
            // the future (vs throwing) on timeout in sshd 2.12.x.
            val session = c.connect(username, host, port)
                .verify(CONNECT_TIMEOUT)
                .session

            // Load the KeyPair(s) from the identity file and register for pubkey auth.
            // TODO(offbox-verify): FileKeyPairProvider(Path...) ctor + loadKeys(SessionContext)
            // signature changed across sshd 2.x; confirm against 2.12.x + that SecurityUtils
            // exposes loadKeyPairIdentities as an alternative.
            val keyPairs = FileKeyPairProvider(key.toPath()).loadKeys(session)
            for (kp in keyPairs) {
                session.addPublicKeyIdentity(kp)
            }

            // auth -> verify -> isSuccess.
            // TODO(offbox-verify): AuthFuture#verify(Duration) overload in 2.12.x.
            val auth = session.auth().verify(AUTH_TIMEOUT)
            if (!auth.isSuccess) {
                runCatching { session.close(false) }
                runCatching { c.stop() }
                return ShellTransportResult.Failed(
                    "auth",
                    IllegalStateException("ryznix SSH auth did not succeed for $username@$host:$port"),
                )
            }

            // Open the exec channel now to validate the entry command runs. The stdio streams
            // are intentionally NOT wired here (follow-up brick); we keep the channel ref so
            // SshShellChannel can report liveness and tear it down.
            // TODO(offbox-verify): createExecChannel(String) vs createExecChannel(String, boolean,
            // boolean) overloads, and ChannelExec#open().verify(Duration) in 2.12.x.
            val channel = session.createExecChannel(entryCommand)
            channel.open().verify(CHANNEL_TIMEOUT)

            AppLogger.d(TAG, "ryznix SSH channel opened to $username@$host:$port")
            ShellTransportResult.Started(SshShellChannel(c, session, channel))
        } catch (t: Throwable) {
            // Release any partially-started client so a failed dial leaks nothing.
            runCatching { client?.stop() }
            AppLogger.w(TAG, "ryznix spawn failed", t)
            ShellTransportResult.Failed("ssh", t)
        }
    }

    /**
     * Releases the pooled [SshClient] in addition to the channel/session. The transport owns
     * the client (per [ShellTransport.stop]'s doc), so it must close it here rather than
     * leaving it running after the channel is torn down.
     */
    override fun stop(channel: ShellChannel) {
        channel.stop()
        if (channel is SshShellChannel) {
            channel.closeClient()
        }
    }

    companion object {
        private const val TAG = "RyznixTransport"

        // TODO(offbox-verify): confirm the verify(...) overloads accept java.time.Duration in
        // sshd 2.12.x (some 2.x lines take a long millis instead).
        private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(15)
        private val AUTH_TIMEOUT: Duration = Duration.ofSeconds(15)
        private val CHANNEL_TIMEOUT: Duration = Duration.ofSeconds(15)
    }
}

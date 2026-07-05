/*
 * SshKeyManager — HONEST, app-private SSH key provisioning for the dual-rigged terminal.
 *
 * WHY THIS EXISTS:
 * The Termux/ryznix profiles reach the Termux userland over SSH to 127.0.0.1:8022 (Termux's
 * default sshd). To authenticate WITHOUT a password prompt or a bundled binary, Tracendroid
 * generates its OWN ed25519 keypair, keeps the PRIVATE key in this app's private storage
 * (never logged, never leaves the device), and hands the PUBLIC key to Termux once via the
 * RUN_COMMAND bridge (see TerminalProfiles.authorizeInTermux). This is the same trust model as
 * any `ssh-keygen` + `ssh-copy-id` — but scoped to a single loopback host.
 *
 * WHAT IT DOES NOT DO (honesty):
 *  - It does not read or touch Termux's own keys.
 *  - It never transmits the private key anywhere. `authorizedKeysLine()` only ever returns the
 *    PUBLIC half.
 *  - It does not pretend a key is "installed on the server" — that is a separate, explicit step
 *    the user triggers (Authorize in Termux) and whose real result is surfaced.
 *
 * IMPLEMENTATION:
 * We use net.i2p.crypto:eddsa (pulled in transitively by sshj) to generate the Ed25519 pair,
 * because it gives us a stable, sshj-compatible key type on all supported API levels (ed25519
 * via java.security KeyPairGenerator is only guaranteed on API 33+). The private key is stored
 * as PKCS#8 DER bytes in a 0600-style app-private file; the public key is serialized to the
 * OpenSSH "ssh-ed25519 AAAA... comment" one-line format for authorized_keys.
 */
package com.ai.assistance.operit.ui.features.toolbox.screens.embeddedterminal

import android.content.Context
import android.util.Base64
import android.util.Log
import net.i2p.crypto.eddsa.EdDSAPrivateKey
import net.i2p.crypto.eddsa.EdDSAPublicKey
import net.i2p.crypto.eddsa.KeyPairGenerator as EdKeyPairGenerator
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.security.KeyPair
import java.security.PrivateKey
import java.security.PublicKey

private const val LOG_TAG = "SshKeyManager"

/** OpenSSH wire-format identifier for an Ed25519 key. */
private const val SSH_ED25519 = "ssh-ed25519"

/** Fixed comment tacked onto the public key so it is recognisable in authorized_keys. */
private const val KEY_COMMENT = "tracendroid@embedded-terminal"

/**
 * Owns Tracendroid's single client identity for reaching Termux over SSH. Files live under the
 * app's private files dir (`filesDir/ssh/`) which is not world-readable.
 */
object SshKeyManager {

    private const val DIR = "ssh"
    private const val PRIVATE_KEY_FILE = "id_ed25519.pk8"
    private const val PUBLIC_KEY_FILE = "id_ed25519.pub"

    private fun dir(context: Context): File =
        File(context.filesDir, DIR).apply { if (!exists()) mkdirs() }

    private fun privateFile(context: Context) = File(dir(context), PRIVATE_KEY_FILE)
    private fun publicFile(context: Context) = File(dir(context), PUBLIC_KEY_FILE)

    /** True once a keypair has been generated on this device. */
    fun hasKey(context: Context): Boolean =
        privateFile(context).exists() && publicFile(context).exists()

    /**
     * Ensure a keypair exists, generating one on first call. Returns the loaded [KeyPair] for use
     * by sshj. The private key never leaves this method's callers; it is not returned as text.
     */
    @Synchronized
    fun ensureKeyPair(context: Context): KeyPair {
        if (!hasKey(context)) generateAndStore(context)
        return loadKeyPair(context)
    }

    private fun generateAndStore(context: Context) {
        val kp = EdKeyPairGenerator().generateKeyPair()
        val priv = kp.private as EdDSAPrivateKey
        val pub = kp.public as EdDSAPublicKey

        // Store the private key as its PKCS#8 DER encoding — a portable, standard container.
        privateFile(context).apply {
            writeBytes(priv.encoded)
            // Best-effort tighten perms; app-private already excludes other apps.
            runCatching { setReadable(false, false); setReadable(true, true); setWritable(false, false); setWritable(true, true) }
        }
        // Store the OpenSSH authorized_keys line (public only).
        publicFile(context).writeText(opensshPublicLine(pub))
        Log.i(LOG_TAG, "Generated new app-private ed25519 identity for embedded terminal.")
    }

    private fun loadKeyPair(context: Context): KeyPair {
        val privBytes = privateFile(context).readBytes()
        val spec = java.security.spec.PKCS8EncodedKeySpec(privBytes)
        val priv = EdDSAPrivateKey(spec)
        // Derive the matching public key from the private seed so the pair is always consistent.
        val curve = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519)
        val pubSpec = EdDSAPublicKeySpec(priv.a, curve)
        val pub = EdDSAPublicKey(pubSpec)
        return KeyPair(pub as PublicKey, priv as PrivateKey)
    }

    /**
     * The exact single line to append to `~/.ssh/authorized_keys` in Termux. PUBLIC key only.
     * Stable across calls (regenerated from the stored public file), so appends are idempotent
     * when the caller de-dupes on this exact string.
     */
    fun authorizedKeysLine(context: Context): String {
        ensureKeyPair(context)
        return publicFile(context).readText().trim()
    }

    /** Serialize an Ed25519 public key to the `ssh-ed25519 AAAA... comment` OpenSSH format. */
    private fun opensshPublicLine(pub: EdDSAPublicKey): String {
        // OpenSSH ed25519 blob: string "ssh-ed25519" + string (32-byte A). Each "string" is a
        // 4-byte big-endian length prefix followed by the bytes.
        val raw = pub.abyte // 32-byte little-endian encoded public point, OpenSSH's expected form.
        val bos = ByteArrayOutputStream()
        val out = DataOutputStream(bos)
        writeSshString(out, SSH_ED25519.toByteArray(Charsets.UTF_8))
        writeSshString(out, raw)
        out.flush()
        val b64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
        return "$SSH_ED25519 $b64 $KEY_COMMENT"
    }

    private fun writeSshString(out: DataOutputStream, bytes: ByteArray) {
        out.writeInt(bytes.size)
        out.write(bytes)
    }
}

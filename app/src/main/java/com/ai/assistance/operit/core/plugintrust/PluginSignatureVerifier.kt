package com.ai.assistance.operit.core.plugintrust

import android.util.Base64
import com.ai.assistance.operit.util.AppLogger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * Verifies a plugin manifest's detached Ed25519 signature against the public key inlined
 * in the manifest itself (`AUDIT_PLAN.md § 1.1`).
 *
 * Parallel to [com.ai.assistance.operit.shell.ShellRootfsSignatureVerifier] but for
 * plugin manifests. The shapes differ enough — different signed payload, different key
 * source — that they don't share a base class; the cryptographic primitives are the
 * same.
 */
class PluginSignatureVerifier {

    companion object {
        private const val TAG = "PluginSignatureVerifier"
    }

    sealed class Result {
        /** Signature checks against the manifest's inline key. */
        data class Ok(val publisherKeyFingerprint: String) : Result()

        /** Manifest body did not parse. */
        data object MalformedManifest : Result()

        /** PEM-wrapped public key did not parse as Ed25519. */
        data class InvalidPublicKey(val reason: String) : Result()

        /** Signature bytes did not validate against the canonical manifest bytes. */
        data object InvalidSignature : Result()

        /** Unexpected exception during verification (algorithm not available, etc). */
        data class Error(val cause: Throwable) : Result()
    }

    /**
     * Verify [signatureBytes] against [manifestBytes] under the Ed25519 public key the
     * manifest carries inline.
     *
     * Returns the SHA-256 fingerprint of the public key on success — the TOFU store keys
     * on this fingerprint, not on the publisher name (which is mutable display text).
     */
    fun verify(manifestBytes: ByteArray, signatureBytes: ByteArray): Result {
        val manifest = PluginManifest.parse(manifestBytes)
            ?: return Result.MalformedManifest

        val publicKey = try {
            loadEd25519PublicKey(manifest.publisherKeyPem)
        } catch (t: Throwable) {
            return Result.InvalidPublicKey(t.message ?: t::class.simpleName ?: "parse error")
        }

        return try {
            val verifier = Signature.getInstance("Ed25519")
            verifier.initVerify(publicKey)
            verifier.update(manifest.canonicalBytes())
            if (verifier.verify(signatureBytes)) {
                Result.Ok(publisherKeyFingerprint(publicKey))
            } else {
                Result.InvalidSignature
            }
        } catch (t: Throwable) {
            AppLogger.w(TAG, "verify failed: ${t.message}")
            Result.Error(t)
        }
    }

    /**
     * SHA-256 fingerprint of the X.509 SubjectPublicKeyInfo encoding, formatted as a
     * lowercase colon-separated hex string. Stable across runs and across re-encodings
     * of the same key material.
     */
    fun publisherKeyFingerprint(publicKey: PublicKey): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(publicKey.encoded)
        return digest.joinToString(":") { byte -> "%02x".format(byte) }
    }

    private fun loadEd25519PublicKey(pem: String): PublicKey {
        val base64 = pem
            .lineSequence()
            .filterNot { line -> line.startsWith("-----") }
            .joinToString(separator = "")
            .replace(Regex("\\s+"), "")
        if (base64.isBlank()) throw IllegalArgumentException("empty PEM body")
        val der = Base64.decode(base64, Base64.DEFAULT)
        val spec = X509EncodedKeySpec(der)
        return KeyFactory.getInstance("Ed25519").generatePublic(spec)
    }
}

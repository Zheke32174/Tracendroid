package com.ai.assistance.operit.shell

import java.io.File
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * Detached Ed25519 signature verifier for the rootfs artifact (PR 2/N).
 *
 * Per docs/SHELL_REBUILD.md the artifact is signed with the project release key. The
 * matching public key ships inside the APK at the path passed to [verify]. Verification
 * happens after SHA-256 pin check passes — defense in depth: the digest catches CDN
 * tampering, the signature catches anyone who controls the GitHub Release page.
 *
 * v1 trust model: a single public key, baked at build time. Key rotation is an app-update
 * event, not a runtime negotiation.
 */
class ShellRootfsSignatureVerifier {

    sealed class Result {
        data object Ok : Result()
        data class PublicKeyMissing(val path: String) : Result()
        data class SignatureMissing(val path: String) : Result()
        data class Invalid(val reason: String) : Result()
        data class Error(val cause: Throwable) : Result()
    }

    /**
     * Verifies [artifact] against [signature] under [publicKeyPem] (X.509 SubjectPublicKeyInfo
     * DER, base64-wrapped PEM is accepted via the PEM helper below).
     */
    fun verify(
        artifact: File,
        signature: File,
        publicKeyPem: File,
    ): Result {
        if (!publicKeyPem.exists()) return Result.PublicKeyMissing(publicKeyPem.absolutePath)
        if (!signature.exists()) return Result.SignatureMissing(signature.absolutePath)
        return try {
            val publicKey = loadEd25519PublicKey(publicKeyPem)
            val sig = Signature.getInstance("Ed25519")
            sig.initVerify(publicKey)
            artifact.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    sig.update(buf, 0, n)
                }
            }
            if (sig.verify(signature.readBytes())) Result.Ok
            else Result.Invalid("Ed25519 signature mismatch")
        } catch (t: Throwable) {
            Result.Error(t)
        }
    }

    private fun loadEd25519PublicKey(pemFile: File): PublicKey {
        val pem = pemFile.readText()
        val base64 = pem
            .lineSequence()
            .filterNot { line -> line.startsWith("-----") }
            .joinToString(separator = "")
            .replace(Regex("\\s+"), "")
        val der = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
        val spec = X509EncodedKeySpec(der)
        return KeyFactory.getInstance("Ed25519").generatePublic(spec)
    }
}

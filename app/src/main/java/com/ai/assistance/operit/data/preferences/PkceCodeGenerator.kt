package com.ai.assistance.operit.data.preferences

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * PKCE (Proof Key for Code Exchange, RFC 7636) helpers for OAuth flows
 * where the client cannot keep a confidential secret — i.e. every mobile
 * client. See docs/OAUTH_PKCE_MIGRATION.md for the migration that
 * introduced this object.
 */
object PkceCodeGenerator {

    /**
     * 64 random bytes → ~86 URL-safe characters after base64url encoding.
     * RFC 7636 § 4.1 allows 43–128.
     */
    private const val VERIFIER_RAW_BYTES = 64

    private const val URL_SAFE_FLAGS =
        Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP

    /**
     * Generate a code_verifier per RFC 7636 § 4.1. URL-safe base64 sits inside
     * the unreserved set [A-Z][a-z][0-9][-._~], so the output is a valid verifier.
     */
    fun generateCodeVerifier(): String {
        val raw = ByteArray(VERIFIER_RAW_BYTES)
        SecureRandom().nextBytes(raw)
        return Base64.encodeToString(raw, URL_SAFE_FLAGS)
    }

    /**
     * Compute code_challenge = base64url-no-pad(SHA-256(verifier)) per RFC 7636 § 4.2.
     */
    fun computeCodeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(hash, URL_SAFE_FLAGS)
    }
}

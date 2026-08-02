package dev.pleiades.masamune.ai.auth

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * RFC 7636 Proof Key for Code Exchange.
 *
 * PKCE is not optional here: the redirect flow uses a custom scheme, and any app on the device
 * can claim the same scheme. Without a verifier, an intercepted authorization code would be
 * redeemable by the interceptor. `S256` only — `plain` is never generated, even though some
 * issuers still advertise it.
 */
data class PkcePair(val verifier: String, val challenge: String) {
    companion object {
        private const val METHOD = "S256"

        /** The method string sent as `code_challenge_method`. */
        const val CHALLENGE_METHOD = METHOD

        fun generate(): PkcePair {
            // 64 random bytes -> 86 base64url chars, inside RFC 7636's 43..128 range.
            val bytes = ByteArray(64)
            SecureRandom().nextBytes(bytes)
            val verifier = bytes.base64Url()
            val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
            return PkcePair(verifier, digest.base64Url())
        }
    }
}

/** Base64url, no padding — the only encoding RFC 7636 and JWT payloads accept. */
internal fun ByteArray.base64Url(): String =
    Base64.encodeToString(this, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

/** An unguessable `state` value, checked on the way back so a stray callback is rejected. */
internal fun newOauthState(): String {
    val bytes = ByteArray(24)
    SecureRandom().nextBytes(bytes)
    return bytes.base64Url()
}

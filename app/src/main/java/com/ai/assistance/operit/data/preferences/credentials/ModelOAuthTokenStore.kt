package com.ai.assistance.operit.data.preferences.credentials

import android.content.Context

/**
 * Per-model-config OAuth token storage, backed by [CredentialVault] (EncryptedSharedPreferences).
 *
 * Mirrors GitHubAuthPreferences' vault pattern but keyed per model-config id, so any number of
 * providers can each hold their own OAuth session. Stores the access token, an optional refresh
 * token, and an absolute expiry (epoch millis). The authorize/exchange/refresh NETWORK flow is a
 * separate brick (it reuses PkceCodeGenerator); this class is only the persistence + expiry layer
 * that [com.ai.assistance.operit.api.chat.llmprovider.OAuthCredentialProvider] reads.
 */
class ModelOAuthTokenStore(context: Context) {

    private val vault = CredentialVault(context, VAULT_STORE)

    /** The stored access token, or null if this config was never authorized. */
    fun accessToken(configId: String): String? = vault.get(vk(configId, ACCESS_TOKEN))

    /** The stored refresh token, if the provider issued one. */
    fun refreshToken(configId: String): String? = vault.get(vk(configId, REFRESH_TOKEN))

    /**
     * Whether the stored access token is at or within [skewMillis] of its expiry. Returns false
     * when no expiry was recorded (some providers issue non-expiring tokens; treat as valid).
     */
    fun isExpired(configId: String, skewMillis: Long = DEFAULT_SKEW_MS): Boolean {
        val expiresAt = vault.get(vk(configId, EXPIRES_AT))?.toLongOrNull() ?: return false
        return System.currentTimeMillis() >= (expiresAt - skewMillis)
    }

    /** Persist tokens for [configId]. [expiresInSeconds] is relative; stored as absolute millis. */
    fun save(
        configId: String,
        accessToken: String,
        refreshToken: String? = null,
        expiresInSeconds: Long? = null,
    ) {
        vault.put(vk(configId, ACCESS_TOKEN), accessToken)
        if (refreshToken != null) vault.put(vk(configId, REFRESH_TOKEN), refreshToken)
        if (expiresInSeconds != null) {
            val at = System.currentTimeMillis() + expiresInSeconds * 1000L
            vault.put(vk(configId, EXPIRES_AT), at.toString())
        }
    }

    /** Forget this config's OAuth session (on logout / re-auth). */
    fun clear(configId: String) {
        vault.remove(vk(configId, ACCESS_TOKEN))
        vault.remove(vk(configId, REFRESH_TOKEN))
        vault.remove(vk(configId, EXPIRES_AT))
    }

    private fun vk(configId: String, field: String): String = "cfg:$configId:$field"

    companion object {
        private const val VAULT_STORE = "oauth_model_credentials"
        private const val ACCESS_TOKEN = "access_token"
        private const val REFRESH_TOKEN = "refresh_token"
        private const val EXPIRES_AT = "expires_at"
        private const val DEFAULT_SKEW_MS = 60_000L // treat as expired a minute early
    }
}

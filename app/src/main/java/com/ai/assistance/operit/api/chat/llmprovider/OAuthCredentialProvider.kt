package com.ai.assistance.operit.api.chat.llmprovider

import com.ai.assistance.operit.data.preferences.credentials.ModelOAuthTokenStore

/**
 * An [ApiKeyProvider] whose "key" is an OAuth 2.0 access token from [ModelOAuthTokenStore].
 *
 * Paired with [BearerAuth] (see AuthStrategy.kt), a fresh access token rides as
 * `Authorization: Bearer <token>` exactly like an API key, so no provider needs to change. Selected in
 * [AIServiceFactory] when a config's authMode is OAUTH.
 *
 * When [oauthConfig] is supplied and the stored access token has expired, this transparently refreshes
 * it via [ModelOAuthClient.refresh] (using the stored refresh token) before failing. It never sends an
 * empty credential: if the config was never authorized, or the token is expired and cannot be refreshed,
 * it throws a message telling the user to (re-)authorize.
 */
class OAuthCredentialProvider(
    private val configId: String,
    private val tokenStore: ModelOAuthTokenStore,
    private val oauthConfig: ModelOAuthConfig? = null,
) : ApiKeyProvider {

    override suspend fun getApiKey(): String {
        val token = tokenStore.accessToken(configId)
            ?: throw IllegalStateException(
                "This model is set to OAuth but has not been authorized yet. " +
                    "Authorize the provider in settings before sending a message."
            )
        if (!tokenStore.isExpired(configId)) return token

        // Expired — attempt a silent refresh when we know this provider's OAuth endpoints.
        oauthConfig?.let { config ->
            ModelOAuthClient(tokenStore).refresh(configId, config).getOrNull()?.let { refreshed ->
                return refreshed.access_token
            }
        }
        throw IllegalStateException(
            "The OAuth access token for this model has expired and could not be refreshed. " +
                "Re-authorize the provider in settings."
        )
    }
}

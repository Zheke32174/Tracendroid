package com.ai.assistance.operit.api.chat.llmprovider

import com.ai.assistance.operit.data.preferences.credentials.ModelOAuthTokenStore

/**
 * An [ApiKeyProvider] whose "key" is an OAuth 2.0 access token from [ModelOAuthTokenStore].
 *
 * This is the credential SOURCE that lets OAuth reach a model: paired with [BearerAuth]
 * (see AuthStrategy.kt), a fresh access token rides as `Authorization: Bearer <token>` exactly
 * like an API key, so no provider needs to change. Selected in [AIServiceFactory] when a config's
 * authMode is OAUTH.
 *
 * Automatic refresh is not wired yet — that lands with the OAuth flow brick that reuses
 * PkceCodeGenerator. Until then this returns a valid stored token and otherwise fails LOUDLY
 * (never silently unauthenticated): if the config was never authorized, or the token has expired,
 * it throws a message telling the user to re-authorize, rather than sending an empty credential.
 */
class OAuthCredentialProvider(
    private val configId: String,
    private val tokenStore: ModelOAuthTokenStore,
) : ApiKeyProvider {

    override suspend fun getApiKey(): String {
        val token = tokenStore.accessToken(configId)
            ?: throw IllegalStateException(
                "This model is set to OAuth but has not been authorized yet. " +
                    "Authorize the provider in settings before sending a message."
            )
        if (tokenStore.isExpired(configId)) {
            throw IllegalStateException(
                "The OAuth access token for this model has expired. Re-authorize the provider " +
                    "in settings. (Automatic refresh ships with the OAuth flow brick.)"
            )
        }
        return token
    }
}

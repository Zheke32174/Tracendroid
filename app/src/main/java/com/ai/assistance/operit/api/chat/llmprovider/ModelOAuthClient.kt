package com.ai.assistance.operit.api.chat.llmprovider

import com.ai.assistance.operit.data.preferences.PkceCodeGenerator
import com.ai.assistance.operit.data.preferences.credentials.ModelOAuthTokenStore
import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** Per-provider OAuth 2.0 + PKCE endpoints and client identity. No client secret — mobile is public. */
data class ModelOAuthConfig(
    val authorizeEndpoint: String,
    val tokenEndpoint: String,
    val clientId: String,
    val redirectUri: String,
    val scopes: List<String> = emptyList(),
)

/** Standard RFC 6749 token-endpoint response. Unknown fields are ignored so any provider parses. */
@Serializable
data class OAuthTokenResponse(
    val access_token: String,
    val token_type: String? = null,
    val expires_in: Long? = null,
    val refresh_token: String? = null,
    val scope: String? = null,
)

/** A pending authorize attempt: the URL to open plus the PKCE verifier + state to check the callback. */
data class OAuthAuthorizeRequest(
    val authorizeUrl: String,
    val codeVerifier: String,
    val state: String,
)

/**
 * Provider-agnostic OAuth 2.0 Authorization-Code + PKCE client for model providers.
 *
 * Generalizes the GitHub PKCE flow (GitHubApiService.getAccessToken, docs/OAUTH_PKCE_MIGRATION.md)
 * so ANY vendor's OAuth can mint the access token that [OAuthCredentialProvider] hands to
 * [BearerAuth]. No client secret is ever used (RFC 7636): the code_verifier replaces it. Successful
 * exchanges/refreshes are persisted into [ModelOAuthTokenStore], keyed by model-config id.
 *
 * This is the network/logic layer only. The WebView that opens [OAuthAuthorizeRequest.authorizeUrl]
 * and returns the redirect (carrying code + state), the per-provider [ModelOAuthConfig] registry, and
 * the settings toggle, are separate bricks.
 */
class ModelOAuthClient(
    private val tokenStore: ModelOAuthTokenStore,
    private val httpClient: OkHttpClient = OkHttpClient(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Build the authorize URL with a fresh PKCE verifier + the caller's [state]. The caller persists
     * the returned verifier/state (pending), opens the URL in a WebView, and on redirect verifies the
     * state and calls [exchangeCode] with the returned code and this verifier.
     */
    fun buildAuthorizeRequest(config: ModelOAuthConfig, state: String): OAuthAuthorizeRequest {
        val verifier = PkceCodeGenerator.generateCodeVerifier()
        val challenge = PkceCodeGenerator.computeCodeChallenge(verifier)
        val builder = config.authorizeEndpoint.toHttpUrl().newBuilder()
            .addQueryParameter("response_type", "code")
            .addQueryParameter("client_id", config.clientId)
            .addQueryParameter("redirect_uri", config.redirectUri)
            .addQueryParameter("state", state)
            .addQueryParameter("code_challenge", challenge)
            .addQueryParameter("code_challenge_method", "S256")
        if (config.scopes.isNotEmpty()) {
            builder.addQueryParameter("scope", config.scopes.joinToString(" "))
        }
        return OAuthAuthorizeRequest(builder.build().toString(), verifier, state)
    }

    /** Exchange an authorization [code] + its [codeVerifier] for tokens; persists on success. */
    suspend fun exchangeCode(
        configId: String,
        config: ModelOAuthConfig,
        code: String,
        codeVerifier: String,
    ): Result<OAuthTokenResponse> {
        val result = tokenRequest(
            config,
            FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("code", code)
                .add("redirect_uri", config.redirectUri)
                .add("client_id", config.clientId)
                .add("code_verifier", codeVerifier)
                .build(),
        )
        result.getOrNull()?.let { persist(configId, it) }
        return result
    }

    /** Refresh the access token using the stored refresh token; persists on success. */
    suspend fun refresh(configId: String, config: ModelOAuthConfig): Result<OAuthTokenResponse> {
        val refreshToken = tokenStore.refreshToken(configId)
            ?: return Result.failure(IllegalStateException("No refresh token stored for this config."))
        val result = tokenRequest(
            config,
            FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", refreshToken)
                .add("client_id", config.clientId)
                .build(),
        )
        // Providers may omit a new refresh token on refresh — keep the existing one if so.
        result.getOrNull()?.let { persist(configId, it, fallbackRefresh = refreshToken) }
        return result
    }

    private fun persist(configId: String, token: OAuthTokenResponse, fallbackRefresh: String? = null) {
        tokenStore.save(
            configId = configId,
            accessToken = token.access_token,
            refreshToken = token.refresh_token ?: fallbackRefresh,
            expiresInSeconds = token.expires_in,
        )
    }

    private suspend fun tokenRequest(config: ModelOAuthConfig, form: FormBody): Result<OAuthTokenResponse> =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(config.tokenEndpoint)
                    .post(form)
                    .addHeader("Accept", "application/json")
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    val body = response.body?.string()
                    if (response.isSuccessful && body != null) {
                        try {
                            Result.success(json.decodeFromString<OAuthTokenResponse>(body))
                        } catch (e: Exception) {
                            AppLogger.e(TAG, "Failed to parse OAuth token response", e)
                            Result.failure(Exception("Failed to parse token response: ${e.message}"))
                        }
                    } else {
                        Result.failure(Exception("Token endpoint HTTP ${response.code}: ${response.message}"))
                    }
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "OAuth token request failed", e)
                Result.failure(e)
            }
        }

    companion object {
        private const val TAG = "ModelOAuthClient"
    }
}

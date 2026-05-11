package com.ai.assistance.operit.ui.features.github

import android.content.Context
import android.net.Uri
import com.ai.assistance.operit.data.api.GitHubApiService
import com.ai.assistance.operit.data.preferences.GitHubAuthPreferences
import com.ai.assistance.operit.data.preferences.GitHubUser
import com.ai.assistance.operit.data.preferences.PkceCodeGenerator

class GitHubOAuthCoordinator(context: Context) {
    private val appContext = context.applicationContext
    private val githubAuth = GitHubAuthPreferences.getInstance(appContext)
    private val githubApiService = GitHubApiService(appContext)

    suspend fun createExternalAuthorizationUrl(): String {
        val state = GitHubAuthPreferences.createOAuthState()
        githubAuth.setPendingOAuthState(state)
        val codeVerifier = PkceCodeGenerator.generateCodeVerifier()
        githubAuth.setPendingCodeVerifier(codeVerifier)
        return githubAuth.getAuthorizationUrl(state = state, codeVerifier = codeVerifier)
    }

    suspend fun completeExternalLogin(uri: Uri): Result<GitHubUser> {
        val expectedState = githubAuth.consumePendingOAuthState()
            ?: return Result.failure(IllegalStateException("Missing pending OAuth state"))
        // PKCE: the verifier must accompany the state. If only one was set, the
        // flow is incomplete and we fail loudly per SECURITY.md "no fallback
        // patterns in security paths."
        val expectedCodeVerifier = githubAuth.consumePendingCodeVerifier()
            ?: return Result.failure(
                IllegalStateException("Missing pending OAuth code verifier")
            )
        return completeLoginFromRedirect(uri, expectedState, expectedCodeVerifier)
    }

    suspend fun completeLoginFromRedirect(
        uri: Uri,
        expectedState: String,
        expectedCodeVerifier: String
    ): Result<GitHubUser> {
        if (!GitHubAuthPreferences.isOAuthRedirectUri(uri)) {
            return Result.failure(IllegalArgumentException("Unsupported OAuth redirect URI"))
        }

        val returnedState = uri.getQueryParameter("state")
        if (returnedState.isNullOrBlank() || returnedState != expectedState) {
            return Result.failure(IllegalStateException("OAuth state mismatch"))
        }

        val error = uri.getQueryParameter("error")
        if (!error.isNullOrBlank()) {
            val errorDescription = uri.getQueryParameter("error_description").orEmpty()
            return Result.failure(
                IllegalStateException(errorDescription.ifBlank { error })
            )
        }

        val code = uri.getQueryParameter("code")
            ?: return Result.failure(IllegalStateException("Missing authorization code"))

        return completeLoginWithCode(code, expectedCodeVerifier)
    }

    suspend fun completeLoginWithCode(
        code: String,
        codeVerifier: String
    ): Result<GitHubUser> {
        return runCatching {
            val tokenResponse =
                githubApiService.getAccessToken(code, codeVerifier).getOrElse { error ->
                    throw error
                }
            githubAuth.updateAccessToken(
                accessToken = tokenResponse.access_token,
                tokenType = tokenResponse.token_type,
                grantedScope = tokenResponse.scope
            )

            val user = githubApiService.getCurrentUser().getOrElse { error ->
                throw error
            }

            githubAuth.saveAuthInfo(
                accessToken = tokenResponse.access_token,
                tokenType = tokenResponse.token_type,
                userInfo = user,
                grantedScope = tokenResponse.scope
            )
            user
        }
    }
}

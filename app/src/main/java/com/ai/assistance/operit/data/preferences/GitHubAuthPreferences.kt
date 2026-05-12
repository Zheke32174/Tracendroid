package com.ai.assistance.operit.data.preferences

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ai.assistance.operit.BuildConfig
import com.ai.assistance.operit.data.preferences.credentials.CredentialVault
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.githubAuthDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "github_auth_preferences")

@Serializable
data class GitHubUser(
    @SerialName("id") val id: Long,
    @SerialName("login") val login: String,
    @SerialName("name") val name: String? = null,
    @SerialName("email") val email: String? = null,
    @SerialName("avatar_url") val avatarUrl: String,
    @SerialName("bio") val bio: String? = null,
    @SerialName("public_repos") val publicRepos: Int? = null,
    @SerialName("followers") val followers: Int? = null,
    @SerialName("following") val following: Int? = null
)

/**
 * GitHub认证偏好设置管理器
 * 负责管理GitHub OAuth认证状态、用户信息和访问令牌
 */
class GitHubAuthPreferences(private val context: Context) {

    companion object {
        // GitHub OAuth相关配置
        val GITHUB_CLIENT_ID = BuildConfig.GITHUB_CLIENT_ID
        // Client secret intentionally absent. Mobile uses PKCE (RFC 7636);
        // see docs/OAUTH_PKCE_MIGRATION.md and SECURITY.md § 4.8.
        const val GITHUB_SCOPE = "notifications,public_repo,user:email,read:user"
        private const val REQUIRED_AUTH_VERSION = 2
        private const val GITHUB_REDIRECT_SCHEME = "operit"
        private const val GITHUB_REDIRECT_HOST = "github-oauth-callback"
        const val GITHUB_REDIRECT_URI = "$GITHUB_REDIRECT_SCHEME://$GITHUB_REDIRECT_HOST"
        
        // 认证相关键
        private val IS_LOGGED_IN = booleanPreferencesKey("is_logged_in")
        // ACCESS_TOKEN, REFRESH_TOKEN, PENDING_OAUTH_CODE_VERIFIER, PENDING_OAUTH_STATE
        // moved to CredentialVault per § 4.9. The DataStore keys below are retained as
        // legacy-source pointers for the one-time migration on first read.
        private val LEGACY_ACCESS_TOKEN = stringPreferencesKey("access_token")
        private val LEGACY_REFRESH_TOKEN = stringPreferencesKey("refresh_token")
        private val LEGACY_PENDING_OAUTH_STATE = stringPreferencesKey("pending_oauth_state")
        private val LEGACY_PENDING_OAUTH_CODE_VERIFIER =
            stringPreferencesKey("pending_oauth_code_verifier")
        private val TOKEN_TYPE = stringPreferencesKey("token_type")
        private val TOKEN_EXPIRES_AT = longPreferencesKey("token_expires_at")
        private val USER_INFO = stringPreferencesKey("user_info")
        private val LAST_LOGIN_TIME = longPreferencesKey("last_login_time")
        private val AUTH_VERSION = longPreferencesKey("auth_version")
        private val GRANTED_SCOPE = stringPreferencesKey("granted_scope")

        // Vault keys mirror the legacy DataStore key names so the migration is a 1:1 copy.
        private const val VAULT_STORE = "github_auth_credentials"
        private const val VK_ACCESS_TOKEN = "access_token"
        private const val VK_REFRESH_TOKEN = "refresh_token"
        private const val VK_PENDING_OAUTH_STATE = "pending_oauth_state"
        private const val VK_PENDING_OAUTH_CODE_VERIFIER = "pending_oauth_code_verifier"
        
        @Volatile
        private var INSTANCE: GitHubAuthPreferences? = null
        
        fun getInstance(context: Context): GitHubAuthPreferences {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: GitHubAuthPreferences(context.applicationContext).also { INSTANCE = it }
            }
        }

        fun createOAuthState(): String {
            val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
            return (1..32)
                .map { chars.random() }
                .joinToString("")
        }

        fun isOAuthRedirectUri(uri: Uri?): Boolean {
            return uri?.scheme == GITHUB_REDIRECT_SCHEME && uri.host == GITHUB_REDIRECT_HOST
        }
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val vault = CredentialVault(context, VAULT_STORE)

    /** Read the access token, migrating from the legacy DataStore copy on first read. */
    private suspend fun readAccessToken(): String? = vault.migrateOnce(
        vaultKey = VK_ACCESS_TOKEN,
        readLegacy = { context.githubAuthDataStore.data.first()[LEGACY_ACCESS_TOKEN] },
        clearLegacy = {
            context.githubAuthDataStore.edit { it.remove(LEGACY_ACCESS_TOKEN) }
        },
    )

    /** Read the refresh token, with the same one-time migration. */
    @Suppress("unused")
    private suspend fun readRefreshToken(): String? = vault.migrateOnce(
        vaultKey = VK_REFRESH_TOKEN,
        readLegacy = { context.githubAuthDataStore.data.first()[LEGACY_REFRESH_TOKEN] },
        clearLegacy = {
            context.githubAuthDataStore.edit { it.remove(LEGACY_REFRESH_TOKEN) }
        },
    )

    private val requiredScopes: Set<String> =
        GITHUB_SCOPE.split(",")
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()

    private fun parseScopeSet(scope: String?): Set<String> {
        return scope
            ?.split(",")
            ?.map { it.trim().lowercase() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            .orEmpty()
    }

    private fun isAuthSessionCurrent(preferences: Preferences): Boolean {
        val grantedScopes = parseScopeSet(preferences[GRANTED_SCOPE])
        val authVersion = preferences[AUTH_VERSION] ?: 0L
        return authVersion >= REQUIRED_AUTH_VERSION && grantedScopes.containsAll(requiredScopes)
    }

    // 登录状态Flow
    val isLoggedInFlow: Flow<Boolean> = context.githubAuthDataStore.data.map { preferences ->
        (preferences[IS_LOGGED_IN] ?: false) && isAuthSessionCurrent(preferences)
    }

    // 访问令牌Flow — reads from CredentialVault inside the map block. The Flow's
    // reactivity is driven by DataStore state changes (IS_LOGGED_IN, AUTH_VERSION,
    // GRANTED_SCOPE), which is exactly when consumers need to re-evaluate.
    val accessTokenFlow: Flow<String?> = context.githubAuthDataStore.data.map { preferences ->
        if (isAuthSessionCurrent(preferences)) readAccessToken() else null
    }

    // 用户信息Flow
    val userInfoFlow: Flow<GitHubUser?> = context.githubAuthDataStore.data.map { preferences ->
        if (!isAuthSessionCurrent(preferences)) {
            return@map null
        }
        val userInfoJson = preferences[USER_INFO]
        if (userInfoJson != null) {
            try {
                json.decodeFromString<GitHubUser>(userInfoJson)
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }
    }

    // 最后登录时间Flow
    val lastLoginTimeFlow: Flow<Long> = context.githubAuthDataStore.data.map { preferences ->
        preferences[LAST_LOGIN_TIME] ?: 0L
    }

    /**
     * 保存认证信息
     */
    suspend fun saveAuthInfo(
        accessToken: String,
        tokenType: String = "bearer",
        expiresIn: Long? = null,
        refreshToken: String? = null,
        userInfo: GitHubUser,
        grantedScope: String? = null
    ) {
        context.githubAuthDataStore.edit { preferences ->
            preferences[IS_LOGGED_IN] = true
            preferences[TOKEN_TYPE] = tokenType
            preferences[USER_INFO] = json.encodeToString(userInfo)
            preferences[LAST_LOGIN_TIME] = System.currentTimeMillis()
            preferences[AUTH_VERSION] = REQUIRED_AUTH_VERSION.toLong()
            preferences[GRANTED_SCOPE] = grantedScope.orEmpty()

            expiresIn?.let {
                preferences[TOKEN_EXPIRES_AT] = System.currentTimeMillis() + (it * 1000)
            }
        }
        // Tokens go to the vault, not to DataStore.
        vault.put(VK_ACCESS_TOKEN, accessToken)
        if (refreshToken != null) vault.put(VK_REFRESH_TOKEN, refreshToken)
    }

    /**
     * 更新用户信息
     */
    suspend fun updateUserInfo(userInfo: GitHubUser) {
        context.githubAuthDataStore.edit { preferences ->
            preferences[USER_INFO] = json.encodeToString(userInfo)
        }
    }

    /**
     * 更新访问令牌
     */
    suspend fun updateAccessToken(
        accessToken: String,
        tokenType: String = "bearer",
        expiresIn: Long? = null,
        grantedScope: String? = null
    ) {
        context.githubAuthDataStore.edit { preferences ->
            preferences[TOKEN_TYPE] = tokenType
            preferences[AUTH_VERSION] = REQUIRED_AUTH_VERSION.toLong()
            preferences[GRANTED_SCOPE] = grantedScope.orEmpty()

            expiresIn?.let {
                preferences[TOKEN_EXPIRES_AT] = System.currentTimeMillis() + (it * 1000)
            }
        }
        vault.put(VK_ACCESS_TOKEN, accessToken)
    }

    /**
     * 检查令牌是否已过期
     */
    suspend fun isTokenExpired(): Boolean {
        val preferences = context.githubAuthDataStore.data.first()
        val expiresAt = preferences[TOKEN_EXPIRES_AT] ?: return false
        return System.currentTimeMillis() >= expiresAt
    }

    /**
     * 获取当前访问令牌
     */
    suspend fun getCurrentAccessToken(): String? {
        val preferences = context.githubAuthDataStore.data.first()
        if (!isAuthSessionCurrent(preferences)) {
            return null
        }
        return readAccessToken()
    }

    /**
     * 获取当前用户信息
     */
    suspend fun getCurrentUserInfo(): GitHubUser? {
        val preferences = context.githubAuthDataStore.data.first()
        if (!isAuthSessionCurrent(preferences)) {
            return null
        }
        val userInfoJson = preferences[USER_INFO]
        return if (userInfoJson != null) {
            try {
                json.decodeFromString<GitHubUser>(userInfoJson)
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }
    }

    /**
     * 检查是否已登录
     */
    suspend fun isLoggedIn(): Boolean {
        val preferences = context.githubAuthDataStore.data.first()
        return (preferences[IS_LOGGED_IN] ?: false) && isAuthSessionCurrent(preferences)
    }

    /**
     * 登出
     */
    suspend fun logout() {
        context.githubAuthDataStore.edit { preferences ->
            preferences.clear()
        }
        // Clear vault entries too — logout is "forget everything", not "forget
        // metadata only".
        vault.remove(VK_ACCESS_TOKEN)
        vault.remove(VK_REFRESH_TOKEN)
        vault.remove(VK_PENDING_OAUTH_STATE)
        vault.remove(VK_PENDING_OAUTH_CODE_VERIFIER)
    }

    suspend fun setPendingOAuthState(state: String) {
        vault.put(VK_PENDING_OAUTH_STATE, state)
    }

    suspend fun consumePendingOAuthState(): String? {
        // Migrate from the legacy DataStore copy if present.
        val state = vault.migrateOnce(
            vaultKey = VK_PENDING_OAUTH_STATE,
            readLegacy = { context.githubAuthDataStore.data.first()[LEGACY_PENDING_OAUTH_STATE] },
            clearLegacy = {
                context.githubAuthDataStore.edit { it.remove(LEGACY_PENDING_OAUTH_STATE) }
            },
        )
        vault.remove(VK_PENDING_OAUTH_STATE)
        return state
    }

    /**
     * Persist the PKCE code_verifier between the authorize-URL build and the
     * callback. Stored in CredentialVault per § 4.9 (encrypted at rest).
     */
    suspend fun setPendingCodeVerifier(verifier: String) {
        vault.put(VK_PENDING_OAUTH_CODE_VERIFIER, verifier)
    }

    /**
     * Read-and-clear the pending PKCE verifier. The callback consumes it once;
     * a second read returns null.
     */
    suspend fun consumePendingCodeVerifier(): String? {
        val verifier = vault.migrateOnce(
            vaultKey = VK_PENDING_OAUTH_CODE_VERIFIER,
            readLegacy = {
                context.githubAuthDataStore.data.first()[LEGACY_PENDING_OAUTH_CODE_VERIFIER]
            },
            clearLegacy = {
                context.githubAuthDataStore.edit {
                    it.remove(LEGACY_PENDING_OAUTH_CODE_VERIFIER)
                }
            },
        )
        vault.remove(VK_PENDING_OAUTH_CODE_VERIFIER)
        return verifier
    }

    /**
     * 生成GitHub OAuth授权URL。
     *
     * The caller is responsible for generating the code_verifier (via
     * PkceCodeGenerator.generateCodeVerifier()) and persisting it via
     * setPendingCodeVerifier() or in-Compose rememberSaveable, depending on
     * the flow. Keeping this function non-suspend lets Compose callers invoke
     * it directly inside remember { } blocks.
     */
    fun getAuthorizationUrl(
        state: String = createOAuthState(),
        codeVerifier: String
    ): String {
        val codeChallenge = PkceCodeGenerator.computeCodeChallenge(codeVerifier)
        return Uri.parse("https://github.com/login/oauth/authorize")
            .buildUpon()
            .appendQueryParameter("client_id", GITHUB_CLIENT_ID)
            .appendQueryParameter("redirect_uri", GITHUB_REDIRECT_URI)
            .appendQueryParameter("scope", GITHUB_SCOPE)
            .appendQueryParameter("state", state)
            .appendQueryParameter("code_challenge", codeChallenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .build()
            .toString()
    }

    /**
     * 获取访问令牌的授权头
     */
    suspend fun getAuthorizationHeader(): String? {
        val token = getCurrentAccessToken()
        return if (token != null) {
            "Bearer $token"
        } else {
            null
        }
    }
} 

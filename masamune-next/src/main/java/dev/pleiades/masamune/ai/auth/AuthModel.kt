package dev.pleiades.masamune.ai.auth

/**
 * The auth seam.
 *
 * Before this file the module had exactly one credential model: a pasted API key
 * (`ProviderConfig.isUsable` was `apiKey.isNotBlank()`). That is the wrong model for a user who
 * pays for subscriptions and drives them through account-authenticated CLIs. So there are now
 * two modes, and the API key is the *fallback*, not the requirement.
 *
 * Everything in this package is deliberately provider-neutral: an [OAuthProfile] is data, the
 * client is one implementation of RFC 6749 / RFC 7636 / RFC 8628, and which providers are
 * offered is a catalog, not a branch.
 */
enum class AuthMode(val label: String) {
    /** OAuth against a provider account. Tokens live in the Keystore-backed vault. */
    SUBSCRIPTION("Subscription account"),

    /** The original path. Still here, still works, no longer the only way in. */
    API_KEY("API key"),
    ;

    companion object {
        fun parse(value: String?): AuthMode =
            entries.firstOrNull { it.name == value } ?: API_KEY
    }
}

/** Which OAuth grant a provider actually supports — measured from its metadata, not assumed. */
enum class OAuthGrant {
    /**
     * RFC 8628 Device Authorization Grant. No redirect URI, no registered callback: the user
     * approves on any browser and the app polls. This is the only grant that works without a
     * client whose redirect URI was registered against *this* app, which is why it is preferred
     * wherever a provider advertises `device_authorization_endpoint`.
     */
    DEVICE_CODE,

    /** RFC 6749 authorization code + RFC 7636 PKCE. Needs a redirect URI this app can receive. */
    AUTHORIZATION_CODE_PKCE,
}

/**
 * One provider's OAuth description.
 *
 * Endpoint values here were read from the provider's own live metadata document rather than
 * recalled — see [OAuthCatalog] for what was fetched and what each fetch returned.
 *
 * @param blockedReason non-null means this provider CANNOT be signed into from this build. The
 *   row renders disabled and prints this sentence. It never renders an enabled button that
 *   opens nothing.
 */
data class OAuthProfile(
    val id: String,
    val label: String,
    val issuer: String,
    val grant: OAuthGrant,
    val deviceAuthorizationEndpoint: String? = null,
    val authorizationEndpoint: String? = null,
    val tokenEndpoint: String = "",
    val revocationEndpoint: String? = null,
    val userInfoEndpoint: String? = null,
    val scope: String = "openid email profile",
    /** Rendered under the Client ID field so the user knows what to create and where. */
    val clientHint: String = "",
    val blockedReason: String? = null,
    /** True for the user-defined row: its endpoints come from runtime OIDC discovery. */
    val isCustom: Boolean = false,
) {
    val isBlocked: Boolean get() = blockedReason != null
}

/**
 * The provider catalog, with the measurement behind every entry.
 *
 * Each metadata document below was fetched while writing this file. The results decide which
 * rows are live and which ship disabled — that is the whole point of recording them:
 *
 *  - `https://accounts.google.com/.well-known/openid-configuration` → HTTP 200, and it
 *    advertises `"device_authorization_endpoint": "https://oauth2.googleapis.com/device/code"`.
 *    A probe `POST` to that endpoint with a made-up client returned HTTP 401
 *    `{"error":"invalid_client","error_description":"The OAuth client was not found."}` — the
 *    endpoint is live and exercisable; the only missing input is a real client ID.
 *
 *  - `https://auth.openai.com/.well-known/openid-configuration` → HTTP 200, PKCE `S256`,
 *    grants `authorization_code` + `refresh_token`, `token_endpoint_auth_methods_supported`
 *    includes `none` (public client). It advertises NO `device_authorization_endpoint`, so the
 *    only available grant is authorization code + PKCE, which needs a redirect URI. A probe
 *    `POST` to the token endpoint returned HTTP 401 `invalid_client` — again live, again
 *    missing only a client ID.
 *
 *  - Anthropic publishes no account-login metadata that resolves:
 *    `console.anthropic.com/.well-known/oauth-authorization-server` redirects to
 *    platform.claude.com and 404s; `claude.ai/.well-known/openid-configuration` returns the web
 *    app's HTML rather than JSON; the one document that does resolve,
 *    `api.anthropic.com/.well-known/oauth-authorization-server`, carries
 *    `"issuer":"https://api.anthropic.com/mcp/gdrive"` — an MCP connector's authorization
 *    server, not Claude account login. So that row is blocked, with that sentence on screen.
 */
object OAuthCatalog {

    const val GOOGLE = "google"
    const val OPENAI = "openai"
    const val ANTHROPIC = "anthropic"
    const val CUSTOM = "custom"

    val google = OAuthProfile(
        id = GOOGLE,
        label = "Google account",
        issuer = "https://accounts.google.com",
        grant = OAuthGrant.DEVICE_CODE,
        deviceAuthorizationEndpoint = "https://oauth2.googleapis.com/device/code",
        authorizationEndpoint = "https://accounts.google.com/o/oauth2/v2/auth",
        tokenEndpoint = "https://oauth2.googleapis.com/token",
        revocationEndpoint = "https://oauth2.googleapis.com/revoke",
        userInfoEndpoint = "https://openidconnect.googleapis.com/v1/userinfo",
        scope = "openid email profile",
        clientHint = "Google Cloud console → Credentials → Create OAuth client ID → " +
            "application type \"TV and Limited Input devices\". That client type is the one " +
            "the device flow accepts, and it needs no redirect URI.",
    )

    val openai = OAuthProfile(
        id = OPENAI,
        label = "OpenAI account",
        issuer = "https://auth.openai.com",
        grant = OAuthGrant.AUTHORIZATION_CODE_PKCE,
        authorizationEndpoint = "https://auth.openai.com/api/accounts/authorize",
        tokenEndpoint = "https://auth.openai.com/api/accounts/oauth/token",
        revocationEndpoint = "https://auth.openai.com/api/accounts/oauth/revoke",
        userInfoEndpoint = "https://auth.openai.com/api/accounts/oauth/userinfo",
        scope = "openid profile email offline_access",
        clientHint = "auth.openai.com advertises no device endpoint, so this is the redirect " +
            "flow: the client ID you paste must have ${OAuthRedirect.URI} registered as an " +
            "allowed redirect URI, or the browser will refuse before any token is issued.",
    )

    val anthropic = OAuthProfile(
        id = ANTHROPIC,
        label = "Anthropic account",
        issuer = "https://claude.ai",
        grant = OAuthGrant.AUTHORIZATION_CODE_PKCE,
        blockedReason = "Anthropic publishes no OAuth metadata for signing in to a Claude " +
            "subscription. console.anthropic.com/.well-known/oauth-authorization-server " +
            "redirects to platform.claude.com and 404s, claude.ai/.well-known/" +
            "openid-configuration returns the web app's HTML instead of JSON, and the one " +
            "document that does resolve — api.anthropic.com/.well-known/" +
            "oauth-authorization-server — has issuer https://api.anthropic.com/mcp/gdrive, " +
            "which is an MCP connector's authorization server, not account login. There is " +
            "nothing here to point a sign-in button at, so it is not shown as one. Use API " +
            "key mode for Anthropic.",
    )

    /**
     * The general seam, and the reason this is a feature rather than three hardcoded buttons:
     * point it at any issuer that publishes `/.well-known/openid-configuration` and the app
     * discovers the endpoints and picks the grant from what that document actually advertises.
     */
    val custom = OAuthProfile(
        id = CUSTOM,
        label = "Other OpenID provider",
        issuer = "",
        grant = OAuthGrant.DEVICE_CODE,
        scope = "openid email profile offline_access",
        clientHint = "Enter the issuer URL. Masamune fetches <issuer>/.well-known/" +
            "openid-configuration and uses the device flow if that document advertises a " +
            "device_authorization_endpoint, otherwise the redirect flow.",
        isCustom = true,
    )

    val all: List<OAuthProfile> = listOf(google, openai, anthropic, custom)

    fun byId(id: String?): OAuthProfile? = all.firstOrNull { it.id == id }
}

/** The one redirect this app can receive, declared in the manifest. */
object OAuthRedirect {
    const val SCHEME = "masamune"
    const val HOST = "oauth"
    const val PATH = "/callback"
    const val URI = "$SCHEME://$HOST$PATH"
}

/** Who the tokens belong to, for the "signed in as" line. Display only — never a decision. */
data class AccountIdentity(
    val subject: String,
    val email: String?,
    val name: String?,
) {
    /** Best human label available, falling back to the opaque subject rather than to "user". */
    val display: String get() = email?.takeIf { it.isNotBlank() }
        ?: name?.takeIf { it.isNotBlank() }
        ?: subject
}

/** A live signed-in session. Refresh token may be absent — some providers do not issue one. */
data class AccountSession(
    val profileId: String,
    val accessToken: String,
    val refreshToken: String?,
    val expiresAt: Long,
    val scope: String,
    val identity: AccountIdentity?,
    val signedInAt: Long,
) {
    fun isExpired(now: Long = System.currentTimeMillis()): Boolean = now >= expiresAt

    /** Refresh a minute early so a request never starts on a token that dies mid-flight. */
    fun needsRefresh(now: Long = System.currentTimeMillis()): Boolean = now >= expiresAt - 60_000L

    val canRefresh: Boolean get() = !refreshToken.isNullOrBlank()
}

/**
 * An OAuth client registration. `secret` is treated as a credential and never rendered.
 *
 * It arrives one of two ways, and which one matters to the surface:
 *  - the user pasted it, because the provider only issues clients out-of-band; or
 *  - the app asked the issuer for it under RFC 7591 ([selfRegistered]), which is the path that
 *    lets sign-in be a single button with no form at all.
 */
data class OAuthClientRegistration(
    val profileId: String,
    val clientId: String,
    val clientSecret: String?,
    /** Only meaningful for [OAuthCatalog.custom]. */
    val issuer: String = "",
    /**
     * True when this client was obtained by dynamic client registration rather than typed in.
     *
     * Kept so the screen can say *"registered automatically"* instead of showing a filled-in box
     * the user does not remember filling, and so "Forget" reads as revoking a client the app
     * created rather than deleting something the user owns elsewhere.
     */
    val selfRegistered: Boolean = false,
    /**
     * True when this client came compiled into the build ([ShippedClients]) rather than from the
     * user or from a registration call.
     *
     * A shipped client is never written to the vault: it is already in the APK, storing a copy
     * would only create a stale duplicate that survives an update that changes it. The flag also
     * keeps "Forget" from offering to delete something that would simply reappear.
     */
    val shipped: Boolean = false,
) {
    val isComplete: Boolean get() = clientId.isNotBlank()

    /** True when the user never had to supply this — the two cases that make sign-in one tap. */
    val isAutomatic: Boolean get() = shipped || selfRegistered
}

/** Thrown by everything in this package. Message is always safe to render verbatim. */
class AuthException(message: String, cause: Throwable? = null) : Exception(message, cause)

package dev.pleiades.masamune.ai.auth

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import org.json.JSONObject

/**
 * The account layer the rest of the app talks to.
 *
 * Owns: the OAuth client registrations the user supplies, the sealed sessions, endpoint
 * resolution (catalog constants for the named providers, live OIDC discovery for a custom
 * issuer), and automatic refresh. Nothing above this class touches a token endpoint.
 *
 * Single instance per process, like [dev.pleiades.masamune.ai.ProviderStore] and
 * [dev.pleiades.masamune.core.capability.CapabilityGate], so the chat surface and the Account
 * screen always see the same session rather than two copies drifting apart.
 */
class AccountStore private constructor(appContext: Context) {

    private val vault = TokenVault.get(appContext)
    private val client = OAuthClient(appContext)
    private val refreshLock = Mutex()

    /** Non-null when token sealing does not work here; subscription mode is disabled with it. */
    val vaultUnavailableReason: String? get() = vault.unavailableReason

    private val _sessions = MutableStateFlow(loadSessions())
    val sessions: StateFlow<Map<String, AccountSession>> = _sessions.asStateFlow()

    private val _registrations = MutableStateFlow(loadRegistrations())
    val registrations: StateFlow<Map<String, OAuthClientRegistration>> = _registrations.asStateFlow()

    fun sessionFor(profileId: String?): AccountSession? =
        profileId?.let { _sessions.value[it] }

    fun registrationFor(profileId: String?): OAuthClientRegistration? =
        profileId?.let { _registrations.value[it] }

    // ---- client registration ------------------------------------------------------------------

    fun saveRegistration(registration: OAuthClientRegistration) {
        vault.writeRegistration(registration)
        _registrations.value = _registrations.value + (registration.profileId to registration)
    }

    fun clearRegistration(profileId: String) {
        vault.clearRegistration(profileId)
        _registrations.value = _registrations.value - profileId
    }

    // ---- endpoints ----------------------------------------------------------------------------

    /**
     * Endpoints for a profile. The named providers carry theirs as constants (read from their
     * live metadata documents — see [OAuthCatalog]); the custom row uses whatever discovery
     * last returned, and refuses rather than guessing if discovery has not run.
     */
    fun endpointsFor(profile: OAuthProfile): Result<ResolvedEndpoints> {
        profile.blockedReason?.let { return Result.failure(AuthException(it)) }
        if (!profile.isCustom) {
            return Result.success(
                ResolvedEndpoints(
                    issuer = profile.issuer,
                    grant = profile.grant,
                    deviceAuthorizationEndpoint = profile.deviceAuthorizationEndpoint,
                    authorizationEndpoint = profile.authorizationEndpoint,
                    tokenEndpoint = profile.tokenEndpoint,
                    revocationEndpoint = profile.revocationEndpoint,
                    userInfoEndpoint = profile.userInfoEndpoint,
                    scope = profile.scope,
                )
            )
        }
        val cached = vault.readDiscovery(profile.id)
            ?: return Result.failure(
                AuthException(
                    "No endpoints for this issuer yet. Enter the issuer URL and tap " +
                        "\"Discover endpoints\" first — Masamune will not invent them."
                )
            )
        return runCatching { cached.toEndpoints() }
    }

    /** Fetches and caches `<issuer>/.well-known/openid-configuration`. */
    suspend fun discoverCustom(issuer: String): Result<ResolvedEndpoints> {
        val result = client.discover(issuer)
        result.getOrNull()?.let { vault.writeDiscovery(OAuthCatalog.CUSTOM, it.toJson()) }
        return result
    }

    // ---- sign-in ------------------------------------------------------------------------------

    /**
     * RFC 8628 sign-in. [onCode] is called once with the user code and verification URL so the
     * dialog can show them, then this suspends in the poll loop until approval or failure.
     */
    suspend fun signInWithDeviceCode(
        profile: OAuthProfile,
        onPhase: (SignInPhase) -> Unit,
        onCode: (DeviceAuthorization) -> Unit,
    ): Result<AccountSession> {
        val (endpoints, registration) = preflightOrRegister(profile, onPhase)
            .getOrElse { return Result.failure(it) }

        onPhase(SignInPhase.REQUESTING_CODE)
        val authorization = client.startDeviceAuthorization(endpoints, registration)
            .getOrElse { return Result.failure(it) }
        onCode(authorization)

        onPhase(SignInPhase.AWAITING_APPROVAL)
        val token = client.pollDeviceToken(endpoints, registration, authorization)
            .getOrElse { return Result.failure(it) }

        onPhase(SignInPhase.RESOLVING_IDENTITY)
        return Result.success(persist(profile.id, endpoints, token, previous = null))
    }

    /**
     * Step one of the redirect flow: mints PKCE + state, records them, returns the URL the
     * caller should open in a browser. Nothing is stored until [completeRedirectSignIn] wins.
     */
    fun beginRedirectSignIn(profile: OAuthProfile): Result<String> {
        val (endpoints, registration) = preflight(profile).getOrElse { return Result.failure(it) }
        val pkce = PkcePair.generate()
        val state = newOauthState()
        val url = client.buildAuthorizationUrl(endpoints, registration, pkce, state)
            .getOrElse { return Result.failure(it) }
        PendingAuthorization.begin(PendingAuthorization.Request(profile.id, state, pkce.verifier))
        return Result.success(url)
    }

    /**
     * The whole browser handshake in one call — the way a terminal CLI does it.
     *
     * Binds a loopback listener, hands the caller the URL to open ([onOpenBrowser], which is where
     * the browser is launched), then waits on that socket for the provider's redirect and exchanges
     * the code. No callback Activity, no registered custom scheme, and nothing for the user to
     * paste: the only thing they must have done is registered a client ID whose type allows a
     * loopback redirect, which is the type these providers give out for native apps.
     *
     * `state` is compared to what was sent before the code is spent — the CSRF check RFC 6749
     * §10.12 requires — and a mismatch fails loudly instead of exchanging a code we did not ask for.
     * The redirect URI is passed to BOTH the authorize URL and the exchange, because the token
     * endpoint compares them and rejects the pair if they differ.
     */
    suspend fun signInWithLoopback(
        profile: OAuthProfile,
        onPhase: (SignInPhase) -> Unit,
        onOpenBrowser: (String) -> Unit,
    ): Result<AccountSession> {
        val (endpoints, registration) = preflightOrRegister(profile, onPhase)
            .getOrElse { return Result.failure(it) }
        val server = LoopbackRedirect.open()
            ?: return Result.failure(
                AuthException(
                    "Could not bind a loopback port for the sign-in callback. Another app may be " +
                        "holding every port, or the network is restricted for this process."
                )
            )
        return server.use {
            val pkce = PkcePair.generate()
            val state = newOauthState()
            val url = client.buildAuthorizationUrl(endpoints, registration, pkce, state, server.redirectUri)
                .getOrElse { return Result.failure(it) }

            onOpenBrowser(url)
            onPhase(SignInPhase.AWAITING_APPROVAL)

            val redirect = withContext(Dispatchers.IO) { server.awaitRedirect() }
                ?: return Result.failure(
                    AuthException(
                        "No callback arrived at ${server.redirectUri} within five minutes. Either " +
                            "the browser was closed, or this client ID does not allow a loopback " +
                            "redirect (register it as a native/desktop app)."
                    )
                )

            redirect.error?.let { err ->
                val detail = redirect.errorDescription?.let { ": $it" } ?: ""
                return Result.failure(AuthException("$err$detail"))
            }
            if (redirect.state != state) {
                return Result.failure(
                    AuthException(
                        "The provider returned a different `state` than the one this sign-in sent. " +
                            "The code was NOT exchanged."
                    )
                )
            }
            val code = redirect.code
                ?: return Result.failure(AuthException("The callback carried no authorization code."))

            onPhase(SignInPhase.EXCHANGING)
            val token = client.exchangeCode(endpoints, registration, code, pkce.verifier, server.redirectUri)
                .getOrElse { return Result.failure(it) }

            onPhase(SignInPhase.RESOLVING_IDENTITY)
            Result.success(persist(profile.id, endpoints, token, previous = null))
        }
    }

    /** Step two: waits for the callback activity, then exchanges the code. */
    suspend fun completeRedirectSignIn(
        profile: OAuthProfile,
        onPhase: (SignInPhase) -> Unit,
    ): Result<AccountSession> {
        val request = PendingAuthorization.current()
            ?: return Result.failure(AuthException("No sign-in is in flight."))
        val (endpoints, registration) = preflight(profile).getOrElse { return Result.failure(it) }

        onPhase(SignInPhase.AWAITING_APPROVAL)
        val delivered = withTimeoutOrNull(REDIRECT_TIMEOUT_MS) {
            PendingAuthorization.result.filterNotNull().first()
        } ?: run {
            PendingAuthorization.clear()
            return Result.failure(
                AuthException(
                    "No callback arrived at ${OAuthRedirect.URI} within five minutes. Either " +
                        "the browser was closed, or that redirect URI is not registered on the " +
                        "client ID you entered."
                )
            )
        }
        PendingAuthorization.clear()
        val code = delivered.getOrElse { return Result.failure(it) }

        onPhase(SignInPhase.EXCHANGING)
        val token = client.exchangeCode(endpoints, registration, code, request.verifier)
            .getOrElse { return Result.failure(it) }

        onPhase(SignInPhase.RESOLVING_IDENTITY)
        return Result.success(persist(profile.id, endpoints, token, previous = null))
    }

    // ---- refresh / sign-out -------------------------------------------------------------------

    /**
     * The token the chat provider actually sends.
     *
     * Refreshes a minute before expiry so a stream never starts on a token that dies mid-flight.
     * Serialised by a mutex: two surfaces asking at once must not both burn the refresh token,
     * because providers that rotate refresh tokens invalidate the loser.
     */
    suspend fun accessToken(profileId: String): Result<String> = refreshLock.withLock {
        val profile = OAuthCatalog.byId(profileId)
            ?: return Result.failure(AuthException("Unknown provider \"$profileId\"."))
        val session = _sessions.value[profileId]
            ?: return Result.failure(
                AuthException(
                    "Not signed in to ${profile.label}. Open About → Account and sign in, or " +
                        "switch this provider to API key mode."
                )
            )
        if (!session.needsRefresh()) return Result.success(session.accessToken)

        if (!session.canRefresh) {
            return if (session.isExpired()) {
                Result.failure(
                    AuthException(
                        "The ${profile.label} access token expired and the provider issued no " +
                            "refresh token, so it cannot be renewed silently. Sign in again."
                    )
                )
            } else {
                // Inside the skew window but still valid — send it rather than fail early.
                Result.success(session.accessToken)
            }
        }
        refreshLocked(profile, session).map { it.accessToken }
    }

    /** User-triggered refresh, from the "Refresh now" row. */
    suspend fun refreshNow(profile: OAuthProfile): Result<AccountSession> = refreshLock.withLock {
        val session = _sessions.value[profile.id]
            ?: return Result.failure(AuthException("Not signed in to ${profile.label}."))
        if (!session.canRefresh) {
            return Result.failure(
                AuthException(
                    "${profile.label} issued no refresh token for this session, so there is " +
                        "nothing to refresh. Signing in again is the only way to extend it."
                )
            )
        }
        refreshLocked(profile, session)
    }

    private suspend fun refreshLocked(
        profile: OAuthProfile,
        session: AccountSession,
    ): Result<AccountSession> {
        val (endpoints, registration) = preflight(profile).getOrElse { return Result.failure(it) }
        val token = client.refresh(endpoints, registration, session.refreshToken.orEmpty())
            .getOrElse { return Result.failure(it) }
        return Result.success(persist(profile.id, endpoints, token, previous = session))
    }

    /**
     * Removes the session locally, and tries to revoke upstream when the provider publishes a
     * revocation endpoint. The returned string says which of those two actually happened.
     */
    suspend fun signOut(profile: OAuthProfile): String {
        val session = _sessions.value[profile.id]
        vault.clearSession(profile.id)
        _sessions.value = _sessions.value - profile.id

        if (session == null) return "There was no stored session for ${profile.label}."
        val (endpoints, registration) = preflight(profile).getOrElse {
            return "Signed out on this device. Nothing was revoked upstream: ${it.message}"
        }
        val token = session.refreshToken ?: session.accessToken
        return client.revoke(endpoints, registration, token).fold(
            onSuccess = { "Signed out, and the token was revoked at ${endpoints.issuer}." },
            onFailure = {
                "Signed out on this device. Upstream revocation did not complete: " +
                    "${it.message} The token stays valid at the provider until it expires."
            },
        )
    }

    // ---- plumbing -----------------------------------------------------------------------------

    /**
     * Everything a flow needs before it can touch the network, or the specific reason it does
     * not have it. Returning the reason rather than null is what lets the screen print the one
     * missing thing instead of a generic failure.
     */
    fun preflight(profile: OAuthProfile): Result<Pair<ResolvedEndpoints, OAuthClientRegistration>> {
        vaultUnavailableReason?.let { return Result.failure(AuthException(it)) }
        val registration = _registrations.value[profile.id]?.takeIf { it.isComplete }
            ?: return Result.failure(
                AuthException(
                    "No OAuth client ID is saved for ${profile.label}. ${profile.clientHint}"
                )
            )
        val endpoints = endpointsFor(profile).getOrElse { return Result.failure(it) }
        return Result.success(endpoints to registration)
    }

    /**
     * [preflight], but it will *get* a client rather than demanding the user already has one.
     *
     * This is what makes the Account screen a login portal instead of a credentials form. OAuth
     * cannot start without a `client_id` — that is the protocol, not a design choice — but RFC 7591
     * lets an issuer hand one out on request. Where the issuer advertises a `registration_endpoint`,
     * the app registers itself on first sign-in and the user only ever taps "Sign in".
     *
     * Order matters and is deliberate:
     *  1. A saved registration always wins. A user who created a client with the provider keeps
     *     using it; re-registering behind their back would silently strand it.
     *  2. Endpoints resolve next. For the custom row that may mean discovery has not run, which
     *     fails with *that* reason rather than being papered over by a registration attempt.
     *  3. Only then, if the issuer advertises registration, do we register — and persist the result,
     *     so this happens once per provider rather than on every sign-in.
     *
     * When the issuer advertises nothing, this fails with the client hint naming what to create and
     * where. That is the honest end of the road for a provider like Google, which publishes no
     * registration endpoint: the screen says so instead of offering a button that cannot work.
     */
    suspend fun preflightOrRegister(
        profile: OAuthProfile,
        onPhase: (SignInPhase) -> Unit = {},
    ): Result<Pair<ResolvedEndpoints, OAuthClientRegistration>> {
        vaultUnavailableReason?.let { return Result.failure(AuthException(it)) }

        val endpoints = endpointsFor(profile).getOrElse { return Result.failure(it) }

        _registrations.value[profile.id]?.takeIf { it.isComplete }?.let {
            return Result.success(endpoints to it)
        }

        if (endpoints.registrationEndpoint == null) {
            return Result.failure(
                AuthException(
                    "${profile.label} does not issue OAuth clients on request — its metadata " +
                        "advertises no registration_endpoint — so Masamune cannot register itself " +
                        "here. ${profile.clientHint}"
                )
            )
        }

        onPhase(SignInPhase.REGISTERING_CLIENT)
        val registration = client.registerClient(
            endpoints = endpoints,
            profileId = profile.id,
            // Every redirect this app can actually receive. Registering a URI we do not listen on
            // would produce a client that authorizes and then dead-ends at the callback.
            redirectUris = listOf(LoopbackRedirect.URI_TEMPLATE, OAuthRedirect.URI),
        ).getOrElse { return Result.failure(it) }

        saveRegistration(registration)
        return Result.success(endpoints to registration)
    }

    private suspend fun persist(
        profileId: String,
        endpoints: ResolvedEndpoints,
        token: TokenResponse,
        previous: AccountSession?,
    ): AccountSession {
        val identity = client.resolveIdentity(endpoints, token) ?: previous?.identity
        val session = AccountSession(
            profileId = profileId,
            accessToken = token.accessToken,
            // A refresh response commonly omits refresh_token; keeping the old one is correct.
            refreshToken = token.refreshToken ?: previous?.refreshToken,
            expiresAt = System.currentTimeMillis() + token.expiresInSeconds * 1000L,
            scope = token.scope,
            identity = identity,
            signedInAt = previous?.signedInAt ?: System.currentTimeMillis(),
        )
        vault.writeSession(session)
        _sessions.value = _sessions.value + (profileId to session)
        return session
    }

    private fun loadSessions(): Map<String, AccountSession> =
        OAuthCatalog.all.mapNotNull { vault.readSession(it.id) }.associateBy { it.profileId }

    private fun loadRegistrations(): Map<String, OAuthClientRegistration> =
        OAuthCatalog.all.mapNotNull { vault.readRegistration(it.id) }.associateBy { it.profileId }

    companion object {
        private const val REDIRECT_TIMEOUT_MS = 5 * 60 * 1000L

        @Volatile
        private var instance: AccountStore? = null

        fun get(context: Context): AccountStore =
            instance ?: synchronized(this) {
                instance ?: AccountStore(context.applicationContext).also { instance = it }
            }
    }
}

private fun ResolvedEndpoints.toJson(): JSONObject = JSONObject().apply {
    put("issuer", issuer)
    put("grant", grant.name)
    put("device_authorization_endpoint", deviceAuthorizationEndpoint ?: "")
    put("authorization_endpoint", authorizationEndpoint ?: "")
    put("token_endpoint", tokenEndpoint)
    put("revocation_endpoint", revocationEndpoint ?: "")
    put("userinfo_endpoint", userInfoEndpoint ?: "")
    put("scope", scope)
    put("registration_endpoint", registrationEndpoint ?: "")
}

private fun JSONObject.toEndpoints(): ResolvedEndpoints = ResolvedEndpoints(
    issuer = optString("issuer"),
    grant = runCatching { OAuthGrant.valueOf(optString("grant")) }
        .getOrDefault(OAuthGrant.AUTHORIZATION_CODE_PKCE),
    deviceAuthorizationEndpoint = optString("device_authorization_endpoint").takeIf { it.isNotBlank() },
    authorizationEndpoint = optString("authorization_endpoint").takeIf { it.isNotBlank() },
    tokenEndpoint = optString("token_endpoint"),
    revocationEndpoint = optString("revocation_endpoint").takeIf { it.isNotBlank() },
    userInfoEndpoint = optString("userinfo_endpoint").takeIf { it.isNotBlank() },
    scope = optString("scope").ifBlank { OAuthCatalog.custom.scope },
    registrationEndpoint = optString("registration_endpoint").takeIf { it.isNotBlank() },
)

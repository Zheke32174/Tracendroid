package dev.pleiades.masamune.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import dev.pleiades.masamune.R
import androidx.lifecycle.viewModelScope
import dev.pleiades.masamune.ai.AiServiceFactory
import dev.pleiades.masamune.ai.ProviderStore
import dev.pleiades.masamune.ai.auth.AccountSession
import dev.pleiades.masamune.ai.auth.AccountStore
import dev.pleiades.masamune.ai.auth.AuthMode
import dev.pleiades.masamune.ai.auth.DeviceAuthorization
import dev.pleiades.masamune.ai.auth.OAuthCatalog
import dev.pleiades.masamune.ai.auth.OAuthClientRegistration
import dev.pleiades.masamune.ai.auth.OAuthGrant
import dev.pleiades.masamune.ai.auth.OAuthProfile
import dev.pleiades.masamune.ai.auth.ResolvedEndpoints
import dev.pleiades.masamune.ai.auth.SignInPhase
import dev.pleiades.masamune.core.capability.Capability
import dev.pleiades.masamune.core.capability.Caller
import dev.pleiades.masamune.core.capability.CapabilityGate
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AccountUiState(
    val mode: AuthMode = AuthMode.API_KEY,
    val activeProfileId: String = "",
    val sessions: Map<String, AccountSession> = emptyMap(),
    val registrations: Map<String, OAuthClientRegistration> = emptyMap(),
    /** Non-null = token sealing is impossible here; every sign-in row renders disabled. */
    val vaultReason: String? = null,
    /**
     * Profile ids whose issuer advertises an RFC 7591 `registration_endpoint`.
     *
     * These need no client ID from the user at all — the app asks the issuer for one on first
     * sign-in. The screen uses this to decide whether to show a credentials form or just a button,
     * which is the whole difference between a login portal and a configuration screen.
     */
    val selfRegisterable: Set<String> = emptySet(),
    val networkGranted: Boolean = false,
    /** Which row is mid-flow. Exactly one at a time; the others stay interactive. */
    val busyProfileId: String? = null,
    val phase: SignInPhase? = null,
    val deviceAuth: DeviceAuthorization? = null,
    val discovered: ResolvedEndpoints? = null,
    val message: String? = null,
    val messageOk: Boolean = false,
) {
    fun sessionFor(profileId: String): AccountSession? = sessions[profileId]
    fun canSelfRegister(profileId: String): Boolean = profileId in selfRegisterable
    fun registrationFor(profileId: String): OAuthClientRegistration? = registrations[profileId]
    fun isBusy(profileId: String): Boolean = busyProfileId == profileId
}

/**
 * Drives About → Account.
 *
 * Holds no OAuth logic of its own — every network step is [AccountStore]'s, which in turn goes
 * through the capability gate. This class exists to turn those suspending calls into a state a
 * screen can render, and to make sure exactly one row can be mid-flow at a time.
 */
class AccountViewModel(private val appContext: Context) : ViewModel() {

    private val accounts = AccountStore.get(appContext)
    private val providers = ProviderStore.get(appContext)
    private val gate = CapabilityGate.get(appContext)

    private val _state = MutableStateFlow(
        AccountUiState(vaultReason = accounts.vaultUnavailableReason)
    )
    val state: StateFlow<AccountUiState> = _state.asStateFlow()

    private var flowJob: Job? = null

    init {
        viewModelScope.launch {
            accounts.sessions.collect { _state.value = _state.value.copy(sessions = it) }
        }
        viewModelScope.launch {
            accounts.registrations.collect { _state.value = _state.value.copy(registrations = it) }
        }
        viewModelScope.launch {
            providers.config.collect {
                _state.value = _state.value.copy(
                    mode = it.authMode,
                    activeProfileId = it.oauthProfileId,
                )
            }
        }
        refreshGrants()
    }

    fun refreshGrants() {
        _state.value = _state.value.copy(
            networkGranted = gate.isGranted(Caller.User, Capability.NETWORK),
            selfRegisterable = selfRegisterableIds(),
        )
    }

    /**
     * Which providers will hand out a client on request, read from their resolved endpoints.
     *
     * Recomputed rather than cached because the custom row's endpoints only exist after discovery
     * runs — a row that becomes self-registering mid-session has to stop showing a form.
     */
    private fun selfRegisterableIds(): Set<String> = OAuthCatalog.all
        .filter { accounts.endpointsFor(it).getOrNull()?.registrationEndpoint != null }
        .map { it.id }
        .toSet()

    fun grantNetwork() {
        gate.grant(Caller.User, Capability.NETWORK)
        refreshGrants()
    }

    fun dismissMessage() {
        _state.value = _state.value.copy(message = null)
    }

    // ---- mode and provider selection -----------------------------------------------------------

    fun setMode(mode: AuthMode) {
        providers.setAuthMode(mode)
    }

    fun useForChat(profile: OAuthProfile) {
        providers.save(
            providers.config.value.copy(
                authMode = AuthMode.SUBSCRIPTION,
                oauthProfileId = profile.id,
            )
        )
        report("Chat now authenticates as the connected ${profile.label}.", ok = true)
    }

    // ---- client registration --------------------------------------------------------------------

    fun saveRegistration(profile: OAuthProfile, clientId: String, clientSecret: String, issuer: String) {
        if (clientId.isBlank()) {
            report("A client ID is required; nothing was saved.", ok = false)
            return
        }
        accounts.saveRegistration(
            OAuthClientRegistration(
                profileId = profile.id,
                clientId = clientId.trim(),
                clientSecret = clientSecret.trim().takeIf { it.isNotBlank() },
                issuer = issuer.trim(),
            )
        )
        report(appContext.getString(R.string.account_client_saved), ok = true)
    }

    fun forgetRegistration(profile: OAuthProfile) {
        accounts.clearRegistration(profile.id)
        report(appContext.getString(R.string.account_client_forgotten), ok = true)
    }

    fun discover(issuer: String) {
        launchFlow(OAuthCatalog.CUSTOM) {
            accounts.discoverCustom(issuer).fold(
                onSuccess = {
                    _state.value = _state.value.copy(
                        discovered = it,
                        selfRegisterable = selfRegisterableIds(),
                    )
                    report(
                        "Discovered ${it.issuer}: grant ${it.grant.name}, token endpoint " +
                            "${it.tokenEndpoint}.",
                        ok = true,
                    )
                },
                onFailure = { report(it.message ?: "Discovery failed.", ok = false) },
            )
        }
    }

    // ---- sign-in ---------------------------------------------------------------------------------

    /**
     * Runs whichever grant the provider actually supports. The choice is read from the resolved
     * endpoints, not from a hardcoded branch, so a discovered issuer gets the right flow.
     */
    fun signIn(profile: OAuthProfile) {
        val endpoints = accounts.endpointsFor(profile).getOrElse {
            report(it.message ?: "No endpoints for ${profile.label}.", ok = false)
            return
        }
        when (endpoints.grant) {
            OAuthGrant.DEVICE_CODE -> signInWithDeviceCode(profile)
            OAuthGrant.AUTHORIZATION_CODE_PKCE -> signInWithRedirect(profile)
        }
    }

    private fun signInWithDeviceCode(profile: OAuthProfile) {
        launchFlow(profile.id) {
            val result = accounts.signInWithDeviceCode(
                profile = profile,
                onPhase = { phase -> _state.value = _state.value.copy(phase = phase) },
                onCode = { auth -> _state.value = _state.value.copy(deviceAuth = auth) },
            )
            finishSignIn(profile, result)
        }
    }

    /**
     * Browser sign-in, the way a terminal CLI does it: bind a loopback listener, send the browser
     * to the provider, catch the redirect on `127.0.0.1`, exchange the code. The user taps once and
     * pastes nothing.
     *
     * The ordering matters and is why the browser is launched from *inside*
     * [AccountStore.signInWithLoopback] via the `onOpenBrowser` callback rather than before it: the
     * listener must already be bound when the provider redirects, and on a fast provider (or a
     * browser that restores a live session) that redirect can arrive almost immediately. Opening
     * the browser first and binding after is a race that fails as "no callback arrived".
     *
     * A browser that cannot be opened is reported through the same failure path as any other, so
     * the button never looks like it did nothing.
     */
    private fun signInWithRedirect(profile: OAuthProfile) {
        launchFlow(profile.id) {
            var browserError: Throwable? = null
            val result = accounts.signInWithLoopback(
                profile = profile,
                onPhase = { phase -> _state.value = _state.value.copy(phase = phase) },
                onOpenBrowser = { url ->
                    // From an application context, so NEW_TASK is required rather than optional.
                    runCatching {
                        appContext.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }.onFailure { browserError = it }
                },
            )
            val browserFailure = browserError
            if (browserFailure != null && result.isFailure) {
                // Name the real cause: with no browser the wait could only ever have timed out.
                report(
                    "No browser on this device could open the provider's sign-in page, so the " +
                        "flow was not started: ${browserFailure.message}",
                    ok = false,
                )
                _state.value = _state.value.copy(phase = null, busyProfileId = null)
            } else {
                finishSignIn(profile, result)
            }
        }
    }

    private fun finishSignIn(profile: OAuthProfile, result: Result<AccountSession>) {
        result.fold(
            onSuccess = { session ->
                // Signing in is a stated intent to use that account, so point chat at it.
                providers.save(
                    providers.config.value.copy(
                        authMode = AuthMode.SUBSCRIPTION,
                        oauthProfileId = profile.id,
                    )
                )
                report(
                    "Signed in to ${profile.label} as ${session.identity?.display ?: "an account " +
                        "the provider did not name"}. Chat now uses this account.",
                    ok = true,
                )
            },
            onFailure = { report(it.message ?: "Failed to authenticate", ok = false) },
        )
    }

    fun cancelSignIn() {
        flowJob?.cancel()
        _state.value = _state.value.copy(
            busyProfileId = null,
            phase = null,
            deviceAuth = null,
            message = "Sign-in cancelled. Nothing was stored.",
            messageOk = false,
        )
    }

    // ---- session management -----------------------------------------------------------------------

    fun refreshNow(profile: OAuthProfile) {
        launchFlow(profile.id) {
            accounts.refreshNow(profile).fold(
                onSuccess = { report("Refreshed. New token valid for a fresh window.", ok = true) },
                onFailure = { report(it.message ?: "Refresh failed.", ok = false) },
            )
        }
    }

    fun signOut(profile: OAuthProfile) {
        launchFlow(profile.id) {
            val outcome = accounts.signOut(profile)
            report(outcome, ok = true)
        }
    }

    /**
     * The only honest way to answer "does this account's token actually work for chat" — it
     * sends one real request through the configured provider using the account credential and
     * reports whatever the endpoint says, verbatim.
     */
    fun runTest(profile: OAuthProfile) {
        launchFlow(profile.id) {
            val config = providers.config.value.copy(
                authMode = AuthMode.SUBSCRIPTION,
                oauthProfileId = profile.id,
            )
            if (config.baseUrl.isBlank() || config.model.isBlank()) {
                report(
                    "Set a base URL and model at About → AI provider first; there is no " +
                        "endpoint to test the token against.",
                    ok = false,
                )
                return@launchFlow
            }
            AiServiceFactory.create(appContext, config).testConnection().fold(
                onSuccess = { report(it, ok = true) },
                onFailure = { report(it.message ?: "The test request failed.", ok = false) },
            )
        }
    }

    // ---- plumbing ----------------------------------------------------------------------------------

    private fun launchFlow(profileId: String, block: suspend () -> Unit) {
        flowJob?.cancel()
        _state.value = _state.value.copy(
            busyProfileId = profileId,
            phase = null,
            deviceAuth = null,
            message = null,
        )
        flowJob = viewModelScope.launch {
            block()
            _state.value = _state.value.copy(busyProfileId = null, phase = null, deviceAuth = null)
        }
    }

    private fun report(message: String, ok: Boolean) {
        _state.value = _state.value.copy(message = message, messageOk = ok)
    }
}

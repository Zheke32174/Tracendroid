package dev.pleiades.masamune.ai.auth

import android.content.Context
import android.net.Uri
import android.util.Base64
import dev.pleiades.masamune.ai.sharedClient
import dev.pleiades.masamune.core.capability.Capability
import dev.pleiades.masamune.core.capability.Caller
import dev.pleiades.masamune.core.capability.CapabilityGate
import dev.pleiades.masamune.core.capability.GateDecision
import dev.pleiades.masamune.core.decline.Decline
import dev.pleiades.masamune.core.decline.DeclineRegistry
import dev.pleiades.masamune.core.halt.HaltController
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONObject

/** Endpoints actually in play for one sign-in, whether hardcoded or discovered. */
data class ResolvedEndpoints(
    val issuer: String,
    val grant: OAuthGrant,
    val deviceAuthorizationEndpoint: String?,
    val authorizationEndpoint: String?,
    val tokenEndpoint: String,
    val revocationEndpoint: String?,
    val userInfoEndpoint: String?,
    val scope: String,
)

/** RFC 8628 §3.2 device authorization response. */
data class DeviceAuthorization(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    /** Pre-filled URI, when the provider gives one. Saves the user typing the code. */
    val verificationUriComplete: String?,
    val expiresAt: Long,
    val intervalSeconds: Int,
)

/** A token endpoint success response, normalised across the two grants. */
data class TokenResponse(
    val accessToken: String,
    val refreshToken: String?,
    val expiresInSeconds: Long,
    val scope: String,
    val idToken: String?,
)

/** Steps the sign-in dialog reports, so a slow flow never looks like a hang. */
enum class SignInPhase {
    REQUESTING_CODE,
    AWAITING_APPROVAL,
    EXCHANGING,
    RESOLVING_IDENTITY,
}

/**
 * The OAuth engine: RFC 6749 authorization code, RFC 7636 PKCE, RFC 8628 device grant.
 *
 * Every outbound call goes through the module's capability gate for NETWORK on caller `user`,
 * exactly like the chat provider does. A denial is returned as a failed [Result] carrying the
 * gate's own message and is recorded in the refusal log, so a sign-in that does not happen is
 * never a button that appeared to do nothing.
 *
 * All I/O is on [Dispatchers.IO]; the device poll loop suspends with `delay`, never sleeps.
 */
class OAuthClient(private val context: Context) {

    /**
     * OIDC discovery. This is what makes "Other OpenID provider" a real feature rather than a
     * text field: the grant is chosen from what the issuer advertises, not from a guess.
     */
    suspend fun discover(issuer: String): Result<ResolvedEndpoints> = withContext(Dispatchers.IO) {
        val base = issuer.trim().trimEnd('/')
        if (base.isEmpty()) {
            return@withContext Result.failure(AuthException("Enter an issuer URL first."))
        }
        if (!base.startsWith("https://")) {
            return@withContext Result.failure(
                AuthException(
                    "Issuer must be https. \"$base\" is not, and an OAuth flow over plain http " +
                        "would hand the authorization code to anyone on the path."
                )
            )
        }
        val url = "$base/.well-known/openid-configuration"
        gateDenial("OIDC discovery for $base")?.let { return@withContext Result.failure(AuthException(it)) }
        runCatching {
            val body = getJson(url)
            val tokenEndpoint = body.optString("token_endpoint")
            if (tokenEndpoint.isBlank()) {
                throw AuthException(
                    "$url returned a document with no token_endpoint. That is not an OpenID " +
                        "Connect provider metadata document, so there is nothing to sign in to."
                )
            }
            val device = body.optString("device_authorization_endpoint").takeIf { it.isNotBlank() }
            ResolvedEndpoints(
                issuer = body.optString("issuer").ifBlank { base },
                grant = if (device != null) OAuthGrant.DEVICE_CODE else OAuthGrant.AUTHORIZATION_CODE_PKCE,
                deviceAuthorizationEndpoint = device,
                authorizationEndpoint = body.optString("authorization_endpoint").takeIf { it.isNotBlank() },
                tokenEndpoint = tokenEndpoint,
                revocationEndpoint = body.optString("revocation_endpoint").takeIf { it.isNotBlank() },
                userInfoEndpoint = body.optString("userinfo_endpoint").takeIf { it.isNotBlank() },
                scope = OAuthCatalog.custom.scope,
            )
        }
    }

    // ---- device grant (RFC 8628) --------------------------------------------------------------

    suspend fun startDeviceAuthorization(
        endpoints: ResolvedEndpoints,
        registration: OAuthClientRegistration,
    ): Result<DeviceAuthorization> = withContext(Dispatchers.IO) {
        val endpoint = endpoints.deviceAuthorizationEndpoint
            ?: return@withContext Result.failure(
                AuthException("This provider advertises no device_authorization_endpoint.")
            )
        gateDenial("device authorization request to $endpoint")
            ?.let { return@withContext Result.failure(AuthException(it)) }

        runCatching {
            val form = FormBody.Builder()
                .add("client_id", registration.clientId)
                .add("scope", endpoints.scope)
                .apply { registration.clientSecret?.let { add("client_secret", it) } }
                .build()
            val json = postForm(endpoint, form)
            val interval = json.optInt("interval", 5).coerceAtLeast(1)
            val expiresIn = json.optLong("expires_in", 600L)
            DeviceAuthorization(
                deviceCode = json.getString("device_code"),
                userCode = json.getString("user_code"),
                // RFC 8628 says verification_uri; Google still emits verification_url.
                verificationUri = json.optString("verification_uri")
                    .ifBlank { json.optString("verification_url") },
                verificationUriComplete = json.optString("verification_uri_complete")
                    .takeIf { it.isNotBlank() },
                expiresAt = System.currentTimeMillis() + expiresIn * 1000L,
                intervalSeconds = interval,
            )
        }
    }

    /**
     * Polls the token endpoint until the user approves, denies, or the code expires.
     *
     * RFC 8628 §3.5: `authorization_pending` means keep going, `slow_down` means add 5 seconds
     * to the interval permanently. Both are normal, neither is an error the user should see.
     */
    suspend fun pollDeviceToken(
        endpoints: ResolvedEndpoints,
        registration: OAuthClientRegistration,
        authorization: DeviceAuthorization,
    ): Result<TokenResponse> = withContext(Dispatchers.IO) {
        val denial = gateDenial("device token poll against ${endpoints.tokenEndpoint}")
        if (denial != null) Result.failure(AuthException(denial))
        else pollLoop(endpoints, registration, authorization)
    }

    private suspend fun pollLoop(
        endpoints: ResolvedEndpoints,
        registration: OAuthClientRegistration,
        authorization: DeviceAuthorization,
    ): Result<TokenResponse> {
        var interval = authorization.intervalSeconds
        while (true) {
            currentCoroutineContext().ensureActive()
            if (HaltController.isHalted) {
                return Result.failure(AuthException(HaltController.haltedRefusal("device code poll")))
            }
            if (System.currentTimeMillis() > authorization.expiresAt) {
                return Result.failure(
                    AuthException(
                        "The device code expired before it was approved. Start the sign-in again."
                    )
                )
            }
            delay(interval * 1000L)

            val form = FormBody.Builder()
                .add("client_id", registration.clientId)
                .add("device_code", authorization.deviceCode)
                .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                .apply { registration.clientSecret?.let { add("client_secret", it) } }
                .build()

            val outcome = runCatching { postFormRaw(endpoints.tokenEndpoint, form) }
            val pair = outcome.getOrElse { failure ->
                return Result.failure(
                    AuthException(
                        "Network error while polling ${endpoints.tokenEndpoint}: ${failure.message}",
                        failure,
                    )
                )
            }
            val code = pair.first
            val bodyText = pair.second
            val json = runCatching { JSONObject(bodyText) }.getOrNull()
            if (code in 200..299 && json != null) {
                return runCatching { json.toTokenResponse(endpoints.scope) }
            }
            when (json?.optString("error")) {
                // RFC 8628 §3.5 — both of these mean "still waiting", not "failed".
                "authorization_pending" -> Unit
                "slow_down" -> interval += 5
                "access_denied" -> return Result.failure(
                    AuthException("You declined the request in the browser, so no token was issued.")
                )
                "expired_token" -> return Result.failure(
                    AuthException("The device code expired before it was approved. Start again.")
                )
                else -> return Result.failure(
                    AuthException(describeFailure(endpoints.tokenEndpoint, code, bodyText))
                )
            }
        }
    }

    // ---- authorization code + PKCE ------------------------------------------------------------

    /** Builds the URL the browser is sent to. Pure string work — no network, so no gate. */
    fun buildAuthorizationUrl(
        endpoints: ResolvedEndpoints,
        registration: OAuthClientRegistration,
        pkce: PkcePair,
        state: String,
    ): Result<String> {
        val endpoint = endpoints.authorizationEndpoint
            ?: return Result.failure(
                AuthException("This provider advertises no authorization_endpoint.")
            )
        val url = Uri.parse(endpoint).buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", registration.clientId)
            .appendQueryParameter("redirect_uri", OAuthRedirect.URI)
            .appendQueryParameter("scope", endpoints.scope)
            .appendQueryParameter("state", state)
            .appendQueryParameter("code_challenge", pkce.challenge)
            .appendQueryParameter("code_challenge_method", PkcePair.CHALLENGE_METHOD)
            .build()
            .toString()
        return Result.success(url)
    }

    suspend fun exchangeCode(
        endpoints: ResolvedEndpoints,
        registration: OAuthClientRegistration,
        code: String,
        verifier: String,
    ): Result<TokenResponse> = withContext(Dispatchers.IO) {
        gateDenial("authorization code exchange at ${endpoints.tokenEndpoint}")
            ?.let { return@withContext Result.failure(AuthException(it)) }
        runCatching {
            val form = FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("code", code)
                .add("redirect_uri", OAuthRedirect.URI)
                .add("client_id", registration.clientId)
                .add("code_verifier", verifier)
                .apply { registration.clientSecret?.let { add("client_secret", it) } }
                .build()
            postForm(endpoints.tokenEndpoint, form).toTokenResponse(endpoints.scope)
        }
    }

    // ---- refresh / revoke ---------------------------------------------------------------------

    suspend fun refresh(
        endpoints: ResolvedEndpoints,
        registration: OAuthClientRegistration,
        refreshToken: String,
    ): Result<TokenResponse> = withContext(Dispatchers.IO) {
        gateDenial("token refresh at ${endpoints.tokenEndpoint}")
            ?.let { return@withContext Result.failure(AuthException(it)) }
        runCatching {
            val form = FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", refreshToken)
                .add("client_id", registration.clientId)
                .apply { registration.clientSecret?.let { add("client_secret", it) } }
                .build()
            // A refresh response usually omits refresh_token; the caller keeps the old one.
            postForm(endpoints.tokenEndpoint, form).toTokenResponse(endpoints.scope)
        }
    }

    /**
     * Best-effort revocation. Sign-out clears the vault whether or not this succeeds — a local
     * sign-out that fails because the provider is unreachable would be a worse outcome than a
     * token that stays valid upstream until it expires, and the screen says which happened.
     */
    suspend fun revoke(
        endpoints: ResolvedEndpoints,
        registration: OAuthClientRegistration,
        token: String,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val endpoint = endpoints.revocationEndpoint
            ?: return@withContext Result.failure(
                AuthException("This provider advertises no revocation_endpoint, so the token " +
                    "was only removed from this device.")
            )
        gateDenial("token revocation at $endpoint")
            ?.let { return@withContext Result.failure(AuthException(it)) }
        runCatching {
            val form = FormBody.Builder()
                .add("token", token)
                .add("client_id", registration.clientId)
                .apply { registration.clientSecret?.let { add("client_secret", it) } }
                .build()
            postForm(endpoint, form)
            Unit
        }
    }

    /**
     * Resolves who is signed in. Prefers the `userinfo` endpoint (a checked answer from the
     * issuer) and falls back to reading the id_token's payload.
     *
     * The fallback does NOT verify the JWT signature, and this is only ever used to render a
     * name. No authorization decision anywhere in this app reads it.
     */
    suspend fun resolveIdentity(
        endpoints: ResolvedEndpoints,
        token: TokenResponse,
    ): AccountIdentity? = withContext(Dispatchers.IO) {
        val fromUserInfo = endpoints.userInfoEndpoint
            ?.takeIf { gateDenial("userinfo lookup at $it") == null }
            ?.let { endpoint ->
                runCatching {
                    val request = Request.Builder()
                        .url(endpoint)
                        .addHeader("Authorization", "Bearer ${token.accessToken}")
                        .addHeader("Accept", "application/json")
                        .get()
                        .build()
                    sharedClient.newCall(request).execute().use { response ->
                        val text = response.body?.string().orEmpty()
                        if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                        JSONObject(text).toIdentity()
                    }
                }.getOrNull()
            }
        fromUserInfo ?: token.idToken?.let { decodeIdTokenPayload(it)?.toIdentity() }
    }

    // ---- plumbing -----------------------------------------------------------------------------

    private fun gateDenial(what: String): String? {
        val decision = CapabilityGate.get(context).check(Caller.User, Capability.NETWORK, what)
        return (decision as? GateDecision.Denied)?.message
    }

    private fun getJson(url: String): JSONObject {
        val request = Request.Builder().url(url).addHeader("Accept", "application/json").get().build()
        sharedClient.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw AuthException(describeFailure(url, response.code, text))
            return runCatching { JSONObject(text) }.getOrElse {
                throw AuthException(
                    "$url answered HTTP ${response.code} but the body is not JSON. First 200 " +
                        "characters: ${text.take(200)}"
                )
            }
        }
    }

    private fun postForm(url: String, form: FormBody): JSONObject {
        val (code, text) = postFormRaw(url, form)
        if (code !in 200..299) throw AuthException(describeFailure(url, code, text))
        return runCatching { JSONObject(text) }.getOrElse {
            throw AuthException("$url answered HTTP $code with a non-JSON body: ${text.take(200)}")
        }
    }

    private fun postFormRaw(url: String, form: FormBody): Pair<Int, String> {
        val request = Request.Builder()
            .url(url)
            .addHeader("Accept", "application/json")
            .post(form)
            .build()
        try {
            sharedClient.newCall(request).execute().use { response ->
                return response.code to response.body?.string().orEmpty()
            }
        } catch (e: IOException) {
            throw AuthException("Network error talking to $url: ${e.message}", e)
        }
    }

    /** Surfaces the provider's own OAuth error verbatim; guessing would be worse than quoting. */
    private fun describeFailure(url: String, code: Int, body: String): String {
        val json = runCatching { JSONObject(body) }.getOrNull()
        val error = json?.optString("error").orEmpty()
        val description = json?.optString("error_description").orEmpty()
        val nested = json?.optJSONObject("error")?.optString("message").orEmpty()
        val detail = listOf(error, description, nested).filter { it.isNotBlank() }.joinToString(" — ")
        val recorded = "HTTP $code from $url" + if (detail.isNotBlank()) ": $detail" else ": ${body.take(300)}"
        DeclineRegistry.record(
            Decline(
                callerTag = Caller.User.tag,
                capability = Capability.NETWORK,
                reason = Decline.Reason.UPSTREAM_ERROR,
                detail = recorded,
                operation = "oauth",
            )
        )
        return recorded
    }
}

private fun JSONObject.toTokenResponse(fallbackScope: String) = TokenResponse(
    accessToken = optString("access_token").ifBlank {
        throw AuthException("The token endpoint returned no access_token: ${toString().take(300)}")
    },
    refreshToken = optString("refresh_token").takeIf { it.isNotBlank() },
    expiresInSeconds = optLong("expires_in", 3600L),
    scope = optString("scope").ifBlank { fallbackScope },
    idToken = optString("id_token").takeIf { it.isNotBlank() },
)

private fun JSONObject.toIdentity() = AccountIdentity(
    subject = optString("sub"),
    email = optString("email").takeIf { it.isNotBlank() },
    name = optString("name").takeIf { it.isNotBlank() }
        ?: optString("preferred_username").takeIf { it.isNotBlank() },
)

/** Reads a JWT's middle segment. Display only — see [OAuthClient.resolveIdentity]. */
private fun decodeIdTokenPayload(idToken: String): JSONObject? = runCatching {
    val payload = idToken.split('.').getOrNull(1) ?: return null
    JSONObject(String(Base64.decode(payload, Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8))
}.getOrNull()

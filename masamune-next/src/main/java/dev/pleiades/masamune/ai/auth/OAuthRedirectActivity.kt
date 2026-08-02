package dev.pleiades.masamune.ai.auth

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Holds the one authorization request currently in flight through the browser.
 *
 * The `state` value is generated per request and checked here. Any app on the device can fire
 * an intent at our scheme, so a callback whose state does not match the pending request is
 * dropped rather than treated as a login — an attacker-supplied code redeemed with our
 * verifier would bind our session to their account.
 */
object PendingAuthorization {

    data class Request(val profileId: String, val state: String, val verifier: String)

    @Volatile
    private var pending: Request? = null

    private val _result = MutableStateFlow<Result<String>?>(null)

    /** Emits the authorization code, or a failure carrying the provider's own error text. */
    val result: StateFlow<Result<String>?> = _result.asStateFlow()

    fun begin(request: Request) {
        pending = request
        _result.value = null
    }

    fun current(): Request? = pending

    fun clear() {
        pending = null
        _result.value = null
    }

    /** Called from the redirect activity. Returns true if the callback was ours. */
    fun deliver(uri: Uri): Boolean {
        val request = pending ?: return false
        val state = uri.getQueryParameter("state")
        if (state == null || state != request.state) return false

        val error = uri.getQueryParameter("error")
        _result.value = if (error != null) {
            val description = uri.getQueryParameter("error_description")
            Result.failure(
                AuthException(
                    "The provider refused the authorization request: $error" +
                        if (description.isNullOrBlank()) "" else " — $description"
                )
            )
        } else {
            val code = uri.getQueryParameter("code")
            if (code.isNullOrBlank()) {
                Result.failure(
                    AuthException(
                        "The redirect carried neither a code nor an error. Full callback: $uri"
                    )
                )
            } else {
                Result.success(code)
            }
        }
        return true
    }
}

/**
 * Receives `masamune://oauth/callback`.
 *
 * Exported because a browser has to be able to reach it; it accepts nothing but its own scheme
 * and does nothing but hand the query to [PendingAuthorization], which drops anything whose
 * `state` it did not issue. There is no UI: it finishes immediately and the Account screen,
 * still on the back stack, picks the result up from the flow.
 */
class OAuthRedirectActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
        finish()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handle(intent)
        finish()
    }

    private fun handle(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != OAuthRedirect.SCHEME) return
        PendingAuthorization.deliver(data)
    }
}

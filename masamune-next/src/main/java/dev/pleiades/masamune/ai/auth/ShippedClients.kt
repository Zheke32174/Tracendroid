package dev.pleiades.masamune.ai.auth

import android.content.Context
import dev.pleiades.masamune.R

/**
 * The OAuth clients this build carries, so sign-in is one tap — the way a desktop app does it.
 *
 * ### Why this exists
 * Claude Desktop, `gcloud`, `gh`, and every CLI that "just works" all do the same thing: they carry
 * their **own** registered OAuth client, open your browser at the provider, and take the redirect
 * back. You never see a client ID because the app already has one. Masamune's Account screen was
 * asking the user to supply that client, which turned signing in into a configuration task — the
 * exact failure this project's rule names.
 *
 * Two paths remove the form, and both are now here:
 *  - [ShippedClients] — a client compiled into the build (this file). One tap, forever, for anyone
 *    who installs it.
 *  - RFC 7591 dynamic registration ([OAuthClient.registerClient]) — the app asks the issuer for a
 *    client at sign-in time. Works only where the issuer advertises a `registration_endpoint`.
 *
 * ### Why a client ID can sit in a resource file
 * A client ID for an installed app is **not a secret**. It appears in plaintext in every authorize
 * URL the browser loads, it is visible to anyone who watches the request, and on its own it reaches
 * no API. The specs say so and the providers say so; that is precisely why PKCE (RFC 7636) exists —
 * the flow is protected by a per-run code verifier, not by the client ID being hidden. No client
 * *secret* is shipped, and none is needed: these are public clients
 * (`token_endpoint_auth_method=none`).
 *
 * ### What is NOT done here, and why
 * Another app's client ID is never borrowed — not Claude Code's, not gcloud's, not gemini-cli's,
 * even though several are public and sitting in open-source repositories. Sending one of those
 * would tell the provider's authorization server that this app *is* that product. That is
 * misrepresentation, and the account that pays the price is the user's, not ours. So the slots
 * below are Masamune's own or they are empty — and an empty slot reports itself as empty.
 */
object ShippedClients {

    /**
     * The client ID this build carries for [profileId], or null when the slot is empty.
     *
     * Blank is treated as absent rather than as a client, because a blank `client_id` sent to an
     * authorization server produces `invalid_client` — a failure that reads like a broken provider
     * instead of an unfilled slot.
     */
    fun clientIdFor(context: Context, profileId: String): String? {
        val res = when (profileId) {
            OAuthCatalog.GOOGLE -> R.string.oauth_client_google
            OAuthCatalog.OPENAI -> R.string.oauth_client_openai
            else -> return null
        }
        return context.getString(res).trim().takeIf { it.isNotEmpty() }
    }

    /** True when this build can sign in to [profileId] with no input from the user at all. */
    fun has(context: Context, profileId: String): Boolean = clientIdFor(context, profileId) != null

    /**
     * The provider's own console page for creating a client, so "create one" is a tap rather than
     * an instruction to go and find a URL. Null where there is nothing useful to link to.
     */
    fun consoleUrlFor(context: Context, profileId: String): String? {
        val res = when (profileId) {
            OAuthCatalog.GOOGLE -> R.string.oauth_console_google
            OAuthCatalog.OPENAI -> R.string.oauth_console_openai
            else -> return null
        }
        return context.getString(res).trim().takeIf { it.isNotEmpty() }
    }

    /**
     * The registration to sign in with, built from the shipped client. No secret is attached —
     * these are public clients, and inventing an empty-string secret would make the token endpoint
     * reject the exchange.
     */
    fun registrationFor(context: Context, profile: OAuthProfile): OAuthClientRegistration? =
        clientIdFor(context, profile.id)?.let {
            OAuthClientRegistration(
                profileId = profile.id,
                clientId = it,
                clientSecret = null,
                issuer = profile.issuer,
                shipped = true,
            )
        }
}

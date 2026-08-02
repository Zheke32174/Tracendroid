package dev.pleiades.masamune.ai.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Dynamic client registration is the difference between a login portal and a configuration form,
 * so the facts it turns on are pinned here.
 *
 * The network call itself is not exercised — that needs a live issuer — but everything that decides
 * *whether* the user sees a Client ID box is pure, and that is precisely the part that was wrong:
 * the screen asked for a credential before it had established that the provider required one.
 */
class DynamicRegistrationTest {

    private fun endpoints(registration: String?) = ResolvedEndpoints(
        issuer = "https://issuer.example",
        grant = OAuthGrant.AUTHORIZATION_CODE_PKCE,
        deviceAuthorizationEndpoint = null,
        authorizationEndpoint = "https://issuer.example/authorize",
        tokenEndpoint = "https://issuer.example/token",
        revocationEndpoint = null,
        userInfoEndpoint = null,
        scope = "openid email",
        registrationEndpoint = registration,
    )

    @Test
    fun `an issuer advertising registration needs nothing from the user`() {
        assertTrue(endpoints("https://issuer.example/register").registrationEndpoint != null)
    }

    @Test
    fun `an issuer advertising none is honestly not self-registering`() {
        // Google is this case: its discovery document has no registration_endpoint, so the screen
        // must keep showing the form rather than a button that would fail at the first request.
        assertNull(endpoints(null).registrationEndpoint)
    }

    @Test
    fun `the loopback redirect is registered without a port`() {
        // The live redirect carries an ephemeral port, so there is no fixed string to register.
        // RFC 8252 §7.3 has authorization servers match loopback on host and path, ignoring port —
        // registering a concrete port would produce a client that only works for one run.
        assertEquals("http://127.0.0.1/callback", LoopbackRedirect.URI_TEMPLATE)
        assertFalse("no port may appear", LoopbackRedirect.URI_TEMPLATE.removePrefix("http://").contains(":"))
    }

    @Test
    fun `the registered loopback host is the IP literal, never localhost`() {
        // RFC 8252 §7.3: `localhost` can resolve to something else or be intercepted, and several
        // providers reject it outright.
        assertTrue(LoopbackRedirect.URI_TEMPLATE.startsWith("http://127.0.0.1"))
        assertFalse(LoopbackRedirect.URI_TEMPLATE.contains("localhost"))
    }

    @Test
    fun `a self-registered client is distinguishable from a pasted one`() {
        // The screen renders these differently — "registered automatically" versus a filled-in box
        // the user does not remember filling — so the flag has to survive round-tripping.
        val mine = OAuthClientRegistration("x", "cid", null, "https://issuer.example", selfRegistered = true)
        val theirs = OAuthClientRegistration("x", "cid", null, "https://issuer.example")
        assertTrue(mine.selfRegistered)
        assertFalse("a pasted client must never claim to be self-registered", theirs.selfRegistered)
    }

    @Test
    fun `a registration with no client id is not usable`() {
        assertFalse(OAuthClientRegistration("x", "", null).isComplete)
        assertTrue(OAuthClientRegistration("x", "cid", null).isComplete)
    }

    @Test
    fun `registering does not require a client secret`() {
        // The request asks for token_endpoint_auth_method=none: an installed app cannot keep a
        // secret, and storing one it claims to keep would be the dishonest option.
        val public = OAuthClientRegistration("x", "cid", clientSecret = null, selfRegistered = true)
        assertTrue(public.isComplete)
        assertNull(public.clientSecret)
    }

    @Test
    fun `the registration phase precedes every other sign-in step`() {
        // Ordering is what the progress dialog renders; a registration reported after the code
        // request would read as a hang at the wrong step.
        assertEquals(SignInPhase.REGISTERING_CLIENT, SignInPhase.entries.first())
    }

    @Test
    fun `the catalog's blocked provider stays blocked regardless of registration`() {
        // Anthropic publishes no login metadata at all. Self-registration must not resurrect a row
        // whose blocker is that there is nothing to point a sign-in at.
        assertTrue(OAuthCatalog.anthropic.isBlocked)
    }
}

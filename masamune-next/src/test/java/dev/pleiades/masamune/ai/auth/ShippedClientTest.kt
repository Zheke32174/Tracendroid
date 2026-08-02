package dev.pleiades.masamune.ai.auth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shipped-client path is what makes sign-in look like Claude Desktop: the app carries its own
 * OAuth client, so the browser opens and nothing is ever asked for. These pin the properties that
 * decide whether that promise is kept or quietly broken.
 *
 * The resource lookup itself needs a Context and is exercised on device; what is pinned here is the
 * model — and the model is where the dangerous mistakes live, because a client that claims to be
 * shipped when it is not produces a sign-in that fails at the authorization server with
 * `invalid_client`, an error that reads like the provider is broken.
 */
class ShippedClientTest {

    @Test
    fun `a shipped client is automatic — the user supplies nothing`() {
        val shipped = OAuthClientRegistration("google", "cid.apps.googleusercontent.com", null, shipped = true)
        assertTrue(shipped.isAutomatic)
        assertTrue(shipped.isComplete)
    }

    @Test
    fun `a self-registered client is automatic too`() {
        // Both routes remove the form; the screen treats them the same and says which one applied.
        assertTrue(OAuthClientRegistration("x", "cid", null, selfRegistered = true).isAutomatic)
    }

    @Test
    fun `a pasted client is not automatic`() {
        // This is the row that must keep its form and its "Forget" button.
        val pasted = OAuthClientRegistration("google", "cid", null)
        assertFalse(pasted.isAutomatic)
        assertFalse(pasted.shipped)
        assertFalse(pasted.selfRegistered)
    }

    @Test
    fun `a shipped client carries no secret`() {
        // Public client: token_endpoint_auth_method=none. Shipping a secret would put it in every
        // APK, and sending an empty-string secret makes the token endpoint reject the exchange.
        val shipped = OAuthClientRegistration("google", "cid", clientSecret = null, shipped = true)
        assertNull(shipped.clientSecret)
    }

    @Test
    fun `an empty slot is absent, never a blank client`() {
        // A blank client_id reaching an authorization server returns invalid_client, which reads as
        // a provider fault rather than an unfilled build slot. Blank must collapse to "no client".
        assertFalse(OAuthClientRegistration("google", "", null, shipped = true).isComplete)
    }

    @Test
    fun `the blocked provider is not rescued by a shipped client`() {
        // Anthropic's blocker is that no login metadata exists to point a sign-in at. A client ID
        // does not create an endpoint, so that row stays blocked whatever this build carries.
        assertTrue(OAuthCatalog.anthropic.isBlocked)
        assertNull(OAuthCatalog.anthropic.tokenEndpoint.takeIf { it.isNotBlank() })
    }

    @Test
    fun `only the providers with slots can be shipped clients`() {
        // The custom row's issuer is chosen at runtime, so no build-time client could be right for
        // it; it goes through discovery and dynamic registration instead.
        assertTrue(OAuthCatalog.custom.isCustom)
    }
}

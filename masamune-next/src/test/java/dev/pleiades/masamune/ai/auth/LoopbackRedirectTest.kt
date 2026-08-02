package dev.pleiades.masamune.ai.auth

import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The loopback receiver is the whole browser-handoff mechanism, so it is tested by actually
 * driving it over HTTP — bind it, hit the URL a real browser would be redirected to, and assert
 * both halves: the app gets the code, and the browser gets a page rather than a connection error.
 */
class LoopbackRedirectTest {

    @Test fun bindsLoopbackAndBuildsAnRfc8252RedirectUri() {
        LoopbackRedirect.open()!!.use { r ->
            assertTrue("must bind a real port", r.port > 0)
            // RFC 8252 §7.3: the IP literal, never the name `localhost`.
            assertTrue(r.redirectUri.startsWith("http://127.0.0.1:"))
            assertTrue(r.redirectUri.endsWith("/callback"))
            assertTrue("`localhost` is rejected by providers", !r.redirectUri.contains("localhost"))
        }
    }

    @Test fun capturesTheAuthorizationCodeTheBrowserRedirectsWith() {
        LoopbackRedirect.open()!!.use { r ->
            var captured: LoopbackRedirect.Redirect? = null
            val waiter = thread { captured = r.awaitRedirect(timeoutMillis = 10_000) }

            // What the provider redirects the browser to.
            val conn = URL("${r.redirectUri}?code=abc123&state=xyz").openConnection() as HttpURLConnection
            val status = conn.responseCode
            val page = conn.inputStream.bufferedReader().use(BufferedReader::readText)
            conn.disconnect()
            waiter.join(10_000)

            // The browser must land on a real page, not a connection reset.
            assertEquals(200, status)
            assertTrue("browser is told what to do next", page.contains("close this tab"))

            // And the app must have the code.
            assertNotNull("redirect not captured", captured)
            assertEquals("abc123", captured!!.code)
            assertEquals("xyz", captured!!.state)
        }
    }

    @Test fun surfacesAProviderErrorRatherThanHidingIt() {
        LoopbackRedirect.open()!!.use { r ->
            var captured: LoopbackRedirect.Redirect? = null
            val waiter = thread { captured = r.awaitRedirect(timeoutMillis = 10_000) }

            val conn = URL("${r.redirectUri}?error=access_denied&error_description=User%20declined")
                .openConnection() as HttpURLConnection
            conn.responseCode
            conn.inputStream.close()
            conn.disconnect()
            waiter.join(10_000)

            val red = captured!!
            assertEquals("access_denied", red.error)
            // Percent-escapes decoded, so the reason can be shown verbatim.
            assertEquals("User declined", red.errorDescription)
            assertNull("no code on a decline", red.code)
        }
    }

    @Test fun timesOutInsteadOfBlockingForever() {
        LoopbackRedirect.open()!!.use { r ->
            assertNull(r.awaitRedirect(timeoutMillis = 250))
        }
    }

    @Test fun closeIsIdempotentSoAnAbandonedSignInReleasesThePort() {
        val r = LoopbackRedirect.open()!!
        r.close()
        r.close()
    }
}

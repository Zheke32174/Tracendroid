package dev.pleiades.masamune.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Rewriting what a user typed is a dangerous privilege, so the boundary is pinned hard here: the
 * forms that unambiguously mean "run this as root" are rewritten, and everything else is left
 * exactly as written. A false positive would run a different command than the one on screen.
 */
class SuRewriteTest {

    private fun cmd(line: String) = (SuRewrite.parse(line) as? SuRewrite.Request.Command)?.commandLine

    // ---- recognised ----------------------------------------------------------------------------

    @Test
    fun `su dash c takes the quoted command whole`() {
        assertEquals("id -u", cmd("su -c 'id -u'"))
        assertEquals("id -u", cmd("su -c \"id -u\""))
    }

    @Test
    fun `su dash c without quotes rejoins the remainder`() {
        // The user's intent is unambiguous even unquoted; dropping everything after the first word
        // would silently run a shorter command than they wrote.
        assertEquals("chown 0:0 /root/x", cmd("su -c chown 0:0 /root/x"))
    }

    @Test
    fun `naming root as the target still resolves to the container root`() {
        assertEquals("id", cmd("su root -c 'id'"))
        assertEquals("id", cmd("su 0 -c id"))
    }

    @Test
    fun `sudo with a bare command is the command`() {
        assertEquals("apk add curl", cmd("sudo apk add curl"))
    }

    @Test
    fun `sudo dash u root is understood`() {
        assertEquals("whoami", cmd("sudo -u root whoami"))
    }

    @Test
    fun `bare su asks for a session, which is reported not faked`() {
        assertEquals(SuRewrite.Request.Interactive, SuRewrite.parse("su"))
        assertEquals(SuRewrite.Request.Interactive, SuRewrite.parse("sudo"))
        assertEquals(SuRewrite.Request.Interactive, SuRewrite.parse("su root"))
    }

    @Test
    fun `su dash c with nothing after it is a session request, not an empty command`() {
        assertEquals(SuRewrite.Request.Interactive, SuRewrite.parse("su -c"))
    }

    // ---- deliberately NOT recognised -------------------------------------------------------------

    @Test
    fun `an ordinary command is untouched`() {
        assertNull(SuRewrite.parse("ls -la"))
        assertNull(SuRewrite.parse("echo su"))
    }

    @Test
    fun `a word merely containing su is not a root request`() {
        // The exact bug class this guards: `sudoku`, `subl`, `sum`, and a path like /usr/bin/super.
        assertNull(SuRewrite.parse("sudoku"))
        assertNull(SuRewrite.parse("sum file"))
        assertNull(SuRewrite.parse("/system/bin/su -c id"))
    }

    @Test
    fun `sudo as another user is not ours to answer`() {
        // The container can only be root. Claiming `-u nobody` would be a rewrite into a different
        // identity than the one asked for.
        assertNull(SuRewrite.parse("sudo -u nobody id"))
    }

    @Test
    fun `an unmodelled flag leaves the line alone rather than guessing`() {
        assertNull(SuRewrite.parse("su --preserve-environment -c id"))
    }

    @Test
    fun `an unterminated quote is not half-parsed`() {
        assertNull(SuRewrite.parse("su -c 'id"))
    }

    // ---- tokenizer -------------------------------------------------------------------------------

    @Test
    fun `tokenizer keeps quoted whitespace together`() {
        assertEquals(listOf("su", "-c", "echo a  b"), SuRewrite.tokenize("su -c 'echo a  b'"))
    }

    @Test
    fun `tokenizer preserves an intentionally empty argument`() {
        assertEquals(listOf("x", ""), SuRewrite.tokenize("x ''"))
    }

    @Test
    fun `tokenizer honours a backslash escape`() {
        assertEquals(listOf("a b"), SuRewrite.tokenize("a\\ b"))
    }
}

package dev.pleiades.masamune.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Environments panel is honest only if its parsers are — an absent tool must parse to "not
 * detected", never to a fabricated version. These lock the probe-output contracts so a future
 * refactor of the shell scripts cannot silently start inventing status.
 */
class EnvironmentProbesTest {

    // ---- checklist ------------------------------------------------------------------------------

    @Test
    fun `checklist keeps declared order and marks absent tools not detected`() {
        val stdout = buildString {
            append("node\tv20.11.0\n")
            append("npm\t10.2.4\n")
            append("git\tgit version 2.43.0\n")
            append("python\t${EnvironmentProbes.ABSENT}\n")
            // pip line omitted entirely — must still parse as absent
            append("codex\t${EnvironmentProbes.ABSENT}\n")
        }
        val result = EnvironmentProbes.parseChecklist(stdout)

        assertEquals(EnvironmentProbes.CHECKLIST_TOOLS.map { it.key }, result.map { it.key })

        val node = result.first { it.key == "node" }
        assertTrue(node.detected)
        assertEquals("v20.11.0", node.version)

        val python = result.first { it.key == "python" }
        assertFalse(python.detected)
        assertNull(python.version)

        val pip = result.first { it.key == "pip" }
        assertFalse(pip.detected)

        val sshd = result.first { it.key == "sshd" }
        assertFalse(sshd.detected)
    }

    // ---- health ---------------------------------------------------------------------------------

    @Test
    fun `health parses pass fail and detail, and missing probe fails`() {
        val stdout = buildString {
            append("proot\tPASS\tproot 5.4.0\n")
            append("system_shell\tPASS\t/system/bin/sh\n")
            append("storage\tFAIL\trun termux-setup-storage\n")
            append("distro\tPASS\tTermux (no /etc/os-release)\n")
            append("network\tFAIL\tno egress to 1.1.1.1\n")
            // abnormalities omitted -> must parse as fail with a reason
        }
        val result = EnvironmentProbes.parseHealth(stdout)
        assertEquals(EnvironmentProbes.HEALTH_PROBES.map { it.key }, result.map { it.key })

        assertTrue(result.first { it.key == "proot" }.passed)
        assertEquals("proot 5.4.0", result.first { it.key == "proot" }.detail)
        assertFalse(result.first { it.key == "storage" }.passed)

        val abnormalities = result.first { it.key == "abnormalities" }
        assertFalse(abnormalities.passed)
    }

    // ---- packages -------------------------------------------------------------------------------

    @Test
    fun `installed packages skip the listing banner and parse name plus version`() {
        val stdout = """
            Listing... Done
            git/stable,now 2.43.0 aarch64 [installed]
            nodejs/stable,now 20.11.0 aarch64 [installed,automatic]
            openssh/stable,now 9.6p1 aarch64 [installed]
        """.trimIndent()
        val pkgs = EnvironmentProbes.parseInstalledPackages(stdout)
        assertEquals(listOf("git", "nodejs", "openssh"), pkgs.map { it.name })
        assertEquals("2.43.0", pkgs.first { it.name == "git" }.version)
    }

    // ---- proot-distro ---------------------------------------------------------------------------

    @Test
    fun `distro list separates installed from available`() {
        val stdout = """
            Alias: alpine
            Status: installed
            Alias: ubuntu
            Alias: debian
            Status: installed
        """.trimIndent()
        val distros = EnvironmentProbes.parseDistroList(stdout)
        assertEquals(listOf("alpine", "ubuntu", "debian"), distros.map { it.alias })
        assertTrue(distros.first { it.alias == "alpine" }.installed)
        assertFalse(distros.first { it.alias == "ubuntu" }.installed)
        assertTrue(distros.first { it.alias == "debian" }.installed)
    }

    // ---- boot tasks -----------------------------------------------------------------------------

    @Test
    fun `boot tasks parse executable bit and absent directory returns null`() {
        assertNull(EnvironmentProbes.parseBootTasks(EnvironmentProbes.ABSENT))

        val stdout = "X\tstart-sshd.sh\n-\tnotes.txt\n"
        val tasks = EnvironmentProbes.parseBootTasks(stdout)
        assertEquals(2, tasks?.size)
        assertTrue(tasks!!.first { it.name == "start-sshd.sh" }.enabled)
        assertFalse(tasks.first { it.name == "notes.txt" }.enabled)
    }

    @Test
    fun `single quote escaping neutralises embedded quotes`() {
        assertEquals("'a'\\''b'", EnvironmentProbes.sq("a'b"))
    }
}

package dev.pleiades.masamune.rom

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The honesty of the whole ROM feature reduces to two claims these tests lock:
 *  1. On a clean sideloaded build — no AVF permission, no readable `/dev/kvm`, no QEMU payload —
 *     the chain reports ABSENT, and the Launch predicate the screen uses (`!isAbsent`) is false.
 *  2. Every closed path carries a non-empty reason that names the thing it lacks; no probe
 *     swallows why.
 *
 * The backends are constructed with their probing seams faked, so the chain is decidable here
 * without an Android runtime, a real `/dev/kvm`, or a real prefix.
 */
class RomChainTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** The state this build actually ships in: all three paths closed. */
    private fun cleanBuildChain(): RomChain = RomChain(
        listOf(
            AvfBackend(virtualizationServicePresent = { false }, holdsManageVirtualMachine = { false }),
            KvmBackend(kvmNode = File(tmp.root, "no-such-kvm-node")),
            TcgBackend(prefixBinDir = tmp.newFolder("empty-prefix-bin")),
        ),
    )

    @Test
    fun `clean build chain reports ABSENT with no live backend`() {
        val result = cleanBuildChain().probe()

        assertTrue("clean build must be ABSENT", result.isAbsent)
        assertNull("no backend is live on a clean build", result.live)
        assertNull(result.liveLabel)
        assertFalse("nothing runs, so nothing runs at native speed", result.nativeSpeed)
    }

    @Test
    fun `Launch predicate is disabled exactly when the chain is ABSENT`() {
        // The screen computes launchEnabled = !chain.isAbsent; this pins that rule at its seam.
        val absent = cleanBuildChain().probe()
        assertFalse("Launch must be disabled when ABSENT", !absent.isAbsent)

        val available = RomChain(
            listOf(kvmAvailable(), tcgUnavailable()),
        ).probe()
        assertTrue("Launch is enabled only when a backend is live", !available.isAbsent)
    }

    @Test
    fun `every closed path carries a non-empty reason that names its missing thing`() {
        val result = cleanBuildChain().probe()

        assertEquals("all three paths are closed on a clean build", 3, result.unavailable.size)
        result.unavailable.forEach { path ->
            assertTrue("reason for ${path.label} must be non-empty", path.reason.isNotBlank())
        }

        val byLabelReason = result.probes.associate { p ->
            p.backend.label to (p.availability as Availability.Unavailable).reason
        }
        val avf = byLabelReason.entries.first { it.key.startsWith("AVF") }.value
        val kvm = byLabelReason.entries.first { it.key.startsWith("KVM") }.value
        val tcg = byLabelReason.entries.first { it.key.startsWith("QEMU") }.value

        assertTrue("AVF reason must name the signature-permission wall", avf.contains("signature"))
        assertTrue("AVF reason must name platform-signing", avf.contains("platform-signed"))
        assertTrue("KVM reason must name /dev/kvm", kvm.contains("/dev/kvm"))
        assertTrue("TCG reason must name QEMU", tcg.contains("QEMU"))
    }

    @Test
    fun `each backend reason equals its documented constant`() {
        assertEquals(
            AvfBackend.REASON,
            (AvfBackend({ false }, { false }).probe() as Availability.Unavailable).reason,
        )
        assertEquals(
            KvmBackend.REASON,
            (KvmBackend(File(tmp.root, "absent")).probe() as Availability.Unavailable).reason,
        )
        assertEquals(
            TcgBackend.REASON,
            (TcgBackend(tmp.newFolder("empty-bin-2")).probe() as Availability.Unavailable).reason,
        )
    }

    @Test
    fun `AVF is unavailable when the service exists but the permission is not held`() {
        // The sideloaded reality: virtualization service present, MANAGE_VIRTUAL_MACHINE denied.
        val backend = AvfBackend(virtualizationServicePresent = { true }, holdsManageVirtualMachine = { false })
        assertTrue(backend.probe() is Availability.Unavailable)
    }

    @Test
    fun `chain takes the best available in order and names it`() {
        // AVF available must win over an also-available KVM: order is the tie-break, not speed re-rank.
        val bothNative = RomChain(
            listOf(avfAvailable(), kvmAvailable(), tcgUnavailable()),
        ).probe()
        assertNotNull(bothNative.live)
        assertTrue(bothNative.liveLabel!!.startsWith("AVF"))
        assertTrue("AVF is native speed", bothNative.nativeSpeed)
        assertFalse(bothNative.isAbsent)

        // With AVF and KVM closed, TCG is the live floor — available, but not native speed.
        val tcgFloor = RomChain(
            listOf(
                AvfBackend({ false }, { false }),
                KvmBackend(File(tmp.root, "absent-kvm")),
                tcgAvailable(),
            ),
        ).probe()
        assertNotNull(tcgFloor.live)
        assertTrue(tcgFloor.liveLabel!!.startsWith("QEMU"))
        assertFalse("TCG is emulation, not native speed", tcgFloor.nativeSpeed)
    }

    // --- fakes -----------------------------------------------------------------------------------

    private fun avfAvailable() =
        AvfBackend(virtualizationServicePresent = { true }, holdsManageVirtualMachine = { true })

    private fun kvmAvailable(): KvmBackend {
        val node = tmp.newFile("kvm-node-${System.nanoTime()}")
        check(node.exists() && node.canRead())
        return KvmBackend(kvmNode = node)
    }

    private fun tcgAvailable(): TcgBackend {
        val bin = tmp.newFolder("prefix-bin-${System.nanoTime()}")
        val qemu = File(bin, "qemu-system-aarch64")
        qemu.writeText("#!/bin/sh\n")
        check(qemu.setExecutable(true)) { "could not mark fake qemu executable" }
        return TcgBackend(prefixBinDir = bin)
    }

    private fun tcgUnavailable(): TcgBackend =
        TcgBackend(prefixBinDir = tmp.newFolder("prefix-bin-empty-${System.nanoTime()}"))
}

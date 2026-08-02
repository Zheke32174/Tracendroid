package dev.pleiades.masamune.rom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The image model's one load-bearing behaviour is honesty about speed: a guest whose arch differs
 * from the host is cross-emulated and slow, and it must be *labelled* so, not discovered by the
 * user (docs/ROM-LAUNCH.md). These lock that, plus the ABI mapping the host-arch detection uses.
 */
class RomImageTest {

    private fun image(arch: RomArch) =
        RomImage(id = 1, name = "guest.img", path = "/ext/roms/guest.img", arch = arch, sizeBytes = 4L * 1024 * 1024 * 1024)

    @Test
    fun `same-arch guest is not cross-emulated`() {
        assertFalse(image(RomArch.AARCH64).isCrossEmulated(RomArch.AARCH64))
        assertFalse(image(RomArch.X86_64).isCrossEmulated(RomArch.X86_64))
    }

    @Test
    fun `x86_64 guest on an aarch64 host is cross-emulated`() {
        assertTrue(image(RomArch.X86_64).isCrossEmulated(RomArch.AARCH64))
    }

    @Test
    fun `speed note names both arches and calls out cross-emulation as slow`() {
        val cross = image(RomArch.X86_64).speedNote(RomArch.AARCH64)
        assertTrue(cross.contains("x86_64"))
        assertTrue(cross.contains("aarch64"))
        assertTrue("cross-emulation must be called out as slow", cross.contains("slow"))

        val same = image(RomArch.AARCH64).speedNote(RomArch.AARCH64)
        assertTrue(same.contains("fast TCG case"))
    }

    @Test
    fun `ABI strings map to the modelled arches, unknowns to null`() {
        assertEquals(RomArch.AARCH64, RomArch.fromAbi("arm64-v8a"))
        assertEquals(RomArch.AARCH64, RomArch.fromAbi("aarch64"))
        assertEquals(RomArch.X86_64, RomArch.fromAbi("x86_64"))
        assertNull(RomArch.fromAbi("armeabi-v7a"))
        assertNull(RomArch.fromAbi("riscv64"))
    }

    @Test
    fun `each arch declares the qemu-system binary a launch would need`() {
        assertEquals("qemu-system-aarch64", RomArch.AARCH64.qemuSystemBinary)
        assertEquals("qemu-system-x86_64", RomArch.X86_64.qemuSystemBinary)
    }
}

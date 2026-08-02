package dev.pleiades.masamune.rom

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The TCG probe used to search only `/data/local/tmp/masamune/usr/bin`, a directory the app can
 * neither write nor execute from under W^X. That is a probe that can only ever answer "absent" —
 * including when a perfectly good QEMU is on the device — which is exactly the shape of dishonesty
 * the ROM surface exists to avoid. These pin the corrected search so it cannot regress silently.
 */
class TcgPayloadSearchTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun executable(dir: File, name: String): File =
        File(dir, name).apply {
            writeText("#!/bin/sh\n")
            check(setExecutable(true)) { "could not mark $name executable" }
        }

    @Test
    fun `the APK-shipped payload name is recognised`() {
        // This is the ONLY name the installer will extract and mark executable, so a probe that
        // does not know it cannot ever see a shipped QEMU.
        val libs = tmp.newFolder("nativeLibraryDir")
        executable(libs, "libmasamuneqemuaarch64.so")
        assertTrue(TcgBackend(listOf(libs)).probe() is Availability.Available)
    }

    @Test
    fun `the ordinary build name is still recognised`() {
        val bin = tmp.newFolder("prefix-bin")
        executable(bin, "qemu-system-aarch64")
        assertTrue(TcgBackend(listOf(bin)).probe() is Availability.Available)
    }

    @Test
    fun `the native library directory is searched before the legacy prefix`() {
        // Order matters: the nativeLibraryDir copy is the one that can actually be executed, so it
        // must win over a same-named file in a directory the app may not be able to exec from.
        val libs = tmp.newFolder("libs")
        val prefix = tmp.newFolder("prefix")
        val shipped = executable(libs, "libmasamuneqemuaarch64.so")
        executable(prefix, "qemu-system-aarch64")
        assertEquals(shipped, TcgBackend(listOf(libs, prefix)).findQemuBinary())
    }

    @Test
    fun `the legacy prefix still answers when the APK ships nothing`() {
        // A rooted or ADB-provisioned device really can host a binary there; closed-by-default is
        // not closed-always, so the fallback stays reachable.
        val libs = tmp.newFolder("libs-empty")
        val prefix = tmp.newFolder("prefix-with-qemu")
        val fallback = executable(prefix, "qemu-system-x86_64")
        assertEquals(fallback, TcgBackend(listOf(libs, prefix)).findQemuBinary())
    }

    @Test
    fun `a non-executable file is not a usable emulator`() {
        // Present-but-not-executable is the exact state a broken extractNativeLibs build leaves
        // behind. Reporting AVAILABLE there would promise a boot that fails at exec time.
        val libs = tmp.newFolder("libs-noexec")
        File(libs, "libmasamuneqemuaarch64.so").writeText("not executable")
        assertTrue(TcgBackend(listOf(libs)).probe() is Availability.Unavailable)
    }

    @Test
    fun `an unrelated library is not mistaken for QEMU`() {
        // nativeLibraryDir is full of ordinary .so files; matching lib*.so alone would report a
        // bootable ROM on every device.
        val libs = tmp.newFolder("libs-other")
        executable(libs, "libandroidx.graphics.path.so")
        executable(libs, "libmasamunebusybox.so")
        executable(libs, "libmasamuneproot.so")
        assertNull(TcgBackend(listOf(libs)).findQemuBinary())
    }

    @Test
    fun `a missing directory is absent, not an error`() {
        val gone = File(tmp.root, "does-not-exist")
        assertTrue(TcgBackend(listOf(gone)).probe() is Availability.Unavailable)
    }

    @Test
    fun `the unavailable reason names the payload, not a package manager`() {
        // The old wording sent the user to "the subsystem package manager", which cannot install
        // an executable the app is able to run. The reason has to describe the real gap.
        val reason = (TcgBackend(listOf(tmp.newFolder("empty"))).probe() as Availability.Unavailable).reason
        assertTrue("names the shipped payload", reason.contains("payload"))
        assertTrue("names the ABI constraint", reason.contains("ABI"))
    }

    @Test
    fun `name matching accepts both forms and rejects near misses`() {
        assertTrue(TcgBackend.isQemuName("qemu-system-aarch64"))
        assertTrue(TcgBackend.isQemuName("libmasamuneqemuaarch64.so"))
        assertTrue("a payload name without .so is not an extracted library", !TcgBackend.isQemuName("libmasamuneqemuaarch64"))
        assertTrue("qemu-img is not a full-system emulator", !TcgBackend.isQemuName("qemu-img"))
        assertTrue(!TcgBackend.isQemuName("libmasamunebusybox.so"))
    }
}

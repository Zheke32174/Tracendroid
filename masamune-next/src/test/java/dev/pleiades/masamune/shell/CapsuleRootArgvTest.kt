package dev.pleiades.masamune.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The proot invocation is the whole of the nested root, and every flag in it is load-bearing. On a
 * device a wrong one fails as "the container is broken" — an error that reads like a payload problem
 * and sends the next session hunting in the wrong place. So the vector is built by a pure function
 * and pinned here, where a mistake shows up as a failing assertion instead.
 */
class CapsuleRootArgvTest {

    private fun argv(
        shared: String? = "/storage/emulated/0",
        command: String = "id",
    ) = CapsuleRoot.prootArgv(
        prootPath = "/lib/libmasamuneproot.so",
        rootfsPath = "/data/capsule/rootfs",
        sharedPath = shared,
        commandLine = command,
        kernelBinds = CapsuleRoot.KERNEL_PATHS,
    )

    @Test
    fun `fake_id0 is present — it is the root itself`() {
        // Without -0 every uid query answers the app's real uid and the container is an ordinary
        // unprivileged shell: the one flag whose absence silently removes the feature.
        assertTrue("-0" in argv())
    }

    @Test
    fun `rootfs is passed with -r and is the container's slash`() {
        val a = argv()
        assertEquals("/data/capsule/rootfs", a[a.indexOf("-r") + 1])
    }

    @Test
    fun `proot is argv zero`() {
        assertEquals("/lib/libmasamuneproot.so", argv().first())
    }

    @Test
    fun `kernel interfaces are bound`() {
        val binds = bindsOf(argv())
        assertTrue("/proc" in binds)
        assertTrue("/sys" in binds)
        assertTrue("/dev" in binds)
    }

    @Test
    fun `shared storage is bound onto sdcard`() {
        assertTrue("/storage/emulated/0:/sdcard" in bindsOf(argv()))
    }

    @Test
    fun `unmounted storage simply drops the share rather than binding nothing`() {
        // A null share must not produce a "-b" with a missing operand, which proot would read as
        // the next flag and fail on in a way that has nothing to do with storage.
        val a = argv(shared = null)
        assertFalse(a.any { it.endsWith(":/sdcard") })
        assertEquals(a.count { it == "-b" }, bindsOf(a).size)
    }

    @Test
    fun `the command is one argument, so quoting cannot split it`() {
        val a = argv(command = "echo 'a b' && id -u")
        assertEquals("echo 'a b' && id -u", a.last())
        assertEquals("-c", a[a.size - 2])
        assertEquals("/bin/sh", a[a.size - 3])
    }

    @Test
    fun `working directory exists inside the container`() {
        val a = argv()
        // /root is created by ensureRootfs; a -w onto a path the rootfs lacks aborts the run.
        assertEquals("/root", a[a.indexOf("-w") + 1])
    }

    @Test
    fun `a missing kernel path is omitted rather than bound blindly`() {
        val a = CapsuleRoot.prootArgv(
            prootPath = "p", rootfsPath = "r", sharedPath = null, commandLine = "id",
            kernelBinds = listOf("/proc"),
        )
        assertEquals(listOf("/proc"), bindsOf(a))
    }

    private fun bindsOf(argv: List<String>): List<String> =
        argv.withIndex().filter { it.value == "-b" }.map { argv[it.index + 1] }
}

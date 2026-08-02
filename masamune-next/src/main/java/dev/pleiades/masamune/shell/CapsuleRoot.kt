package dev.pleiades.masamune.shell

import android.content.Context
import android.os.Environment
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * `su` as a nested object: root **inside a container**, with storage shared into it.
 *
 * The capsule ([CapsuleShellBackend]) gives Masamune its own userland running as the app's uid.
 * This gives that userland a *root* — not by asking the device for one, but by owning the
 * namespace the shell lives in. `proot`'s `fake_id0` extension answers every uid/gid question
 * with 0 and lets `chown`, `chmod` and package-manager-style operations succeed, so a real distro
 * behaves normally with no `su` binary, no ADB rung and no unlocked bootloader.
 *
 * ### What this root IS and IS NOT — stated here so no surface can imply otherwise
 * **Is:** complete authority over the container's filesystem. `whoami` says root, `chown` works,
 * `apt`/`pacman`-style installs into the prefix work, a rootfs can be populated and used.
 *
 * **Is not:** kernel privilege. proot is `ptrace` and path translation in userspace; the kernel
 * still sees Masamune's app uid. It cannot touch `system_server`, other apps' data, or any
 * `/proc` or device node the app uid may not read. That is why this does **not** satisfy
 * Yojimbo's "start the server as root" rung, which genuinely needs uid 0 to reach into the
 * platform. Two different powers that are easy to conflate; [FakeRootScope] names them apart.
 *
 * ### Why the binaries live where they do
 * Executables must come from the installer-owned native library directory (Android's W^X rule
 * blocks executing anything the app can write). The rootfs, being data the container writes into,
 * lives in app storage — and its `/bin` entries are **symlinks back to the one busybox** in that
 * native directory. So the kernel only ever execs a file it considers executable, while the
 * container still sees an ordinary `/bin/sh`.
 */
class CapsuleRoot(context: Context) {

    private val appContext = context.applicationContext
    private val capsule = CapsuleShellBackend(appContext)

    /** What a caller is asking for, so a surface cannot quietly promise the stronger one. */
    enum class FakeRootScope {
        /** Root over the container's own filesystem. Real, needs no privilege, works today. */
        CONTAINER_ONLY,
    }

    /** The container's root filesystem. Data — never executed from here; see the class KDoc. */
    val rootfs: File get() = File(capsule.prefixDir, "rootfs")

    sealed class Availability {
        data object Ready : Availability()
        data class Unavailable(val reason: String) : Availability()
    }

    fun availability(): Availability {
        val proot = capsule.proot
            ?: return Availability.Unavailable(
                "This build ships no proot payload for this device's ABI, so there is no container " +
                    "to be root inside. Only arm64-v8a is built today."
            )
        if (!proot.canExecute()) {
            return Availability.Unavailable("${proot.path} is present but not executable.")
        }
        if (capsule.availability() !is CapsuleShellBackend.Availability.Ready) {
            return Availability.Unavailable(
                "The shell payload is missing, so the container would have no userland to run."
            )
        }
        return Availability.Ready
    }

    /**
     * Create the container's filesystem skeleton and populate `/bin` with symlinks to busybox.
     *
     * Idempotent: re-running only adds what is absent, so this is safe on every launch. The
     * directories `proot` binds over (`/proc`, `/sys`, `/dev`) must EXIST as mount points even
     * though they are empty — a bind onto a missing directory fails, which is the same lesson the
     * openwrt stratum work recorded.
     */
    suspend fun ensureRootfs(): File = withContext(Dispatchers.IO) {
        val root = rootfs
        listOf("bin", "etc", "tmp", "root", "proc", "sys", "dev", "home", "usr/bin")
            .forEach { File(root, it).mkdirs() }

        val bb = File(appContext.applicationInfo.nativeLibraryDir, BUSYBOX)
        if (bb.exists()) {
            val bin = File(root, "bin")
            // `sh` first: without it the container has no shell at all and every run fails with a
            // confusing exec error rather than an honest one. The rest comes from busybox itself,
            // so the container gets exactly the applets this payload was built with — a hardcoded
            // list would drift from the binary and leave real applets unreachable by bare name.
            for (applet in listOf("sh", "ash") + appletNames(bb, root)) {
                val link = File(bin, applet)
                if (!link.exists()) {
                    runCatching { android.system.Os.symlink(bb.path, link.path) }
                }
            }
        }
        // A minimal passwd/group so tools that look the user up see root rather than failing.
        File(root, "etc/passwd").takeIf { !it.exists() }?.writeText("root:x:0:0:root:/root:/bin/sh\n")
        File(root, "etc/group").takeIf { !it.exists() }?.writeText("root:x:0:\n")
        File(root, "etc/hostname").takeIf { !it.exists() }?.writeText("masamune\n")
        root
    }

    /**
     * Run [commandLine] as **root inside the container**.
     *
     * The proot invocation, argument by argument, because each one is load-bearing:
     *  - `-r <rootfs>`  the container's `/`.
     *  - `-0`           fake_id0: every uid/gid query answers 0. This IS the nested `su`.
     *  - `-b <shared>`  storage shared in, so the container and the app see the same files.
     *  - `-b /proc -b /sys -b /dev`  the kernel interfaces a shell expects; without them even
     *    `ps` and `/dev/null` break.
     *  - `-w /root`     a working directory that exists inside the container.
     *
     * Failure is honest: with no proot payload this returns a named [TermuxShellBackend.Outcome.DispatchFailed]
     * rather than silently running the command *outside* the container, which would look like
     * success while giving none of the isolation or the root that was asked for.
     */
    suspend fun runAsRoot(
        commandLine: String,
        scope: FakeRootScope = FakeRootScope.CONTAINER_ONLY,
        timeoutMillis: Long = 120_000L,
    ): TermuxShellBackend.Outcome {
        when (val a = availability()) {
            is Availability.Unavailable -> return TermuxShellBackend.Outcome.DispatchFailed(a.reason)
            Availability.Ready -> Unit
        }
        val proot = capsule.proot ?: return TermuxShellBackend.Outcome.DispatchFailed("proot vanished.")
        val root = ensureRootfs()
        val argv = prootArgv(proot.path, root.path, sharedStorage()?.path, commandLine)
        return capsule.runArgv(argv, root, timeoutMillis)
    }

    /** The external storage directory to share in, or null when it is not currently mounted. */
    private fun sharedStorage(): File? = runCatching {
        Environment.getExternalStorageDirectory().takeIf { it.isDirectory }
    }.getOrNull()

    /**
     * The applets this busybox really has, from `busybox --list`.
     *
     * Falls back to [CORE_APPLETS] if that call fails for any reason — a container with a core
     * `/bin` still works, whereas one with an empty `/bin` looks broken. The fallback is a floor,
     * never the normal path.
     */
    private suspend fun appletNames(busybox: File, cwd: File): List<String> {
        val listed = runCatching { capsule.runArgv(listOf(busybox.path, "--list"), cwd, 10_000L) }
            .getOrNull() as? TermuxShellBackend.Outcome.Completed
        val names = listed?.stdout.orEmpty()
            .lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        return names.ifEmpty { CORE_APPLETS }
    }

    companion object {
        private const val BUSYBOX = "libmasamunebusybox.so"

        /** Floor for `/bin` when `busybox --list` cannot be read. Enough for a usable shell. */
        private val CORE_APPLETS = listOf(
            "ls", "cat", "cp", "mv", "rm", "mkdir", "rmdir", "chmod", "chown", "ln", "echo",
            "printf", "grep", "sed", "awk", "find", "xargs", "head", "tail", "sort", "uniq", "wc",
            "cut", "tr", "tar", "gzip", "gunzip", "env", "id", "uname", "ps", "kill", "sleep",
            "date", "test", "true", "false", "which", "basename", "dirname", "readlink",
            "realpath", "stat", "touch", "du", "seq", "expr", "wget", "mktemp", "timeout",
        )

        /**
         * Build proot's argv. Pure string work, so the whole invocation is unit-testable without a
         * device — which matters because a wrong flag here fails at run time in ways that read
         * like "the container is broken" rather than "the arguments were wrong".
         */
        fun prootArgv(
            prootPath: String,
            rootfsPath: String,
            sharedPath: String?,
            commandLine: String,
            kernelBinds: List<String> = KERNEL_PATHS.filter { File(it).exists() },
        ): List<String> = buildList {
            add(prootPath)
            add("-r"); add(rootfsPath)
            add("-0")                       // fake_id0 — the nested root itself
            // Kernel interfaces the shell expects. Bound only if present on the host: proot fails
            // the whole run on a bind whose source is missing, so a probe beats an assumption.
            for (p in kernelBinds) { add("-b"); add(p) }
            // Shared storage: same bytes visible to the app and to the container.
            sharedPath?.let { add("-b"); add("$it:/sdcard") }
            add("-w"); add("/root")
            add("/bin/sh"); add("-c"); add(commandLine)
        }

        /** The host paths a container shell expects to see; bound through rather than recreated. */
        val KERNEL_PATHS = listOf("/proc", "/sys", "/dev")
    }
}

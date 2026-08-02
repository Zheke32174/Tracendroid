package dev.pleiades.masamune.rom

import android.content.Context
import android.content.pm.PackageManager
import java.io.File

/**
 * How a second kernel could actually be booted on this device — and, on a stock sideloaded
 * build, why none of the ways are open. See docs/ROM-LAUNCH.md.
 *
 * Booting a real OS means running a *second kernel*, and there are exactly three ways to do that
 * on an Android phone. Two are gated by a privilege Masamune cannot grant itself; one is gated by
 * a payload that is absent from this build. Each backend below probes for its own path and, when
 * that path is closed, reports [Availability.Unavailable] carrying the *exact sentence* naming
 * what is missing — the same honest-gating rule the rest of the app follows (a control that is
 * dishonest about being dead is worse than a disabled one that explains itself).
 *
 * A native-speed ROM without root or platform-signing is **not possible** (docs/ROM-LAUNCH.md
 * "Honesty ledger"). Nothing here fabricates one, and no code below pretends a boot happened.
 */
sealed interface RomBackend {
    /** Human name of the path, shown verbatim when this backend is the one in use or the one missing. */
    val label: String

    /**
     * Whether this backend runs the guest at the host's native speed. True only for the
     * hardware-assisted paths (AVF, KVM); pure userspace emulation (TCG) is 5–20× slower and
     * says so rather than letting the user discover it.
     */
    val nativeSpeed: Boolean

    /** Probe for this path. Pure over its injected seams, so it is unit-testable off-device. */
    fun probe(): Availability
}

/**
 * The result of a single [RomBackend.probe].
 *
 * [Unavailable] always carries a [reason] that is safe to render verbatim and names the missing
 * thing — the probe never swallows why. [Unknown] is reserved for a probe that genuinely cannot
 * decide (e.g. an I/O error reading the node), distinct from a decided "not present".
 */
sealed interface Availability {
    data object Available : Availability
    data class Unavailable(val reason: String) : Availability
    data object Unknown : Availability
}

/**
 * **AVF** — Android Virtualization Framework. Native speed, and unreachable for a sideloaded APK.
 *
 * AVF boots a protected VM through the platform's `VirtualizationService`, but starting one needs
 * `android.permission.MANAGE_VIRTUAL_MACHINE`, which is **signature**-level: Android's own Linux
 * Terminal holds it by being platform-signed, and a sideloaded app cannot. So this probe reports
 * AVAILABLE only when *both* the virtualization service is present *and* this app actually holds
 * the permission — which, on a stock locked device with a sideloaded build, it does not.
 *
 * This is the intended upgrade path, not a dead branch: if Masamune is platform-signed or installed
 * as a system app on a ROM the user controls, the permission becomes grantable and this backend
 * lights up with no code change — the chain already probes for it (docs/ROM-LAUNCH.md "Where AVF
 * becomes reachable"). That is why AVF is designed in now rather than dismissed.
 *
 * Both facts are injected as seams so the probe is decidable in a unit test without an Android
 * runtime; [real] wires them to the platform.
 */
class AvfBackend(
    private val virtualizationServicePresent: () -> Boolean,
    private val holdsManageVirtualMachine: () -> Boolean,
) : RomBackend {

    override val label: String = "AVF (Android Virtualization Framework)"
    override val nativeSpeed: Boolean = true

    override fun probe(): Availability =
        if (virtualizationServicePresent() && holdsManageVirtualMachine()) {
            Availability.Available
        } else {
            // One sentence covers both closed doors: neither the service being absent nor the
            // permission being ungrantable is distinguishable-to-the-user without leaking internals,
            // and the remedy (be platform-signed) is the same for both.
            Availability.Unavailable(REASON)
        }

    companion object {
        /** The exact sentence from docs/ROM-LAUNCH.md — do not paraphrase; the doc owns the wording. */
        const val REASON: String =
            "This device does not expose the virtualization service, or Masamune is not " +
                "platform-signed. Native-speed VMs need a signature permission a sideloaded app " +
                "cannot hold."

        /**
         * `android.software.virtualization_framework` (added API 34). Referenced as a literal so
         * compiling against a lower runtime constant is never in question; `hasSystemFeature`
         * simply returns false where the feature is unknown.
         */
        private const val FEATURE_VIRTUALIZATION: String = "android.software.virtualization_framework"

        /** `android.permission.MANAGE_VIRTUAL_MACHINE` — signature-level; a sideloaded APK is denied. */
        private const val PERMISSION_MANAGE_VM: String = "android.permission.MANAGE_VIRTUAL_MACHINE"

        /** Wire the probe to the real platform: system-feature lookup + a self-permission check. */
        fun real(context: Context): AvfBackend = AvfBackend(
            virtualizationServicePresent = {
                context.packageManager.hasSystemFeature(FEATURE_VIRTUALIZATION)
            },
            holdsManageVirtualMachine = {
                context.checkSelfPermission(PERMISSION_MANAGE_VM) == PackageManager.PERMISSION_GRANTED
            },
        )
    }
}

/**
 * **KVM** via `/dev/kvm`. Native speed, and gated behind a node that stock devices do not expose
 * to an unprivileged uid.
 *
 * The probe is a file-presence-plus-readability check on the node: on a stock device the node is
 * absent or root-owned, so an unprivileged process (the app runs at its own uid; the subsystem
 * shell at uid 2000) cannot open it. On a rooted device the chain finds it — again with no code
 * change (docs/ROM-LAUNCH.md).
 *
 * The node path is injected so a test can point it at a temp file (present/readable) or a
 * nonexistent path (absent) and get a decided result without touching `/dev`.
 */
class KvmBackend(
    private val kvmNode: File = File(DEFAULT_KVM_NODE),
) : RomBackend {

    override val label: String = "KVM (/dev/kvm)"
    override val nativeSpeed: Boolean = true

    override fun probe(): Availability =
        if (kvmNode.exists() && kvmNode.canRead()) {
            Availability.Available
        } else {
            Availability.Unavailable(REASON)
        }

    companion object {
        const val DEFAULT_KVM_NODE: String = "/dev/kvm"

        /** The exact sentence from docs/ROM-LAUNCH.md. */
        const val REASON: String = "/dev/kvm is not present or not readable by uid 2000."
    }
}

/**
 * **QEMU TCG** — pure userspace full-system emulation. Needs nothing (no root, no permission, no
 * kernel feature) but runs the guest 5–20× slower than native. It is the honest floor: a genuinely
 * booting ROM — its own kernel, its own init, its own uid space — just slow.
 *
 * The one thing it *does* need is the QEMU binary itself, and QEMU is a **payload** — a build input
 * that is absent in this build, on the same pattern as any donor payload whose absence a capability
 * reports rather than fakes (docs/DONOR-SURFACES.md; Requirement.Payload in the flow model). So on a
 * clean build this probe finds no QEMU and reports UNAVAILABLE. It never fabricates a boot from a
 * binary that is not there.
 *
 * ### Where the binary has to live, and why this changed
 * This probe originally looked only in `/data/local/tmp/masamune/usr/bin`, from the era when the
 * Linux side of Masamune was a Termux-style prefix populated over the ADB rung and run at uid 2000.
 * That search could never succeed for the app itself: `/data/local/tmp` is not app-writable, and
 * Android's W^X rule refuses to execute a binary out of app-writable storage anyway. A QEMU
 * cross-built and dropped there would have been found by nothing and run by no one — the probe would
 * have gone on reporting "absent" with a perfectly good payload sitting on the device.
 *
 * So the payload is now searched for where an executable can actually live with no privilege at all:
 * the installer-owned **native library directory**, exactly as the shell capsule's busybox and proot
 * do (`jniLibs/README.md`, [dev.pleiades.masamune.shell.CapsuleShellBackend]). Shipped in the APK,
 * marked executable by the installer, runnable at the app's own uid.
 *
 * The legacy prefix stays in the search list *after* it, because on a rooted or ADB-provisioned
 * device a binary really can be placed and executed there. A path that is closed by default is not
 * the same as one that is closed always — but it is a fallback, not the expectation.
 *
 * Storage note: only the QEMU binaries are searched for here; the multi-GB ROM *image* lives
 * elsewhere (see [RomImage]). This probe is about the emulator, not the image.
 *
 * The search directories are injected so a test can point them at temp dirs with or without a fake
 * QEMU binary.
 */
class TcgBackend(
    private val searchDirs: List<File> = listOf(File(DEFAULT_PREFIX_BIN)),
) : RomBackend {

    /** Single-directory form, so existing call sites and tests read unchanged. */
    constructor(prefixBinDir: File) : this(listOf(prefixBinDir))

    override val label: String = "QEMU TCG (userspace emulation)"
    override val nativeSpeed: Boolean = false

    override fun probe(): Availability =
        if (findQemuBinary() != null) {
            Availability.Available
        } else {
            Availability.Unavailable(REASON)
        }

    /**
     * The first executable QEMU across [searchDirs], searched in order, or null when none is there.
     *
     * Two names are accepted, because the same binary is called different things depending on where
     * it lives: shipped inside the APK it must match `lib*.so` for the installer to extract it and
     * mark it executable, while an ordinary build calls it `qemu-system-<arch>`. Insisting on one
     * name would make a perfectly good payload invisible. `listFiles` returns null for a directory
     * that does not exist, which collapses to "absent" rather than to an error.
     */
    fun findQemuBinary(): File? = searchDirs.firstNotNullOfOrNull { dir ->
        dir.listFiles { f -> f.isFile && f.canExecute() && isQemuName(f.name) }?.minByOrNull { it.name }
    }

    companion object {
        /**
         * The legacy Termux-style prefix. Searched last, and not app-writable — see the class KDoc
         * for why it is no longer where the payload is expected to be.
         */
        const val DEFAULT_PREFIX_BIN: String = "/data/local/tmp/masamune/usr/bin"

        /** QEMU's full-system binaries are named `qemu-system-<arch>` (e.g. `qemu-system-aarch64`). */
        const val QEMU_SYSTEM_PREFIX: String = "qemu-system-"

        /** The APK-shipped form, in `jniLibs/<abi>/`, on the same naming rule as busybox and proot. */
        const val QEMU_PAYLOAD_PREFIX: String = "libmasamuneqemu"

        /** True for either name the QEMU payload can legitimately carry. */
        fun isQemuName(name: String): Boolean =
            name.startsWith(QEMU_SYSTEM_PREFIX) ||
                (name.startsWith(QEMU_PAYLOAD_PREFIX) && name.endsWith(".so"))

        /** The exact sentence from docs/ROM-LAUNCH.md. */
        const val REASON: String =
            "This build ships no QEMU payload for this device's ABI, and none is installed in the " +
                "Masamune prefix. Without an emulator there is no kernel to boot."

        /**
         * The real search order: the installer-owned native library directory first — the one place
         * an app can execute from with no privilege rung — then the legacy prefix.
         */
        fun real(context: Context): TcgBackend = TcgBackend(
            listOf(
                File(context.applicationInfo.nativeLibraryDir),
                File(DEFAULT_PREFIX_BIN),
            ),
        )
    }
}

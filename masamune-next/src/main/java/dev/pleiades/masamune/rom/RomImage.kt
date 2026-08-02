package dev.pleiades.masamune.rom

/**
 * The architecture a ROM image's kernel is built for. Two are modelled because they are the two
 * that matter on an ARM phone: a same-arch guest (the fast TCG case) and an x86_64 guest, which is
 * cross-emulated on top of no hardware assist and therefore slow (docs/ROM-LAUNCH.md "What TCG
 * actually gets you").
 *
 * [qemuSystemBinary] is the full-system emulator a launch would need for this arch — the payload
 * [TcgBackend] probes the prefix for. Named here so the image model states its own dependency.
 */
enum class RomArch(val label: String, val qemuSystemBinary: String) {
    AARCH64("aarch64", "qemu-system-aarch64"),
    X86_64("x86_64", "qemu-system-x86_64");

    companion object {
        /**
         * Map a device ABI string (e.g. from `Build.SUPPORTED_ABIS`) to a [RomArch], or null when
         * it is neither of the two modelled arches. `arm64-v8a` is aarch64; `x86_64` is itself.
         */
        fun fromAbi(abi: String): RomArch? = when (abi.lowercase()) {
            "arm64-v8a", "aarch64" -> AARCH64
            "x86_64" -> X86_64
            else -> null
        }
    }
}

/**
 * A ROM image the user has added — a bootable OS image on disk. **None are bundled**; the registry
 * starts empty and only what the user adds appears.
 *
 * ### Where the image lives, and why it is not the prefix
 * [path] points into **app-scoped external storage**, not the Masamune prefix. Quoting
 * docs/ROM-LAUNCH.md verbatim: *"A ROM image is measured in gigabytes and `/data/local/tmp` is not
 * the place for it; the image goes to app-scoped external storage with the prefix holding only the
 * QEMU binaries. This also means the image survives a prefix rebuild."* So the prefix holds the
 * emulator (the [TcgBackend] payload) and this GB-sized image sits beside the app's other external
 * files, surviving a prefix wipe.
 *
 * @property id registry row id (0 before it is persisted).
 * @property name display name, taken from the picked document.
 * @property path absolute path in app-scoped external storage — never the prefix.
 * @property arch the guest kernel's architecture; decides which `qemu-system-*` a launch would use.
 * @property sizeBytes on-disk size of the copied image, for the honest "this is gigabytes" readout.
 */
data class RomImage(
    val id: Long,
    val name: String,
    val path: String,
    val arch: RomArch,
    val sizeBytes: Long,
) {
    /**
     * True when this image's arch differs from the host's — the cross-architecture case (an x86_64
     * image on an ARM phone). Cross-emulation runs on top of no hardware assist and is slow enough
     * that it should be labelled, not discovered (docs/ROM-LAUNCH.md).
     */
    fun isCrossEmulated(hostArch: RomArch): Boolean = arch != hostArch

    /**
     * A one-line speed characterisation for the given host, stated up front rather than left for the
     * user to find out. Same-arch is the fast TCG case; cross-arch is called out as slow.
     */
    fun speedNote(hostArch: RomArch): String =
        if (isCrossEmulated(hostArch)) {
            "${arch.label} on a ${hostArch.label} host — cross-architecture emulation, no hardware " +
                "assist. This boots, and it is slow; expect a console to come up long before a desktop."
        } else {
            "${arch.label} on a ${hostArch.label} host — same-arch emulation, the fast TCG case. " +
                "A console-mode guest boots in a workable time; a full desktop is functional but heavy."
        }
}

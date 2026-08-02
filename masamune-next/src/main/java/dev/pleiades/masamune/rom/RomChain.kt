package dev.pleiades.masamune.rom

import android.content.Context

/**
 * The backend fallback chain — the same shape as Yojimbo's privilege chain
 * (Shizuku → Dhizuku → Device Owner → ADB): probe in order, take the best available, **name the
 * one in use**, and report ABSENT rather than pretend (docs/ROM-LAUNCH.md "The backend chain").
 *
 * ```
 * AVF  →  KVM  →  QEMU TCG  →  ABSENT
 * ```
 *
 * "Best" is order, not speed-max-after-the-fact: AVF and KVM are native and come first, TCG is the
 * slow-but-unprivileged floor and comes last, and when all three are [Availability.Unavailable] the
 * terminal state is ABSENT — no backend, and the collected reasons are what the surface shows.
 *
 * On a clean sideloaded build every backend is unavailable (no AVF permission, no readable
 * `/dev/kvm`, no QEMU payload), so [RomChainResult.isAbsent] is true and there is nothing to launch.
 * That is the shipped state, by design.
 */
class RomChain(val backends: List<RomBackend>) {

    /**
     * Probe every backend in order and fold the results into a [RomChainResult].
     *
     * All backends are probed (not just up to the first hit) so the surface can show each path's
     * status and, when the chain is absent, every reason at once. The *live* backend is still
     * defined as the first AVAILABLE in chain order, so "take the best available" holds.
     */
    fun probe(): RomChainResult =
        RomChainResult(backends.map { RomBackendProbe(it, it.probe()) })

    companion object {
        /**
         * The real chain for this device, in fallback order. AVF binds to the platform's
         * virtualization service + self-permission check; KVM uses its default node path; TCG needs
         * the context too, because the only directory an app can execute from is its own
         * installer-owned native library directory (see [TcgBackend.real]). Constructed fresh per
         * probe so a permission or payload that appears later (a platform-signed reinstall, an APK
         * update that carries the QEMU payload) is seen without an app restart.
         */
        fun real(context: Context): RomChain = RomChain(
            listOf(
                AvfBackend.real(context),
                KvmBackend(),
                TcgBackend.real(context),
            ),
        )
    }
}

/** One backend paired with what its probe returned. */
data class RomBackendProbe(val backend: RomBackend, val availability: Availability)

/**
 * The folded outcome of probing the whole chain.
 *
 * [live] is the first AVAILABLE backend in chain order, or null — and null *is* ABSENT, the honest
 * terminal state. [unavailable] carries every closed path's label and reason, in order, so a UI can
 * spell out exactly why there is nothing to launch instead of a bare "unavailable".
 */
class RomChainResult(val probes: List<RomBackendProbe>) {

    /** The backend actually in use — first AVAILABLE in chain order — or null when the chain is ABSENT. */
    val live: RomBackend? =
        probes.firstOrNull { it.availability is Availability.Available }?.backend

    /** The live backend's name, shown when reporting which path is in use. Null when ABSENT. */
    val liveLabel: String? get() = live?.label

    /** Whether the live backend runs the guest at native speed. False when ABSENT (nothing runs). */
    val nativeSpeed: Boolean get() = live?.nativeSpeed ?: false

    /**
     * ABSENT: no backend is available. On a clean sideloaded build this is always true, which is
     * exactly what disables the Launch control and drives the "no backend available" notice.
     */
    val isAbsent: Boolean get() = live == null

    /** Every closed path, in chain order, as (label, reason) — the sentences the surface shows. */
    val unavailable: List<UnavailablePath> = probes.mapNotNull { p ->
        (p.availability as? Availability.Unavailable)?.let { UnavailablePath(p.backend.label, it.reason) }
    }
}

/** A backend that reported [Availability.Unavailable], with the sentence naming what it lacks. */
data class UnavailablePath(val label: String, val reason: String)

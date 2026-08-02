package dev.pleiades.masamune.shell

import android.content.Context
import dev.pleiades.masamune.core.capability.Capability
import dev.pleiades.masamune.core.capability.Caller
import dev.pleiades.masamune.core.capability.CapabilityGate
import dev.pleiades.masamune.core.capability.GateDecision
import dev.pleiades.masamune.core.decline.Decline
import dev.pleiades.masamune.core.decline.DeclineRegistry

/**
 * The single capability-gated shell entry for the whole app.
 *
 * Before this existed, only the Terminal surface drove [TermuxShellBackend], and the SHELL
 * capability check plus the honest-outcome handling lived inline in its ViewModel. Any other
 * caller that needed to run a command — the AI operator, a flow step — would have had to
 * re-implement the gate and the Termux-absent handling, or bypass them. This facade is the one
 * place that: (1) checks the SHELL capability for the *named* caller, recording a classified
 * refusal when it is denied; (2) checks that a Termux backend is actually present, recording the
 * matching decline when it is not; and only then (3) hands the line to the backend.
 *
 * The Terminal surface routes every dispatch through here as [Caller.User]. The operator/flow
 * layer does not yet call it — adopting it there needs an edit outside this surface's package —
 * but the entry point is now defined so that when it does, the gating is identical and cannot be
 * skipped. See docs/DONOR-SURFACES.md §4 (the RUN_COMMAND contract the operator/flow also use).
 */
class ShellDispatcher(context: Context) {

    private val appContext = context.applicationContext
    private val backend = TermuxShellBackend(appContext)

    /**
     * Masamune's own userland. Tried FIRST, and when it is present Termux is never asked — which is
     * the whole point: an app that has to ask the thing it replaces has not replaced it. The Termux
     * path stays as a fallback for a device whose ABI this build ships no payload for, so retiring
     * the delegation costs nobody a working shell.
     */
    private val capsule = CapsuleShellBackend(appContext)

    private val gate = CapabilityGate.get(appContext)

    /** True when commands run on the bundled userland instead of being handed to another app. */
    val usingCapsule: Boolean get() = capsule.availability() is CapsuleShellBackend.Availability.Ready

    /** Name of the shell design actually in force, for the surface header. */
    val designName: String get() = if (usingCapsule) capsule.designName else backend.designName

    /**
     * With the capsule present the shell is unconditionally available — it needs no second app and
     * no granted permission — so this reports Ready without consulting Termux at all.
     */
    fun availability(): TermuxShellBackend.Availability =
        if (usingCapsule) TermuxShellBackend.Availability.Ready else backend.availability()

    /** Outcome of a gated dispatch. [Ran] carries the backend's own honest outcome shape. */
    sealed class Dispatch {
        data class Ran(val outcome: TermuxShellBackend.Outcome) : Dispatch()

        /** The SHELL capability is not held by the caller. Message is the gate's own wording. */
        data class Gated(val message: String) : Dispatch()

        /** No Termux backend to drive. The reason is recorded in the decline log. */
        data class Unavailable(val availability: TermuxShellBackend.Availability) : Dispatch()
    }

    /**
     * Gate-check for [caller], verify a backend is present, then run [commandLine].
     *
     * A denial or an absent backend is recorded in [DeclineRegistry] before returning, so a
     * refusal is never silent regardless of which caller asked.
     */
    suspend fun dispatch(
        caller: Caller,
        commandLine: String,
        workdir: String = TermuxContract.HOME,
        failsafe: Boolean = false,
        timeoutMillis: Long = 120_000L,
    ): Dispatch {
        val decision = gate.check(caller, Capability.SHELL, "run \"$commandLine\"")
        if (decision is GateDecision.Denied) return Dispatch.Gated(decision.message)

        // Masamune's own userland first. The capability gate above still applies — running on our
        // own busybox is not a way around it — but no other app, and no Termux permission, is
        // involved once the payload is present.
        if (usingCapsule) {
            return Dispatch.Ran(capsule.run(commandLine, workdir, timeoutMillis, failsafe))
        }

        when (val availability = backend.availability()) {
            TermuxShellBackend.Availability.NotInstalled -> {
                DeclineRegistry.record(
                    Decline(
                        callerTag = caller.tag,
                        capability = Capability.SHELL,
                        reason = Decline.Reason.TARGET_APP_ABSENT,
                        detail = "${TermuxContract.PACKAGE} is not installed.",
                        operation = commandLine,
                    )
                )
                return Dispatch.Unavailable(availability)
            }
            TermuxShellBackend.Availability.PermissionNotGranted -> {
                DeclineRegistry.record(
                    Decline(
                        callerTag = caller.tag,
                        capability = Capability.SHELL,
                        reason = Decline.Reason.TARGET_PERMISSION_MISSING,
                        detail = "${TermuxContract.PERMISSION} not granted.",
                        operation = commandLine,
                    )
                )
                return Dispatch.Unavailable(availability)
            }
            TermuxShellBackend.Availability.Ready -> Unit
        }

        return Dispatch.Ran(backend.run(commandLine, workdir, timeoutMillis, failsafe))
    }
}

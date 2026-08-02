package dev.pleiades.masamune.operator

import android.content.Context
import dev.pleiades.masamune.core.capability.Capability
import dev.pleiades.masamune.core.capability.Caller
import dev.pleiades.masamune.core.capability.CapabilityGate
import dev.pleiades.masamune.core.capability.GateDecision

/**
 * The narrow capability check the operator's action blocks route through, and the reason it is an
 * interface rather than a direct [CapabilityGate] call.
 *
 * docs/AI-OPERATOR.md makes it a hard rule that operator actions pass through [CapabilityGate]
 * with [Caller.AiAgent] — the gate that already checks the [dev.pleiades.masamune.core.halt.
 * HaltController] first and persists per-(caller × capability) grants, so the operator gets no
 * blanket pass and can be stopped between any two blocks. The block impls need exactly one thing
 * from that gate ("may the AI agent do this, or here is why not"), and depending on a narrower
 * contract is what keeps them unit-testable on the JVM: a test supplies an allow/deny double
 * instead of a real `Context`-backed gate.
 */
fun interface OperatorGate {
    /** Allowed, or a [GateDecision.Denied] whose message is safe to surface and names the halt/grant cause. */
    fun check(capability: Capability, what: String): GateDecision

    companion object {
        /** The production gate: the real [CapabilityGate] singleton, always as [Caller.AiAgent]. */
        fun real(context: Context): OperatorGate {
            val gate = CapabilityGate.get(context.applicationContext)
            return OperatorGate { capability, what -> gate.check(Caller.AiAgent, capability, what) }
        }
    }
}

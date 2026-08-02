package dev.pleiades.masamune.core.capability

import android.content.Context
import dev.pleiades.masamune.core.decline.Decline
import dev.pleiades.masamune.core.decline.DeclineRegistry
import dev.pleiades.masamune.core.halt.HaltController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Default-deny per-(caller x capability) gate.
 *
 * This is not advisory. Every filesystem mutation, every shell dispatch and every provider
 * call in this module routes through [check] and refuses on Denied. The grant matrix is
 * user-visible and user-editable at Settings -> Capabilities, and refusals are recorded in
 * the decline log (Settings -> Refusal log) so a denied call is never silent.
 *
 * Seeding: on first run `user` gets METADATA + FILE_READ, because a person opening a file
 * browser is not making a privilege request. Everything else — including FILE_WRITE, SHELL
 * and NETWORK for `user` — starts denied and must be granted explicitly. `ai-agent` and any
 * `plugin:` caller start with nothing.
 */
class CapabilityGate private constructor(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _grants = MutableStateFlow(loadGrants())

    /** Set of "callerTag|CAPABILITY" keys currently granted. */
    val grants: StateFlow<Set<String>> = _grants.asStateFlow()

    init {
        if (!prefs.getBoolean(KEY_SEEDED, false)) {
            val seeded = setOf(
                key(Caller.User, Capability.METADATA),
                key(Caller.User, Capability.FILE_READ),
            )
            prefs.edit().putStringSet(KEY_GRANTS, seeded).putBoolean(KEY_SEEDED, true).apply()
            _grants.value = seeded
        }
    }

    fun isGranted(caller: Caller, capability: Capability): Boolean {
        if (capability == Capability.UNCLASSIFIED) return false
        return key(caller, capability) in _grants.value
    }

    fun grant(caller: Caller, capability: Capability) {
        if (capability == Capability.UNCLASSIFIED) return
        mutate { it + key(caller, capability) }
    }

    fun revoke(caller: Caller, capability: Capability) {
        mutate { it - key(caller, capability) }
    }

    /**
     * The single decision point. Checks the halt flag first — a halted system refuses
     * everything regardless of grants — then the grant matrix. Denials are logged.
     */
    fun check(caller: Caller, capability: Capability, what: String): GateDecision {
        if (HaltController.isHalted) {
            val msg = HaltController.haltedRefusal(what)
            DeclineRegistry.record(
                Decline(
                    callerTag = caller.tag,
                    capability = capability,
                    reason = Decline.Reason.HALTED,
                    detail = msg,
                    operation = what,
                )
            )
            return GateDecision.Denied(msg)
        }
        if (isGranted(caller, capability)) return GateDecision.Allowed

        val msg = "Denied: caller \"${caller.tag}\" does not hold ${capability.name}. " +
            "Operation: $what. Grant it at Settings → Capabilities."
        DeclineRegistry.record(
            Decline(
                callerTag = caller.tag,
                capability = capability,
                reason = Decline.Reason.CAPABILITY_NOT_GRANTED,
                detail = msg,
                operation = what,
            )
        )
        return GateDecision.Denied(msg)
    }

    private fun mutate(transform: (Set<String>) -> Set<String>) {
        val next = transform(_grants.value)
        prefs.edit().putStringSet(KEY_GRANTS, next).apply()
        _grants.value = next
    }

    private fun loadGrants(): Set<String> =
        prefs.getStringSet(KEY_GRANTS, emptySet())?.toSet() ?: emptySet()

    companion object {
        private const val PREFS_NAME = "masamune_capability_grants"
        private const val KEY_GRANTS = "grants"
        private const val KEY_SEEDED = "seeded_v1"

        fun key(caller: Caller, capability: Capability) = "${caller.tag}|${capability.name}"

        /** Callers the grant matrix renders a row for. */
        val KNOWN_CALLERS: List<Caller> = listOf(Caller.User, Caller.AiAgent)

        /** Capabilities the grant matrix renders a column for (UNCLASSIFIED is never grantable). */
        val GRANTABLE: List<Capability> =
            Capability.entries.filter { it != Capability.UNCLASSIFIED }

        @Volatile
        private var instance: CapabilityGate? = null

        fun get(context: Context): CapabilityGate =
            instance ?: synchronized(this) {
                instance ?: CapabilityGate(context).also { instance = it }
            }
    }
}

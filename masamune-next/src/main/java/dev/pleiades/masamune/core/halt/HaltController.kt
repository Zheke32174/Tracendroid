package dev.pleiades.masamune.core.halt

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One-tap kill switch, salvaged in shape from the donor tree's HaltController and reduced to
 * what this module actually enforces.
 *
 * Halt is consulted by [dev.pleiades.masamune.core.capability.CapabilityGate.check], which is
 * the single decision point in front of every filesystem mutation, shell dispatch and provider
 * call. While halted, every one of those refuses regardless of grants. Halt is one-directional
 * until [clear].
 *
 * Reachable from the Chat screen's stop control and from Settings.
 */
object HaltController {

    private const val AUDIT_BUFFER_SIZE = 32

    sealed class State {
        data object Running : State()
        data class Halted(val at: Long, val by: String, val reason: String) : State()
    }

    data class HaltEvent(val at: Long, val by: String, val reason: String)

    fun interface Listener {
        fun onHalt(event: HaltEvent)
    }

    private val _state = MutableStateFlow<State>(State.Running)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _audit = MutableStateFlow<List<HaltEvent>>(emptyList())
    val audit: StateFlow<List<HaltEvent>> = _audit.asStateFlow()

    private val auditBuffer = CopyOnWriteArrayList<HaltEvent>()
    private val listeners = CopyOnWriteArrayList<Listener>()

    val isHalted: Boolean get() = _state.value is State.Halted

    fun requestHalt(by: String, reason: String) {
        val now = System.currentTimeMillis()
        val event = HaltEvent(now, by, reason)
        recordAudit(event)
        if (_state.value is State.Halted) return
        _state.value = State.Halted(now, by, reason)
        for (l in listeners.toList()) {
            runCatching { l.onHalt(event) }
        }
    }

    fun clear() {
        _state.value = State.Running
    }

    fun registerListener(listener: Listener) = listeners.add(listener)

    fun unregisterListener(listener: Listener) = listeners.remove(listener)

    fun haltedRefusal(surface: String): String {
        val s = _state.value
        return if (s is State.Halted) {
            "Halted by ${s.by} (${s.reason}). Surface: $surface. Clear the halt before " +
                "issuing new $surface calls."
        } else {
            "Halt cleared just now; please retry."
        }
    }

    private fun recordAudit(event: HaltEvent) {
        auditBuffer.add(event)
        while (auditBuffer.size > AUDIT_BUFFER_SIZE) auditBuffer.removeAt(0)
        _audit.value = auditBuffer.toList()
    }
}

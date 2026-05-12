package com.ai.assistance.operit.core.halt

import com.ai.assistance.operit.util.AppLogger
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Sovereign-user halt control (§ 4.7 of docs/THREAT_MODEL.md, principle 7 of
 * docs/SECURITY.md).
 *
 * One-tap kill switch the user can pull from anywhere. Sets a halt flag that every
 * blast-radius surface — AIToolHandler, JsPluginGate, AiToolGate, ShellIpcClient, the
 * proot foreground service — consults before doing anything new. Halt requests are
 * audit-logged with who and why.
 *
 * Halt is a one-direction state until [clear] is called. A new chat session calls
 * [clear] to start fresh; ad-hoc halt-and-resume is not a v1 concern.
 *
 * Listeners registered through [registerListener] are invoked when halt fires. They run
 * synchronously on the requester's thread — listeners should be fast and non-throwing.
 */
object HaltController {

    private const val TAG = "HaltController"
    private const val AUDIT_BUFFER_SIZE = 64

    /** What the rest of the app sees about the current halt state. */
    sealed class State {
        data object Running : State()
        data class Halted(val at: Long, val by: String, val reason: String) : State()
    }

    /** One halt event in the audit ring. */
    data class HaltEvent(
        val at: Long,
        val by: String,
        val reason: String,
        /** Best-effort snapshot of in-flight context at halt time. */
        val context: String? = null,
    )

    fun interface Listener {
        fun onHalt(event: HaltEvent)
    }

    private val _state = MutableStateFlow<State>(State.Running)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _audit = MutableStateFlow<List<HaltEvent>>(emptyList())
    val audit: StateFlow<List<HaltEvent>> = _audit.asStateFlow()

    private val auditBuffer = CopyOnWriteArrayList<HaltEvent>()
    private val listeners = CopyOnWriteArrayList<Listener>()

    /** True while the system is in the Halted state. Fast-path for gate checks. */
    val isHalted: Boolean
        get() = _state.value is State.Halted

    /**
     * Request a halt. Idempotent — if already halted, records a second audit entry but
     * does not re-fire listeners. The first reason wins on the StateFlow.
     */
    fun requestHalt(by: String, reason: String, context: String? = null) {
        val now = System.currentTimeMillis()
        val event = HaltEvent(now, by, reason, context)
        recordAudit(event)
        val previous = _state.value
        if (previous is State.Halted) {
            AppLogger.d(TAG, "additional halt request while halted: by=$by reason=$reason")
            return
        }
        _state.value = State.Halted(now, by, reason)
        AppLogger.d(TAG, "halted: by=$by reason=$reason")
        // Snapshot the listener list before iterating so a re-entrant register call from
        // inside a listener doesn't disturb the current dispatch.
        val snapshot = listeners.toList()
        for (l in snapshot) {
            runCatching { l.onHalt(event) }
                .onFailure { e -> AppLogger.w(TAG, "halt listener threw: ${e.message}") }
        }
    }

    /** Clear the halt and return to Running. Audit ring is preserved. */
    fun clear() {
        _state.value = State.Running
        AppLogger.d(TAG, "halt cleared")
    }

    fun registerListener(listener: Listener) {
        listeners.add(listener)
    }

    fun unregisterListener(listener: Listener) {
        listeners.remove(listener)
    }

    /** Standard deny payload for callers that need to surface a halted call. */
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
        while (auditBuffer.size > AUDIT_BUFFER_SIZE) {
            auditBuffer.removeAt(0)
        }
        _audit.value = auditBuffer.toList()
    }
}

package com.ai.assistance.operit.core.agent.decline

import com.ai.assistance.operit.util.AppLogger
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Publishes and observes AI declines (§ 4.13).
 *
 * Two surfaces:
 *  - [active] — the most recent decline the user hasn't yet acknowledged. The UI
 *    overlay surfaces this. Only one active decline at a time; new declines while the
 *    previous is still active replace the active reference (the audit ring keeps both).
 *  - [recent]  — bounded ring of the last N declines, for the audit screen.
 *
 * Acknowledgement actions ([acknowledgeAbandon] / [acknowledgeRephrase] /
 * [acknowledgeReprompt]) record the user's chosen next move in the audit ring and clear
 * the active decline. The registry does NOT automatically re-prompt the agent; that's
 * the agent loop's responsibility, and the registry's contract is just to surface and
 * dismiss. Per `SECURITY.md` principle 8 there is no implicit retry path.
 *
 * Backends or pipeline components call [record] when they detect a decline.
 */
object DeclineRegistry {

    private const val TAG = "DeclineRegistry"
    private const val AUDIT_BUFFER_SIZE = 32

    /** Why the user dismissed the active decline. */
    enum class UserResponse {
        Abandon,
        Rephrase,
        Reprompt,
        /** The decline was cleared programmatically (e.g. new session). */
        Cleared,
    }

    /** One row in the audit ring. */
    data class AuditEntry(
        val decline: AgentDecline,
        val userResponse: UserResponse? = null,
        val acknowledgedAtMillis: Long? = null,
    )

    private val _active = MutableStateFlow<AgentDecline?>(null)
    val active: StateFlow<AgentDecline?> = _active.asStateFlow()

    private val _recent = MutableStateFlow<List<AuditEntry>>(emptyList())
    val recent: StateFlow<List<AuditEntry>> = _recent.asStateFlow()

    private val auditBuffer = CopyOnWriteArrayList<AuditEntry>()

    /** Publish a fresh decline. */
    fun record(decline: AgentDecline) {
        AppLogger.d(TAG, "decline recorded: ${decline.classification} ${decline.reason.take(80)}")
        appendAudit(AuditEntry(decline = decline))
        _active.value = decline
    }

    /** User abandoned the action after the decline. Audit reflects the choice. */
    fun acknowledgeAbandon() = acknowledge(UserResponse.Abandon)

    /** User chose to rephrase. The chat layer is responsible for the actual rephrase UI. */
    fun acknowledgeRephrase() = acknowledge(UserResponse.Rephrase)

    /** User chose to re-prompt from scratch (explicit, not automatic). */
    fun acknowledgeReprompt() = acknowledge(UserResponse.Reprompt)

    /** Programmatic clear — e.g. new session begins. */
    fun clear() {
        if (_active.value != null) {
            acknowledge(UserResponse.Cleared)
        }
    }

    private fun acknowledge(response: UserResponse) {
        val current = _active.value ?: return
        _active.value = null
        // Replace the most recent matching entry's user response. If the decline isn't in
        // the ring (unlikely), append a fresh entry so the audit still records the action.
        val now = System.currentTimeMillis()
        val updated = buildList {
            var replaced = false
            for (entry in auditBuffer.asReversed()) {
                if (!replaced && entry.decline === current && entry.userResponse == null) {
                    add(entry.copy(userResponse = response, acknowledgedAtMillis = now))
                    replaced = true
                } else {
                    add(entry)
                }
            }
        }.asReversed()
        auditBuffer.clear()
        auditBuffer.addAll(updated)
        _recent.value = auditBuffer.toList()
        AppLogger.d(TAG, "decline acknowledged: $response")
    }

    private fun appendAudit(entry: AuditEntry) {
        auditBuffer.add(entry)
        while (auditBuffer.size > AUDIT_BUFFER_SIZE) {
            auditBuffer.removeAt(0)
        }
        _recent.value = auditBuffer.toList()
    }
}

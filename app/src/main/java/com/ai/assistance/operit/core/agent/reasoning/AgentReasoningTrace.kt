package com.ai.assistance.operit.core.agent.reasoning

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-flight AI reasoning trace (§ 4.7 + § 4.13 follow-up).
 *
 * The chat pipeline appends the AI's reasoning content (thinking tokens, intermediate
 * step text, whatever the backend exposes) here as it streams in. When a halt fires
 * ([HaltController][com.ai.assistance.operit.core.halt.HaltController]) or a decline is
 * recorded ([DeclineRegistry][com.ai.assistance.operit.core.agent.decline.DeclineRegistry]),
 * the snapshot is captured into the audit event verbatim. This closes the "AI reasoning
 * state preserved at the moment of halt / decline" requirement those two sections
 * called out.
 *
 * Bounded: a single trace is capped at [MAX_BYTES] (16 KiB) — long enough to capture
 * a meaningful chunk of reasoning, short enough that the audit ring entries stay
 * manageable. When the cap is hit [append] keeps the most recent bytes (tail-keeping
 * matches the human reading order — what the AI just said is what matters at halt time).
 *
 * Clearing happens when a new turn begins, or explicitly. The trace is NOT cleared
 * automatically by halt or decline — the audit event has already captured the snapshot,
 * and a follow-on operation may want to read the same trace.
 */
object AgentReasoningTrace {

    /** 16 KiB ceiling per trace. */
    const val MAX_BYTES: Int = 16 * 1024

    private val buffer = AtomicReference<String?>(null)

    private val _flow = MutableStateFlow<String?>(null)
    /** Observers can subscribe (e.g. a debug surface that shows the live trace). */
    val flow: StateFlow<String?> = _flow.asStateFlow()

    /** Current snapshot, or null if nothing has been recorded for this turn. */
    fun current(): String? = buffer.get()

    /** Replace the trace with [text]. Pass null or empty to clear. */
    fun set(text: String?) {
        val trimmed = text?.takeIf { it.isNotEmpty() }
        buffer.set(trimmed?.let { capToTail(it) })
        _flow.value = buffer.get()
    }

    /** Append [text] to the trace. Truncates from the head when the total exceeds [MAX_BYTES]. */
    fun append(text: String) {
        if (text.isEmpty()) return
        val current = buffer.get() ?: ""
        val combined = current + text
        val capped = capToTail(combined)
        buffer.set(capped)
        _flow.value = capped
    }

    /** Clear the trace. Called at turn boundaries. */
    fun clear() {
        buffer.set(null)
        _flow.value = null
    }

    private fun capToTail(text: String): String {
        if (text.length <= MAX_BYTES) return text
        return text.substring(text.length - MAX_BYTES)
    }
}

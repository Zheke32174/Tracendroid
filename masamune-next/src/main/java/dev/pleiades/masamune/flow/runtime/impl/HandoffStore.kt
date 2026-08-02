package dev.pleiades.masamune.flow.runtime.impl

import dev.pleiades.masamune.flow.expr.Value

/**
 * The inter-fiber hand-off queues behind `Variables give` / `Variables take` — the second piece of
 * flow-wide shared state, alongside [AtomicStore].
 *
 * Where the atomics are a keyed *cell set* (one value per name, last-writer-wins), this is a keyed
 * set of **FIFO queues**, one per taker fiber, carrying a [Handoff] (the giver's transferable
 * variables plus the giver's own URI). A `give` addressed to a taker enqueues; a `take` on that
 * taker dequeues in order — the concurrency-safe mailbox the donor's `Variables take` documents.
 *
 * ### The park/resume race is closed under one lock
 * A `take` that finds its queue empty must *park* until a `give` arrives, and a `give` may land in
 * the exact window between the taker's emptiness check and its parking. So the check and the park
 * are one atomic step here: [arm] takes the lock, and *either* hands back a waiting [Handoff]
 * immediately *or* records the resume callback as parked — a `give` can never slip through the gap,
 * because [give] takes the same lock and, seeing a parked taker, fires it rather than enqueuing.
 *
 * Like [AtomicStore] this is live coordination state, deliberately **not** persisted: a queued
 * hand-off is in-flight coordination for a running flow, not a fiber's resumable program state.
 * A flow that restarts starts its mailboxes empty, exactly as a restarted process starts its
 * in-memory channels empty.
 */
internal class HandoffStore {
    /** One hand-off: what a `give` transfers and who gave it. */
    data class Handoff(val giverUri: String, val values: Map<String, Value>)

    private val lock = Any()
    private val queues = HashMap<String, ArrayDeque<Handoff>>()
    private val parked = HashMap<String, (Handoff) -> Unit>()

    /**
     * `give`: deliver [handoff] to taker [takerId]. If that taker is parked in a `take`, resume it
     * directly with this hand-off (nothing is queued — the mailbox stayed empty because the taker
     * was already waiting). Otherwise append to its FIFO queue for a later `take`.
     */
    fun give(takerId: String, handoff: Handoff) = synchronized(lock) {
        val waiter = parked.remove(takerId)
        if (waiter != null) {
            waiter(handoff)
        } else {
            queues.getOrPut(takerId) { ArrayDeque() }.addLast(handoff)
        }
    }

    /**
     * `take` for [takerId]: if a hand-off is already queued, invoke [onReady] with it at once and
     * return true (no park). Otherwise register [onReady] as the parked resume for this taker and
     * return false — the caller then awaits, and a future [give] will fire [onReady].
     *
     * Exactly one of the two happens under the lock, so a concurrent [give] cannot be lost.
     */
    fun arm(takerId: String, onReady: (Handoff) -> Unit): Boolean = synchronized(lock) {
        val q = queues[takerId]
        val next = q?.removeFirstOrNull()
        if (next != null) {
            if (q.isEmpty()) queues.remove(takerId)
            onReady(next)
            true
        } else {
            parked[takerId] = onReady
            false
        }
    }

    /** Cancel a parked `take` (its fiber was stopped or the flow shut down before a `give` arrived). */
    fun unarm(takerId: String) = synchronized(lock) { parked.remove(takerId) }
}

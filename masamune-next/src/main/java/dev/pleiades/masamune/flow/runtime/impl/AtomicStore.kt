package dev.pleiades.masamune.flow.runtime.impl

import dev.pleiades.masamune.flow.expr.Value

/**
 * The one piece of state shared *across* fibers of a flow — the store behind the atomic blocks.
 *
 * Every other block reads and writes a single fiber's private frame, which is what lets fibers be
 * cheap and independently serializable. The atomics are the sanctioned exception (see
 * `catalog/CatalogConcurrency.kt`): a flow-wide, key-addressed cell set for shared counters and
 * flags. One [AtomicStore] is created per running flow and shared by that flow's atomic blocks.
 *
 * It is guarded by a plain monitor rather than a coroutine [kotlinx.coroutines.sync.Mutex]: the
 * operations are trivial map reads and writes with no suspension inside the lock, and a fiber's
 * `Atomic compare & store` must observe a consistent value against a concurrent `Atomic add` from
 * another fiber. Cells are keyed by the block's bound variable name — the same name the value is
 * loaded into or stored from — which is what `Atomic clear all` clears in one call.
 *
 * This store is deliberately **not** persisted. It is live coordination state for a running flow,
 * not a fiber's resumable program state; a flow that restarts starts its shared counters fresh,
 * exactly as a restarted process starts its in-memory locks fresh.
 */
internal class AtomicStore {
    private val lock = Any()
    private val cells = HashMap<String, Value>()

    fun load(key: String): Value = synchronized(lock) { cells[key] ?: Value.Null }

    fun store(key: String, value: Value) = synchronized(lock) { cells[key] = value }

    /** Add [delta] to the stored number (absent reads as 0) and return the sum, as one atomic step. */
    fun addAndGet(key: String, delta: Double): Value = synchronized(lock) {
        val current = (cells[key] as? Value.Num)?.value ?: 0.0
        val sum = Value.Num(current + delta)
        cells[key] = sum
        sum
    }

    /** Store [value] only if the current cell equals [expected]; return whether it did, as one atomic step. */
    fun compareAndStore(key: String, expected: Value, value: Value): Boolean = synchronized(lock) {
        val current = cells[key] ?: Value.Null
        if (current == expected) {
            cells[key] = value
            true
        } else {
            false
        }
    }

    fun clearAll() = synchronized(lock) { cells.clear() }
}

package dev.pleiades.masamune.flow.catalog

import dev.pleiades.masamune.flow.model.BlockCategory
import dev.pleiades.masamune.flow.model.BlockSpec

/**
 * The only blocks that cross the fiber boundary.
 *
 * Every other block in the catalog reads and writes one fiber's own variable frame, which is
 * what makes fibers cheap and independently serializable. These seven are the sanctioned
 * exceptions: atomics for shared counters and flags, and the give/take pair for a bounded
 * hand-off queue. That the exceptions are enumerable in seven rows is the reason the frame can
 * stay unsynchronized everywhere else.
 *
 * Blocks are listed in Automate's own palette order, which is the order this catalog and the
 * palette both render. See `docs/donors/RE-automate.md`.
 */
internal val CONCURRENCY_BLOCKS: List<BlockSpec> = category(BlockCategory.CONCURRENCY) {
    action(
        "atomic_add", "Atomic add & load",
        "Adds the delta value to the stored value and assign the sum to the variable.",
        args = listOf(
            any("delta", "Delta value", "zero"),
        ),
    )
    decision(
        "atomic_cas", "Atomic compare & store",
        "Stores the value of variable if the stored value equal the expected value.",
        args = listOf(
            any("expect", "Expected value", "null"),
        ),
    )
    action(
        "atomic_clear_all", "Atomic clear all",
        "Clears all values stored for this flow.",
    )
    action(
        "atomic_load", "Atomic load",
        "Assigns the stored value to variable.",
    )
    action(
        "atomic_store", "Atomic store",
        "Stores the value of variable.",
    )
    action(
        "variables_give", "Variables give",
        "Gives variable values to another fiber. This block in combination with a Variables " +
            "take are used for communication between fibers in a concurrency safe way.",
        args = listOf(
            text("takerFiberUri", "Taker fiber URI"),
        ),
    )
    decision(
        "variables_take", "Variables take",
        "Takes variable values given by other fibers, as a concurrency-safe FIFO queue.",
        proceed = AWAIT,
        outputs = listOf(
            out("giverFiberUri", "Giver fiber URI"),
        ),
    )
}

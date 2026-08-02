package dev.pleiades.masamune.flow.runtime.impl

import dev.pleiades.masamune.flow.expr.Value
import dev.pleiades.masamune.flow.model.FlowNode
import dev.pleiades.masamune.flow.model.Port
import dev.pleiades.masamune.flow.runtime.BlockImpl
import dev.pleiades.masamune.flow.runtime.Fiber
import dev.pleiades.masamune.flow.runtime.Outcome

/**
 * The atomics — the concurrency blocks that touch the flow-wide [AtomicStore] rather than one
 * fiber's frame. Each is keyed by its bound `variable`: the same name the value is stored from or
 * loaded into. They are the cheap half of the concurrency palette; the give/take hand-off pair
 * needs an inter-fiber, URI-addressed await queue that this build does not have, and stays
 * unimplemented so the scheduler's honest gate reports it rather than a faked success.
 */

/** `Atomic store` — copy the fiber's variable into the shared cell. */
internal class AtomicStoreBlock(private val store: AtomicStore) : BlockImpl {
    override val specId = "atomic_store"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val name = node.targetVariable()
            ?: return Outcome.Fail("Atomic store has no variable bound (output 'variable').")
        store.store(name, fiber.readVariable(name))
        return Outcome.Proceed(Port.OK)
    }
}

/** `Atomic load` — copy the shared cell into the fiber's variable. */
internal class AtomicLoadBlock(private val store: AtomicStore) : BlockImpl {
    override val specId = "atomic_load"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val name = node.targetVariable()
            ?: return Outcome.Fail("Atomic load has no variable bound (output 'variable').")
        return Outcome.Proceed(Port.OK, mapOf(name to store.load(name)))
    }
}

/** `Atomic add & load` — add the delta to the shared cell and take the sum into the variable, atomically. */
internal class AtomicAddBlock(private val store: AtomicStore) : BlockImpl {
    override val specId = "atomic_add"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val name = node.targetVariable()
            ?: return Outcome.Fail("Atomic add has no variable bound (output 'variable').")
        val sum = store.addAndGet(name, args["delta"].asNumOrNull() ?: 0.0)
        return Outcome.Proceed(Port.OK, mapOf(name to sum))
    }
}

/**
 * `Atomic compare & store` — store the fiber's variable into the shared cell only if the cell
 * currently equals the expected value. YES when it did, NO when it did not — the compare-and-swap
 * that lets two fibers contend for one flag without a lost update.
 */
internal class AtomicCasBlock(private val store: AtomicStore) : BlockImpl {
    override val specId = "atomic_cas"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val name = node.targetVariable()
            ?: return Outcome.Fail("Atomic compare & store has no variable bound (output 'variable').")
        val stored = store.compareAndStore(name, args["expect"] ?: Value.Null, fiber.readVariable(name))
        return Outcome.Proceed(if (stored) Port.YES else Port.NO)
    }
}

/** `Atomic clear all` — drop every shared cell for this flow. No variable to bind. */
internal class AtomicClearAllBlock(private val store: AtomicStore) : BlockImpl {
    override val specId = "atomic_clear_all"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        store.clearAll()
        return Outcome.Proceed(Port.OK)
    }
}

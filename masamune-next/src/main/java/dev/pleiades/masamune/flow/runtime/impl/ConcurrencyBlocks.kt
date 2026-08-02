package dev.pleiades.masamune.flow.runtime.impl

import dev.pleiades.masamune.flow.expr.Value
import dev.pleiades.masamune.flow.model.FlowNode
import dev.pleiades.masamune.flow.model.Port
import dev.pleiades.masamune.flow.runtime.BlockImpl
import dev.pleiades.masamune.flow.runtime.Fiber
import dev.pleiades.masamune.flow.runtime.Outcome
import dev.pleiades.masamune.flow.runtime.Waker

/**
 * The concurrency blocks that touch flow-wide shared state rather than one fiber's frame: the
 * atomics (keyed cells, over [AtomicStore]) and the give/take hand-off pair (per-taker FIFO
 * mailboxes, over [HandoffStore]).
 *
 * The atomics are keyed by their bound `variable` — the same name the value is stored from or
 * loaded into. The give/take pair is the inter-fiber, URI-addressed await queue: it became
 * implementable once the [Waker] contract could deliver a value **on resume** (a `take` that parks
 * empty receives what a later `give` hands it), which is the general await-with-result capability
 * the scheduler now carries.
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

/**
 * `Variables give` — hand this fiber's variables to another fiber's mailbox.
 *
 * The destination is the `takerFiberUri` argument, which names the taker fiber (a fiber id — the
 * URI the fork blocks bind as `varChildFiberURI`). What transfers is this fiber's *user* variables:
 * every key that is not runtime control state (control keys are `$`-prefixed — the call/catch
 * stacks and loop cursors — and stay private to the giver). The block always proceeds OK; a give to
 * a taker that has not yet run, or never takes, simply waits in that taker's FIFO queue, which is
 * the point of a mailbox. A blank `takerFiberUri` has nowhere to deliver and is a visible failure.
 */
internal class VariablesGiveBlock(private val store: HandoffStore) : BlockImpl {
    override val specId = "variables_give"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val takerId = args["takerFiberUri"].asTextOrNull()?.takeIf { it.isNotBlank() }
            ?: return Outcome.Fail("Variables give has no takerFiberUri to deliver to.")
        val transferable = fiber.variables.filterKeys { !it.startsWith("\$") }
        store.give(takerId, HandoffStore.Handoff(fiber.id, transferable))
        return Outcome.Proceed(Port.OK)
    }
}

/**
 * `Variables take` — receive variables another fiber gave, FIFO. Binds the giver's variables into
 * this fiber's frame and reports who gave them on `giverFiberUri`.
 *
 * If a hand-off is already queued for this fiber it is taken at once; if none is, the fiber parks
 * (the catalog's AWAIT) and resumes the moment a `give` addressed to it arrives — carrying the
 * given values *on resume*, which the plain [Outcome.Await] could not do until the [Waker] contract
 * gained a writes map. The check-or-park is atomic in [HandoffStore.arm], so a `give` racing the
 * park is never lost.
 */
internal class VariablesTakeBlock(private val store: HandoffStore) : BlockImpl {
    override val specId = "variables_take"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val giverUriOut = node.outputs["giverFiberUri"]?.takeIf { it.isNotBlank() }
        return Outcome.Await("Awaiting Variables give", HandoffWaker(store, fiber.id, giverUriOut))
    }
}

/**
 * The [Waker] behind `Variables take`. On [start] it arms the mailbox: [HandoffStore.arm] either
 * delivers a queued hand-off immediately or parks this resume until a `give` fires it. Either way
 * the resume carries the giver's values plus the giver URI on the bound output — the await-with-
 * result the extended contract allows. It holds no live continuation, only the store registration,
 * so a parked take survives serialization exactly as [TimerWaker] does; [cancel] unarms it.
 */
private class HandoffWaker(
    private val store: HandoffStore,
    private val takerId: String,
    private val giverUriOut: String?,
) : Waker {
    override fun start(resume: (Port, Map<String, Value>) -> Unit) {
        store.arm(takerId) { handoff ->
            val writes = LinkedHashMap<String, Value>(handoff.values)
            giverUriOut?.let { writes[it] = Value.Text(handoff.giverUri) }
            resume(Port.OK, writes)
        }
    }

    override fun cancel() {
        store.unarm(takerId)
    }
}

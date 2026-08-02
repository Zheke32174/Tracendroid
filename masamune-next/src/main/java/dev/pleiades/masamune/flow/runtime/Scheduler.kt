package dev.pleiades.masamune.flow.runtime

import dev.pleiades.masamune.flow.expr.Value
import dev.pleiades.masamune.flow.model.BlockCategory
import dev.pleiades.masamune.flow.model.BlockSpec
import dev.pleiades.masamune.flow.model.FlowGraph
import dev.pleiades.masamune.flow.model.Port
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The cooperative scheduler that runs fibers over a flow.
 *
 * Automate's scheduling model is the specification here, and two of its properties are the
 * whole reason this is hand-written rather than a naive "one coroutine per fiber":
 *
 *  1. **Cooperative, not thread-per-fiber.** Many fibers advance on a single dispatch loop.
 *     A fiber that must block for real work (disk, network, a subprocess in the uid-2000
 *     prefix) parks in [FiberStatus.AWAITING] via an [Outcome.Await] and yields the loop; it
 *     does not hold a thread while it waits. Threads are spent only inside a block's own
 *     suspend work, never on idle fibers.
 *  2. **Persist at every block boundary.** After each step the fiber's full state is handed to
 *     [FiberStore]. If the process dies, restart reloads every non-terminal fiber and resumes
 *     it at its last block. This is why a fiber is immutable data and why the scheduler is the
 *     single writer — persistence has one well-defined point to hook.
 *
 * The scheduler is the *only* mutator of fiber state. A block returns an [Outcome]; the
 * scheduler applies it, persists, and routes. Nothing else advances a fiber.
 */
class Scheduler(
    private val graph: FlowGraph,
    private val specs: (String) -> BlockSpec?,
    private val impls: (String) -> BlockImpl?,
    private val resolver: ArgResolver,
    private val store: FiberStore,
    private val scope: CoroutineScope,
    /**
     * Consulted before each block runs. A halted flow parks: the loop stops advancing fibers
     * but does not tear them down, so resume picks up exactly where halt caught them. This is
     * the seam the app's `HaltController` and the AI operator's stop button bind to — halt is a
     * scheduler property, not a flag each block remembers to check.
     */
    private val isHalted: () -> Boolean = { false },
) {
    private val mutex = Mutex()
    private val fibers = LinkedHashMap<String, Fiber>()
    private val wakers = HashMap<String, Waker>()

    /** Events that re-drive the loop: a fiber became ready, or a waker fired. */
    private val ready = Channel<String>(Channel.UNLIMITED)

    /** Load any fibers persisted from a previous process and re-arm the loop for each. */
    suspend fun restore() = mutex.withLock {
        for (f in store.loadAll(graph.id)) {
            fibers[f.id] = f
            if (!f.status.isTerminal) ready.trySend(f.id)
        }
    }

    /**
     * Create a new fiber and schedule it. Used by a manual start, the `Flow start` block, and
     * (with [seedVariables]) by [fork].
     */
    suspend fun start(fiberId: String, at: String? = null, seedVariables: Map<String, Value> = emptyMap()): Fiber =
        mutex.withLock {
            val begin = at ?: startingNode()
            val f = Fiber(
                id = fiberId,
                flowId = graph.id,
                currentNode = begin,
                variables = seedVariables,
                status = if (begin == null) FiberStatus.STOPPED else FiberStatus.READY,
            )
            fibers[f.id] = f
            store.save(f)
            if (!f.status.isTerminal) ready.trySend(f.id)
            f
        }

    /**
     * Clone a running fiber, ported from Automate's `Fork`. The child gets a **deep copy** of
     * the parent's variable frame — [Value] being immutable makes the copy structural, but the
     * map is fresh so a later write in one fiber never leaks into the other.
     */
    suspend fun fork(parent: Fiber, childId: String, childAt: String): Fiber =
        start(childId, childAt, seedVariables = parent.variables.toMap())

    /**
     * Run until every fiber is terminal. Each iteration takes one ready fiber and advances it a
     * single block, persisting at the boundary. The loop exits when no fiber is running,
     * awaiting, or ready — i.e. the flow is stopped, which Automate defines as *all* fibers
     * stopped, not the first.
     */
    suspend fun run() {
        while (true) {
            if (allTerminal()) return
            val id = ready.receive()
            if (isHalted()) {
                // Re-queue and stop advancing. The fiber stays exactly where it is, persisted,
                // so an un-halt (or a restart) resumes it at this block rather than past it.
                ready.trySend(id)
                return
            }
            step(id)
        }
    }

    private suspend fun step(id: String) {
        val fiber = mutex.withLock { fibers[id] } ?: return
        if (fiber.status.isTerminal) return
        val node = fiber.currentNode?.let(graph::node)
            ?: return terminate(id, fiber.stopped())      // nowhere to be ⇒ stopped
        val spec = specs(node.specId)
            ?: return terminate(id, fiber.errored("unknown block '${node.specId}'"))

        val args = resolver.resolve(spec, node, fiber).getOrElse {
            return terminate(id, fiber.errored(it.message ?: "argument evaluation failed"))
        }
        val impl = impls(node.specId)
            ?: return terminate(id, fiber.errored(gateReason(spec)))

        val outcome = runCatching { impl.run(fiber.copy(status = FiberStatus.RUNNING), node, args) }
            .getOrElse { return terminate(id, fiber.errored(it.message ?: "block threw")) }

        apply(id, fiber, node.id, outcome)
    }

    /** Apply a block's [Outcome]: bind writes, then route / park / stop / fail. */
    private suspend fun apply(id: String, fiber: Fiber, nodeId: String, outcome: Outcome) {
        val written = outcome.writes.entries.fold(fiber) { f, (k, v) -> f.withVariable(k, v) }
        when (outcome) {
            is Outcome.Proceed -> {
                val nextNode = graph.next(nodeId, outcome.port)
                if (nextNode == null) {
                    terminate(id, written.stopped())       // unconnected port ⇒ normal stop
                } else {
                    val moved = written.moveTo(nextNode, outcome.port)
                    persist(id, moved)
                    ready.trySend(id)
                }
            }
            is Outcome.Await -> park(id, written, outcome)
            is Outcome.Stop -> terminate(id, written.stopped())
            is Outcome.Fail -> terminate(id, written.errored(outcome.message))
        }
    }

    private suspend fun park(id: String, fiber: Fiber, await: Outcome.Await) {
        val parked = fiber.copy(status = FiberStatus.AWAITING, awaitReason = await.reason)
        persist(id, parked)
        wakers[id] = await.wake
        await.wake.start { port ->
            // Fired: bind nothing new, move on the given port. Re-enters the loop via `ready`.
            scope.launch {
                mutex.withLock { wakers.remove(id) }
                val current = mutex.withLock { fibers[id] } ?: return@launch
                val nextNode = graph.next(current.currentNode ?: return@launch, port)
                if (nextNode == null) {
                    terminate(id, current.stopped())
                } else {
                    persist(id, current.moveTo(nextNode, port))
                    ready.trySend(id)
                }
            }
        }
    }

    private suspend fun persist(id: String, fiber: Fiber) = mutex.withLock {
        fibers[id] = fiber
        store.save(fiber)
    }

    private suspend fun terminate(id: String, fiber: Fiber) {
        mutex.withLock {
            wakers.remove(id)?.cancel()
            fibers[id] = fiber
            store.save(fiber)
        }
        // Nudge the loop so it can re-check the all-terminal condition.
        ready.trySend(id)
    }

    /**
     * The loop's exit condition: every fiber is terminal.
     *
     * This deliberately does *not* inspect the ready channel (its `isEmpty` is an experimental,
     * removed-in-later-versions API and racy besides). Correctness rests on a different
     * invariant instead: [terminate] always nudges the channel, so after the last fiber ends
     * the loop is guaranteed one more wakeup, re-checks here, sees all-terminal, and returns.
     * A flow whose only non-terminal fibers are [FiberStatus.AWAITING] is *not* done — the loop
     * blocks in `receive()` until a waker fires, which is the correct "waiting on a condition"
     * behaviour, not a hang.
     */
    private suspend fun allTerminal(): Boolean = mutex.withLock {
        fibers.isNotEmpty() && fibers.values.all { it.status.isTerminal }
    }

    /**
     * Where a manually-started fiber begins: the first `Flow` block that can originate a fiber,
     * else the first node with no incoming edge. A flow with neither has nowhere to start, and
     * the fiber is born stopped rather than pointed at an arbitrary block.
     */
    private fun startingNode(): String? {
        val targeted = graph.connections.map { it.toNode }.toSet()
        val flowStart = graph.nodes.firstOrNull {
            specs(it.specId)?.category == BlockCategory.FLOW && it.id !in targeted
        }
        return (flowStart ?: graph.nodes.firstOrNull { it.id !in targeted })?.id
    }

    /**
     * The message shown when a block has a spec but no runnable impl. In this build that means
     * the block is gated — its payload or permission is absent — so the honest thing is to name
     * the requirement rather than say "not implemented". A gated block that runs anyway would be
     * the silent-no-op failure the whole campaign exists to remove.
     */
    private fun gateReason(spec: BlockSpec): String =
        if (spec.requires.isEmpty()) {
            "block '${spec.id}' has no implementation in this build"
        } else {
            "block '${spec.name}' needs " +
                spec.requires.joinToString(", ") { it.label } +
                ", which is not available in this build"
        }

    fun snapshot(): List<Fiber> = fibers.values.toList()
}

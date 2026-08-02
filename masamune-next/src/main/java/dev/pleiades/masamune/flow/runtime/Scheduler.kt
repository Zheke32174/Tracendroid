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
    fiberLifecycleHolder: FiberLifecycleHolder? = null,
) : FiberLifecycle {

    init {
        // Serve `Fiber stopped`: the block reaches this scheduler through the holder its registry
        // handed it. Set once, at construction, before any fiber runs.
        fiberLifecycleHolder?.delegate = this
    }

    /** Callbacks waiting on a fiber id to terminate, guarded by [mutex]. Fired once, then dropped. */
    private val stopWaiters = HashMap<String, MutableList<() -> Unit>>()

    override fun awaitStopped(fiberId: String, onStopped: () -> Unit) {
        scope.launch {
            val fireNow = mutex.withLock {
                val f = fibers[fiberId]
                if (f == null || f.status.isTerminal) {
                    true                                   // unknown or already terminal ⇒ stopped
                } else {
                    stopWaiters.getOrPut(fiberId) { mutableListOf() }.add(onStopped)
                    false
                }
            }
            if (fireNow) onStopped()
        }
    }

    override fun cancelAwait(fiberId: String, onStopped: () -> Unit) {
        scope.launch {
            mutex.withLock {
                stopWaiters[fiberId]?.let { list ->
                    list.remove(onStopped)
                    if (list.isEmpty()) stopWaiters.remove(fiberId)
                }
            }
        }
    }

    /** Take (under [mutex]) the waiters registered for [fiberId], to fire outside the lock. */
    private fun drainStopWaiters(fiberId: String): List<() -> Unit> =
        stopWaiters.remove(fiberId).orEmpty()
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
            // A bad argument is a failure like any other: give an installed `Failure catch` its
            // chance before the fiber dies, so a transient expression error can be retried too.
            return failFiber(id, fiber, node.id, it.message ?: "argument evaluation failed", "argument")
        }
        val impl = impls(node.specId)
            ?: return terminate(id, fiber.errored(gateReason(spec)))

        val outcome = runCatching { impl.run(fiber.copy(status = FiberStatus.RUNNING), node, args) }
            .getOrElse { return failFiber(id, fiber, node.id, it.message ?: "block threw", "exception") }

        apply(id, fiber, node.id, outcome)
    }

    /** Apply a block's [Outcome]: bind writes, then route / park / stop / fail / jump / fork. */
    private suspend fun apply(id: String, fiber: Fiber, nodeId: String, outcome: Outcome) {
        val written = outcome.writes.entries.fold(fiber) { f, (k, v) -> f.withVariable(k, v) }
        when (outcome) {
            is Outcome.Proceed -> advance(id, written, nodeId, outcome.port)
            is Outcome.Await -> park(id, written, outcome)
            is Outcome.Stop -> terminate(id, written.stopped())
            is Outcome.Fail -> failFiber(id, written, nodeId, outcome.message, "failure")

            // Direct transfer to a node the graph does not connect us to (Go to / Subroutine).
            is Outcome.Jump -> {
                persist(id, written.copy(currentNode = outcome.target, enteredBy = null, status = FiberStatus.READY, awaitReason = null))
                ready.trySend(id)
            }

            is Outcome.Fork -> {
                // The child begins at the NEW/YES dot with a deep copy of the (already-written)
                // parent frame. No child if that dot is unwired — the parent just continues.
                graph.next(nodeId, Port.YES)?.let { childAt ->
                    spawnChild(outcome.childId, childAt, written.variables)
                }
                advance(id, written, nodeId, Port.NO)      // parent walks OK/NO
            }

            is Outcome.StopFlow -> {
                persist(id, written)                       // keep this block's writes before the sweep
                stopAllFibers()
            }

            is Outcome.StopFiber -> {
                stopNamedFiber(outcome.fiberId)
                advance(id, written, nodeId, Port.OK)      // the stopper itself continues
            }
        }
    }

    /**
     * Route a fiber out of [nodeId] by [port] — the one place normal routing and subroutine
     * return meet.
     *
     * A connected port moves the fiber. An **unconnected** port is where the two readings diverge:
     * a top-level fiber (empty call stack) stops normally, exactly as before; a fiber inside a
     * subroutine (a return address waiting on the stack) *returns* — it pops and resumes at the
     * caller. This is the whole of the call/return mechanism: entering a `Subroutine` pushes the
     * caller's continuation, and the body reaching its own unconnected end is what pops it. An
     * explicit [Outcome.Stop] (`Flow stop` / `Fiber stop`) is unaffected and still ends the fiber.
     */
    private suspend fun advance(id: String, fiber: Fiber, nodeId: String, port: Port) {
        val nextNode = graph.next(nodeId, port)
        if (nextNode != null) {
            persist(id, fiber.moveTo(nextNode, port))
            ready.trySend(id)
            return
        }
        val stack = fiber.callStack()
        if (stack.isEmpty()) {
            terminate(id, fiber.stopped())                 // top level ⇒ normal stop
            return
        }
        val returnTo = stack.last()
        val popped = fiber.withCallStack(stack.dropLast(1))
        if (returnTo == RETURN_STOP) {
            terminate(id, popped.stopped())                // caller had nowhere to go
        } else {
            persist(id, popped.copy(currentNode = returnTo, enteredBy = null, status = FiberStatus.READY, awaitReason = null))
            ready.trySend(id)
        }
    }

    /**
     * A block failed. Give the innermost [Outcome]-facing `Failure catch` its turn before the
     * fiber dies: bump that frame's retry count, and if it is within the limit, route the fiber
     * back to the catch block carrying the failure detail — the catch block then leaves by its
     * NO/retry dot. Past the limit the frame is spent, popped, and the failure propagates as a
     * real error. With no catch frame at all this is exactly the old behaviour: the fiber errors.
     */
    private suspend fun failFiber(id: String, fiber: Fiber, nodeId: String, message: String, type: String) {
        val frames = fiber.catchFrames()
        if (frames.isEmpty()) {
            terminate(id, fiber.errored(message))
            return
        }
        val top = frames.last()
        val nextCount = top.count + 1
        if (nextCount > top.limit) {
            // Handler exhausted: drop it and let the failure stand.
            terminate(id, fiber.withCatchFrames(frames.dropLast(1)).errored(message))
            return
        }
        val bumped = frames.dropLast(1) + top.copy(count = nextCount)
        val pending = encodePendingFailure(top.node, nextCount, type, message, nodeId)
        val routed = fiber.withCatchFrames(bumped).withVariable(CATCH_PENDING, pending)
        persist(id, routed.copy(currentNode = top.node, enteredBy = null, status = FiberStatus.READY, awaitReason = null))
        ready.trySend(id)
    }

    /** Create and schedule a fork child. Same-flow, deep-copied frame — [Value] immutability makes the copy structural while the fresh map keeps the two fibers' writes apart. */
    private suspend fun spawnChild(childId: String, at: String, frame: Map<String, Value>) = mutex.withLock {
        val child = Fiber(
            id = childId,
            flowId = graph.id,
            currentNode = at,
            variables = frame.toMap(),
            status = FiberStatus.READY,
        )
        fibers[childId] = child
        store.save(child)
        ready.trySend(childId)
    }

    /** Stop every non-terminal fiber of this flow (`Flow stop`). Each ends as a normal stop; parked wakers are torn down. */
    private suspend fun stopAllFibers() {
        val fired = mutex.withLock {
            val out = ArrayList<() -> Unit>()
            for (fid in fibers.keys.toList()) {
                val f = fibers[fid] ?: continue
                if (f.status.isTerminal) continue
                wakers.remove(fid)?.cancel()
                val stopped = f.stopped()
                fibers[fid] = stopped
                store.save(stopped)
                out += drainStopWaiters(fid)
            }
            out
        }
        fired.forEach { it() }
        ready.trySend("")   // nudge the loop to re-check all-terminal
    }

    /** Stop one fiber by id if it is present and still running (`Fiber stop`). Unknown or already-terminal ids are a no-op. */
    private suspend fun stopNamedFiber(fiberId: String) {
        val fired = mutex.withLock {
            val f = fibers[fiberId] ?: return@withLock emptyList<() -> Unit>()
            if (f.status.isTerminal) return@withLock emptyList<() -> Unit>()
            wakers.remove(fiberId)?.cancel()
            val stopped = f.stopped()
            fibers[fiberId] = stopped
            store.save(stopped)
            drainStopWaiters(fiberId)
        }
        fired.forEach { it() }
        ready.trySend(fiberId)
    }

    private suspend fun park(id: String, fiber: Fiber, await: Outcome.Await) {
        val parked = fiber.copy(status = FiberStatus.AWAITING, awaitReason = await.reason)
        persist(id, parked)
        wakers[id] = await.wake
        await.wake.start { port, writes ->
            // Fired: bind any values the wait produced (empty for a bare completion), then move on
            // the given port. Re-enters the loop via `ready`.
            scope.launch {
                mutex.withLock { wakers.remove(id) }
                val current = mutex.withLock { fibers[id] } ?: return@launch
                val bound = writes.entries.fold(current) { f, (k, v) -> f.withVariable(k, v) }
                val nextNode = graph.next(bound.currentNode ?: return@launch, port)
                if (nextNode == null) {
                    terminate(id, bound.stopped())
                } else {
                    persist(id, bound.moveTo(nextNode, port))
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
        val fired = mutex.withLock {
            wakers.remove(id)?.cancel()
            fibers[id] = fiber
            store.save(fiber)
            drainStopWaiters(id)
        }
        fired.forEach { it() }        // wake any `Fiber stopped` awaiting this id
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

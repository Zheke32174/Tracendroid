package dev.pleiades.masamune.flow.runtime.impl

import dev.pleiades.masamune.flow.expr.Value
import dev.pleiades.masamune.flow.model.FlowGraph
import dev.pleiades.masamune.flow.model.FlowNode
import dev.pleiades.masamune.flow.model.Port
import dev.pleiades.masamune.flow.runtime.BlockImpl
import dev.pleiades.masamune.flow.runtime.CALL_STACK
import dev.pleiades.masamune.flow.runtime.CATCH_PENDING
import dev.pleiades.masamune.flow.runtime.CATCH_STACK
import dev.pleiades.masamune.flow.runtime.CatchFrame
import dev.pleiades.masamune.flow.runtime.Fiber
import dev.pleiades.masamune.flow.runtime.FiberLifecycle
import dev.pleiades.masamune.flow.runtime.Outcome
import dev.pleiades.masamune.flow.runtime.Waker
import dev.pleiades.masamune.flow.runtime.RETURN_STOP
import dev.pleiades.masamune.flow.runtime.callStack
import dev.pleiades.masamune.flow.runtime.catchFrames
import dev.pleiades.masamune.flow.runtime.encodeCallStack
import dev.pleiades.masamune.flow.runtime.encodeCatchFrames
import java.util.UUID

/**
 * The Flow category's runnable blocks — the graph's own control structure, made to actually move
 * a fiber.
 *
 * These are the blocks the whole plane leans on: the two shapes carry no meaning until something
 * forks, jumps, calls and returns. Each one is faithful to the mapping stated in
 * `catalog/CatalogFlow.kt` — where Automate's FAIL/NEW/DO dots are folded onto YES/NO — and each
 * one keeps the scheduler the single mutator: a block hands back an [Outcome] describing the move
 * and never touches another fiber itself.
 *
 * The blocks that need graph shape (which node a matched `Label` is, which node a `Subroutine`
 * body starts at) take the [FlowGraph] at construction. That is read-only knowledge available
 * before any fiber runs, so closing over it costs nothing and keeps the impl a pure function of
 * (fiber, node, args) at run time.
 */

/** `Flow beginning` — a fiber's origin. Emits its identity outputs and proceeds; it never pauses. */
internal class FlowBeginningBlock : BlockImpl {
    override val specId = "flow_beginning"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val writes = LinkedHashMap<String, Value>()
        // No inbound payload exists in a manually-started fiber, so the payload output is Null —
        // the honest "nothing was handed in", never a fabricated value.
        node.outputs["varPayload"]?.let { writes[it] = Value.Null }
        node.outputs["varFiberUri"]?.let { writes[it] = Value.Text(fiber.id) }
        return Outcome.Proceed(Port.OK, writes)
    }
}

/** `Label` — a pure landing pad for `Go to`. It does nothing but pass control on through OK. */
internal class LabelBlock : BlockImpl {
    override val specId = "label"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome =
        Outcome.Proceed(Port.OK)
}

/**
 * `Go to` — transfer control to the `Label` whose value matches [labelValue].
 *
 * A `Label` carries no configurable value field in the catalog, so its user-facing value is its
 * node **note** (the on-canvas comment) — the only per-node string a bare `Label` can hold. `Go
 * to` matches [labelValue] against that. A match jumps straight to the label node (leaving
 * "through the Label", as the donor puts it, not through a port); no match leaves by OK, which is
 * exactly what the catalog says the OK dot means for this block.
 */
internal class GotoBlock(private val graph: FlowGraph) : BlockImpl {
    override val specId = "goto"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val wanted = (args["labelValue"] ?: Value.Null).asText()
        val target = graph.nodes.firstOrNull { it.specId == "label" && (it.note ?: "") == wanted }
        return if (target != null) Outcome.Jump(target.id) else Outcome.Proceed(Port.OK)
    }
}

/**
 * `Fork` — start a new fiber cloning this one. NEW⇒YES is the child's path, OK⇒NO the parent's.
 *
 * The impl mints the child id itself so it can publish it on `varChildFiberURI` in the same
 * breath, and hands the scheduler an [Outcome.Fork] to do the actual spawn-at-YES /
 * advance-parent-along-NO. A fiber's URI in this build *is* its id, which is what lets a later
 * `Fiber stop` address this child by the very string written here.
 */
internal class ForkBlock : BlockImpl {
    override val specId = "fork"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val childId = "${fiber.id}-fork-${UUID.randomUUID()}"
        val writes = LinkedHashMap<String, Value>()
        node.outputs["varChildFiberURI"]?.let { writes[it] = Value.Text(childId) }
        node.outputs["varParentFiberURI"]?.let { writes[it] = Value.Text(fiber.id) }
        return Outcome.Fork(childId, writes)
    }
}

/**
 * `Subroutine` — call a body region in this same fiber and resume after it returns.
 *
 * NEW⇒YES is the callee body, OK⇒NO the caller's continuation. The call is same-fiber with a
 * return-address stack (the task's explicit model), not a spawned fiber: entering pushes the NO
 * target as the return address and jumps to the YES body; the body running off its own
 * unconnected end pops that address and resumes the caller (see `Scheduler.advance`). A body with
 * no NO target pushes [RETURN_STOP], so returning from a call the caller could not continue past
 * stops the fiber — the correct end, not a dangling jump. A `Subroutine` with no YES body is a
 * no-op that simply takes NO.
 *
 * The return address is pushed as an ordinary [Outcome.writes] entry, so the scheduler stays the
 * single writer of fiber state and the push serializes with everything else at the boundary.
 */
internal class SubroutineBlock(private val graph: FlowGraph) : BlockImpl {
    override val specId = "subroutine"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val body = graph.next(node.id, Port.YES) ?: return Outcome.Proceed(Port.NO)
        val returnTo = graph.next(node.id, Port.NO) ?: RETURN_STOP
        val stack = fiber.callStack() + returnTo
        return Outcome.Jump(body, writes = mapOf(CALL_STACK to encodeCallStack(stack)))
    }
}

/**
 * `Failure catch` — install a per-fiber handler for a failure in a subsequent block. OK⇒YES is
 * the normal path; FAIL⇒NO is the retry path.
 *
 * On the **normal** first visit it pushes a catch frame (this node, the retry limit, zero retries
 * spent) and leaves by YES, its outputs zeroed — nothing has failed yet. When a later block does
 * fail, the scheduler routes the fiber back here carrying the failure detail; this impl sees that
 * detail addressed to its own node, publishes it on the output variables (retry count, failing
 * block id, type, message), clears it, and leaves by NO so the graph can loop back and retry. The
 * scheduler enforces the limit: past it, the failure propagates as a real error instead of
 * returning here. The frame persists for the rest of the fiber, approximating Automate's "any
 * subsequent block"; a fresh normal entry of a *different* catch nests a new frame on top.
 */
internal class FailureCatchBlock : BlockImpl {
    override val specId = "failure_catch"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val pending = (fiber.readVariable(CATCH_PENDING) as? Value.DictV)?.entries
        val addressedHere = (pending?.get("node") as? Value.Text)?.value == node.id

        if (pending != null && addressedHere) {
            // Retry re-entry: surface the caught failure on the bound outputs and take the NO dot.
            val writes = LinkedHashMap<String, Value>()
            node.outputs["varRetryCount"]?.let { writes[it] = pending["count"] ?: Value.Num(0.0) }
            node.outputs["varFailureStatementId"]?.let { writes[it] = pending["statementId"] ?: Value.Null }
            node.outputs["varFailureType"]?.let { writes[it] = pending["type"] ?: Value.Null }
            node.outputs["varFailureMessage"]?.let { writes[it] = pending["message"] ?: Value.Null }
            writes[CATCH_PENDING] = Value.Null       // consumed
            return Outcome.Proceed(Port.NO, writes)
        }

        // Normal entry: arm the handler and take the YES dot with outputs zeroed.
        val limit = (args["retryLimit"].asNumOrNull() ?: 3.0).toInt().coerceAtLeast(0)
        val frames = fiber.catchFrames() + CatchFrame(node.id, limit, 0)
        val writes = LinkedHashMap<String, Value>()
        writes[CATCH_STACK] = encodeCatchFrames(frames)
        node.outputs["varRetryCount"]?.let { writes[it] = Value.Num(0.0) }
        node.outputs["varFailureStatementId"]?.let { writes[it] = Value.Null }
        node.outputs["varFailureType"]?.let { writes[it] = Value.Null }
        node.outputs["varFailureMessage"]?.let { writes[it] = Value.Null }
        return Outcome.Proceed(Port.YES, writes)
    }
}

/**
 * `Flow stop` — stop this flow and all its fibers. With no `flowUri` (the default, "the current
 * flow") it hands the scheduler an [Outcome.StopFlow]. Naming *another* flow is not silently
 * ignored and not faked: there is no multi-flow registry in this build to resolve one, so the
 * block fails with that stated, rather than pretending to stop something it cannot reach.
 */
internal class FlowStopBlock : BlockImpl {
    override val specId = "flow_stop"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val uri = (args["flowUri"] as? Value.Text)?.value
        if (uri != null && uri.isNotBlank() && uri != fiber.flowId) {
            return Outcome.Fail(
                "Flow stop can stop the current flow, but stopping another flow ('$uri') needs a " +
                    "multi-flow registry, which does not exist in this build.",
            )
        }
        return Outcome.StopFlow()
    }
}

/**
 * `Fiber stop` — stop another fiber by URI. The documented default is to stop nothing, so a blank
 * URI simply proceeds. A real URI (a fiber id, e.g. one published by `Fork`) is handed to the
 * scheduler to stop; a URI that no longer names a live fiber is a harmless no-op there.
 */
internal class FiberStopBlock : BlockImpl {
    override val specId = "fiber_stop"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val uri = (args["fiberUri"] as? Value.Text)?.value
        return if (uri.isNullOrBlank()) Outcome.Proceed(Port.OK) else Outcome.StopFiber(uri)
    }
}

/**
 * `Log append` — append a message to the flow's [FlowLog].
 *
 * A missing message is a visible failure rather than an empty line, so a mis-bound input shows
 * itself. The `whenLogging` flag (default "always log") suppresses the line unless logging is on:
 * when set and [FlowLog.loggingEnabled] is false the block proceeds without writing, which is the
 * documented "only when logging" behaviour, not a silent drop. Reads the flag through [asFlag] so a
 * cleared checkbox arriving as `Value.Text("false")` is honoured.
 */
internal class LogAppendBlock(private val log: FlowLog) : BlockImpl {
    override val specId = "log_append"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val message = args["message"].asTextOrNull()
            ?: return Outcome.Fail("Log append needs a message.")
        val onlyWhenLogging = args["whenLogging"].asFlag(default = false)
        if (onlyWhenLogging && !log.loggingEnabled) return Outcome.Proceed(Port.OK)
        log.append(message)
        return Outcome.Proceed(Port.OK)
    }
}

/**
 * `Flow start` — launch another flow through the injected [FlowStarter] host.
 *
 * Honest gate-at-run: with no host wired ([starterProvider] yields null) the block fails by name —
 * this build cannot reach another flow — rather than pretending to start one. A blank `flowUri` has
 * no flow to name and fails; a `flowUri` the host cannot resolve returns null and also fails, so a
 * mistyped reference is visible instead of a silent no-op. On success it binds the started fiber's
 * URI to `varChildFiberURI`, the handle a later `Flow stop` / `Variables give` addresses. The
 * `stopWithParent` option is read from the node's options and passed through to the host.
 */
internal class FlowStartBlock(private val starterProvider: () -> FlowStarter?) : BlockImpl {
    override val specId = "flow_start"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val starter = starterProvider()
            ?: return Outcome.Fail(
                "Flow start cannot reach another flow: no multi-flow host is wired in this build.",
            )
        val flowUri = args["flowUri"].asTextOrNull()?.takeIf { it.isNotBlank() }
            ?: return Outcome.Fail("Flow start needs a flowUri.")
        val payload = args["payload"] ?: Value.Null
        val stopWithParent = Value.Text(node.options["stopWithParent"].orEmpty()).asFlag(default = false)
        val childUri = starter.start(flowUri, payload, stopWithParent, fiber.flowId)
            ?: return Outcome.Fail("Flow start: no flow resolves to '$flowUri'.")
        val writes = LinkedHashMap<String, Value>()
        node.outputs["varChildFiberURI"]?.takeIf { it.isNotBlank() }?.let { writes[it] = Value.Text(childUri) }
        return Outcome.Proceed(Port.OK, writes)
    }
}

/**
 * `Fiber stopped` — await a child fiber's termination, then take YES.
 *
 * The catalog marks it AWAIT: it waits for the fiber named by `fiberUri` to stop, "either manually
 * or by an error". Only the scheduler knows a fiber's lifecycle, so the block reaches it through the
 * injected [FiberLifecycle]; with none wired it fails by name rather than parking on a lifecycle
 * nothing will report. A blank `fiberUri` is a visible failure. An id that is already stopped — or
 * never named a live fiber — resolves at once (it is not running), so the block never hangs on a
 * fiber that cannot stop.
 */
internal class FiberStoppedBlock(private val lifecycleProvider: () -> FiberLifecycle?) : BlockImpl {
    override val specId = "fiber_stopped"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val lifecycle = lifecycleProvider()
            ?: return Outcome.Fail("Fiber stopped cannot observe fibers: no scheduler lifecycle is wired.")
        val uri = args["fiberUri"].asTextOrNull()?.takeIf { it.isNotBlank() }
            ?: return Outcome.Fail("Fiber stopped needs a fiberUri.")
        return Outcome.Await("Awaiting fiber '$uri' to stop", FiberStoppedWaker(lifecycle, uri))
    }
}

/**
 * The [Waker] behind `Fiber stopped`. On [start] it registers with the [FiberLifecycle]; the
 * registration fires — now if the target is already stopped, later when it terminates — and resumes
 * the awaiting fiber by YES. It holds only the registration, not a live continuation, and [cancel]
 * withdraws it if the awaiting fiber is itself stopped first.
 */
private class FiberStoppedWaker(
    private val lifecycle: FiberLifecycle,
    private val fiberUri: String,
) : Waker {
    private var callback: (() -> Unit)? = null

    override fun start(resume: (Port, Map<String, Value>) -> Unit) {
        val c = { resume(Port.YES, emptyMap<String, Value>()) }
        callback = c
        lifecycle.awaitStopped(fiberUri, c)
    }

    override fun cancel() {
        callback?.let { lifecycle.cancelAwait(fiberUri, it) }
    }
}

package dev.pleiades.masamune.flow.runtime

import dev.pleiades.masamune.flow.expr.Value
import dev.pleiades.masamune.flow.model.BlockSpec
import dev.pleiades.masamune.flow.model.FlowNode
import dev.pleiades.masamune.flow.model.Port

/**
 * The seam between the scheduler and the 418 blocks' actual behaviour.
 *
 * The scheduler knows how to walk a graph and persist a fiber; it knows nothing about what
 * "copy a file" or "is the device unlocked" *does*. A [BlockImpl] supplies that, and the
 * runtime holds a registry of them keyed by `BlockSpec.id`. Splitting it this way is what lets
 * the scheduler be pure, testable data-movement while each block's effect lives next to the
 * platform API it wraps.
 */
interface BlockImpl {
    /** The `BlockSpec.id` this implements — the same stable class-name key the catalog uses. */
    val specId: String

    /**
     * Perform this block for [fiber], already positioned on [node].
     *
     * Inputs are resolved by the runner and handed in as [args]; the impl never re-parses an
     * expression. The impl returns an [Outcome] describing where the fiber goes next and what
     * changed in its frame — it does **not** mutate the fiber, because a fiber is immutable
     * persisted data and only the scheduler advances it.
     */
    suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome
}

/**
 * What a block did, as data the scheduler applies.
 *
 * A block cannot reach into the fiber and move it; it can only *describe* the move. This keeps
 * the single writer of fiber state — the scheduler — able to persist at the exact boundary,
 * and keeps block implementations trivially testable (assert on the returned [Outcome], no
 * scheduler needed).
 */
sealed interface Outcome {
    /** Variables to bind before routing. Empty for a block with no outputs. */
    val writes: Map<String, Value>

    /**
     * Leave by [port], writing [writes] first. An action uses [Port.OK]; a decision uses
     * [Port.YES]/[Port.NO]. If the port is unconnected the scheduler stops the fiber — that
     * is one of Automate's four normal terminations, not an error.
     */
    data class Proceed(
        val port: Port,
        override val writes: Map<String, Value> = emptyMap(),
    ) : Outcome

    /**
     * Park the fiber until [wake] signals. The block observed a condition that is not yet true
     * (a `Proceed = when true` decision, an awaited completion). The scheduler moves the fiber
     * to [FiberStatus.AWAITING] and hands the [Waker] the resumption; nothing spins.
     *
     * [reason] is shown in the monitor so a parked fiber explains itself.
     */
    data class Await(
        val reason: String,
        val wake: Waker,
        override val writes: Map<String, Value> = emptyMap(),
    ) : Outcome

    /** End this fiber normally. `Flow stop` / `Fiber stop`, or an action that self-terminates. */
    data class Stop(override val writes: Map<String, Value> = emptyMap()) : Outcome

    /**
     * End this fiber with a cause. Distinct from [Stop] so the monitor can show why, and so a
     * `Failure catch` upstream can route on it. The message is user-facing.
     */
    data class Fail(val message: String, override val writes: Map<String, Value> = emptyMap()) :
        Outcome
}

/**
 * How an [Outcome.Await] resumes. Registered with the scheduler, which invokes [start] once and
 * expects a later callback carrying the port to leave by.
 *
 * Kept as an explicit interface rather than a coroutine so that a parked fiber holds no live
 * continuation — the scheduler can serialize and shut down with fibers parked, and re-arm their
 * wakers on restart from the block spec. A coroutine continuation cannot survive that; a
 * declarative waker can.
 */
interface Waker {
    /** Arm the wait. [resume] is called with the exit port when the condition is met. */
    fun start(resume: (Port) -> Unit)

    /** Tear down the wait — the fiber was stopped or the flow shut down before it fired. */
    fun cancel()
}

/**
 * Resolves a block's declared input arguments into concrete [Value]s for [BlockImpl.run].
 *
 * This is where the expr layer meets the runtime. An argument in expression mode is evaluated
 * against the fiber's variable frame; an argument in constant mode is taken literally; an
 * unspecified argument becomes [Value.Null] and the block applies its documented default.
 *
 * The [ExprEval] seam is narrow on purpose: the runtime depends on "evaluate this text in this
 * scope, or tell me why it failed", not on the parser/evaluator's internals. That decoupling is
 * what lets this file compile against the expr layer by contract rather than by co-development.
 */
class ArgResolver(private val eval: ExprEval) {
    fun resolve(spec: BlockSpec, node: FlowNode, fiber: Fiber): Result<Map<String, Value>> {
        val out = LinkedHashMap<String, Value>(spec.args.size)
        for (arg in spec.args) {
            val raw = node.args[arg.key]
            if (raw.isNullOrEmpty()) {
                // Unspecified: the block applies its own default. Null is the signal for that,
                // never a silent zero/empty-string that a block could mistake for real input.
                out[arg.key] = Value.Null
                continue
            }
            val isExpr = node.argIsExpression[arg.key] ?: false
            out[arg.key] = if (isExpr) {
                eval.eval(raw, fiber.variables).getOrElse {
                    return Result.failure(
                        ExprArgError(node.id, arg.key, it.message ?: "expression failed"),
                    )
                }
            } else {
                Value.Text(raw)
            }
        }
        return Result.success(out)
    }
}

/**
 * The narrow contract the runtime needs from the expression layer. The `expr/Evaluator`
 * satisfies it; the runtime never sees anything wider.
 */
fun interface ExprEval {
    /**
     * Evaluate [expression] with [scope] supplying variable values. A failed evaluation is a
     * [Result.failure] carrying a message naming what went wrong — never a thrown exception, so
     * the runtime turns a bad expression into a clean fiber error rather than a crash.
     */
    fun eval(expression: String, scope: Map<String, Value>): Result<Value>
}

/**
 * A resolvable argument failed to evaluate. Carries enough to point the user at the field.
 *
 * The field is `detail`, not `cause`: `Throwable.cause` already exists as an open `Throwable?`,
 * and a `String` property of that name would shadow it and demand an `override` of the wrong
 * type. Naming it `detail` keeps the human-readable reason distinct from the exception chain.
 */
class ExprArgError(val nodeId: String, val argKey: String, val detail: String) :
    Exception("node $nodeId argument '$argKey': $detail")

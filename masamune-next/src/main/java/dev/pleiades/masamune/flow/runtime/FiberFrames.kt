package dev.pleiades.masamune.flow.runtime

import dev.pleiades.masamune.flow.expr.Value

/**
 * The fiber's *control* state — call stack, catch frames, loop cursors — encoded inside the
 * ordinary variable frame rather than as new [Fiber] fields.
 *
 * This is a deliberate design choice, not a shortcut. Persist-and-resume requires a fiber's
 * entire live state to serialize at every block boundary, and a fiber's variable frame already
 * round-trips through [FiberCodec] because every [Value] kind does. Encoding call/catch/loop
 * state as [Value]s in that same map means it inherits that serialization for free: a subroutine
 * call in flight, a retry counter mid-catch, a `For each` cursor — all survive a process death
 * and resume exactly, with **no** schema change to [Fiber] and **no** new branch in [FiberCodec].
 * A new `Fiber` field would have had to be threaded through both, and every future field would be
 * one more place the resume invariant could crack.
 *
 * ### Why these keys can never collide with a user variable
 * Every key here begins with `$`. The expression lexer accepts an identifier only as
 * `[A-Za-z_][A-Za-z0-9_]*` (see `expr/Lexer.scanWord`), and the editor's output-name field
 * enforces the same rule, so no name a user can type — as an expression variable or as an output
 * binding — can start with, or even contain, `$`. This namespace is therefore private by
 * construction, not by convention.
 *
 * The scheduler is the sole writer of the call stack and catch frames (it pushes on `Subroutine`
 * / `Failure catch` outcomes and pops on return / retry); block impls read them but hand back
 * updates as ordinary [Outcome.writes], keeping the scheduler the single mutator of fiber state.
 * The loop cursor is owned end-to-end by the `For each` impl, which is why it is written through
 * writes and read straight back on the next visit.
 */

/** Return-address stack for [Subroutine]. Each entry is the node to resume at when the callee ends. */
internal const val CALL_STACK = "\$call"

/** Active [FailureCatch] handlers, innermost last. Each frame is `{node, limit, count}`. */
internal const val CATCH_STACK = "\$catch"

/** The failure being routed to a catch handler right now: `{node, count, type, message, statementId}`. */
internal const val CATCH_PENDING = "\$catch_pending"

/** Empty return address = "the caller had nowhere to go", so returning from this call stops the fiber. */
internal const val RETURN_STOP = ""

/** The private cursor key for one `For each` node — namespaced by node id so nested loops never share. */
internal fun forEachKey(nodeId: String): String = "\$foreach:$nodeId"

// ------------------------------------------------------------------- call stack

internal fun Fiber.callStack(): List<String> =
    (readVariable(CALL_STACK) as? Value.ArrayV)?.items?.map { (it as? Value.Text)?.value ?: RETURN_STOP }
        ?: emptyList()

internal fun encodeCallStack(stack: List<String>): Value.ArrayV =
    Value.ArrayV(stack.map { Value.Text(it) })

internal fun Fiber.withCallStack(stack: List<String>): Fiber =
    withVariable(CALL_STACK, encodeCallStack(stack))

// ------------------------------------------------------------------ catch frames

/** One `Failure catch` handler: the block to route a caught failure back to, its retry limit, and how many retries it has already spent. */
internal data class CatchFrame(val node: String, val limit: Int, val count: Int)

internal fun Fiber.catchFrames(): List<CatchFrame> =
    (readVariable(CATCH_STACK) as? Value.ArrayV)?.items?.mapNotNull { entry ->
        val d = (entry as? Value.DictV)?.entries ?: return@mapNotNull null
        val node = (d["node"] as? Value.Text)?.value ?: return@mapNotNull null
        val limit = (d["limit"] as? Value.Num)?.value?.toInt() ?: return@mapNotNull null
        val count = (d["count"] as? Value.Num)?.value?.toInt() ?: 0
        CatchFrame(node, limit, count)
    } ?: emptyList()

internal fun encodeCatchFrames(frames: List<CatchFrame>): Value.ArrayV =
    Value.ArrayV(
        frames.map {
            Value.DictV(
                mapOf(
                    "node" to Value.Text(it.node),
                    "limit" to Value.Num(it.limit.toDouble()),
                    "count" to Value.Num(it.count.toDouble()),
                ),
            )
        },
    )

internal fun Fiber.withCatchFrames(frames: List<CatchFrame>): Fiber =
    withVariable(CATCH_STACK, encodeCatchFrames(frames))

/** The failure detail a caught error carries back to its `Failure catch` block, for its output variables. */
internal fun encodePendingFailure(
    catchNode: String,
    count: Int,
    type: String,
    message: String,
    statementId: String,
): Value.DictV = Value.DictV(
    mapOf(
        "node" to Value.Text(catchNode),
        "count" to Value.Num(count.toDouble()),
        "type" to Value.Text(type),
        "message" to Value.Text(message),
        "statementId" to Value.Text(statementId),
    ),
)

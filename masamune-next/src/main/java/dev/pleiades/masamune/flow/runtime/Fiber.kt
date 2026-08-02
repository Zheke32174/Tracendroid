package dev.pleiades.masamune.flow.runtime

import dev.pleiades.masamune.flow.expr.Value
import dev.pleiades.masamune.flow.model.Port

/**
 * A running instance of a flow, ported from Automate's fiber model (see
 * `docs/donors/RE-automate.md`).
 *
 * The distinction from the flow itself is load-bearing and is the reason this type exists
 * separately from `FlowGraph`: one flow may have **many** fibers running at once, each with
 * its own program counter and its **own** copy of the variables. A fiber is the thing that
 * moves; the flow is the thing it moves through.
 *
 * Every field here is part of the persisted snapshot. Automate resumes a fiber from its last
 * block after a device shutdown, and that is not a feature layered on afterwards — it dictates
 * that a fiber's *entire* live state be serializable at every block boundary. The whole
 * runtime is shaped by that requirement: there is no live object graph, no open handle, no
 * coroutine continuation captured in here. A fiber is data, and the scheduler is what breathes.
 *
 * Persistence is hand-rolled through `org.json` in [FiberCodec], not a serialization plugin.
 * This module deliberately carries no kotlinx-serialization surface (see the module
 * `build.gradle.kts` header) so R8 has nothing reflective to break; every persisted type here
 * follows that same rule and encodes itself explicitly.
 */
data class Fiber(
    val id: String,
    val flowId: String,

    /**
     * The block this fiber is at. Null means "not yet started" — the scheduler will route it
     * to a starting block on first step. It is never null again once running; a fiber that
     * has nowhere to go is [FiberStatus.STOPPED], not a fiber with a null pointer.
     */
    val currentNode: String? = null,

    /**
     * Which incoming path the fiber arrived by. Purely informational for the runtime (a block
     * has one logical `IN`), but preserved because a block implementation may branch on it and
     * because the monitor renders it.
     */
    val enteredBy: Port? = null,

    /**
     * This fiber's private variable frame. A [Fork] deep-copies it; two fibers never share
     * a variable. Values are the expr layer's [Value], so the frame is serializable exactly
     * because [Value] is a closed, serializable hierarchy — a variable that could hold an
     * arbitrary object would break resume, which is why the expr layer's value type is sealed
     * rather than `Any?`.
     */
    val variables: Map<String, Value> = emptyMap(),

    val status: FiberStatus = FiberStatus.READY,

    /**
     * Set only when [status] is [FiberStatus.ERROR]. Carries the human-readable cause — an
     * expression that failed, a block that reported unsupported, a payload that was absent —
     * so the monitor can show *why* a fiber died rather than only that it did. Never holds a
     * stack trace or anything not meant for a user's eyes.
     */
    val errorMessage: String? = null,

    /**
     * The reason a fiber is parked in [FiberStatus.AWAITING], if it is. The scheduler owns the
     * matching wakeup; this string is what the monitor shows ("waiting for Wi-Fi to connect").
     */
    val awaitReason: String? = null,
) {
    fun withVariable(name: String, value: Value): Fiber =
        copy(variables = variables + (name to value))

    fun readVariable(name: String): Value = variables[name] ?: Value.Null

    /** Move to [node], recording the port it was reached by. Clears any await state. */
    fun moveTo(node: String, via: Port): Fiber =
        copy(currentNode = node, enteredBy = via, status = FiberStatus.READY, awaitReason = null)

    fun stopped(): Fiber = copy(status = FiberStatus.STOPPED, awaitReason = null)

    fun errored(message: String): Fiber =
        copy(status = FiberStatus.ERROR, errorMessage = message, awaitReason = null)
}

/**
 * A fiber's lifecycle, and it is deliberately small.
 *
 * A fiber is [READY] to take a step, [RUNNING] while a block's task is in flight, [AWAITING]
 * while parked on a condition or completion it cannot make progress on, or terminal ([STOPPED]
 * or [ERROR]). A flow is considered stopped only when *all* of its fibers are terminal — the
 * scheduler, not any single fiber, decides that.
 */
enum class FiberStatus {
    READY,
    RUNNING,
    AWAITING,
    STOPPED,
    ERROR;

    val isTerminal: Boolean get() = this == STOPPED || this == ERROR
}

package dev.pleiades.masamune.flow.runtime

/**
 * The seam `Fiber stopped` awaits on: "tell me when this fiber has terminated." Only the
 * [Scheduler] knows a fiber's lifecycle, so the scheduler implements this and a block reaches it
 * through a [FiberLifecycleHolder] — the indirection that breaks the construction cycle (the block
 * registry is built before the scheduler that will serve it).
 */
interface FiberLifecycle {
    /**
     * Register interest in [fiberId] having stopped. [onStopped] is invoked once: immediately if the
     * fiber is already terminal or unknown (an id that never named a live fiber is "stopped" — it is
     * not running), or later, when it terminates. The call itself does not block.
     */
    fun awaitStopped(fiberId: String, onStopped: () -> Unit)

    /** Drop a pending [awaitStopped] registration whose awaiting fiber was itself stopped first. */
    fun cancelAwait(fiberId: String, onStopped: () -> Unit)
}

/**
 * A settable indirection to a [FiberLifecycle]. The [dev.pleiades.masamune.flow.runtime.BlockRegistry]
 * creates one and hands `Fiber stopped` a `{ holder.delegate }` provider; the [Scheduler] built for
 * that flow sets itself as the delegate. Until a scheduler wires itself in, the delegate is null and
 * `Fiber stopped` fails by name rather than parking forever on a lifecycle nothing will report.
 *
 * `@Volatile` because the scheduler sets it on one coroutine and a block reads it on another.
 */
class FiberLifecycleHolder {
    @Volatile
    var delegate: FiberLifecycle? = null
}

package dev.pleiades.masamune.flow.runtime.trigger

import dev.pleiades.masamune.flow.expr.Value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * An event source that can *start a flow* — the trigger half of an n8n-style flow plane.
 *
 * The manual flow plane runs a graph when the user presses Run. A trigger is the other half: a flow
 * declares an entry that fires on an event — a schedule, a broadcast, a threshold — and the runtime
 * starts the flow when it does. This interface is the narrow, `android.*`-free contract for one such
 * source: [arm] begins listening and calls back with a payload on each event; [disarm] stops. The
 * source knows nothing about flows — [TriggerHost] wires [arm]'s callback to launching a flow, which
 * keeps a trigger unit-testable (arm it, fire it, assert the callback) without any scheduler.
 */
interface FlowTrigger {
    /** Begin listening. [onFire] is invoked with an event payload each time the source fires. */
    fun arm(onFire: (Value) -> Unit)

    /** Stop listening. After this the source fires no more, and re-[arm]ing starts fresh. */
    fun disarm()
}

/**
 * A trigger fired by hand: [fire] delivers an event now. The simplest real trigger — a button, an
 * incoming message the app already received, a test — and the honest floor of the subsystem: an
 * event that actually happened, not a simulated one. Fires nothing until armed, and nothing after
 * disarm.
 */
class ManualTrigger : FlowTrigger {
    private var callback: ((Value) -> Unit)? = null

    /** Whether this trigger is currently armed (listening). */
    val isArmed: Boolean get() = callback != null

    override fun arm(onFire: (Value) -> Unit) { callback = onFire }

    override fun disarm() { callback = null }

    /** Deliver an event with [payload] to the armed listener; a no-op when disarmed. */
    fun fire(payload: Value = Value.Null) { callback?.invoke(payload) }
}

/**
 * A trigger that fires on a fixed interval — the schedule/cron trigger.
 *
 * The loop runs on the supplied [scope] (so it is cancelled with the flow's lifecycle and, in a
 * test, advanced by a virtual clock rather than real waiting — the same discipline as `Delay`'s
 * waker). Each tick fires with the tick count as the payload. A non-positive period never arms, so
 * a misconfigured interval is inert rather than a busy loop.
 */
class IntervalTrigger(private val scope: CoroutineScope, private val periodMs: Long) : FlowTrigger {
    private var job: Job? = null

    override fun arm(onFire: (Value) -> Unit) {
        if (periodMs <= 0L) return
        job = scope.launch {
            var tick = 0L
            while (isActive) {
                delay(periodMs)
                tick += 1
                onFire(Value.Num(tick.toDouble()))
            }
        }
    }

    override fun disarm() {
        job?.cancel()
        job = null
    }
}

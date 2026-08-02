package dev.pleiades.masamune.flow.runtime.impl

import dev.pleiades.masamune.flow.expr.Value
import dev.pleiades.masamune.flow.model.FlowNode
import dev.pleiades.masamune.flow.model.Port
import dev.pleiades.masamune.flow.runtime.BlockImpl
import dev.pleiades.masamune.flow.runtime.Fiber
import dev.pleiades.masamune.flow.runtime.Outcome
import dev.pleiades.masamune.flow.runtime.Waker
import java.util.TimeZone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The permission-free Date & time blocks: [DelayBlock] and [TimeZoneGetBlock].
 *
 * The picker blocks (`Date pick`, `Time pick`, `Duration pick`) need a UI surface, and `Time
 * await` / `Time window` need a wall-clock alarm model that schedules against a time of day and
 * survives reboot — neither exists in this build, so they stay unimplemented behind the scheduler's
 * honest gate. `Delay` is the one timed block that needs nothing but a clock, so it is the one
 * built here, alongside `Time zone get`, which only reads the JVM's default zone.
 */

/**
 * `Delay` — park the fiber for a duration, then continue.
 *
 * This is the canonical [Outcome.Await]: the block does not spin or hold a thread, it returns a
 * [Waker] and yields the dispatch loop, and the scheduler moves the fiber to AWAITING. A
 * non-positive duration is not worth a park — it proceeds at once. The duration is read as a
 * number of milliseconds; a missing or non-numeric duration is a visible failure, not a silent
 * zero, because "delay for nothing" and "delay misconfigured" must not look the same.
 *
 * The waker runs on the scheduler's own [scope], which is what makes a parked delay both
 * cancellable (the scope is cancelled when the flow is torn down) and testable (a test scope's
 * virtual clock advances the delay without real waiting).
 */
internal class DelayBlock(private val scope: CoroutineScope) : BlockImpl {
    override val specId = "delay"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val ms = args["duration"].asNumOrNull()
            ?: return Outcome.Fail("Delay needs a duration in milliseconds.")
        val millis = ms.toLong()
        if (millis <= 0L) return Outcome.Proceed(Port.OK)
        return Outcome.Await("Delaying $millis ms", TimerWaker(scope, millis))
    }
}

/**
 * A [Waker] that resumes by OK after a real (or virtual-time) delay.
 *
 * It holds a [Job], not a captured continuation, so a parked fiber persists no live coroutine —
 * the scheduler can serialize and shut down with the fiber AWAITING and re-arm this waker from the
 * block spec on restore, which a suspended continuation could never survive. [cancel] tears the
 * timer down when the fiber is stopped or the flow ends before it fires.
 */
private class TimerWaker(private val scope: CoroutineScope, private val millis: Long) : Waker {
    private var job: Job? = null

    override fun start(resume: (Port, Map<String, Value>) -> Unit) {
        job = scope.launch {
            delay(millis)
            resume(Port.OK, emptyMap())
        }
    }

    override fun cancel() {
        job?.cancel()
    }
}

/**
 * `Time zone get` — report the device's default time zone. Reads `java.util.TimeZone.getDefault`
 * (no permission, no framework surface beyond the JVM) and publishes the IANA id and the raw
 * offset in milliseconds — the offset ignoring daylight saving, which is what "the time zone's
 * offset" names as a stable property of the zone rather than of the instant.
 */
internal class TimeZoneGetBlock : BlockImpl {
    override val specId = "time_zone_get"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val zone = TimeZone.getDefault()
        val writes = LinkedHashMap<String, Value>()
        node.outputs["varTimeZoneId"]?.let { writes[it] = Value.Text(zone.id) }
        node.outputs["varTimeZoneOffset"]?.let { writes[it] = Value.Num(zone.rawOffset.toDouble()) }
        return Outcome.Proceed(Port.OK, writes)
    }
}

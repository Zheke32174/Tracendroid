package dev.pleiades.masamune.flow.runtime.trigger

import dev.pleiades.masamune.flow.expr.Value
import dev.pleiades.masamune.flow.runtime.impl.FlowStarter
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The trigger plane end to end: a [FlowTrigger] firing must launch its bound flow through the
 * [FlowStarter], carrying the event payload — the same launch path `Flow start` uses. Fakes and a
 * virtual clock, no device, no scheduler.
 */
class TriggerHostTest {

    /** Records every launch the host requests. */
    private class RecordingStarter : FlowStarter {
        val started = ArrayList<Pair<String, Value>>()
        override suspend fun start(flowUri: String, payload: Value, stopWithParent: Boolean, parentFlowId: String): String? {
            started.add(flowUri to payload)
            return "$flowUri#run${started.size}"
        }
    }

    @Test fun aManualTriggerFiringStartsTheBoundFlowWithThePayload() = runTest {
        val starter = RecordingStarter()
        val host = TriggerHost(starter, this)
        val trigger = ManualTrigger()
        host.install("flowA", trigger)
        assertTrue(trigger.isArmed)
        assertEquals(setOf("flowA"), host.armedFlows)

        trigger.fire(Value.Text("event-1"))
        runCurrent()
        assertEquals(listOf("flowA" to Value.Text("event-1")), starter.started)
    }

    @Test fun anIntervalTriggerStartsTheFlowOncePerPeriod() = runTest {
        val starter = RecordingStarter()
        val host = TriggerHost(starter, this)
        host.install("cronFlow", IntervalTrigger(this, periodMs = 100))

        advanceTimeBy(350)   // 100, 200, 300 -> three ticks
        runCurrent()
        assertEquals(3, starter.started.size)
        assertTrue(starter.started.all { it.first == "cronFlow" })
        assertEquals(Value.Num(1.0), starter.started[0].second)   // payload is the tick count
        assertEquals(Value.Num(3.0), starter.started[2].second)
        host.disarmAll()     // stop the interval loop so runTest can finish
    }

    @Test fun uninstallDisarmsSoNoFurtherFiringsStartTheFlow() = runTest {
        val starter = RecordingStarter()
        val host = TriggerHost(starter, this)
        val trigger = ManualTrigger()
        host.install("flowA", trigger)
        host.uninstall("flowA")

        assertFalse(trigger.isArmed)
        assertTrue(host.armedFlows.isEmpty())
        trigger.fire(Value.Text("late"))
        runCurrent()
        assertTrue(starter.started.isEmpty())
    }

    @Test fun disarmAllStopsAnIntervalTrigger() = runTest {
        val starter = RecordingStarter()
        val host = TriggerHost(starter, this)
        host.install("cronFlow", IntervalTrigger(this, periodMs = 100))
        advanceTimeBy(150)   // one tick
        runCurrent()
        val afterOne = starter.started.size
        host.disarmAll()
        advanceTimeBy(500)   // no more ticks should land
        runCurrent()
        assertEquals(afterOne, starter.started.size)
        assertTrue(host.armedFlows.isEmpty())
    }

    @Test fun reinstallReplacesTheBindingWithoutDoubleArming() = runTest {
        val starter = RecordingStarter()
        val host = TriggerHost(starter, this)
        val first = ManualTrigger()
        val second = ManualTrigger()
        host.install("flowA", first)
        host.install("flowA", second)   // replaces first
        assertFalse("the replaced trigger is disarmed", first.isArmed)
        assertTrue(second.isArmed)

        first.fire(Value.Text("stale"))
        second.fire(Value.Text("live"))
        runCurrent()
        assertEquals(listOf("flowA" to Value.Text("live")), starter.started)
    }
}

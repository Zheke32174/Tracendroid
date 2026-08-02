package dev.pleiades.masamune.operator

import dev.pleiades.masamune.core.capability.Capability
import dev.pleiades.masamune.core.capability.GateDecision
import dev.pleiades.masamune.flow.expr.Value
import dev.pleiades.masamune.flow.model.FlowNode
import dev.pleiades.masamune.flow.runtime.ArgResolver
import dev.pleiades.masamune.flow.runtime.ExprEval
import dev.pleiades.masamune.flow.runtime.Fiber
import dev.pleiades.masamune.flow.runtime.FiberStatus
import dev.pleiades.masamune.flow.runtime.InMemoryFiberStore
import dev.pleiades.masamune.flow.runtime.Outcome
import dev.pleiades.masamune.flow.runtime.Scheduler
import dev.pleiades.masamune.operator.a11y.FocusedField
import dev.pleiades.masamune.operator.a11y.GlobalKey
import dev.pleiades.masamune.operator.a11y.ScreenActuator
import dev.pleiades.masamune.operator.a11y.ScreenshotResult
import dev.pleiades.masamune.operator.a11y.SimplifiedNode
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves the operator-as-a-fiber properties that the design rests on and that are easy to get
 * wrong: the observe→decide→act loop runs on the real [Scheduler] and terminates on a Finish, the
 * chosen Interface block actually reaches the actuator, the accessibility gate disables the whole
 * thing by omission when the service is off, an action fails honestly (never no-ops) when the
 * service drops, the capability gate can refuse, and halt stops the loop between blocks.
 */
class OperatorLoopTest {

    /** Records every touch so a test can assert the operator really drove the actuator. */
    private class FakeActuator(
        private val layout: SimplifiedNode = node("Button", text = "Login", clickable = true),
    ) : ScreenActuator {
        val taps = mutableListOf<Pair<Int, Int>>()
        val typed = mutableListOf<String>()
        val keys = mutableListOf<GlobalKey>()
        var focused: FocusedField? = FocusedField("hello", "com.example", editable = true)

        override suspend fun dumpLayout(): SimplifiedNode = layout
        override suspend fun tap(x: Int, y: Int): Boolean { taps += x to y; return true }
        override suspend fun longPress(x: Int, y: Int): Boolean { taps += x to y; return true }
        override suspend fun swipe(x0: Int, y0: Int, x1: Int, y1: Int, durationMs: Long): Boolean = true
        override suspend fun clickNodeMatching(query: String): Boolean = true
        override suspend fun readFocusedField(): FocusedField? = focused
        override suspend fun setFocusedText(text: String): Boolean { typed += text; return true }
        override suspend fun globalKey(key: GlobalKey): Boolean { keys += key; return true }
        override suspend fun screenshot(): ScreenshotResult = ScreenshotResult.Unavailable("test")
    }

    private val allowGate = OperatorGate { _, _ -> GateDecision.Allowed }
    private val denyGate = OperatorGate { _, what -> GateDecision.Denied("Denied: $what") }

    /** A resolver whose eval is never reached — the operator graph carries no expression args. */
    private val resolver = ArgResolver(ExprEval { _, _ -> Result.success(Value.Null) })

    private fun CoroutineScope.scheduler(
        actuator: ScreenActuator?,
        decider: OperatorDecider,
        gate: OperatorGate = allowGate,
        trace: OperatorTrace = OperatorTrace {},
    ): Scheduler {
        val graph = OperatorLoop.buildGraph()
        return Scheduler(
            graph = graph,
            specs = OperatorLoop.specLookup,
            impls = OperatorLoop.buildImplLookup(graph, this, { actuator }, gate, decider, trace),
            resolver = resolver,
            store = InMemoryFiberStore(),
            scope = this,
        )
    }

    @Test fun loopObservesDecidesActsThenFinishes() = runTest {
        val actuator = FakeActuator()
        val seenObservations = mutableListOf<String>()
        // step 0: tap (10,20); step 1: done. The tap routes to interact_touch → actuator.tap.
        val decider = OperatorDecider { _, observation, step ->
            seenObservations += observation
            if (step == 0) {
                OperatorDecision.Act(
                    InterfaceCall(
                        blockId = "interact_touch",
                        args = mapOf("gesture" to Value.Text("Click"), "x0" to Value.Num(10.0), "y0" to Value.Num(20.0)),
                        label = "tap (10,20)",
                    ),
                    reason = "tap the login button",
                )
            } else {
                OperatorDecision.Finish("logged in")
            }
        }
        val transcript = mutableListOf<String>()
        val sched = scheduler(actuator, decider, trace = { transcript += it })
        sched.start(UUID.randomUUID().toString(), at = OperatorLoop.NODE_OBSERVE, seedVariables = mapOf(OperatorLoop.VAR_GOAL to Value.Text("log in")))
        sched.run()

        val fiber = sched.snapshot().single()
        assertEquals(FiberStatus.STOPPED, fiber.status)            // Finish → decide NO → normal stop
        assertEquals(listOf(10 to 20), actuator.taps)              // the chosen Interface block reached the actuator
        assertTrue(seenObservations.first().contains("Login"))     // observe fed the real screen tree to decide
        assertEquals("log in", fiber.readVariable(OperatorLoop.VAR_GOAL).asText())
        assertTrue(transcript.any { it.contains("tap (10,20)") })
        assertTrue(transcript.any { it.startsWith("done:") })
    }

    @Test fun typeActionReachesSetText() = runTest {
        val actuator = FakeActuator()
        val decider = OperatorDecider { _, _, step ->
            if (step == 0) {
                OperatorDecision.Act(
                    InterfaceCall("key_send_characters", mapOf("characters" to Value.Text("neo")), "type neo"),
                    "type the username",
                )
            } else {
                OperatorDecision.Finish("done")
            }
        }
        val sched = scheduler(actuator, decider)
        sched.start(UUID.randomUUID().toString(), at = OperatorLoop.NODE_OBSERVE, seedVariables = mapOf(OperatorLoop.VAR_GOAL to Value.Text("type")))
        sched.run()
        assertEquals(listOf("neo"), actuator.typed)
    }

    @Test fun serviceOffGatesLoopByOmissionNamingRequirement() = runTest {
        // No actuator ⇒ the Interface impls are not registered ⇒ the observe node's inspect_layout
        // has no impl ⇒ the scheduler names the missing requirement instead of running.
        val decider = OperatorDecider { _, _, _ -> OperatorDecision.Finish("unreached") }
        val graph = OperatorLoop.buildGraph()
        val lookup = OperatorLoop.buildImplLookup(graph, this, { null }, allowGate, decider)
        assertNull("inspect_layout must be omitted when the service is off", lookup("inspect_layout"))

        val sched = Scheduler(graph, OperatorLoop.specLookup, lookup, resolver, InMemoryFiberStore(), this)
        sched.start(UUID.randomUUID().toString(), at = OperatorLoop.NODE_OBSERVE)
        sched.run()
        val fiber = sched.snapshot().single()
        assertEquals(FiberStatus.ERROR, fiber.status)
        assertTrue(fiber.errorMessage!!.contains("Accessibility service"))
    }

    @Test fun actionFailsHonestlyWhenActuatorAbsentAtRun() = runTest {
        // Impl exists (registered), but the actuator drops before it runs: it must Fail, not no-op.
        val block = InspectLayoutBlock({ null }, allowGate)
        val outcome = block.run(Fiber("f", "flow"), FlowNode("n", "inspect_layout", 0f, 0f), emptyMap())
        assertTrue(outcome is Outcome.Fail)
        assertTrue((outcome as Outcome.Fail).message.contains("Accessibility service"))
    }

    @Test fun capabilityGateCanRefuseAnAction() = runTest {
        val block = InteractTouchBlock({ FakeActuator() }, denyGate)
        val node = FlowNode("n", "interact_touch", 0f, 0f)
        val args = mapOf("gesture" to Value.Text("Click"), "x0" to Value.Num(1.0), "y0" to Value.Num(2.0))
        val outcome = block.run(Fiber("f", "flow"), node, args)
        assertTrue(outcome is Outcome.Fail)
        assertTrue((outcome as Outcome.Fail).message.contains("interact_touch"))
    }

    @Test fun keySendRejectsUnknownKeyByName() = runTest {
        val block = KeySendBlock({ FakeActuator() }, allowGate)
        val outcome = block.run(
            Fiber("f", "flow"),
            FlowNode("n", "key_send", 0f, 0f),
            mapOf("keyCode" to Value.Text("KEYCODE_FLOOB")),
        )
        assertTrue(outcome is Outcome.Fail)
        assertTrue((outcome as Outcome.Fail).message.contains("FLOOB"))
    }

    @Test fun interfaceCallEncodeDecodeRoundTrips() {
        val call = InterfaceCall(
            blockId = "interact_touch",
            args = mapOf("gesture" to Value.Text("Swipe"), "x0" to Value.Num(1.0), "y0" to Value.Num(2.0)),
            label = "swipe",
            outputs = mapOf("varContent" to "\$op.out"),
        )
        val back = InterfaceCall.decode(call.encode())!!
        assertEquals(call, back)
        assertNull("a non-dict value decodes to null", InterfaceCall.decode(Value.Text("x")))
    }

    @Test fun haltStopsTheLoopBetweenBlocks() = runTest {
        // Halt is honoured by the scheduler itself: with isHalted true from the start, no block runs.
        val actuator = FakeActuator()
        val decider = OperatorDecider { _, _, _ -> OperatorDecision.Act(
            InterfaceCall("interact_touch", mapOf("gesture" to Value.Text("Click"), "x0" to Value.Num(0.0), "y0" to Value.Num(0.0)), "tap"),
            "tap",
        ) }
        val graph = OperatorLoop.buildGraph()
        val sched = Scheduler(
            graph = graph,
            specs = OperatorLoop.specLookup,
            impls = OperatorLoop.buildImplLookup(graph, this, { actuator }, allowGate, decider),
            resolver = resolver,
            store = InMemoryFiberStore(),
            scope = this,
            isHalted = { true },
        )
        sched.start(UUID.randomUUID().toString(), at = OperatorLoop.NODE_OBSERVE, seedVariables = mapOf(OperatorLoop.VAR_GOAL to Value.Text("x")))
        sched.run()
        // The fiber never advanced past its starting node; nothing was tapped.
        assertTrue(actuator.taps.isEmpty())
        assertFalse(sched.snapshot().single().status.isTerminal)
    }

    private companion object {
        fun node(className: String, text: String? = null, clickable: Boolean = false) = SimplifiedNode(
            className = className, text = text, contentDesc = null, resourceId = null,
            bounds = "[0,0][100,100]", clickable = clickable, editable = false, children = emptyList(),
        )
    }
}

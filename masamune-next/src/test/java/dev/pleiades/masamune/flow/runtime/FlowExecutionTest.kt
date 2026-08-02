package dev.pleiades.masamune.flow.runtime

import dev.pleiades.masamune.flow.catalog.BlockCatalog
import dev.pleiades.masamune.flow.expr.Value
import dev.pleiades.masamune.flow.model.BlockCategory
import dev.pleiades.masamune.flow.model.BlockShape
import dev.pleiades.masamune.flow.model.BlockSpec
import dev.pleiades.masamune.flow.model.Connection
import dev.pleiades.masamune.flow.model.FlowGraph
import dev.pleiades.masamune.flow.model.FlowNode
import dev.pleiades.masamune.flow.model.Port
import dev.pleiades.masamune.flow.runtime.impl.DelayBlock
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end proof that a flow actually *executes* over the real [BlockRegistry], the real
 * expression [dev.pleiades.masamune.flow.runtime.ExprEvalAdapter], and a live [Scheduler]. Each
 * test drives a graph the way the ViewModel does and asserts on the fibers the scheduler produces —
 * the block impls are never called in isolation, so what these prove is the *seam working*, not a
 * unit in a jar. The scenarios are the ones easiest to get subtly wrong: fork isolation across a
 * real run, subroutine call/return, a goto→label jump, a for-each over a real array, YES/NO
 * routing on the expression decision, a delay that parks and resumes, failure-catch retry, and a
 * persistence round-trip over the new control state.
 */
class FlowExecutionTest {

    // ---------------------------------------------------------------- harness

    private fun node(
        id: String,
        specId: String,
        args: Map<String, String> = emptyMap(),
        exprs: Map<String, Boolean> = emptyMap(),
        outputs: Map<String, String> = emptyMap(),
        note: String? = null,
    ) = FlowNode(id, specId, 0f, 0f, options = emptyMap(), args = args, argIsExpression = exprs, outputs = outputs, note = note)

    /** A `Variable set`-style node: assign the expression [value] to variable [into]. */
    private fun setVar(id: String, into: String, value: String) =
        node(id, "variable_assign", args = mapOf("value" to value), exprs = mapOf("value" to true), outputs = mapOf("variable" to into))

    private fun TestScope.scheduler(
        graph: FlowGraph,
        impls: ((String) -> BlockImpl?)? = null,
        specs: (String) -> BlockSpec? = { BlockCatalog[it] },
    ): Scheduler {
        val registry = BlockRegistry(graph, this)
        return Scheduler(
            graph = graph,
            specs = specs,
            impls = impls ?: registry.lookup,
            resolver = ArgResolver(ExprEvalAdapter()),
            store = InMemoryFiberStore(),
            scope = this,
        )
    }

    // ------------------------------------------------------------------- tests

    @Test fun forkIsolatesFramesAcrossARealRun() = runTest {
        val g = FlowGraph(
            id = "f", name = "fork",
            nodes = listOf(
                setVar("seed", "shared", "1"),
                node("fk", "fork"),
                setVar("child", "shared", "99"),   // YES: child's own copy
                setVar("parent", "pdone", "1"),     // NO: parent continues
            ),
            connections = listOf(
                Connection("seed", Port.OK, "fk"),
                Connection("fk", Port.YES, "child"),
                Connection("fk", Port.NO, "parent"),
            ),
        )
        val sched = scheduler(g)
        sched.start("root")
        sched.run()

        val fibers = sched.snapshot()
        assertEquals(2, fibers.size)                                   // parent + spawned child
        val parent = fibers.single { it.id == "root" }
        val child = fibers.single { it.id != "root" }
        assertEquals(Value.Num(1.0), parent.readVariable("shared"))    // child's 99 did NOT leak back
        assertEquals(Value.Num(1.0), parent.readVariable("pdone"))     // parent walked its own NO path
        assertEquals(Value.Num(99.0), child.readVariable("shared"))    // child mutated its own copy
        assertTrue(fibers.all { it.status == FiberStatus.STOPPED })
    }

    @Test fun subroutineCallsBodyThenReturnsToCaller() = runTest {
        val g = FlowGraph(
            id = "f", name = "sub",
            nodes = listOf(
                node("S", "subroutine"),
                setVar("B", "body", "1"),      // callee body; its unconnected OK returns
                setVar("AF", "after", "1"),    // caller's continuation
            ),
            connections = listOf(
                Connection("S", Port.YES, "B"),
                Connection("S", Port.NO, "AF"),
            ),
        )
        val sched = scheduler(g)
        sched.start("fib")
        sched.run()

        val f = sched.snapshot().single()
        assertEquals(Value.Num(1.0), f.readVariable("body"))    // body ran
        assertEquals(Value.Num(1.0), f.readVariable("after"))   // and control returned to the caller
        assertEquals("AF", f.currentNode)
        assertEquals(FiberStatus.STOPPED, f.status)
    }

    @Test fun gotoJumpsToMatchingLabelSkippingItsOwnOkEdge() = runTest {
        val g = FlowGraph(
            id = "f", name = "goto",
            nodes = listOf(
                node("G", "goto", args = mapOf("labelValue" to "target")),
                setVar("bad", "bad", "1"),      // wired to G.OK — must NOT run when the label matches
                node("L", "label", note = "target"),
                setVar("after", "reached", "1"),
            ),
            connections = listOf(
                Connection("G", Port.OK, "bad"),
                Connection("L", Port.OK, "after"),
            ),
        )
        val sched = scheduler(g)
        sched.start("fib")
        sched.run()

        val f = sched.snapshot().single()
        assertEquals(Value.Num(1.0), f.readVariable("reached"))   // jumped through the label
        assertEquals(Value.Null, f.readVariable("bad"))           // the OK edge was not taken
        assertEquals(FiberStatus.STOPPED, f.status)
    }

    @Test fun forEachWalksARealArray() = runTest {
        val g = FlowGraph(
            id = "f", name = "loop",
            nodes = listOf(
                node("FB", "flow_beginning"),
                node(
                    "FE", "for_each",
                    args = mapOf("container" to "[10, 20, 30]"),
                    exprs = mapOf("container" to true),
                    outputs = mapOf("varEntryValue" to "cur", "varEntryIndex" to "idx"),
                ),
                node(
                    "B", "array_add",
                    args = mapOf("value" to "cur"),
                    exprs = mapOf("value" to true),
                    outputs = mapOf("variable" to "collected"),
                ),
                setVar("done", "done", "1"),
            ),
            connections = listOf(
                Connection("FB", Port.OK, "FE"),
                Connection("FE", Port.YES, "B"),      // DO: loop body
                Connection("B", Port.OK, "FE"),       // back for the next element
                Connection("FE", Port.NO, "done"),    // exhausted
            ),
        )
        val sched = scheduler(g)
        sched.start("fib")
        sched.run()

        val f = sched.snapshot().single()
        assertEquals(
            Value.ArrayV(listOf(Value.Num(10.0), Value.Num(20.0), Value.Num(30.0))),
            f.readVariable("collected"),
        )
        assertEquals(Value.Num(2.0), f.readVariable("idx"))   // last index visited
        assertEquals(Value.Num(1.0), f.readVariable("done"))  // NO path ran after exhaustion
        assertEquals(FiberStatus.STOPPED, f.status)
    }

    @Test fun expressionTrueRoutesYesAndNo() = runTest {
        suspend fun route(expression: String): Value {
            val g = FlowGraph(
                id = "f", name = "dec",
                nodes = listOf(
                    node("FB", "flow_beginning"),
                    node("D", "expression_decision", args = mapOf("expression" to expression), exprs = mapOf("expression" to true)),
                    setVar("y", "hit", "1"),
                    setVar("n", "hit", "2"),
                ),
                connections = listOf(
                    Connection("FB", Port.OK, "D"),
                    Connection("D", Port.YES, "y"),
                    Connection("D", Port.NO, "n"),
                ),
            )
            val sched = scheduler(g)
            sched.start("fib")
            sched.run()
            return sched.snapshot().single().readVariable("hit")
        }
        assertEquals(Value.Num(1.0), route("1 < 2"))   // true ⇒ YES
        assertEquals(Value.Num(2.0), route("1 > 2"))   // false ⇒ NO
    }

    @Test fun delayReturnsAwaitThenResumes() = runTest {
        val block = DelayBlock(this)
        val outcome = block.run(
            Fiber("f", "flow", currentNode = "d"),
            node("d", "delay"),
            mapOf("duration" to Value.Num(50.0)),
        )
        assertTrue("delay must park, not proceed", outcome is Outcome.Await)

        var resumedBy: Port? = null
        (outcome as Outcome.Await).wake.start { resumedBy = it }
        assertNull("still parked before the timer elapses", resumedBy)   // parked
        testScheduler.advanceUntilIdle()
        assertEquals(Port.OK, resumedBy)                                 // resumed by OK
    }

    @Test fun delayParksInAFlowAndResumesToTheNextBlock() = runTest {
        val g = FlowGraph(
            id = "f", name = "delay",
            nodes = listOf(
                node("FB", "flow_beginning"),
                node("Dl", "delay", args = mapOf("duration" to "50"), exprs = mapOf("duration" to true)),
                setVar("after", "ran", "1"),
            ),
            connections = listOf(
                Connection("FB", Port.OK, "Dl"),
                Connection("Dl", Port.OK, "after"),
            ),
        )
        val sched = scheduler(g)
        sched.start("fib")
        sched.run()

        val f = sched.snapshot().single()
        assertEquals(Value.Num(1.0), f.readVariable("ran"))   // resumed and ran the block past the delay
        assertEquals(FiberStatus.STOPPED, f.status)
    }

    @Test fun failureCatchRetriesUntilTheBlockSucceeds() = runTest {
        // A block that fails once (recording its attempt count), then succeeds.
        val flaky = object : BlockImpl {
            override val specId = "flaky"
            override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
                val tries = (fiber.readVariable("tries") as? Value.Num)?.value ?: 0.0
                return if (tries < 1.0) {
                    Outcome.Fail("boom", writes = mapOf("tries" to Value.Num(tries + 1)))
                } else {
                    Outcome.Proceed(Port.OK, writes = mapOf("succeeded" to Value.Num(1.0)))
                }
            }
        }
        val g = FlowGraph(
            id = "f", name = "catch",
            nodes = listOf(
                node("FB", "flow_beginning"),
                node(
                    "FC", "failure_catch",
                    args = mapOf("retryLimit" to "2"),
                    exprs = mapOf("retryLimit" to true),
                    outputs = mapOf("varRetryCount" to "rc"),
                ),
                node("flaky", "flaky"),
                setVar("ok", "done", "1"),
            ),
            connections = listOf(
                Connection("FB", Port.OK, "FC"),
                Connection("FC", Port.YES, "flaky"),   // OK path: run the guarded block
                Connection("flaky", Port.OK, "ok"),
                Connection("FC", Port.NO, "flaky"),     // FAIL/retry path: try the block again
            ),
        )
        val registry = BlockRegistry(g, this)
        val sched = scheduler(
            g,
            impls = { id -> if (id == "flaky") flaky else registry.lookup(id) },
            specs = { id -> if (id == "flaky") BlockSpec("flaky", "flaky", BlockCategory.GENERAL, BlockShape.ACTION, "test") else BlockCatalog[id] },
        )
        sched.start("fib")
        sched.run()

        val f = sched.snapshot().single()
        assertEquals(Value.Num(1.0), f.readVariable("succeeded"))   // the block eventually ran clean
        assertEquals(Value.Num(1.0), f.readVariable("done"))        // and control continued past it
        assertEquals(Value.Num(1.0), f.readVariable("rc"))          // exactly one retry was reported
        assertEquals(FiberStatus.STOPPED, f.status)
    }

    @Test fun codecRoundTripsCallCatchAndLoopState() {
        // A fiber carrying every kind of private control state the runtime adds — all of it inside
        // the variable frame, so FiberCodec already round-trips it with no schema change.
        val fiber = Fiber(
            id = "id", flowId = "flow", currentNode = "n", enteredBy = Port.NO,
            variables = mapOf(
                "user" to Value.Text("visible"),
                CALL_STACK to encodeCallStack(listOf("returnNode", RETURN_STOP)),
                CATCH_STACK to encodeCatchFrames(listOf(CatchFrame("catchNode", 3, 1))),
                CATCH_PENDING to encodePendingFailure("catchNode", 1, "failure", "boom", "failingNode"),
                forEachKey("loopNode") to Value.DictV(
                    mapOf(
                        "vals" to Value.ArrayV(listOf(Value.Num(1.0), Value.Num(2.0))),
                        "keys" to Value.ArrayV(listOf(Value.Null, Value.Null)),
                        "i" to Value.Num(1.0),
                    ),
                ),
            ),
            status = FiberStatus.READY,
        )
        val back = FiberCodec.decodeFromString(FiberCodec.encodeToString(fiber))
        assertEquals(fiber, back)
        // The decoded control state re-reads through the same helpers the scheduler uses.
        assertEquals(listOf("returnNode", RETURN_STOP), back.callStack())
        assertEquals(listOf(CatchFrame("catchNode", 3, 1)), back.catchFrames())
    }
}

package dev.pleiades.masamune.flow.runtime

import dev.pleiades.masamune.flow.expr.Value
import dev.pleiades.masamune.flow.model.BlockCategory
import dev.pleiades.masamune.flow.model.BlockShape
import dev.pleiades.masamune.flow.model.BlockSpec
import dev.pleiades.masamune.flow.model.Connection
import dev.pleiades.masamune.flow.model.FlowGraph
import dev.pleiades.masamune.flow.model.FlowNode
import dev.pleiades.masamune.flow.model.Port
import dev.pleiades.masamune.flow.model.Requirement
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves the runtime properties that are easy to get wrong and that the whole flow plane
 * rests on: correct routing by port, an unconnected port ending a fiber *normally*, fork
 * variable isolation, honest gating of a block with no impl, and a persist→restore round-trip
 * that resumes at the last block rather than restarting.
 */
class SchedulerTest {

    /** A block that writes one variable then leaves by OK. */
    private fun setVar(id: String, name: String, value: Value) = object : BlockImpl {
        override val specId = id
        override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome =
            Outcome.Proceed(Port.OK, writes = mapOf(name to value))
    }

    private fun spec(
        id: String,
        shape: BlockShape = BlockShape.ACTION,
        requires: Set<Requirement> = emptySet(),
    ) = BlockSpec(id, id, BlockCategory.GENERAL, shape, "test", requires = requires)

    private fun CoroutineScope.harness(
        graph: FlowGraph,
        specs: Map<String, BlockSpec>,
        impls: Map<String, BlockImpl>,
        store: FiberStore = InMemoryFiberStore(),
    ) = Scheduler(
        graph = graph,
        specs = { specs[it] },
        impls = { impls[it] },
        // No test block ever Awaits, so the resolver's value is never consulted; still real.
        resolver = ArgResolver(ExprEval { _, _ -> Result.success(Value.Null) }),
        store = store,
        // The TestScope itself — a real, non-leaking scope. GlobalScope would leak; a waker
        // launch (never triggered here) would otherwise outlive the test.
        scope = this,
    )

    @Test fun routesAndTerminatesOnUnconnectedPort() = runTest {
        val g = FlowGraph(
            id = "f", name = "f",
            nodes = listOf(FlowNode("a", "set", 0f, 0f), FlowNode("b", "set", 0f, 0f)),
            connections = listOf(Connection("a", Port.OK, "b")),
        )
        val sched = harness(g, mapOf("set" to spec("set")), mapOf("set" to setVar("set", "x", Value.Num(1.0))))
        sched.start("fib1")
        sched.run()
        val f = sched.snapshot().single()
        assertEquals(FiberStatus.STOPPED, f.status)      // b's OK is unconnected → normal stop
        assertEquals("b", f.currentNode)                 // stopped AT b, having reached and run it
        assertEquals(Value.Num(1.0), f.readVariable("x"))
    }

    @Test fun decisionRoutesByYesNo() = runTest {
        val decideYes = object : BlockImpl {
            override val specId = "dec"
            override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>) =
                Outcome.Proceed(Port.YES)
        }
        val g = FlowGraph(
            id = "f", name = "f",
            nodes = listOf(
                FlowNode("d", "dec", 0f, 0f),
                FlowNode("y", "set", 0f, 0f),
                FlowNode("n", "set", 0f, 0f),
            ),
            connections = listOf(Connection("d", Port.YES, "y"), Connection("d", Port.NO, "n")),
        )
        val sched = harness(
            g,
            mapOf("dec" to spec("dec", BlockShape.DECISION), "set" to spec("set")),
            mapOf("dec" to decideYes, "set" to setVar("set", "hit", Value.Text("yes-branch"))),
        )
        sched.start("fib")
        sched.run()
        assertEquals(Value.Text("yes-branch"), sched.snapshot().single().readVariable("hit"))
    }

    @Test fun forkIsolatesVariableFrames() = runTest {
        val parent = Fiber("p", "f", currentNode = "x", variables = mapOf("v" to Value.Num(1.0)))
        val g = FlowGraph("f", "f", nodes = listOf(FlowNode("x", "set", 0f, 0f)))
        val sched = harness(g, mapOf("set" to spec("set")), mapOf("set" to setVar("set", "v", Value.Num(1.0))))
        val child = sched.fork(parent, "c", "x")
        assertEquals(Value.Num(1.0), child.readVariable("v"))
        val mutatedChild = child.withVariable("v", Value.Num(99.0))
        assertEquals(Value.Num(1.0), parent.readVariable("v"))       // parent untouched by child write
        assertEquals(Value.Num(99.0), mutatedChild.readVariable("v"))
    }

    @Test fun gatedBlockErrorsWithNamedRequirement() = runTest {
        val g = FlowGraph("f", "f", nodes = listOf(FlowNode("a", "priv", 0f, 0f)))
        // Spec exists and declares a requirement, but no impl is registered → gated.
        val sched = harness(g, mapOf("priv" to spec("priv", requires = setOf(Requirement.Uid2000))), emptyMap())
        sched.start("fib")
        sched.run()
        val f = sched.snapshot().single()
        assertEquals(FiberStatus.ERROR, f.status)
        assertTrue(f.errorMessage!!.contains("Privileged shell"))    // names the requirement, not "unimplemented"
    }

    @Test fun persistRestoreResumesAtLastBlock() = runTest {
        val store = InMemoryFiberStore()
        // A fiber persisted mid-flow at "b", as if the process had died there.
        store.save(
            Fiber(
                "resumed", "f", currentNode = "b", status = FiberStatus.READY,
                variables = mapOf("carried" to Value.Text("survived")),
            ),
        )
        val g = FlowGraph(
            id = "f", name = "f",
            nodes = listOf(FlowNode("a", "set", 0f, 0f), FlowNode("b", "set", 0f, 0f)),
            connections = listOf(Connection("a", Port.OK, "b")),
        )
        val sched = harness(g, mapOf("set" to spec("set")), mapOf("set" to setVar("set", "ran", Value.Num(1.0))), store)
        sched.restore()
        sched.run()
        val f = sched.snapshot().single()
        assertEquals("resumed", f.id)
        assertEquals(Value.Text("survived"), f.readVariable("carried"))  // the frame survived the "shutdown"
        assertEquals(Value.Num(1.0), f.readVariable("ran"))              // b actually ran on resume, not before
        assertEquals(FiberStatus.STOPPED, f.status)
    }

    @Test fun codecRoundTripsEveryValueKind() {
        val f = Fiber(
            "id", "flow", currentNode = "n", enteredBy = Port.YES,
            variables = mapOf(
                "num" to Value.Num(1.5),
                "big" to Value.BigInt(java.math.BigInteger("123456789012345678901234567890")),
                "txt" to Value.Text("x"),
                "nul" to Value.Null,
                "arr" to Value.ArrayV(listOf(Value.Num(1.0), Value.Text("a"))),
                "dic" to Value.DictV(mapOf("k" to Value.Num(2.0))),
            ),
            status = FiberStatus.AWAITING, awaitReason = "waiting",
        )
        val back = FiberCodec.decodeFromString(FiberCodec.encodeToString(f))
        assertEquals(f, back)
        // A bigint and an equal-valued number must NOT collapse — the expr layer treats them
        // differently, so persistence must keep them distinct.
        assertTrue(back.readVariable("big") is Value.BigInt)
        assertTrue(back.readVariable("num") is Value.Num)
    }
}

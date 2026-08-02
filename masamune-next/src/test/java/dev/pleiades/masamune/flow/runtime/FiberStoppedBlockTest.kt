package dev.pleiades.masamune.flow.runtime

import dev.pleiades.masamune.flow.catalog.BlockCatalog
import dev.pleiades.masamune.flow.expr.Value
import dev.pleiades.masamune.flow.model.BlockSpec
import dev.pleiades.masamune.flow.model.Connection
import dev.pleiades.masamune.flow.model.FlowGraph
import dev.pleiades.masamune.flow.model.FlowNode
import dev.pleiades.masamune.flow.model.Port
import dev.pleiades.masamune.flow.runtime.impl.FiberStoppedBlock
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `Fiber stopped` awaits a fiber's termination through the [FiberLifecycle] seam. Two layers: the
 * block against a fake lifecycle (gating + the resume-YES-on-stop path), and end to end over a real
 * [Scheduler], where the scheduler *is* the lifecycle and a forked child stopping resumes a parent
 * parked on `fiber_stopped`.
 */
class FiberStoppedBlockTest {

    // ------------------------------------------------------------------ block against a fake seam

    private fun node() = FlowNode("n", "fiber_stopped", 0f, 0f)

    @Test fun missingLifecycleFailsByName() = runTest {
        val out = FiberStoppedBlock { null }.run(
            Fiber("f", "flow"), node(), mapOf("fiberUri" to Value.Text("child1")),
        )
        assertTrue(out is Outcome.Fail)
        assertTrue((out as Outcome.Fail).message.contains("no scheduler lifecycle"))
    }

    @Test fun blankUriFails() = runTest {
        val life = object : FiberLifecycle {
            override fun awaitStopped(fiberId: String, onStopped: () -> Unit) {}
            override fun cancelAwait(fiberId: String, onStopped: () -> Unit) {}
        }
        val out = FiberStoppedBlock { life }.run(Fiber("f", "flow"), node(), mapOf("fiberUri" to Value.Text("")))
        assertTrue(out is Outcome.Fail)
    }

    @Test fun awaitsThenResumesYesWhenTheFiberStops() = runTest {
        var registered: (() -> Unit)? = null
        val life = object : FiberLifecycle {
            override fun awaitStopped(fiberId: String, onStopped: () -> Unit) { registered = onStopped }
            override fun cancelAwait(fiberId: String, onStopped: () -> Unit) {}
        }
        val out = FiberStoppedBlock { life }.run(
            Fiber("f", "flow"), node(), mapOf("fiberUri" to Value.Text("child1")),
        )
        assertTrue("must park", out is Outcome.Await)

        var resumedBy: Port? = null
        (out as Outcome.Await).wake.start { port, _ -> resumedBy = port }
        assertNull("not resumed until the fiber stops", resumedBy)
        registered?.invoke()                       // the lifecycle reports the fiber stopped
        assertEquals(Port.YES, resumedBy)
    }

    // ------------------------------------------------------------------ end to end over the scheduler

    private fun node(id: String, specId: String, args: Map<String, String> = emptyMap(),
                     exprs: Map<String, Boolean> = emptyMap(), outputs: Map<String, String> = emptyMap()) =
        FlowNode(id, specId, 0f, 0f, options = emptyMap(), args = args, argIsExpression = exprs, outputs = outputs)

    private fun TestScope.scheduler(graph: FlowGraph): Scheduler {
        val registry = BlockRegistry(graph, this)
        return Scheduler(
            graph = graph,
            specs = { BlockCatalog[it] },
            impls = registry.lookup,
            resolver = ArgResolver(ExprEvalAdapter()),
            store = InMemoryFiberStore(),
            scope = this,
            fiberLifecycleHolder = registry.fiberLifecycle,   // the scheduler serves the lifecycle
        )
    }

    @Test fun aStoppedChildResumesAParentAwaitingIt() = runTest {
        // Parent forks a child (id -> `kid`). Child path: a label, then it stops (OK unconnected).
        // Parent path: fiber_stopped(kid) -> YES -> mark done. The parent must resume when the child
        // stops and set `done`.
        val g = FlowGraph(
            id = "f", name = "fiberstopped",
            nodes = listOf(
                node("fk", "fork", outputs = mapOf("varChildFiberURI" to "kid")),
                node("child", "label", args = mapOf("name" to "L")),   // child runs then stops (OK unwired)
                node("fs", "fiber_stopped", args = mapOf("fiberUri" to "kid"), exprs = mapOf("fiberUri" to true)),
                node("done", "variable_assign", args = mapOf("value" to "1"),
                    exprs = mapOf("value" to true), outputs = mapOf("variable" to "parentDone")),
            ),
            connections = listOf(
                Connection("fk", Port.YES, "child"),   // child branch
                Connection("fk", Port.NO, "fs"),       // parent awaits the child
                Connection("fs", Port.YES, "done"),    // resumes YES when the child stops
            ),
        )
        val sched = scheduler(g)
        sched.start("root")
        sched.run()

        val fibers = sched.snapshot()
        val parent = fibers.single { it.id == "root" }
        assertEquals(Value.Num(1.0), parent.readVariable("parentDone"))   // parent resumed and finished
        assertTrue(fibers.all { it.status == FiberStatus.STOPPED })
    }
}

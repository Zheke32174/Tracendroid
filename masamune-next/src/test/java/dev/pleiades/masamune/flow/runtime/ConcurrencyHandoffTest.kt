package dev.pleiades.masamune.flow.runtime

import dev.pleiades.masamune.flow.catalog.BlockCatalog
import dev.pleiades.masamune.flow.expr.Value
import dev.pleiades.masamune.flow.model.BlockSpec
import dev.pleiades.masamune.flow.model.Connection
import dev.pleiades.masamune.flow.model.FlowGraph
import dev.pleiades.masamune.flow.model.FlowNode
import dev.pleiades.masamune.flow.model.Port
import dev.pleiades.masamune.flow.runtime.impl.HandoffStore
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves the `Variables give` / `Variables take` hand-off pair — the first block to deliver a value
 * *on resume* through the extended [Waker] contract. Two layers: [HandoffStore] in isolation (queue
 * order + the park/give race), and the pair end-to-end over a real [Scheduler], where one fiber
 * parks on `take` and a sibling's `give` resumes it carrying the handed-over variables.
 */
class ConcurrencyHandoffTest {

    // ------------------------------------------------------------------ HandoffStore unit

    @Test fun giveThenTakeDequeuesInFifoOrder() {
        val store = HandoffStore()
        store.give("t", HandoffStore.Handoff("a", mapOf("v" to Value.Num(1.0))))
        store.give("t", HandoffStore.Handoff("b", mapOf("v" to Value.Num(2.0))))
        val got = ArrayList<HandoffStore.Handoff>()
        assertTrue(store.arm("t") { got.add(it) })   // first already queued -> fired now
        assertTrue(store.arm("t") { got.add(it) })   // second still queued -> fired now
        assertFalse(store.arm("t") { got.add(it) })  // empty now -> parks
        assertEquals(listOf("a", "b"), got.map { it.giverUri })
    }

    @Test fun armThenGiveFiresTheParkedTaker() {
        val store = HandoffStore()
        var received: HandoffStore.Handoff? = null
        assertFalse(store.arm("t") { received = it })   // empty -> parks
        assertNull(received)
        store.give("t", HandoffStore.Handoff("g", mapOf("x" to Value.Text("hi"))))
        assertEquals("g", received?.giverUri)
        assertEquals(Value.Text("hi"), received?.values?.get("x"))
    }

    @Test fun unarmDropsAParkedTakerSoALaterGiveQueuesInstead() {
        val store = HandoffStore()
        var fired = false
        store.arm("t") { fired = true }
        store.unarm("t")
        store.give("t", HandoffStore.Handoff("g", emptyMap()))
        assertFalse(fired)                              // the cancelled taker was not fired
        var late: HandoffStore.Handoff? = null
        assertTrue(store.arm("t") { late = it })        // the give queued, a fresh arm takes it
        assertEquals("g", late?.giverUri)
    }

    @Test fun mailboxesAreIndependentPerTaker() {
        val store = HandoffStore()
        store.give("t1", HandoffStore.Handoff("a", emptyMap()))
        var t2fired = false
        assertFalse(store.arm("t2") { t2fired = true }) // t2 empty despite t1 having mail
        assertFalse(t2fired)
    }

    // ------------------------------------------------------------------ end-to-end over the scheduler

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
        )
    }

    @Test fun giveResumesAParkedTakeCarryingTheHandedVariables() = runTest {
        // Parent forks a child (child id bound to `kid`). Child path: `take` (parks empty).
        // Parent path: set `gift`, then `give` to `kid`. The child must resume with gift + giver id.
        val g = FlowGraph(
            id = "f", name = "handoff",
            nodes = listOf(
                node("fk", "fork", outputs = mapOf("varChildFiberURI" to "kid")),
                node("tk", "variables_take", outputs = mapOf("giverFiberUri" to "who")),
                node("setv", "variable_assign", args = mapOf("value" to "42"),
                    exprs = mapOf("value" to true), outputs = mapOf("variable" to "gift")),
                node("gv", "variables_give", args = mapOf("takerFiberUri" to "kid"),
                    exprs = mapOf("takerFiberUri" to true)),
            ),
            connections = listOf(
                Connection("fk", Port.YES, "tk"),     // child takes
                Connection("fk", Port.NO, "setv"),    // parent sets the gift
                Connection("setv", Port.OK, "gv"),    // then gives
            ),
        )
        val sched = scheduler(g)
        sched.start("root")
        sched.run()

        val fibers = sched.snapshot()
        assertEquals(2, fibers.size)
        val child = fibers.single { it.id != "root" }
        assertEquals(Value.Num(42.0), child.readVariable("gift"))   // received the given variable
        assertEquals(Value.Text("root"), child.readVariable("who")) // and who gave it
        assertTrue(fibers.all { it.status == FiberStatus.STOPPED })  // nobody left parked
    }
}

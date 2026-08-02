package dev.pleiades.masamune.flow.runtime

import dev.pleiades.masamune.flow.expr.Value
import dev.pleiades.masamune.flow.model.FlowNode
import dev.pleiades.masamune.flow.model.Port
import dev.pleiades.masamune.flow.runtime.impl.FlowStartBlock
import dev.pleiades.masamune.flow.runtime.impl.FlowStarter
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `Flow start` over a fake [FlowStarter] host: it launches by URI, binds the child fiber URI,
 * passes the payload/stopWithParent through, and fails by name for a missing host, a blank URI, or
 * a flow that does not resolve — never a fabricated child.
 */
class FlowStartBlockTest {

    private class FakeStarter(private val known: Set<String>) : FlowStarter {
        var lastCall: List<Any?>? = null
        override suspend fun start(flowUri: String, payload: Value, stopWithParent: Boolean, parentFlowId: String): String? {
            lastCall = listOf(flowUri, payload, stopWithParent, parentFlowId)
            return if (flowUri in known) "$flowUri#fiber1" else null
        }
    }

    private fun node(outputs: Map<String, String> = emptyMap(), options: Map<String, String> = emptyMap()) =
        FlowNode("n", "flow_start", 0f, 0f, options = options, outputs = outputs)

    @Test fun startsAndBindsChildUriPassingPayloadAndOption() = runTest {
        val starter = FakeStarter(setOf("flowB"))
        val out = FlowStartBlock({ starter }).run(
            Fiber("f", "flowA"),
            node(outputs = mapOf("varChildFiberURI" to "kid"), options = mapOf("stopWithParent" to "true")),
            mapOf("flowUri" to Value.Text("flowB"), "payload" to Value.Num(7.0)),
        )
        assertTrue(out is Outcome.Proceed && out.port == Port.OK)
        assertEquals(Value.Text("flowB#fiber1"), (out as Outcome.Proceed).writes["kid"])
        assertEquals(listOf<Any?>("flowB", Value.Num(7.0), true, "flowA"), starter.lastCall)
    }

    @Test fun missingHostFailsByName() = runTest {
        val out = FlowStartBlock({ null }).run(
            Fiber("f", "flowA"), node(), mapOf("flowUri" to Value.Text("flowB")),
        )
        assertTrue(out is Outcome.Fail)
        assertTrue((out as Outcome.Fail).message.contains("multi-flow host"))
    }

    @Test fun blankUriFails() = runTest {
        val out = FlowStartBlock({ FakeStarter(emptySet()) }).run(
            Fiber("f", "flowA"), node(), mapOf("flowUri" to Value.Text("")),
        )
        assertTrue(out is Outcome.Fail)
    }

    @Test fun unresolvedFlowFails() = runTest {
        val out = FlowStartBlock({ FakeStarter(setOf("flowB")) }).run(
            Fiber("f", "flowA"), node(), mapOf("flowUri" to Value.Text("nope")),
        )
        assertTrue(out is Outcome.Fail)
        assertTrue((out as Outcome.Fail).message.contains("nope"))
    }
}

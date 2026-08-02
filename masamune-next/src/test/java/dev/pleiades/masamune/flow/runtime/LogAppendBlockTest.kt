package dev.pleiades.masamune.flow.runtime

import dev.pleiades.masamune.flow.expr.Value
import dev.pleiades.masamune.flow.model.FlowNode
import dev.pleiades.masamune.flow.model.Port
import dev.pleiades.masamune.flow.runtime.impl.FlowLog
import dev.pleiades.masamune.flow.runtime.impl.InMemoryFlowLog
import dev.pleiades.masamune.flow.runtime.impl.LogAppendBlock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** `Log append` writes to the flow log, honours `whenLogging`, and fails on a missing message. */
class LogAppendBlockTest {

    private fun node() = FlowNode("n", "log_append", 0f, 0f)

    @Test fun appendsTheMessage() = runTest {
        val log = InMemoryFlowLog()
        val out = LogAppendBlock(log).run(
            Fiber("f", "flow"), node(), mapOf("message" to Value.Text("hello")),
        )
        assertTrue(out is Outcome.Proceed && out.port == Port.OK)
        assertEquals(listOf("hello"), log.snapshot())
    }

    @Test fun missingMessageFails() = runTest {
        val log = InMemoryFlowLog()
        val out = LogAppendBlock(log).run(Fiber("f", "flow"), node(), emptyMap())
        assertTrue(out is Outcome.Fail)
        assertTrue(log.snapshot().isEmpty())
    }

    @Test fun whenLoggingSuppressesWhileLoggingIsOff() = runTest {
        val off = object : FlowLog {
            override val loggingEnabled = false
            val lines = ArrayList<String>()
            override fun append(message: String) { lines.add(message) }
        }
        val out = LogAppendBlock(off).run(
            Fiber("f", "flow"), node(),
            mapOf("message" to Value.Text("skip me"), "whenLogging" to Value.Text("true")),
        )
        assertTrue(out is Outcome.Proceed)          // proceeds, but wrote nothing
        assertTrue(off.lines.isEmpty())
    }

    @Test fun whenLoggingFalseAlwaysWrites() = runTest {
        val log = InMemoryFlowLog()
        LogAppendBlock(log).run(
            Fiber("f", "flow"), node(),
            mapOf("message" to Value.Text("always"), "whenLogging" to Value.Text("false")),
        )
        assertEquals(listOf("always"), log.snapshot())
    }
}

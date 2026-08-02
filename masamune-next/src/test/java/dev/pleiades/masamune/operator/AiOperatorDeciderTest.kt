package dev.pleiades.masamune.operator

import dev.pleiades.masamune.ai.AiException
import dev.pleiades.masamune.ai.AiService
import dev.pleiades.masamune.ai.PromptTurn
import dev.pleiades.masamune.flow.expr.Value
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves the real decide step: the LLM's JSON reply maps onto the Interface-block vocabulary, and
 * a reply that is not a usable action throws (so the decide block errors visibly) rather than
 * inventing a tap. The provider is the existing [AiService] seam — no new auth path.
 */
class AiOperatorDeciderTest {

    /** A provider that replies with a fixed body, streamed in two chunks to exercise accumulation. */
    private class FakeService(private val reply: String) : AiService {
        override val providerModel = "fake:test"
        override fun stream(turns: List<PromptTurn>): Flow<String> {
            val mid = reply.length / 2
            return flowOf(reply.substring(0, mid), reply.substring(mid))
        }
        override suspend fun testConnection(): Result<String> = Result.success("ok")
    }

    @Test fun tapReplyBecomesInteractTouch() = runTest {
        val decider = AiOperatorDecider(FakeService("""{"action":"tap","x":10,"y":20,"reason":"button"}"""))
        val decision = decider.decide("goal", "screen", 0)
        assertTrue(decision is OperatorDecision.Act)
        val call = (decision as OperatorDecision.Act).call
        assertEquals("interact_touch", call.blockId)
        assertEquals(Value.Text("Click"), call.args["gesture"])
        assertEquals(Value.Num(10.0), call.args["x0"])
        assertEquals(Value.Num(20.0), call.args["y0"])
    }

    @Test fun keyReplyBecomesKeySend() = runTest {
        val decider = AiOperatorDecider(FakeService("""{"action":"key","key":"BACK","reason":"go back"}"""))
        val call = (decider.decide("g", "s", 0) as OperatorDecision.Act).call
        assertEquals("key_send", call.blockId)
        assertEquals(Value.Text("BACK"), call.args["keyCode"])
    }

    @Test fun doneReplyFinishes() = runTest {
        val decider = AiOperatorDecider(FakeService("""Sure! {"done":true,"reason":"all set"} done."""))
        val decision = decider.decide("g", "s", 3)
        assertTrue(decision is OperatorDecision.Finish)
        assertEquals("all set", (decision as OperatorDecision.Finish).reason)
    }

    @Test fun nonJsonReplyThrows() = runTest {
        val decider = AiOperatorDecider(FakeService("I cannot do that."))
        val thrown = runCatching { decider.decide("g", "s", 0) }.exceptionOrNull()
        assertTrue(thrown is AiException)
    }

    @Test fun unknownActionThrows() = runTest {
        val decider = AiOperatorDecider(FakeService("""{"action":"teleport","reason":"why not"}"""))
        val thrown = runCatching { decider.decide("g", "s", 0) }.exceptionOrNull()
        assertTrue(thrown is AiException)
        assertTrue(thrown!!.message!!.contains("teleport"))
    }
}

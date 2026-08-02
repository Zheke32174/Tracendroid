package dev.pleiades.masamune.flow.runtime

import dev.pleiades.masamune.apps.CallState
import dev.pleiades.masamune.apps.MobileOperator
import dev.pleiades.masamune.apps.MobileServiceState
import dev.pleiades.masamune.apps.SubscriptionRef
import dev.pleiades.masamune.apps.SubscriptionUsage
import dev.pleiades.masamune.apps.TelephonyReader
import dev.pleiades.masamune.flow.expr.Value
import dev.pleiades.masamune.flow.model.FlowNode
import dev.pleiades.masamune.flow.model.Port
import dev.pleiades.masamune.flow.runtime.impl.telephonyLookup
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit proof that the Telephony one-shot blocks branch and bind correctly — run against a
 * [FakeTelephonyReader] on the JVM, never a device, which is exactly what the `android.*`-free
 * [TelephonyReader] seam buys (the same seam shape the Apps, Settings, Battery&Power, Sensor, Location and
 * Connectivity blocks use). Each test drives a block the way the runtime does — an args map of resolved
 * [Value]s and a [FlowNode] carrying the output bindings — and asserts on the [Outcome] and its writes. The
 * honest failure shape is the point of the coverage: a cellular state the device cannot read is a visible
 * [Outcome.Fail], never a fabricated `false`/`0`/empty operator and never a silent NO; a real "not roaming
 * / out of service / not registered" is a NO, distinct from an unreadable state (a Fail). The absent-seam
 * path is checked for all six blocks.
 */
class TelephonyReaderBlocksTest {

    /**
     * A fully scriptable fake standing in for the real telephony stack. A `null` reading is exactly what a
     * device with no `TelephonyManager` / a refused permission would answer, and the block turns that `null`
     * into a named Fail. Each field is independently scriptable so a test can exercise one block's read in
     * isolation; [subscription] is keyed by usage so `subscription_default_get` can vary by usage.
     */
    private class FakeTelephonyReader(
        private val callState: CallState? = null,
        private val signal: Int? = null,
        private val operator: MobileOperator? = null,
        private val serviceState: MobileServiceState? = null,
        private val subscription: Map<SubscriptionUsage, SubscriptionRef?> = emptyMap(),
        private val roaming: Boolean? = null,
    ) : TelephonyReader {
        override suspend fun callState(): CallState? = callState
        override suspend fun cellSignalLevel(): Int? = signal
        override suspend fun mobileOperator(): MobileOperator? = operator
        override suspend fun mobileServiceState(): MobileServiceState? = serviceState
        override suspend fun defaultSubscription(usage: SubscriptionUsage): SubscriptionRef? =
            subscription[usage]
        override suspend fun isRoaming(): Boolean? = roaming
    }

    private fun node(specId: String, vararg outputs: Pair<String, String>) =
        FlowNode("n", specId, 0f, 0f, outputs = outputs.toMap())

    private fun fiber() = Fiber("f", "flow")

    /** Fetch a single registered impl from the lookup composed over [seam]. */
    private fun block(specId: String, seam: TelephonyReader?): BlockImpl =
        telephonyLookup { seam }[specId] ?: error("no registered block for $specId")

    // ------------------------------------------------------------------ call_state

    @Test fun callStateYesWhenActualMatchesRequested() = runTest {
        val seam = FakeTelephonyReader(callState = CallState.RINGING)
        val outcome = block("call_state", seam).run(
            fiber(), node("call_state"), mapOf("state" to Value.Text("ringing")),
        )
        assertEquals(Port.YES, (outcome as Outcome.Proceed).port)
    }

    @Test fun callStateNoWhenActualDiffersFromRequested() = runTest {
        val seam = FakeTelephonyReader(callState = CallState.IDLE)
        val outcome = block("call_state", seam).run(
            fiber(), node("call_state"), mapOf("state" to Value.Text("off-hook")),
        )
        assertEquals("a mismatch is NO, not a Fail", Port.NO, (outcome as Outcome.Proceed).port)
    }

    @Test fun callStateDefaultsToIdle() = runTest {
        // No state arg → the documented default (Idle); an idle device matches → YES.
        val seam = FakeTelephonyReader(callState = CallState.IDLE)
        val outcome = block("call_state", seam).run(fiber(), node("call_state"), emptyMap())
        assertEquals(Port.YES, (outcome as Outcome.Proceed).port)
    }

    @Test fun callStateFailsOnUnrecognizedState() = runTest {
        val seam = FakeTelephonyReader(callState = CallState.IDLE)
        val outcome = block("call_state", seam).run(
            fiber(), node("call_state"), mapOf("state" to Value.Text("busy")),
        )
        assertTrue("an unrecognized state Fails by name", outcome is Outcome.Fail)
    }

    @Test fun callStateFailsWhenUnreadable() = runTest {
        val outcome = block("call_state", FakeTelephonyReader(callState = null)).run(
            fiber(), node("call_state"), emptyMap(),
        )
        assertTrue(outcome is Outcome.Fail)
    }

    // ------------------------------------------------------------------ cell_signal_level (band)

    @Test fun cellSignalLevelBindsAndYesWithinBand() = runTest {
        val seam = FakeTelephonyReader(signal = 3)
        val outcome = block("cell_signal_level", seam).run(
            fiber(),
            node("cell_signal_level", "varLevel" to "lvl"),
            mapOf("minLevel" to Value.Num(2.0), "maxLevel" to Value.Num(4.0)),
        )
        val proceed = outcome as Outcome.Proceed
        assertEquals(Port.YES, proceed.port)
        assertEquals(Value.Num(3.0), proceed.writes["lvl"])
    }

    @Test fun cellSignalLevelNoOutsideBandButStillBinds() = runTest {
        val seam = FakeTelephonyReader(signal = 1)
        val outcome = block("cell_signal_level", seam).run(
            fiber(),
            node("cell_signal_level", "varLevel" to "lvl"),
            mapOf("minLevel" to Value.Num(3.0)),
        )
        val proceed = outcome as Outcome.Proceed
        assertEquals("outside the band is NO", Port.NO, proceed.port)
        assertEquals("varLevel binds the real reading regardless of branch", Value.Num(1.0), proceed.writes["lvl"])
    }

    @Test fun cellSignalLevelFailsWhenUnreadable() = runTest {
        val outcome = block("cell_signal_level", FakeTelephonyReader(signal = null)).run(
            fiber(), node("cell_signal_level", "varLevel" to "lvl"), emptyMap(),
        )
        assertTrue(outcome is Outcome.Fail)
        assertNull((outcome as Outcome.Fail).writes["lvl"])
    }

    // ------------------------------------------------------------------ mobile_operator

    @Test fun mobileOperatorBindsFieldsAndYesWithNoFilter() = runTest {
        val op = MobileOperator.Registered(name = "Telco", code = "31026", countryCode = "us")
        val seam = FakeTelephonyReader(operator = op)
        val outcome = block("mobile_operator", seam).run(
            fiber(),
            node(
                "mobile_operator",
                "varCurrentOperatorName" to "name",
                "varCurrentOperatorCode" to "code",
                "varCurrentOperatorCountryCode" to "country",
            ),
            emptyMap(),
        )
        val proceed = outcome as Outcome.Proceed
        assertEquals("no filter → any registered operator is YES", Port.YES, proceed.port)
        assertEquals(Value.Text("Telco"), proceed.writes["name"])
        assertEquals(Value.Text("31026"), proceed.writes["code"])
        assertEquals(Value.Text("us"), proceed.writes["country"])
    }

    @Test fun mobileOperatorYesWhenFilterMatchesCaseInsensitively() = runTest {
        val op = MobileOperator.Registered(name = "Telco", code = "31026")
        val seam = FakeTelephonyReader(operator = op)
        val outcome = block("mobile_operator", seam).run(
            fiber(), node("mobile_operator"), mapOf("operatorName" to Value.Text("telco")),
        )
        assertEquals(Port.YES, (outcome as Outcome.Proceed).port)
    }

    @Test fun mobileOperatorNoWhenFilterMismatchButStillBinds() = runTest {
        val op = MobileOperator.Registered(name = "Telco", code = "31026")
        val seam = FakeTelephonyReader(operator = op)
        val outcome = block("mobile_operator", seam).run(
            fiber(),
            node("mobile_operator", "varCurrentOperatorName" to "name"),
            mapOf("operatorName" to Value.Text("Other")),
        )
        val proceed = outcome as Outcome.Proceed
        assertEquals("filter mismatch is NO", Port.NO, proceed.port)
        assertEquals("the current operator is bound regardless of branch", Value.Text("Telco"), proceed.writes["name"])
    }

    @Test fun mobileOperatorNoWhenNotRegistered() = runTest {
        // NotRegistered is a real read: no service / no SIM → NO, never a Fail.
        val outcome = block("mobile_operator", FakeTelephonyReader(operator = MobileOperator.NotRegistered)).run(
            fiber(), node("mobile_operator", "varCurrentOperatorName" to "name"), emptyMap(),
        )
        val proceed = outcome as Outcome.Proceed
        assertEquals(Port.NO, proceed.port)
        assertNull(proceed.writes["name"])
    }

    @Test fun mobileOperatorFailsWhenUnreadable() = runTest {
        val outcome = block("mobile_operator", FakeTelephonyReader(operator = null)).run(
            fiber(), node("mobile_operator", "varCurrentOperatorName" to "name"), emptyMap(),
        )
        assertTrue(outcome is Outcome.Fail)
    }

    // ------------------------------------------------------------------ mobile_service_state

    @Test fun mobileServiceStateYesInServiceAndBinds() = runTest {
        val seam = FakeTelephonyReader(serviceState = MobileServiceState.IN_SERVICE)
        val outcome = block("mobile_service_state", seam).run(
            fiber(), node("mobile_service_state", "varCurrentServiceState" to "st"), emptyMap(),
        )
        val proceed = outcome as Outcome.Proceed
        assertEquals(Port.YES, proceed.port)
        assertEquals(Value.Text("In service"), proceed.writes["st"])
    }

    @Test fun mobileServiceStateNoOutOfServiceButStillBinds() = runTest {
        // Out of service is a real read → NO (and still binds the state), never a Fail.
        val seam = FakeTelephonyReader(serviceState = MobileServiceState.OUT_OF_SERVICE)
        val outcome = block("mobile_service_state", seam).run(
            fiber(), node("mobile_service_state", "varCurrentServiceState" to "st"), emptyMap(),
        )
        val proceed = outcome as Outcome.Proceed
        assertEquals(Port.NO, proceed.port)
        assertEquals(Value.Text("Out of service"), proceed.writes["st"])
    }

    @Test fun mobileServiceStateFailsWhenUnreadable() = runTest {
        val outcome = block("mobile_service_state", FakeTelephonyReader(serviceState = null)).run(
            fiber(), node("mobile_service_state", "varCurrentServiceState" to "st"), emptyMap(),
        )
        assertTrue(outcome is Outcome.Fail)
        assertNull((outcome as Outcome.Fail).writes["st"])
    }

    // ------------------------------------------------------------------ subscription_default_get

    @Test fun subscriptionDefaultGetBindsIdAndSlotAndOk() = runTest {
        val seam = FakeTelephonyReader(
            subscription = mapOf(SubscriptionUsage.GENERIC to SubscriptionRef(subscriptionId = 5, simSlotIndex = 1)),
        )
        val outcome = block("subscription_default_get", seam).run(
            fiber(),
            node("subscription_default_get", "varSubscriptionId" to "sub", "varSimSlotIndex" to "slot"),
            emptyMap(),
        )
        val proceed = outcome as Outcome.Proceed
        assertEquals("a getter leaves by OK", Port.OK, proceed.port)
        assertEquals(Value.Num(5.0), proceed.writes["sub"])
        assertEquals(Value.Num(1.0), proceed.writes["slot"])
    }

    @Test fun subscriptionDefaultGetLeavesSlotUnboundWhenAbsent() = runTest {
        // A readable id but no slot mapping (READ_PHONE_STATE absent for the slot) binds only the id.
        val seam = FakeTelephonyReader(
            subscription = mapOf(SubscriptionUsage.VOICE to SubscriptionRef(subscriptionId = 3, simSlotIndex = null)),
        )
        val outcome = block("subscription_default_get", seam).run(
            fiber(),
            node("subscription_default_get", "varSubscriptionId" to "sub", "varSimSlotIndex" to "slot"),
            mapOf("usage" to Value.Text("voice")),
        )
        val proceed = outcome as Outcome.Proceed
        assertEquals(Value.Num(3.0), proceed.writes["sub"])
        assertNull("an absent slot binds nothing", proceed.writes["slot"])
    }

    @Test fun subscriptionDefaultGetFailsOnUnrecognizedUsage() = runTest {
        val seam = FakeTelephonyReader(
            subscription = mapOf(SubscriptionUsage.GENERIC to SubscriptionRef(subscriptionId = 1)),
        )
        val outcome = block("subscription_default_get", seam).run(
            fiber(), node("subscription_default_get", "varSubscriptionId" to "sub"),
            mapOf("usage" to Value.Text("gaming")),
        )
        assertTrue("an unrecognized usage Fails by name", outcome is Outcome.Fail)
    }

    @Test fun subscriptionDefaultGetFailsWhenUnreadable() = runTest {
        // No valid default (INVALID_SUBSCRIPTION_ID → null from the seam) → Fail, never a fabricated -1.
        val seam = FakeTelephonyReader(subscription = mapOf(SubscriptionUsage.GENERIC to null))
        val outcome = block("subscription_default_get", seam).run(
            fiber(), node("subscription_default_get", "varSubscriptionId" to "sub"), emptyMap(),
        )
        assertTrue(outcome is Outcome.Fail)
        assertNull((outcome as Outcome.Fail).writes["sub"])
    }

    // ------------------------------------------------------------------ roaming

    @Test fun roamingYesWhenRoaming() = runTest {
        val outcome = block("roaming", FakeTelephonyReader(roaming = true)).run(
            fiber(), node("roaming"), emptyMap(),
        )
        assertEquals(Port.YES, (outcome as Outcome.Proceed).port)
    }

    @Test fun roamingNoWhenNotRoaming() = runTest {
        // A real false is NO, not a Fail — "not roaming" is a successful read.
        val outcome = block("roaming", FakeTelephonyReader(roaming = false)).run(
            fiber(), node("roaming"), emptyMap(),
        )
        assertEquals(Port.NO, (outcome as Outcome.Proceed).port)
    }

    @Test fun roamingFailsWhenUnreadable() = runTest {
        val outcome = block("roaming", FakeTelephonyReader(roaming = null)).run(
            fiber(), node("roaming"), emptyMap(),
        )
        assertTrue(outcome is Outcome.Fail)
    }

    // ------------------------------------------------------------------ absent seam (all six)

    @Test fun allBlocksFailByNameWhenSeamAbsent() = runTest {
        val lookup = telephonyLookup { null }
        for ((id, impl) in lookup) {
            val outcome = impl.run(fiber(), node(id), emptyMap())
            assertTrue("$id must Fail when the seam is absent", outcome is Outcome.Fail)
            assertTrue((outcome as Outcome.Fail).message.contains("telephony seam"))
        }
    }

    // ------------------------------------------------------------------ composition helper

    @Test fun telephonyLookupExposesExactlyTheSixRegisteredBlocks() {
        val lookup = telephonyLookup { null }
        assertEquals(
            setOf(
                "call_state",
                "cell_signal_level",
                "mobile_operator",
                "mobile_service_state",
                "subscription_default_get",
                "roaming",
            ),
            lookup.keys,
        )
        // Gated by omission — place/answer/end/screen a call, dial, DTMF (actions).
        assertNull(lookup["call_answer"])
        assertNull(lookup["call_end"])
        assertNull(lookup["call_number"])
        assertNull(lookup["call_screening_response"])
        assertNull(lookup["ringer_silence"])
        assertNull(lookup["dtmf_tone_play"]) // AWAIT
        assertNull(lookup["dtmf_tone_stop"])
        assertNull(lookup["dial_number"])
        // Sensitive send actions — SMS is not implemented at all in this slice.
        assertNull(lookup["ussd_request"]) // CALL_PHONE
        // AWAIT triggers (incoming / outgoing / screening call).
        assertNull(lookup["call_incoming"]) // AWAIT
        assertNull(lookup["call_outgoing"]) // AWAIT
        assertNull(lookup["call_screening"]) // AWAIT
        // SET / writes.
        assertNull(lookup["mobile_network_preferred_set"]) // SHELL
        assertNull(lookup["subscription_default_set"]) // SHELL
        assertNull(lookup["subscription_set_state"]) // SHELL
        // SHELL-gated read.
        assertNull(lookup["mobile_network_preferred"]) // SHELL
        // Scans and pickers (location / UI).
        assertNull(lookup["cell_site_near"]) // ACCESS_FINE_LOCATION scan
        assertNull(lookup["cell_site_pick"]) // ACCESS_FINE_LOCATION picker
        assertNull(lookup["subscription_pick"]) // picker
        // Composes over the layers below via `telephonyLookup(...)[id] ?: base`.
        assertNull(lookup["network_type"]) // Connectivity
        assertNull(lookup["location_get"]) // Location
        assertNull(lookup["battery_level"]) // Battery & power
        assertEquals("roaming", lookup["roaming"]!!.specId)
    }
}

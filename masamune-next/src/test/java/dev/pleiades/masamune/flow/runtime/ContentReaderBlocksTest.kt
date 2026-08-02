package dev.pleiades.masamune.flow.runtime

import dev.pleiades.masamune.apps.CalendarEvent
import dev.pleiades.masamune.apps.CalendarEventQuery
import dev.pleiades.masamune.apps.ContactMatch
import dev.pleiades.masamune.apps.ContentQuery
import dev.pleiades.masamune.apps.ContentReader
import dev.pleiades.masamune.flow.expr.Value
import dev.pleiades.masamune.flow.model.FlowNode
import dev.pleiades.masamune.flow.model.Port
import dev.pleiades.masamune.flow.runtime.impl.contentLookup
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit proof that the Content read/query blocks branch and bind correctly — run against a
 * [FakeContentReader] on the JVM, never a device, which is exactly what the `android.*`-free [ContentReader]
 * seam buys (the same seam shape the Apps, Settings, …, Telephony and CameraAndSound blocks use). Each test
 * drives a block the way the runtime does — an args map of resolved [Value]s and a [FlowNode] carrying the
 * output bindings — and asserts on the [Outcome] and its writes. The honest failure shape is the point of the
 * coverage: content the device cannot read is a visible [Outcome.Fail], never a fabricated empty row set or
 * blank contact and never a silent NO; a real empty result (no events, zero rows, [ContactMatch.NotFound]) is
 * a NO / OK-with-empty-array, distinct from an unreadable state (a Fail). The absent-seam path is checked for
 * all four blocks.
 */
class ContentReaderBlocksTest {

    /**
     * A fully scriptable fake standing in for the real content stack. A `null` reading is exactly what a
     * device with no `ContentResolver` / a refused permission would answer, and the block turns that `null`
     * into a named Fail. Each field is independently scriptable so a test can exercise one block's read in
     * isolation; an empty list / [ContactMatch.NotFound] is a real "no match" the block routes to NO/OK.
     */
    private class FakeContentReader(
        private val calendarEvent: CalendarEvent? = null,
        private val calendarEvents: List<String>? = null,
        private val contact: ContactMatch? = null,
        private val rows: List<List<String?>>? = null,
    ) : ContentReader {
        override suspend fun calendarEvent(eventUri: String): CalendarEvent? = calendarEvent
        override suspend fun queryCalendarEvents(query: CalendarEventQuery): List<String>? = calendarEvents
        override suspend fun queryContact(queryValue: String, valueType: String?): ContactMatch? = contact
        override suspend fun query(spec: ContentQuery): List<List<String?>>? = rows
    }

    private fun node(specId: String, vararg outputs: Pair<String, String>) =
        FlowNode("n", specId, 0f, 0f, outputs = outputs.toMap())

    private fun fiber() = Fiber("f", "flow")

    /** Fetch a single registered impl from the lookup composed over [seam]. */
    private fun block(specId: String, seam: ContentReader?): BlockImpl =
        contentLookup { seam }[specId] ?: error("no registered block for $specId")

    // ------------------------------------------------------------------ calendar_event_get

    @Test fun calendarEventGetBindsPresentFieldsAndOk() = runTest {
        val seam = FakeContentReader(
            calendarEvent = CalendarEvent(
                calendarUri = "content://calendar/1",
                beginTimestamp = 1000L,
                endTimestamp = 2000L,
                allDay = true,
                title = "Standup",
                attendees = listOf("a@x", "b@x"),
            ),
        )
        val outcome = block("calendar_event_get", seam).run(
            fiber(),
            node(
                "calendar_event_get",
                "varCalendarURI" to "cal", "varBeginTimestamp" to "begin", "varEndTimestamp" to "end",
                "varAllDay" to "allDay", "varTitle" to "title", "varAttendees" to "att",
                "varDescription" to "desc",
            ),
            mapOf("eventUri" to Value.Text("content://calendar/events/9")),
        )
        val proceed = outcome as Outcome.Proceed
        assertEquals("a getter leaves by OK", Port.OK, proceed.port)
        assertEquals(Value.Text("content://calendar/1"), proceed.writes["cal"])
        assertEquals(Value.Num(1000.0), proceed.writes["begin"])
        assertEquals(Value.Num(2000.0), proceed.writes["end"])
        assertEquals(Value.TRUE, proceed.writes["allDay"])
        assertEquals(Value.Text("Standup"), proceed.writes["title"])
        assertEquals(Value.ArrayV(listOf(Value.Text("a@x"), Value.Text("b@x"))), proceed.writes["att"])
        assertNull("an absent field binds nothing", proceed.writes["desc"])
    }

    @Test fun calendarEventGetFailsWhenEventUriMissing() = runTest {
        val outcome = block("calendar_event_get", FakeContentReader(calendarEvent = CalendarEvent())).run(
            fiber(), node("calendar_event_get"), emptyMap(),
        )
        assertTrue("a blank eventUri Fails by name", outcome is Outcome.Fail)
    }

    @Test fun calendarEventGetFailsWhenUnreadable() = runTest {
        val outcome = block("calendar_event_get", FakeContentReader(calendarEvent = null)).run(
            fiber(), node("calendar_event_get", "varTitle" to "title"),
            mapOf("eventUri" to Value.Text("content://calendar/events/9")),
        )
        assertTrue(outcome is Outcome.Fail)
        assertNull((outcome as Outcome.Fail).writes["title"])
    }

    // ------------------------------------------------------------------ calendar_event_query

    @Test fun calendarEventQueryYesAndBindsWhenMatches() = runTest {
        val seam = FakeContentReader(calendarEvents = listOf("content://ev/1", "content://ev/2"))
        val outcome = block("calendar_event_query", seam).run(
            fiber(),
            node("calendar_event_query", "varEventURIs" to "uris"),
            mapOf("title" to Value.Text("Stand%")),
        )
        val proceed = outcome as Outcome.Proceed
        assertEquals(Port.YES, proceed.port)
        assertEquals(
            Value.ArrayV(listOf(Value.Text("content://ev/1"), Value.Text("content://ev/2"))),
            proceed.writes["uris"],
        )
    }

    @Test fun calendarEventQueryNoAndBindsEmptyWhenNoMatch() = runTest {
        // An empty result is a real "no events matched" → NO with an empty array, never a Fail.
        val seam = FakeContentReader(calendarEvents = emptyList())
        val outcome = block("calendar_event_query", seam).run(
            fiber(), node("calendar_event_query", "varEventURIs" to "uris"), emptyMap(),
        )
        val proceed = outcome as Outcome.Proceed
        assertEquals("no match is NO, not a Fail", Port.NO, proceed.port)
        assertEquals("an empty result binds an empty array", Value.ArrayV(emptyList()), proceed.writes["uris"])
    }

    @Test fun calendarEventQueryFailsWhenUnreadable() = runTest {
        val outcome = block("calendar_event_query", FakeContentReader(calendarEvents = null)).run(
            fiber(), node("calendar_event_query", "varEventURIs" to "uris"), emptyMap(),
        )
        assertTrue(outcome is Outcome.Fail)
        assertNull((outcome as Outcome.Fail).writes["uris"])
    }

    // ------------------------------------------------------------------ contact_query

    @Test fun contactQueryYesAndBindsWhenFound() = runTest {
        val seam = FakeContentReader(
            contact = ContactMatch.Found(
                displayName = "Ada",
                phoneNumber = "+15551234",
                groups = listOf("Friends"),
                uri = "content://contacts/7",
            ),
        )
        val outcome = block("contact_query", seam).run(
            fiber(),
            node(
                "contact_query",
                "varDisplayName" to "name", "varPhoneNumber" to "phone", "varGroups" to "groups",
                "varUri" to "uri", "varEmail" to "email",
            ),
            mapOf("queryValue" to Value.Text("Ada")),
        )
        val proceed = outcome as Outcome.Proceed
        assertEquals(Port.YES, proceed.port)
        assertEquals(Value.Text("Ada"), proceed.writes["name"])
        assertEquals(Value.Text("+15551234"), proceed.writes["phone"])
        assertEquals(Value.ArrayV(listOf(Value.Text("Friends"))), proceed.writes["groups"])
        assertEquals(Value.Text("content://contacts/7"), proceed.writes["uri"])
        assertNull("an absent field binds nothing", proceed.writes["email"])
    }

    @Test fun contactQueryNoWhenNotFound() = runTest {
        // NotFound is a real read: nobody matched → NO, never a Fail.
        val outcome = block("contact_query", FakeContentReader(contact = ContactMatch.NotFound)).run(
            fiber(), node("contact_query", "varDisplayName" to "name"),
            mapOf("queryValue" to Value.Text("Nobody")),
        )
        val proceed = outcome as Outcome.Proceed
        assertEquals(Port.NO, proceed.port)
        assertNull(proceed.writes["name"])
    }

    @Test fun contactQueryFailsWhenLookupValueMissing() = runTest {
        val outcome = block("contact_query", FakeContentReader(contact = ContactMatch.NotFound)).run(
            fiber(), node("contact_query"), emptyMap(),
        )
        assertTrue("a blank lookup value Fails by name", outcome is Outcome.Fail)
    }

    @Test fun contactQueryFailsWhenUnreadable() = runTest {
        val outcome = block("contact_query", FakeContentReader(contact = null)).run(
            fiber(), node("contact_query", "varDisplayName" to "name"),
            mapOf("queryValue" to Value.Text("Ada")),
        )
        assertTrue(outcome is Outcome.Fail)
    }

    // ------------------------------------------------------------------ content_query

    @Test fun contentQueryBindsRowsAndOk() = runTest {
        val seam = FakeContentReader(rows = listOf(listOf("1", "Ada"), listOf("2", null)))
        val outcome = block("content_query", seam).run(
            fiber(),
            node("content_query", "varResult" to "res"),
            mapOf("uri" to Value.Text("content://x/rows")),
        )
        val proceed = outcome as Outcome.Proceed
        assertEquals(Port.OK, proceed.port)
        assertEquals(
            Value.ArrayV(
                listOf(
                    Value.ArrayV(listOf(Value.Text("1"), Value.Text("Ada"))),
                    Value.ArrayV(listOf(Value.Text("2"), Value.Null)),
                ),
            ),
            proceed.writes["res"],
        )
    }

    @Test fun contentQueryBindsEmptyArrayWhenNoRows() = runTest {
        // Zero rows is a real empty result → OK with an empty array, never a Fail.
        val outcome = block("content_query", FakeContentReader(rows = emptyList())).run(
            fiber(), node("content_query", "varResult" to "res"),
            mapOf("uri" to Value.Text("content://x/rows")),
        )
        val proceed = outcome as Outcome.Proceed
        assertEquals(Port.OK, proceed.port)
        assertEquals(Value.ArrayV(emptyList()), proceed.writes["res"])
    }

    @Test fun contentQueryFailsWhenUriMissing() = runTest {
        val outcome = block("content_query", FakeContentReader(rows = emptyList())).run(
            fiber(), node("content_query", "varResult" to "res"), emptyMap(),
        )
        assertTrue("a blank uri Fails by name", outcome is Outcome.Fail)
    }

    @Test fun contentQueryFailsWhenUnreadable() = runTest {
        val outcome = block("content_query", FakeContentReader(rows = null)).run(
            fiber(), node("content_query", "varResult" to "res"),
            mapOf("uri" to Value.Text("content://x/rows")),
        )
        assertTrue(outcome is Outcome.Fail)
        assertNull((outcome as Outcome.Fail).writes["res"])
    }

    // ------------------------------------------------------------------ absent seam (all four)

    @Test fun allBlocksFailByNameWhenSeamAbsent() = runTest {
        val lookup = contentLookup { null }
        for ((id, impl) in lookup) {
            val outcome = impl.run(fiber(), node(id), emptyMap())
            assertTrue("$id must Fail when the seam is absent", outcome is Outcome.Fail)
            assertTrue((outcome as Outcome.Fail).message.contains("content seam"))
        }
    }

    // ------------------------------------------------------------------ composition helper

    @Test fun contentLookupExposesExactlyTheFourRegisteredBlocks() {
        val lookup = contentLookup { null }
        assertEquals(
            setOf(
                "calendar_event_get",
                "calendar_event_query",
                "contact_query",
                "content_query",
            ),
            lookup.keys,
        )
        // Gated by omission — insert / update / delete / write content (actions).
        assertNull(lookup["content_insert"])
        assertNull(lookup["content_update"])
        assertNull(lookup["content_delete"])
        assertNull(lookup["content_write"])
        // Add device state (actions) + the WATCH alarm get/await.
        assertNull(lookup["calendar_event_add"]) // WRITE_CALENDAR
        assertNull(lookup["alarm"]) // WATCH await
        assertNull(lookup["alarm_add"])
        assertNull(lookup["timer_add"])
        // Accounts — AccountManager writes and sync.
        assertNull(lookup["account_generic_add"])
        assertNull(lookup["account_sync_request"])
        assertNull(lookup["account_sync_set_state"])
        assertNull(lookup["account_sync_enabled"]) // WATCH read via AccountManager
        // Offer / view content (intent actions) + provider call.
        assertNull(lookup["content_offer"])
        assertNull(lookup["content_view"])
        assertNull(lookup["content_provider_call"])
        // Await content (triggers / callbacks).
        assertNull(lookup["content_changed"])
        assertNull(lookup["content_shared"])
        assertNull(lookup["content_offer_result"])
        // Copy content to external storage (file write).
        assertNull(lookup["content_read"])
        // Flow-local SQLite (not ContentResolver).
        assertNull(lookup["database_query"])
        assertNull(lookup["database_modify"])
        // Pickers (UI).
        assertNull(lookup["account_pick"])
        assertNull(lookup["calendar_pick"])
        assertNull(lookup["contact_pick"])
        assertNull(lookup["content_pick"])
        assertNull(lookup["keychain_alias_pick"])
        // Composes over the layers below via `contentLookup(...)[id] ?: base`.
        assertNull(lookup["roaming"]) // Telephony
        assertNull(lookup["network_type"]) // Connectivity
        assertNull(lookup["battery_level"]) // Battery & power
        assertEquals("content_query", lookup["content_query"]!!.specId)
    }
}

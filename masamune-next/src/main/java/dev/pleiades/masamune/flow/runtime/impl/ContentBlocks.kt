package dev.pleiades.masamune.flow.runtime.impl

import dev.pleiades.masamune.apps.CalendarEventQuery
import dev.pleiades.masamune.apps.ContactMatch
import dev.pleiades.masamune.apps.ContentQuery
import dev.pleiades.masamune.apps.ContentReader
import dev.pleiades.masamune.flow.expr.Value
import dev.pleiades.masamune.flow.model.FlowNode
import dev.pleiades.masamune.flow.model.Port
import dev.pleiades.masamune.flow.runtime.BlockImpl
import dev.pleiades.masamune.flow.runtime.Fiber
import dev.pleiades.masamune.flow.runtime.Outcome

/**
 * The Content category's **unprivileged read/query** slice — the organ an AI phone operator needs to read
 * another app's content right now: retrieve a calendar event by URI, search for calendar events, look up a
 * contact, and run a generic `ContentResolver` query.
 *
 * ### Why this subset and not the whole (large) category
 * `CatalogContent` mixes a handful of unprivileged reads with the category's real weight — *inserting /
 * updating / deleting / writing* content, *adding* calendar events / alarms / timers, all the *account*
 * (`AccountManager`) writes and sync, *offering / sharing / viewing* content through intent actions, a
 * provider *method call*, the *awaits* (content changed / offered / shared), *copying content to external
 * storage*, the flow-local *SQLite* blocks, and every *picker*. Only the read/query subset can be expressed
 * through the read-only [ContentReader] seam, and only those run here:
 *  - **Registered (4):** `calendar_event_get`, `calendar_event_query`, `contact_query` and `content_query`.
 *    Each is a single `ContentResolver` read or query.
 *  - Everything else is gated by omission (see [contentLookup]).
 *
 * ### The seam, copied from the Apps, Settings, …, Telephony and CameraAndSound blocks
 * Every device call lives behind the injected [ContentReader] — a narrow, `android.*`-free contract, the
 * exact shape [dev.pleiades.masamune.apps.AppInspector], [dev.pleiades.masamune.apps.SystemSettings],
 * [dev.pleiades.masamune.apps.TelephonyReader] and [dev.pleiades.masamune.apps.AudioController] give their
 * categories. Two consequences, both deliberate:
 *
 *  1. **JVM-testable.** Each block reads its args as *plain data*, then calls the seam, so the whole file is
 *     unit-testable against a fake on an ordinary JVM — a device is needed to run these, never to test their
 *     branch logic.
 *  2. **Honest gate at run.** Every impl re-resolves its [ContentReader] provider and fails with
 *     [CONTENT_ABSENT] when there is no seam (the app process is not wired in, or it dropped mid-run). A read
 *     that returns `null` becomes a named [Outcome.Fail] ("could not be read") — **never** a fabricated empty
 *     row set or a blank contact, and **never** a silent NO. A real empty query result
 *     ([ContactMatch.NotFound], an empty URI list, zero rows) is a *successful* read routed to NO / OK with
 *     an empty array; only an unreadable state Fails.
 *
 * ### WATCH decisions collapse to their one-shot form
 * The catalog marks `calendar_event_query` (and the gated `alarm`/`account_sync_enabled`) WATCH-capable
 * (search now, or suspend until content changes). The watching form needs the monitor subsystem this build
 * does not have, so the one-shot query — "which events match *now*" — is what runs, which is exactly what a
 * decision or getter in a running flow evaluates. This mirrors the Telephony one-shot collapses.
 *
 * ### Arguments with no faithful one-shot meaning are documented as ignored, not guessed
 * `calendar_event_query`'s `startOffset`/`endOffset`/`attendees`/`availability` and `content_query`'s
 * `resultType`/`columnResultType` have no honest one-shot mapping through this read-only seam, so they are
 * ignored rather than half-applied — the same honest simplification by which the Telephony blocks ignore
 * `subscriptionId` (see [dev.pleiades.masamune.apps.CalendarEventQuery] and [ContentQuery]).
 *
 * The composition helper [contentLookup] mirrors [telephonyLookup], [audioLookup], [connectivityLookup] and
 * the rest: it returns the impls keyed by spec id so a caller composes `contentLookup(provider)[id] ?:
 * base.lookup(id)`.
 */

/** The sentence shown whenever a Content block cannot reach a content seam. Modelled on [TELEPHONY_ABSENT]. */
internal val CONTENT_ABSENT: String =
    "This content block cannot act: no content seam is available, so Masamune cannot read the device's " +
        "calendar events, contacts or generic content-provider rows. The seam is wired only inside the " +
        "Android app process; when it is absent the block fails by name rather than reporting content that " +
        "was never actually read."

// --------------------------------------------------------------------------- calendar event get

/**
 * `calendar_event_get` (Calendar event get) — retrieve a calendar event's details by URI.
 *
 * ACTION: reads the event at `eventUri` through the seam and binds every present output field
 * (`varCalendarURI`, `varBeginTimestamp`, `varEndTimestamp`, `varAllDay`, `varTimeZone`, `varTitle`,
 * `varDescription`, `varLocationName`, `varColor`, `varAttendees`, `varAvailability`, `varAccessLevel`), then
 * leaves by OK. A blank `eventUri` Fails **by name** before the seam is reached. An event the seam cannot
 * read — no `ContentResolver`, an unresolved URI, or the `READ_CALENDAR` grant is absent — Fails **by name**,
 * never a fabricated blank event. Each field binds only when the event carries it (honest omission).
 *
 * Carries `READ_CALENDAR` in the catalog; that is honored at run by the seam returning `null` (→ a named
 * Fail), not by leaving the block unregistered.
 */
internal class CalendarEventGetBlock(
    private val contentProvider: () -> ContentReader?,
) : BlockImpl {
    override val specId = "calendar_event_get"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val reader = contentProvider() ?: return Outcome.Fail(CONTENT_ABSENT)
        val uri = args["eventUri"].asNonBlankText()
            ?: return Outcome.Fail("calendar_event_get needs an eventUri.")
        val event = reader.calendarEvent(uri)
            ?: return Outcome.Fail("calendar_event_get: the calendar event could not be read.")
        val writes = LinkedHashMap<String, Value>()
        event.calendarUri?.let { node.outputs["varCalendarURI"]?.bind(writes, Value.Text(it)) }
        event.beginTimestamp?.let { node.outputs["varBeginTimestamp"]?.bind(writes, Value.Num(it.toDouble())) }
        event.endTimestamp?.let { node.outputs["varEndTimestamp"]?.bind(writes, Value.Num(it.toDouble())) }
        event.allDay?.let { node.outputs["varAllDay"]?.bind(writes, Value.truth(it)) }
        event.timeZone?.let { node.outputs["varTimeZone"]?.bind(writes, Value.Text(it)) }
        event.title?.let { node.outputs["varTitle"]?.bind(writes, Value.Text(it)) }
        event.description?.let { node.outputs["varDescription"]?.bind(writes, Value.Text(it)) }
        event.locationName?.let { node.outputs["varLocationName"]?.bind(writes, Value.Text(it)) }
        event.color?.let { node.outputs["varColor"]?.bind(writes, Value.Num(it.toDouble())) }
        event.attendees?.let { list ->
            node.outputs["varAttendees"]?.bind(writes, Value.ArrayV(list.map { Value.Text(it) }))
        }
        event.availability?.let { node.outputs["varAvailability"]?.bind(writes, Value.Text(it)) }
        event.accessLevel?.let { node.outputs["varAccessLevel"]?.bind(writes, Value.Text(it)) }
        return Outcome.Proceed(Port.OK, writes)
    }
}

// --------------------------------------------------------------------------- calendar event query

/**
 * `calendar_event_query` (Calendar event query) — search for calendar events matching a filter.
 *
 * DECISION: the one-shot form of the catalog's WATCH decision. It builds a [CalendarEventQuery] from the
 * cleanly-mappable filters (`calendarUri`, `minTimestamp`, `maxTimestamp`, `title`, `description`,
 * `locationName`, `alLDay`), reads the matching event URIs through the seam, **always** binds `varEventURIs`
 * from the result, and routes YES when there is at least one match, NO when the result is empty. An **empty
 * result is a real "no events matched"** routed to NO with an empty `varEventURIs` array — never a Fail. Only
 * an unreadable calendar (no `ContentResolver`, or the `READ_CALENDAR` grant is absent) Fails **by name**.
 *
 * The `startOffset`/`endOffset`/`attendees`/`availability` arguments have no faithful one-shot meaning and
 * are ignored (see file KDoc). Carries `READ_CALENDAR` — honored at run by the seam's `null` (→ named Fail).
 */
internal class CalendarEventQueryBlock(
    private val contentProvider: () -> ContentReader?,
) : BlockImpl {
    override val specId = "calendar_event_query"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val reader = contentProvider() ?: return Outcome.Fail(CONTENT_ABSENT)
        val query = CalendarEventQuery(
            calendarUri = args["calendarUri"].asNonBlankText(),
            minTimestamp = args["minTimestamp"].asNumOrNull()?.toLong(),
            maxTimestamp = args["maxTimestamp"].asNumOrNull()?.toLong(),
            title = args["title"].asNonBlankText(),
            description = args["description"].asNonBlankText(),
            locationName = args["locationName"].asNonBlankText(),
            allDay = args["alLDay"].asFlagOrNull(),
        )
        val uris = reader.queryCalendarEvents(query)
            ?: return Outcome.Fail("calendar_event_query: the calendar events could not be read.")
        val writes = LinkedHashMap<String, Value>()
        node.outputs["varEventURIs"]?.bind(writes, Value.ArrayV(uris.map { Value.Text(it) }))
        return Outcome.Proceed(if (uris.isNotEmpty()) Port.YES else Port.NO, writes)
    }
}

// --------------------------------------------------------------------------- contact query

/**
 * `contact_query` (Contact query) — search for a contact and retrieve its details.
 *
 * DECISION: reads the contact matching `queryValue` (interpreted per the optional `valueType`) through the
 * seam. A [ContactMatch.Found] binds every present field (`varDisplayName`, `varNickname`, `varCompany`,
 * `varPhoneNumber`, `varEmail`, `varPostalAddress`, `varGroups`, `varUri`) and routes YES; a
 * [ContactMatch.NotFound] is a real "found nobody" routed to NO with nothing bound; a `null` (contacts could
 * not be read — no `ContentResolver`, or the `READ_CONTACTS` grant is absent) Fails **by name**. A blank
 * `queryValue` Fails by name before the seam is reached. The distinction between NotFound (NO) and a `null`
 * (Fail) is the whole point of the three-valued read (mirrors `mobile_operator`).
 *
 * Carries `READ_CONTACTS` — honored at run by the seam's `null` (→ named Fail), not by omission.
 */
internal class ContactQueryBlock(
    private val contentProvider: () -> ContentReader?,
) : BlockImpl {
    override val specId = "contact_query"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val reader = contentProvider() ?: return Outcome.Fail(CONTENT_ABSENT)
        val queryValue = args["queryValue"].asNonBlankText()
            ?: return Outcome.Fail("contact_query needs a lookup value.")
        val valueType = args["valueType"].asNonBlankText()
        val match = reader.queryContact(queryValue, valueType)
            ?: return Outcome.Fail("contact_query: the contact could not be read.")
        return when (match) {
            is ContactMatch.NotFound -> Outcome.Proceed(Port.NO)
            is ContactMatch.Found -> {
                val writes = LinkedHashMap<String, Value>()
                match.displayName?.let { node.outputs["varDisplayName"]?.bind(writes, Value.Text(it)) }
                match.nickname?.let { node.outputs["varNickname"]?.bind(writes, Value.Text(it)) }
                match.company?.let { node.outputs["varCompany"]?.bind(writes, Value.Text(it)) }
                match.phoneNumber?.let { node.outputs["varPhoneNumber"]?.bind(writes, Value.Text(it)) }
                match.email?.let { node.outputs["varEmail"]?.bind(writes, Value.Text(it)) }
                match.postalAddress?.let { node.outputs["varPostalAddress"]?.bind(writes, Value.Text(it)) }
                match.groups?.let { list ->
                    node.outputs["varGroups"]?.bind(writes, Value.ArrayV(list.map { Value.Text(it) }))
                }
                match.uri?.let { node.outputs["varUri"]?.bind(writes, Value.Text(it)) }
                Outcome.Proceed(Port.YES, writes)
            }
        }
    }
}

// --------------------------------------------------------------------------- generic content query

/**
 * `content_query` (Content query) — run a generic `ContentResolver` query against a content URI.
 *
 * ACTION: builds a [ContentQuery] from the cleanly-mappable arguments (`uri`, `projection`, `selection`,
 * `parameters`, `sortOrder`, `offset`, `limit`), reads the matching rows through the seam, binds `varResult`
 * to an array of rows (each row an array of text cells, a `NULL` cell binding [Value.Null]), and leaves by
 * OK. A blank `uri` Fails **by name** before the seam is reached. A **zero-row result is a real empty
 * result** bound as an empty `varResult` array (still OK) — never a Fail. Only unreadable content (no
 * `ContentResolver`, an unresolved URI, or a missing provider read permission) Fails **by name**.
 *
 * The catalog's `resultType`/`columnResultType` typing is not modelled — rows are reported as text — the same
 * honest one-shot simplification documented in the file KDoc.
 */
internal class ContentQueryBlock(
    private val contentProvider: () -> ContentReader?,
) : BlockImpl {
    override val specId = "content_query"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val reader = contentProvider() ?: return Outcome.Fail(CONTENT_ABSENT)
        val uri = args["uri"].asNonBlankText()
            ?: return Outcome.Fail("content_query needs a uri.")
        val spec = ContentQuery(
            uri = uri,
            projection = args["projection"].asStringListOrNull(),
            selection = args["selection"].asNonBlankText(),
            parameters = args["parameters"].asStringListOrNull(),
            sortOrder = args["sortOrder"].asNonBlankText(),
            offset = args["offset"].asNumOrNull()?.toInt(),
            limit = args["limit"].asNumOrNull()?.toInt(),
        )
        val rows = reader.query(spec)
            ?: return Outcome.Fail("content_query: the content could not be read.")
        val result = Value.ArrayV(
            rows.map { row -> Value.ArrayV(row.map { cell -> cell?.let { Value.Text(it) } ?: Value.Null }) },
        )
        val writes = LinkedHashMap<String, Value>()
        node.outputs["varResult"]?.bind(writes, result)
        return Outcome.Proceed(Port.OK, writes)
    }
}

// --------------------------------------------------------------------------- composition + helpers

/**
 * The four registered Content read/query impls, keyed by spec id, all sharing one [provider].
 *
 * Mirrors [telephonyLookup], [audioLookup], [connectivityLookup] and the rest: it always returns the map, and
 * the honest gate is the per-block gate-at-run (each fails with [CONTENT_ABSENT] when the provider yields no
 * seam), so a caller composes over its base registry exactly as the other categories do:
 *
 * ```
 * val content = contentLookup(contentReader)
 * fun lookup(id: String): BlockImpl? = content[id] ?: audio[id] ?: … ?: base.lookup(id)
 * ```
 *
 * ### What stays gated by omission, and why
 * The category's many remaining blocks are deliberately **not** here, so at run time the scheduler finds no
 * impl and gates them by the honest-by-omission mechanism the catalog's own `requires` set (or the block's
 * own shape) expresses. Because the [ContentReader] seam is a read-only `ContentResolver` `query()` surface,
 * every gated block is a *write*, an *intent action*, a *provider call*, an *await*, a *content-to-storage
 * copy*, a *SQLite* touch, or a *picker* — none of which a read-only query seam can host, so there is nothing
 * to build-but-not-register here. They are omitted on these honest grounds, grouped:
 *  - **Insert / update / delete / write content (actions).** `content_insert`, `content_update`,
 *    `content_delete` and `content_write` mutate another app's content — mutations a read-only seam cannot
 *    honestly model, exactly as the Connectivity radio toggles are gated.
 *  - **Add device state (actions).** `calendar_event_add` (WRITE_CALENDAR), `alarm_add` and `timer_add` add
 *    a calendar event / alarm / timer — device-state writes, not reads. `alarm` (WATCH) gets-or-awaits the
 *    next Clock alarm — an await over a state this read-only seam does not surface.
 *  - **Accounts — `AccountManager` writes and sync.** `account_generic_add`, `account_sync_request` and
 *    `account_sync_set_state` write/trigger account state; `account_sync_enabled` (WATCH) reads sync state
 *    through `AccountManager`, outside this `ContentResolver`-query seam. The whole `account_*` family is
 *    gated.
 *  - **Offer / view content (intent actions).** `content_offer` offers content to other apps and
 *    `content_view` opens content in another app via an intent — UI/intent actions, not state reads.
 *  - **Call a provider method (action).** `content_provider_call` invokes an arbitrary method on a content
 *    provider — an effect a read-only query seam cannot serve.
 *  - **Await content (triggers / callbacks).** `content_changed`, `content_shared` and `content_offer_result`
 *    suspend until a content change / share / offer-result event — over-time awaits the missing monitor
 *    subsystem cannot run, not one-shot reads.
 *  - **Copy content to external storage (action).** `content_read` copies a content URI's bytes to a file on
 *    external storage (SD card); its sole output is the file it *creates*, so it is a filesystem write, not a
 *    `ContentResolver` query — gated to keep the [ContentReader] seam read-only, exactly as `content_write`
 *    is gated.
 *  - **Flow-local SQLite (not `ContentResolver`).** `database_query` reads and `database_modify` writes a
 *    local SQLite database *file* — outside the content-provider seam entirely — so both are gated: the read
 *    is not a `ContentResolver` read, and the modify is a write.
 *  - **Pickers (UI).** `account_pick`, `calendar_pick`, `contact_pick`, `content_pick` and
 *    `keychain_alias_pick` drive user-facing pickers — the user choosing *is* the grant, none is a one-shot
 *    state read.
 */
fun contentLookup(provider: () -> ContentReader?): Map<String, BlockImpl> = listOf(
    CalendarEventGetBlock(provider),
    CalendarEventQueryBlock(provider),
    ContactQueryBlock(provider),
    ContentQueryBlock(provider),
).associateBy { it.specId }

/** Bind [value] under this non-blank output-variable name into [writes]; a blank name binds nothing. */
private fun String.bind(writes: MutableMap<String, Value>, value: Value) {
    if (isNotBlank()) writes[this] = value
}

/** A text argument trimmed to null when blank/absent — distinct from an empty string a user typed. */
private fun Value?.asNonBlankText(): String? = this.asTextOrNull()?.takeIf { it.isNotBlank() }

/**
 * A `flag(...)` argument as a real three-valued boolean: `null` when the argument is absent (no constraint),
 * otherwise the parsed flag. Distinct from [asFlag], whose non-null default would turn "unset" into a real
 * `false` constraint the query has no reason to impose.
 */
private fun Value?.asFlagOrNull(): Boolean? =
    if (this == null || this == Value.Null) null else this.asFlag()

/**
 * An `array(...)` argument as a list of non-blank strings, or `null` when absent — a lone scalar reads as a
 * one-element list. `null` (not an empty list) is "the documented default" (all columns / no parameters).
 */
private fun Value?.asStringListOrNull(): List<String>? = when (this) {
    is Value.ArrayV -> items.mapNotNull { it.asNonBlankText() }
    null, Value.Null -> null
    else -> asNonBlankText()?.let { listOf(it) }
}

package dev.pleiades.masamune.apps

/**
 * The seam between the Content category's **unprivileged read/query** block impls and the real device
 * content stack (`ContentResolver`, `CalendarContract`, `ContactsContract`).
 *
 * Every way an unprivileged, one-shot Content block can *read/query* another app's content — retrieve a
 * calendar event's details by URI, search for calendar events, look up a contact, and run a generic
 * `ContentResolver` query against an arbitrary content URI — is one method here, and — exactly like
 * [AppInspector] does for the Apps blocks, [SystemSettings] for the Settings blocks, [PowerState] for the
 * Battery&Power blocks, [SensorReader] for the Sensor blocks, [LocationReader] for the Location blocks,
 * [ConnectivityReader] for the Connectivity blocks, [TelephonyReader] for the Telephony blocks and
 * [AudioController] for the CameraAndSound audio blocks — there is deliberately nothing `android.*` on this
 * interface. That single constraint is what buys the whole slice its JVM-testability:
 * [dev.pleiades.masamune.flow.runtime.impl.contentLookup]'s blocks depend on this plain-data contract, never
 * on `ContentResolver`/`CalendarContract`/`ContactsContract`, so every block and all its branch logic can be
 * exercised against a fake on an ordinary unit-test JVM. A device is needed to *run* these blocks, never to
 * *test* their logic.
 *
 * ### The honest gate has one clean shape here too
 * When the app process (the only thing that can hand out a real [AndroidContentReader]) is not wired in,
 * there is simply no seam, and a block that cannot get one fails visibly by name
 * ([dev.pleiades.masamune.flow.runtime.impl.CONTENT_ABSENT]) rather than reporting content it never read.
 *
 * ### Honest failure shapes: not-readable vs. a real "no match"
 * The reads model two genuinely different "negative" cases, and keeping them distinct is the whole point of
 * the honest-gating rule:
 *  - **`null` means "could not be read".** There is no `ContentResolver`, the URI does not resolve, or the
 *    read needs a runtime permission the process was not granted (the real impl catches the
 *    `SecurityException` and returns `null`). The block routes `null` to a visible
 *    [dev.pleiades.masamune.flow.runtime.Outcome.Fail] **by name** — it never fabricates an empty row set or
 *    a blank contact a downstream block would trust as a real reading.
 *  - **A real *empty result* / [ContactMatch.NotFound] means "read fine, and there was nothing to match".** A
 *    calendar query that matched no events (an empty URI list), a content query that returned zero rows, or a
 *    contact lookup that found nobody is a *successful* read with an empty/NO answer — distinct from a read
 *    that could not be performed at all. An empty list is a real state the query block binds and routes to NO
 *    (or OK with an empty array); only an unreadable `null` Fails.
 *
 * ### Runtime permissions shape the *run-time* failure, never keep a read unregistered
 * `calendar_event_get`/`calendar_event_query` carry `READ_CALENDAR` and `contact_query` carries
 * `READ_CONTACTS` — ordinary (dangerous) runtime permissions the Content read subset is predicated on.
 * Exactly as `call_state` is registered despite carrying `READ_PHONE_STATE`, these reads *are* registered:
 * the honest gate for a missing grant is the seam returning `null` and the block failing **by name**, not a
 * fabricated value and not leaving the block unregistered.
 *
 * This slice is entirely read-only over the `ContentResolver` `query()` surface: everything it can touch is
 * another app's content *read* through calendar/contacts/generic queries. Every catalog block that *inserts /
 * updates / deletes / writes* content, *adds* a calendar event / alarm / timer, *touches accounts*
 * (`AccountManager` writes and sync), *offers / shares / views* content through an intent action, *calls* a
 * provider method, *awaits* a content change / offer / share, *copies content to external storage*
 * (`content_read`), reads/writes a flow-local *SQLite* database, or drives a *picker* has no method here and
 * is gated by omission (see [dev.pleiades.masamune.flow.runtime.impl.contentLookup]'s KDoc) — a read-only
 * `query()` seam does not write, offer, call, await, copy-to-storage or pick.
 *
 * Every method is `suspend` because a content read can touch a blocking `ContentResolver`; the real impl does
 * so off the caller's thread without the contract changing shape, and the fake simply returns.
 */
interface ContentReader {

    /**
     * The calendar event at [eventUri], as a plain [CalendarEvent], or `null` when it cannot be read (no
     * `ContentResolver`, the URI resolves to no event, or the `READ_CALENDAR` grant is absent — the real impl
     * catches the `SecurityException` and returns `null`). `null` routes the `calendar_event_get` action to a
     * named Fail; a real event binds every present field and leaves by OK. There is no "empty" case for a
     * single get by URI — an event that cannot be located is an unreadable `null`, never a fabricated blank.
     */
    suspend fun calendarEvent(eventUri: String): CalendarEvent?

    /**
     * The URIs of the calendar events matching [query], or `null` when the calendar cannot be read (no
     * `ContentResolver`, or the `READ_CALENDAR` grant is absent). An **empty list** is a real "no events
     * matched" the `calendar_event_query` decision binds and routes to NO; `null` routes a named Fail. Only
     * the cleanly-mappable filters on [CalendarEventQuery] are honored (see its KDoc).
     */
    suspend fun queryCalendarEvents(query: CalendarEventQuery): List<String>?

    /**
     * The contact matching [queryValue] (interpreted per [valueType], or auto-deduced when `null`):
     * [ContactMatch.Found] with the readable contact fields, [ContactMatch.NotFound] when no contact matched,
     * or `null` when contacts cannot be read (no `ContentResolver`, or the `READ_CONTACTS` grant is absent).
     *
     * Three-valued so "found this contact" is YES, "found nobody" is a real NO, and "cannot read contacts" is
     * a named Fail — distinctions a plain nullable contact object could not carry (mirrors [MobileOperator]).
     */
    suspend fun queryContact(queryValue: String, valueType: String?): ContactMatch?

    /**
     * The rows returned by a generic `ContentResolver` query described by [spec], as a list of rows where
     * each row is a list of nullable cell strings (a `null` cell is a real SQL `NULL`, distinct from an empty
     * string), or `null` when the content cannot be read (no `ContentResolver`, the URI does not resolve, or
     * the process lacks the provider's read permission — the real impl catches the `SecurityException`). An
     * **empty list** is a real "zero rows" the `content_query` action binds (an empty array) and leaves by
     * OK; `null` routes a named Fail. Cells are reported as text — a one-shot simplification of the catalog's
     * richer `resultType`/`columnResultType` typing (see the block KDoc).
     */
    suspend fun query(spec: ContentQuery): List<List<String?>>?
}

/**
 * A calendar event reduced to the values the catalog's `calendar_event_get` outputs use, as plain data — a
 * real data class rather than a leaked `ContentValues`/cursor row. The mapping from a `CalendarContract`
 * cursor to this object lives entirely in [AndroidContentReader], so nothing `android.*` crosses the seam.
 *
 * Every field is nullable because a real event fills only the columns the calendar exposes; the
 * `calendar_event_get` block binds each present field and leaves the absent ones **unbound** rather than a
 * fabricated blank — never an empty title a flow would treat as real. Timestamps are epoch milliseconds.
 */
data class CalendarEvent(
    val calendarUri: String? = null,
    val beginTimestamp: Long? = null,
    val endTimestamp: Long? = null,
    val allDay: Boolean? = null,
    val timeZone: String? = null,
    val title: String? = null,
    val description: String? = null,
    val locationName: String? = null,
    val color: Int? = null,
    val attendees: List<String>? = null,
    val availability: String? = null,
    val accessLevel: String? = null,
)

/**
 * The cleanly-mappable subset of the `calendar_event_query` filter, as plain data. A one-shot query cannot
 * faithfully reconstruct every donor filter, so the arguments with no honest one-shot meaning — `startOffset`
 * / `endOffset` (relative-window offsets), `attendees` and `availability` — are **not** modelled and are
 * documented as ignored by the block rather than half-applied (the same honest simplification by which the
 * Telephony blocks ignore `subscriptionId`). Timestamps are epoch milliseconds; a `null` field is "no
 * constraint on this column".
 */
data class CalendarEventQuery(
    val calendarUri: String? = null,
    val minTimestamp: Long? = null,
    val maxTimestamp: Long? = null,
    val title: String? = null,
    val description: String? = null,
    val locationName: String? = null,
    val allDay: Boolean? = null,
)

/**
 * The result of a `contact_query` lookup that the seam *could* read. Distinct from the seam method's `null`
 * (contacts could not be read at all), so the `contact_query` decision tells "found nobody" (NO) apart from
 * "cannot read contacts" (a named Fail) — the same three-valued shape [MobileOperator] gives the Telephony
 * operator read.
 */
sealed interface ContactMatch {
    /**
     * A contact was found — the decision's YES branch, carrying the readable contact fields. Every field is
     * nullable because a real contact fills only the data the provider exposes; the block binds each present
     * field and leaves the absent ones **unbound** rather than a fabricated blank.
     *
     *  - [displayName] — `varDisplayName`.
     *  - [nickname] — `varNickname`.
     *  - [company] — `varCompany`.
     *  - [phoneNumber] — the primary phone number (`varPhoneNumber`).
     *  - [email] — the primary e-mail (`varEmail`).
     *  - [postalAddress] — the primary postal address (`varPostalAddress`).
     *  - [groups] — the contact's groups (`varGroups`).
     *  - [uri] — the contact's URI (`varUri`).
     */
    data class Found(
        val displayName: String? = null,
        val nickname: String? = null,
        val company: String? = null,
        val phoneNumber: String? = null,
        val email: String? = null,
        val postalAddress: String? = null,
        val groups: List<String>? = null,
        val uri: String? = null,
    ) : ContactMatch

    /** No contact matched the lookup value — a real read with a NO answer (never a Fail). */
    data object NotFound : ContactMatch
}

/**
 * The cleanly-mappable subset of a generic `content_query`, as plain data. The catalog's richer typing
 * arguments (`resultType`, `columnResultType`) are **not** modelled — this one-shot slice reports rows as
 * arrays of text — and are documented as such by the block rather than half-applied. A `null` field is "the
 * documented default" (all columns / all rows / no order).
 *
 *  - [uri] — the content URI to query (always present; a blank URI is a Fail before the seam is reached).
 *  - [projection] — the columns to read, or `null` for all.
 *  - [selection] — the where-clause, or `null` for all content.
 *  - [parameters] — the selection arguments.
 *  - [sortOrder] — the order-by, or `null` for no order.
 *  - [offset] / [limit] — the row window; `null` means the documented default.
 */
data class ContentQuery(
    val uri: String,
    val projection: List<String>? = null,
    val selection: String? = null,
    val parameters: List<String>? = null,
    val sortOrder: String? = null,
    val offset: Int? = null,
    val limit: Int? = null,
)

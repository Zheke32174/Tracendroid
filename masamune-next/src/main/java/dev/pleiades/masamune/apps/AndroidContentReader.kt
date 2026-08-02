package dev.pleiades.masamune.apps

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.CalendarContract
import android.provider.ContactsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The real, device-backed [ContentReader] — the Android glue that turns the plain-data contract into reads of
 * `ContentResolver`, `CalendarContract` and `ContactsContract`.
 *
 * This is the only file in the slice that touches `android.*`, and it is compile-only from the unit tests'
 * point of view: the blocks never see it, they see [ContentReader]. Keeping every framework call on this side
 * of the seam is what lets [dev.pleiades.masamune.flow.runtime.impl.ContentBlocks] stay JVM-testable against
 * a fake.
 *
 * ### STATE READS ONLY
 * There is deliberately no way to insert/update/delete/write content, add a calendar event/alarm/timer, touch
 * accounts, offer/share/view content, call a provider method, await a content change, copy content to
 * external storage, or read/write a SQLite file from here. This class runs `ContentResolver` `query()` calls
 * against calendar/contacts/generic content and nothing else; every mutating, intent, await, copy, SQLite and
 * picker Content block is gated by omission in [dev.pleiades.masamune.flow.runtime.impl.contentLookup] and
 * has no glue here.
 *
 * ### Honest boundaries — a missing reading is `null`, never a fabricated state
 *  - **No `ContentResolver` / an unresolved URI is `null`, not a guess.** The block routes `null` to a named
 *    Fail — never a fabricated empty row set or blank contact.
 *  - **A permission-refused read is `null`.** Reads guarded by `READ_CALENDAR`/`READ_CONTACTS` or a
 *    provider's own read permission catch the `SecurityException` and return `null` — the block Fails "by
 *    name" on a missing grant rather than pretending to know the content.
 *  - **A real "empty result" is an empty list, not `null`.** A calendar query that matched no events, a
 *    content query that returned zero rows, or a contact lookup that found nobody is a successful read the
 *    block routes to NO / OK-with-empty-array, kept distinct from the unreadable `null`.
 */
class AndroidContentReader(private val context: Context) : ContentReader {

    private val resolver get() = context.contentResolver

    override suspend fun calendarEvent(eventUri: String): CalendarEvent? = withContext(Dispatchers.IO) {
        val uri = runCatching { Uri.parse(eventUri) }.getOrNull() ?: return@withContext null
        try {
            resolver.query(uri, null, null, null, null)?.use { c ->
                if (!c.moveToFirst()) return@withContext null
                CalendarEvent(
                    calendarUri = c.longOrNull(CalendarContract.Events.CALENDAR_ID)?.let {
                        ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI, it).toString()
                    },
                    beginTimestamp = c.longOrNull(CalendarContract.Events.DTSTART),
                    endTimestamp = c.longOrNull(CalendarContract.Events.DTEND),
                    allDay = c.longOrNull(CalendarContract.Events.ALL_DAY)?.let { it != 0L },
                    timeZone = c.stringOrNull(CalendarContract.Events.EVENT_TIMEZONE),
                    title = c.stringOrNull(CalendarContract.Events.TITLE),
                    description = c.stringOrNull(CalendarContract.Events.DESCRIPTION),
                    locationName = c.stringOrNull(CalendarContract.Events.EVENT_LOCATION),
                    color = c.longOrNull(CalendarContract.Events.EVENT_COLOR)?.toInt(),
                    availability = c.longOrNull(CalendarContract.Events.AVAILABILITY)?.toString(),
                    accessLevel = c.longOrNull(CalendarContract.Events.ACCESS_LEVEL)?.toString(),
                )
            }
        } catch (_: SecurityException) {
            null // READ_CALENDAR not granted — honest null, the block Fails by name
        } catch (_: RuntimeException) {
            null
        }
    }

    override suspend fun queryCalendarEvents(query: CalendarEventQuery): List<String>? =
        withContext(Dispatchers.IO) {
            val selection = StringBuilder()
            val argsList = ArrayList<String>()
            fun and(clause: String, vararg values: String) {
                if (selection.isNotEmpty()) selection.append(" AND ")
                selection.append(clause)
                argsList.addAll(values)
            }
            query.minTimestamp?.let { and("${CalendarContract.Events.DTSTART} >= ?", it.toString()) }
            query.maxTimestamp?.let { and("${CalendarContract.Events.DTSTART} <= ?", it.toString()) }
            query.title?.let { and("${CalendarContract.Events.TITLE} LIKE ?", it) }
            query.description?.let { and("${CalendarContract.Events.DESCRIPTION} LIKE ?", it) }
            query.locationName?.let { and("${CalendarContract.Events.EVENT_LOCATION} LIKE ?", it) }
            query.allDay?.let { and("${CalendarContract.Events.ALL_DAY} = ?", if (it) "1" else "0") }
            try {
                resolver.query(
                    CalendarContract.Events.CONTENT_URI,
                    arrayOf(CalendarContract.Events._ID),
                    selection.toString().ifEmpty { null },
                    argsList.toTypedArray().ifEmpty { null },
                    null,
                )?.use { c ->
                    val uris = ArrayList<String>()
                    while (c.moveToNext()) {
                        val id = c.longOrNull(CalendarContract.Events._ID) ?: continue
                        uris.add(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id).toString())
                    }
                    uris
                }
            } catch (_: SecurityException) {
                null // READ_CALENDAR not granted — honest null, the block Fails by name
            } catch (_: RuntimeException) {
                null
            }
        }

    override suspend fun queryContact(queryValue: String, valueType: String?): ContactMatch? =
        withContext(Dispatchers.IO) {
            try {
                val uri = Uri.withAppendedPath(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI,
                    Uri.encode(queryValue),
                )
                resolver.query(uri, null, null, null, null)?.use { c ->
                    if (!c.moveToFirst()) return@withContext ContactMatch.NotFound
                    val contactId = c.longOrNull(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                    ContactMatch.Found(
                        displayName = c.stringOrNull(ContactsContract.Contacts.DISPLAY_NAME),
                        phoneNumber = c.stringOrNull(ContactsContract.CommonDataKinds.Phone.NUMBER),
                        uri = contactId?.let {
                            ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, it).toString()
                        },
                    )
                } ?: ContactMatch.NotFound
            } catch (_: SecurityException) {
                null // READ_CONTACTS not granted — honest null, the block Fails by name
            } catch (_: RuntimeException) {
                null
            }
        }

    override suspend fun query(spec: ContentQuery): List<List<String?>>? = withContext(Dispatchers.IO) {
        val uri = runCatching { Uri.parse(spec.uri) }.getOrNull() ?: return@withContext null
        try {
            resolver.query(
                uri,
                spec.projection?.toTypedArray(),
                spec.selection,
                spec.parameters?.toTypedArray(),
                spec.sortOrder,
            )?.use { c ->
                val rows = ArrayList<List<String?>>()
                val width = c.columnCount
                while (c.moveToNext()) {
                    val row = ArrayList<String?>(width)
                    for (col in 0 until width) {
                        row.add(if (c.isNull(col)) null else c.getString(col))
                    }
                    rows.add(row)
                }
                rows
            }
        } catch (_: SecurityException) {
            null // provider read permission not granted — honest null, the block Fails by name
        } catch (_: RuntimeException) {
            null
        }
    }

    /** The [column]'s value as a nullable string, or null when the column is absent from the cursor. */
    private fun Cursor.stringOrNull(column: String): String? {
        val idx = getColumnIndex(column)
        return if (idx < 0 || isNull(idx)) null else getString(idx)
    }

    /** The [column]'s value as a nullable long, or null when the column is absent or null. */
    private fun Cursor.longOrNull(column: String): Long? {
        val idx = getColumnIndex(column)
        return if (idx < 0 || isNull(idx)) null else getLong(idx)
    }
}

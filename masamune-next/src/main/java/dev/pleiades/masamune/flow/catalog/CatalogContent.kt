package dev.pleiades.masamune.flow.catalog

import dev.pleiades.masamune.flow.model.BlockCategory
import dev.pleiades.masamune.flow.model.BlockSpec

/**
 * Content providers, accounts, calendars, contacts and SQLite.
 *
 * This is the category where Android's runtime permissions bite hardest, and where the
 * distinction between reading data and letting the user hand it over matters: `Contact query`
 * carries [READ_CONTACTS] because it reads the provider directly, while `Contact pick` carries
 * nothing because the user picking a contact *is* the grant.
 *
 * Blocks are listed in Automate's own palette order, which is the order this catalog and the
 * palette both render. See `docs/donors/RE-automate.md`.
 */
internal val CONTENT_BLOCKS: List<BlockSpec> = category(BlockCategory.CONTENT) {
    accountsAlarmsCalendarsAndContacts()
    contentProvidersAndDatabases()
}

/** Accounts and their sync state, alarms, calendar events and contacts. */
private fun Blocks.accountsAlarmsCalendarsAndContacts() {
    action(
        "account_generic_add", "Account generic add",
        "Adds or replaces a generic credentials account.",
        args = listOf(
            text("accountName", "Account name"),
            any("username", "Username"),
            any("password", "Password"),
        ),
    )
    decision(
        "account_pick", "Account pick",
        "Lets the user choose an account.",
        args = listOf(
            any("accountType", "Account type", "any"),
            num("timeout", "Timeout", "no timeout"),
            any("notificationChannelId", "Notification channel", "the flow default or Flow"),
            flag("startActivity", "Show window"),
        ),
        outputs = listOf(
            out("varAccountName", "Account name"),
            out("varAccountType", "Account type"),
        ),
    )
    action(
        "account_sync_request", "Account sync request",
        "Requests a manual account data sync.",
        args = listOf(
            any("accountName", "Account name", "all accessible accounts"),
            any("accountType", "Account type", "all types"),
            any("authority", "Data authority", "everything"),
        ),
    )
    decision(
        "account_sync_enabled", "Account sync enabled",
        "Checks if automatic account data sync is enabled or disabled.",
        proceed = WATCH,
        args = listOf(
            any("accountName", "Account name", "all accessible accounts"),
            any("accountType", "Account type", "all types"),
            any("authority", "Data authority", "everything"),
        ),
    )
    action(
        "account_sync_set_state", "Account sync set state",
        "Enables or disables automatic account data sync.",
        args = listOf(
            any("accountName", "Account name", "all accessible accounts"),
            any("accountType", "Account type", "all types"),
            any("authority", "Data authority", "everything"),
            flag("state", "Auto-sync"),
        ),
    )
    decision(
        "alarm", "Alarm",
        "Gets or awaits the next alarm in the default Clock app.",
        proceed = WATCH,
        outputs = listOf(
            out("varAlarmTimestamp", "Alarm timestamp"),
        ),
    )
    action(
        "alarm_add", "Alarm add",
        "Adds an alarm in the default Clock app.",
        args = listOf(
            num("timeOfDay", "Time of day", "midnight"),
            text("label", "Label"),
            any("weekdays", "Repeat weekdays", "no repeat"),
            text("soundUri", "Sound URI"),
            flag("vibrate", "Vibrate"),
        ),
    )
    action(
        "calendar_event_add", "Calendar event add",
        "Adds an event to a calendar.",
        args = listOf(
            text("calendarUri", "Calendar URI"),
            any("beginTimestamp", "Start timestamp", "current time"),
            num("endTimestamp", "End timestamp", "1 hours after start"),
            any("timeZone", "Time zone", "the current time zone"),
            text("title", "Title"),
            any("description", "Description"),
            any("locationName", "Location name"),
            any("color", "Color", "calendar color"),
            any("attendees", "Attendees"),
            any("availability", "Availability"),
            any("accessLevel", "Privacy"),
            any("reminderMethod", "Reminder method", "no reminder"),
            any("reminderPeriod", "Reminder period"),
        ),
        outputs = listOf(
            out("varEventUri", "Created Event URI"),
        ),
        requires = setOf(WRITE_CALENDAR),
    )
    action(
        "calendar_event_get", "Calendar event get",
        "Retrieves calendar event details.",
        args = listOf(
            text("eventUri", "Event URI"),
        ),
        outputs = listOf(
            out("varCalendarURI", "Calendar URI"),
            out("varBeginTimestamp", "Begin timestamp"),
            out("varEndTimestamp", "End timestamp"),
            out("varAllDay", "All day"),
            out("varTimeZone", "TimeZone"),
            out("varTitle", "Title"),
            out("varDescription", "Description"),
            out("varLocationName", "Location name"),
            out("varColor", "Color"),
            out("varAttendees", "Attendees"),
            out("varAvailability", "Availability"),
            out("varAccessLevel", "Privacy"),
        ),
        requires = setOf(READ_CALENDAR),
    )
    decision(
        "calendar_event_query", "Calendar event query",
        "Searches for, or await calendar events.",
        args = listOf(
            text("calendarUri", "Calendar URI", "any visible calendar"),
            any("minTimestamp", "Minimum timestamp", "current time, only used with Immediately"),
            any("maxTimestamp", "Maximum timestamp", "the maximum timestamp, i"),
            num("startOffset", "Start offset", "0"),
            num("endOffset", "End offset", "0"),
            text("title", "Title", "any title, may contain glob pattern"),
            text("description", "Description", "any description, may contain glob pattern"),
            text("locationName", "Location name", "any location, may contain glob pattern"),
            any("attendees", "Attendees", "no attendee necessary"),
            any("availability", "Availability", "any availability"),
            flag("alLDay", "All day"),
        ),
        outputs = listOf(
            out("varEventURIs", "Event URIs"),
        ),
        requires = setOf(READ_CALENDAR),
    )
    decision(
        "calendar_pick", "Calendar pick",
        "Lets the user choose one of the device's calendars.",
        args = listOf(
            flag("writable", "Writable"),
            flag("hidden", "Hidden"),
            num("timeout", "Timeout", "no timeout"),
            any("notificationChannelId", "Notification channel", "the flow default or Flow"),
            flag("startActivity", "Show window"),
        ),
        outputs = listOf(
            out("varCalendarURI", "Calendar URI"),
        ),
        requires = setOf(READ_CALENDAR),
    )
    decision(
        "contact_query", "Contact query",
        "Searches for a contact and retrieves its details.",
        args = listOf(
            any("queryValue", "Lookup value"),
            any("valueType", "Value type", "deduced from the value itself"),
        ),
        outputs = listOf(
            out("varDisplayName", "Display name"),
            out("varNickname", "Nickname"),
            out("varCompany", "Company name"),
            out("varPhoneNumber", "Primary phone number"),
            out("varEmail", "Primary e-mail"),
            out("varPostalAddress", "Primary postal address"),
            out("varGroups", "Groups"),
            out("varUri", "Contact URI"),
        ),
        requires = setOf(READ_CONTACTS),
    )
    decision(
        "contact_pick", "Contact pick",
        "Lets the user choose a contact from the Contacts app.",
        args = listOf(
            num("timeout", "Timeout", "no timeout"),
            any("notificationChannelId", "Notification channel", "the flow default or Flow"),
            flag("startActivity", "Show window"),
        ),
        outputs = listOf(
            out("varContactURI", "Contact URI"),
        ),
    )
    action(
        "content_changed", "Content changed",
        "Awaits change in content provided by another app, such as contacts or calendar " +
            "events.",
        args = listOf(
            text("uri", "Content URI"),
            flag("descendants", "Descendants"),
        ),
        outputs = listOf(
            out("varChangeUri", "Content URI"),
        ),
    )
}

/** Generic content-provider access, sharing, and the flow-local SQLite database. */
private fun Blocks.contentProvidersAndDatabases() {
    action(
        "content_delete", "Content delete",
        "Deletes content in another app, such as contacts or calendar events.",
        args = listOf(
            text("uri", "Content URI"),
            text("selection", "Where clause", "all content"),
            arr("parameters", "Parameters"),
        ),
        outputs = listOf(
            out("varRowCount", "Number of rows deleted"),
        ),
    )
    action(
        "content_insert", "Content insert",
        "Inserts new content into another app, such as contacts or calendar events.",
        args = listOf(
            text("uri", "Content URI"),
            dict("values", "Values"),
        ),
        outputs = listOf(
            out("varRowUri", "Content URI"),
        ),
    )
    action(
        "content_offer", "Content offer",
        "Offers content to other apps.",
        args = listOf(
            text("title", "Title", "the Flow beginning title"),
            any("mimeType", "Content MIME type", "any content"),
        ),
        outputs = listOf(
            out("varContentMimeType", "Content MIME type"),
        ),
    )
    action(
        "content_offer_result", "Content offer result",
        "Gives the offered content to the requesting app.",
        args = listOf(
            text("contentUri", "Content URI", "to cancel the request without content"),
            text("mimeType", "Content MIME type", "resolved from the URI"),
            any("flags", "Flags"),
        ),
    )
    decision(
        "content_pick", "Content pick",
        "Lets the user choose content provided by other apps.",
        args = listOf(
            any("mimeType", "Content MIME type", "any content"),
            flag("persistent", "Persistent"),
            num("timeout", "Timeout", "no timeout"),
            any("notificationChannelId", "Notification channel", "the flow default or Flow"),
            flag("startActivity", "Show window"),
        ),
        outputs = listOf(
            out("varContentURI", "Content URI"),
            out("varContentMimeType", "Content MIME type"),
        ),
    )
    action(
        "content_provider_call", "Content provider call",
        "Calls an method on a content provided.",
        args = listOf(
            text("authority", "Provider authority"),
            text("method", "Call method"),
            any("arg", "Call argument"),
            dict("extras", "Extras"),
        ),
        outputs = listOf(
            out("varResult", "Result"),
        ),
    )
    action(
        "content_query", "Content query",
        "Queries content provided by another app, such as contacts or calendar events.",
        args = listOf(
            text("uri", "Content URI"),
            arr("projection", "Columns", "all"),
            text("selection", "Where clause", "all content"),
            arr("parameters", "Parameters"),
            any("sortOrder", "Order by", "no order"),
            any("offset", "Offset", "0"),
            num("limit", "Limit", "500 (the maximum limit)"),
            arr("resultType", "Result type", "Rows as arrays"),
            any("columnResultType", "Column result types", "Default"),
        ),
        outputs = listOf(
            out("varResult", "Result"),
        ),
    )
    action(
        "content_read", "Content read",
        "Copies content provided by another app to external storage (SD card).",
        args = listOf(
            text("sourceUri", "Content URI"),
            text("targetPath", "Destination path", "a file in the \"Download\" directory"),
        ),
        outputs = listOf(
            out("varContentFile", "Content file"),
            out("varContentDisplayName", "Content display name"),
            out("varContentMimeType", "Content MIME type"),
        ),
    )
    action(
        "content_shared", "Content shared",
        "Awaits content shared/sent from within another app.",
        args = listOf(
            text("title", "Title", "the Flow beginning title"),
            any("mimeType", "Content MIME type", "any content"),
            arr("multiple", "Multiple", "false"),
        ),
        outputs = listOf(
            out("varContentUri", "Content URI"),
            out("varContentMimeType", "Content MIME type"),
            out("varContentText", "Content text"),
            out("varContentSubject", "Content subject"),
        ),
    )
    action(
        "content_update", "Content update",
        "Updates content in another app, such as contacts or calendar events.",
        args = listOf(
            text("uri", "Content URI"),
            text("selection", "Where clause", "all content"),
            arr("parameters", "Parameters"),
            dict("values", "Values"),
        ),
        outputs = listOf(
            out("varRowCount", "Number of rows updated"),
        ),
    )
    action(
        "content_view", "Content view",
        "Opens some content for viewing.",
        args = listOf(
            text("uri", "URI"),
            text("mimeType", "MIME type", "deduced from the URI if necessary"),
            text("packageName", "Package", "system preferred app for the MIME type"),
            flag("chooser", "Chooser"),
        ),
    )
    action(
        "content_write", "Content write",
        "Copies the content of a file on external storage (SD card) to a content provider.",
        args = listOf(
            text("sourcePath", "Source path"),
            text("targetUri", "Content URI"),
            text("openMode", "Open mode", "Write"),
        ),
    )
    action(
        "database_modify", "Database modify",
        "Modifies content stored in a SQLite database file.",
        args = listOf(
            text("databaseFile", "Database file"),
            any("statement", "Statement", "to just open/create the database file"),
            arr("parameters", "Parameters"),
            num("resultType", "Result type", "Number of rows affected"),
        ),
        outputs = listOf(
            out("varResult", "Result"),
        ),
    )
    action(
        "database_query", "Database query",
        "Queries content stored in a SQLite database file.",
        args = listOf(
            text("databaseFile", "Database file"),
            any("statement", "Statement", "to just open the database file"),
            arr("parameters", "Parameters"),
            arr("resultType", "Result type", "Rows as arrays"),
            any("columnResultType", "Column result types", "Default"),
        ),
        outputs = listOf(
            out("varResult", "Result"),
        ),
    )
    decision(
        "keychain_alias_pick", "Keychain credentials pick",
        "Lets the user choose an alias for cryptographic credentials, i.e. key and/or " +
            "certificate, stored in the system keychain.",
        args = listOf(
            any("keyTypes", "Key types", "any type"),
            any("issuers", "Issuer", "any issuer"),
            any("preselectedAlias", "Pre-selected alias", "none"),
            num("timeout", "Timeout", "no timeout"),
            any("notificationChannelId", "Notification channel", "the flow default or Flow"),
            flag("startActivity", "Show window"),
        ),
        outputs = listOf(
            out("varAlias", "Alias"),
        ),
    )
    action(
        "timer_add", "Timer add",
        "Adds a countdown timer in the default Clock app.",
        args = listOf(
            num("duration", "Duration", "10 minutes"),
            text("label", "Label"),
        ),
    )
}

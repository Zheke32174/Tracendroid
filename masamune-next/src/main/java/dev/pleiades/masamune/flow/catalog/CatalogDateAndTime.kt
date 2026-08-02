package dev.pleiades.masamune.flow.catalog

import dev.pleiades.masamune.flow.model.BlockCategory
import dev.pleiades.masamune.flow.model.BlockSpec
import dev.pleiades.masamune.flow.model.ProceedMode

/**
 * Waiting, and asking about the clock.
 *
 * `Delay`, `Time await` and `Time window` are the three blocks in the catalog with no
 * [ProceedMode] list, and the omission is the faithful reading: Automate spends their Proceed
 * field on alarm accuracy rather than on tense, so the field is carried as the enumeration it
 * actually is ([TIMING_ACCURACY], [TIME_WINDOW_PROCEED]) and not translated into a tense it
 * does not express. Inexact lets Android batch the wake-up with every other app's; it is the
 * difference between a scheduled flow that costs nothing and one that holds a wake-lock.
 *
 * Blocks are listed in Automate's own palette order, which is the order this catalog and the
 * palette both render. See `docs/donors/RE-automate.md`.
 */
internal val DATE_AND_TIME_BLOCKS: List<BlockSpec> = category(BlockCategory.DATE_AND_TIME) {
    action(
        "delay", "Delay",
        "Delays execution, pause the fiber for a set duration.",
        options = listOf(TIMING_ACCURACY),
        args = listOf(
            num("duration", "Duration"),
            flag("wakeup", "Wake up"),
        ),
    )
    decision(
        "date_pick", "Date pick",
        "Lets the user choose a date.",
        args = listOf(
            text("title", "Title", "no title"),
            any("style", "Style", "Keypad"),
            any("initialTimestamp", "Initial date", "none"),
            num("timeout", "Timeout", "no timeout"),
            any("notificationChannelId", "Notification channel", "the flow default or Flow"),
            flag("startActivity", "Show window"),
        ),
        outputs = listOf(
            out("varTimestamp", "Picked date"),
        ),
    )
    decision(
        "duration_pick", "Duration pick",
        "Lets the user choose a duration time.",
        args = listOf(
            text("title", "Title", "no title"),
            flag("showSeconds", "Seconds precision"),
            flag("signed", "Signed"),
            num("initialDuration", "Initial duration", "none"),
            num("timeout", "Timeout", "no timeout"),
            any("notificationChannelId", "Notification channel", "the flow default or Flow"),
            flag("startActivity", "Show window"),
        ),
        outputs = listOf(
            out("varDuration", "Duration"),
        ),
    )
    decision(
        "time_await", "Time await",
        "Awaits a specific or recurring time of day.",
        options = listOf(TIMING_ACCURACY),
        args = listOf(
            num("timeOfDay", "Time of day", "midnight"),
            any("weekdays", "Weekdays", "every weekday"),
            num("dayOfMonth", "Day of month", "every day of the month"),
            any("months", "Month", "every month"),
            any("year", "Year", "every year"),
            any("timeZone", "Time zone", "the current time zone"),
            flag("wakeup", "Wake up"),
        ),
    )
    decision(
        "time_pick", "Time pick",
        "Lets the user choose a time of day.",
        args = listOf(
            text("title", "Title", "no title"),
            any("style", "Style", "Keypad"),
            num("initialTimeOfDay", "Initial time of day", "none"),
            num("timeout", "Timeout", "no timeout"),
            any("notificationChannelId", "Notification channel", "the flow default or Flow"),
            flag("startActivity", "Show window"),
        ),
        outputs = listOf(
            out("varTimeOfDay", "Picked time of day"),
        ),
    )
    decision(
        "time_window", "Time window",
        "Checks, or awaits a specific or recurring window of time.",
        options = listOf(TIME_WINDOW_PROCEED),
        args = listOf(
            any("timestamp", "Timestamp", "current time, only used with Immediately"),
            num("duration", "Duration", "the duration until midnight"),
            num("timeOfDay", "Time of day", "midnight"),
            any("weekdays", "Weekdays", "every weekday"),
            num("dayOfMonth", "Day of month", "every day of the month"),
            any("months", "Month", "every month"),
            any("year", "Year", "every year"),
            any("timeZone", "Time zone", "the current time zone"),
            flag("wakeup", "Wake up"),
        ),
    )
    action(
        "time_zone_get", "Time zone get",
        "Gets the time zone used by the device.",
        proceed = WATCH_VALUE,
        outputs = listOf(
            out("varTimeZoneId", "Time zone ID"),
            out("varTimeZoneOffset", "Time zone offset"),
        ),
    )
}

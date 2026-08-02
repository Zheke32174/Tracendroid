package dev.pleiades.masamune.flow.catalog

import dev.pleiades.masamune.flow.model.ArgSpec
import dev.pleiades.masamune.flow.model.ArgType
import dev.pleiades.masamune.flow.model.BlockCategory
import dev.pleiades.masamune.flow.model.BlockShape
import dev.pleiades.masamune.flow.model.BlockSpec
import dev.pleiades.masamune.flow.model.OptionSpec
import dev.pleiades.masamune.flow.model.OutSpec
import dev.pleiades.masamune.flow.model.ProceedMode
import dev.pleiades.masamune.flow.model.Requirement

/*
 * The vocabulary the sixteen catalog files are written in.
 *
 * 418 specs is a data table, not prose, and a data table is only auditable against its donor
 * if one row occupies few enough lines to hold in the eye at once. Constructing `BlockSpec`
 * directly costs six named arguments and a `BlockCategory` repeated 418 times; the builder
 * below binds the category once per file and names the shape by which function you call, so a
 * row reduces to what actually varies between blocks.
 *
 * Everything here is `internal`: the catalog's public surface is `BlockCatalog`, and a caller
 * that reaches for `text(...)` is building a spec at run time, which is exactly the thing a
 * static catalog exists to prevent.
 */

/**
 * Collects the specs of one [BlockCategory].
 *
 * [action] and [decision] are separate functions rather than one function taking a
 * [BlockShape] because the shape is the single most consequential and most easily mistyped
 * field in a spec — a `DECISION` written as an `ACTION` silently loses the NO path and every
 * flow built on it routes wrong. Making it the verb puts it where a reader's eye lands first
 * and where a diff cannot hide it.
 */
internal class Blocks(private val category: BlockCategory) {
    private val specs = ArrayList<BlockSpec>(72)

    /** One `IN`, one outcome: [dev.pleiades.masamune.flow.model.Port.OK]. */
    fun action(
        id: String,
        name: String,
        summary: String,
        proceed: List<ProceedMode> = emptyList(),
        options: List<OptionSpec> = emptyList(),
        args: List<ArgSpec> = emptyList(),
        outputs: List<OutSpec> = emptyList(),
        requires: Set<Requirement> = emptySet(),
    ) = add(BlockShape.ACTION, id, name, summary, proceed, options, args, outputs, requires)

    /** One `IN`, two outcomes: YES and NO. */
    fun decision(
        id: String,
        name: String,
        summary: String,
        proceed: List<ProceedMode> = emptyList(),
        options: List<OptionSpec> = emptyList(),
        args: List<ArgSpec> = emptyList(),
        outputs: List<OutSpec> = emptyList(),
        requires: Set<Requirement> = emptySet(),
    ) = add(BlockShape.DECISION, id, name, summary, proceed, options, args, outputs, requires)

    private fun add(
        shape: BlockShape,
        id: String,
        name: String,
        summary: String,
        proceed: List<ProceedMode>,
        options: List<OptionSpec>,
        args: List<ArgSpec>,
        outputs: List<OutSpec>,
        requires: Set<Requirement>,
    ) {
        specs += BlockSpec(
            id = id,
            name = name,
            category = category,
            shape = shape,
            summary = summary,
            options = options,
            proceedModes = proceed,
            args = args,
            outputs = outputs,
            requires = requires,
        )
    }

    internal fun build(): List<BlockSpec> = specs
}

/** Builds one category's block list, in Automate's own palette order. */
internal fun category(category: BlockCategory, build: Blocks.() -> Unit): List<BlockSpec> =
    Blocks(category).apply(build).build()

// ---------------------------------------------------------------- arguments

/*
 * The `key` passed to every helper below is Automate's own field identifier, lifted from the
 * `id` attribute of the corresponding list item on `llamalab.com/automate/doc/block/<id>.html`.
 * Using the donor's identifier rather than a tidied-up one is what lets a ported flow file be
 * checked against the donor's documentation field by field — including its typos, which is why
 * `wordDir` and similar appear here spelled exactly as the donor spells them.
 *
 * The third parameter is the *documented default*, not a value: Automate leaves nearly every
 * argument blank-able, and an argument whose blank behaviour is undocumented is one the user
 * has to discover by experiment.
 */

internal fun text(key: String, label: String, default: String? = null): ArgSpec =
    ArgSpec(key, label, ArgType.TEXT, optional = true, defaultBlurb = default)

internal fun num(key: String, label: String, default: String? = null): ArgSpec =
    ArgSpec(key, label, ArgType.NUMBER, optional = true, defaultBlurb = default)

internal fun flag(key: String, label: String, default: String? = null): ArgSpec =
    ArgSpec(key, label, ArgType.BOOLEAN, optional = true, defaultBlurb = default)

internal fun arr(key: String, label: String, default: String? = null): ArgSpec =
    ArgSpec(key, label, ArgType.ARRAY, optional = true, defaultBlurb = default)

internal fun dict(key: String, label: String, default: String? = null): ArgSpec =
    ArgSpec(key, label, ArgType.DICT, optional = true, defaultBlurb = default)

/**
 * An argument whose type the donor's documentation does not pin down.
 *
 * [ArgType.ANY] is a deliberate answer, not a gap. Most of these are Automate's enumerated
 * constants (audio focus, notification category, scan mode) which it accepts as either a
 * number or one of its own named constants, and a few are genuinely polymorphic. Guessing
 * `NUMBER` at one of them would make the editor reject a valid expression, which is a worse
 * failure than declining to narrow the type.
 */
internal fun any(key: String, label: String, default: String? = null): ArgSpec =
    ArgSpec(key, label, ArgType.ANY, optional = true, defaultBlurb = default)

internal fun out(key: String, label: String, blurb: String? = null): OutSpec =
    OutSpec(key, label, blurb)

// ------------------------------------------------------------------ proceed

/**
 * A condition block's Proceed set: test the condition now, or suspend until it changes.
 *
 * This is the shape Automate gives almost every decision block, and it is the whole of organ
 * 2 — `Wi-Fi enabled` with [ProceedMode.IMMEDIATELY] is a constraint check, the same block
 * with [ProceedMode.ON_ENTER] is an event trigger, and there is only one block either way.
 */
internal val WATCH: List<ProceedMode> = listOf(
    ProceedMode.IMMEDIATELY,
    ProceedMode.ON_ENTER,
    ProceedMode.ON_EXIT,
    ProceedMode.ON_CHANGE,
)

/**
 * A getter's Proceed set: read the value now, or suspend until it changes.
 *
 * Distinct from [WATCH] because there is no condition to enter or leave — `Clipboard get` has
 * no true and false state, only an old value and a new one.
 */
internal val WATCH_VALUE: List<ProceedMode> = listOf(
    ProceedMode.IMMEDIATELY,
    ProceedMode.ON_CHANGE,
)

/** A task's Proceed set: fire and continue, or suspend until the task finishes. */
internal val AWAIT: List<ProceedMode> = listOf(
    ProceedMode.IMMEDIATELY,
    ProceedMode.ON_COMPLETION,
)

// ------------------------------------------------------------------ options

/**
 * A yes/no compile-time option.
 *
 * The default is `no` throughout, which is the conservative reading: every option expressed
 * this way turns an extra behaviour on (hide this beginning, stop the child with its parent,
 * allow a secure lock to be disabled), so `no` is the behaviour you get by not choosing.
 */
internal fun flagOption(key: String, label: String): OptionSpec = OptionSpec(
    key = key,
    label = label,
    choices = listOf(OptionSpec.Choice("no", "No"), OptionSpec.Choice("yes", "Yes")),
    defaultChoice = "no",
)

/**
 * Automate's Proceed option on `Delay` and `Time await`, which enumerates alarm accuracy rather
 * than tense and so cannot be a [ProceedMode].
 *
 * Kept as a plain option instead of being forced into the enum: `Inexact` lets Android batch
 * the wake-up with other alarms and is the reason a scheduled flow does not drain the battery,
 * so it is a real choice with a real cost, not an implementation detail. Both blocks always
 * suspend, so there is no tense left for a [ProceedMode] list to express and they carry none.
 */
internal val TIMING_ACCURACY: OptionSpec = OptionSpec(
    key = "continuity",
    label = "Accuracy",
    choices = listOf(
        OptionSpec.Choice("inexact", "Inexact"),
        OptionSpec.Choice("exact", "Exact"),
    ),
    defaultChoice = "inexact",
)

/**
 * `Time window`'s Proceed option, which is the one place Automate puts tense and accuracy in the
 * same field.
 *
 * `Immediately` tests whether now is inside the window; `Inexact` and `Exact` both suspend —
 * to the start of the window on an odd visit and to its end on an even one — and differ only
 * in alarm precision. Splitting that into a [ProceedMode] list plus [TIMING_ACCURACY] would
 * present the user with two dropdowns where the donor has one, and would let them select a
 * combination the donor cannot represent.
 */
internal val TIME_WINDOW_PROCEED: OptionSpec = OptionSpec(
    key = "continuity",
    label = "Proceed",
    choices = listOf(
        OptionSpec.Choice("immediately", "Immediately"),
        OptionSpec.Choice("inexact", "Inexact"),
        OptionSpec.Choice("exact", "Exact"),
    ),
    defaultChoice = "immediately",
)

/** Which stage of an incoming call `Call incoming` resumes on. */
internal val INCOMING_CALL_STAGE: OptionSpec = OptionSpec(
    key = "continuity",
    label = "Proceed",
    choices = listOf(
        OptionSpec.Choice("ringing", "When ringing"),
        OptionSpec.Choice("answered", "When answered"),
        OptionSpec.Choice("missed", "When missed"),
        OptionSpec.Choice("hung_up", "When hung up"),
    ),
    defaultChoice = "ringing",
)

/** Which stage of an outgoing call `Call outgoing` resumes on. */
internal val OUTGOING_CALL_STAGE: OptionSpec = OptionSpec(
    key = "continuity",
    label = "Proceed",
    choices = listOf(
        OptionSpec.Choice("dialing", "When dialing"),
        OptionSpec.Choice("hung_up", "When hung up"),
    ),
    defaultChoice = "dialing",
)

/**
 * How `Key send` / `Key send characters` inject their events.
 *
 * Both routes are gated — the soft-keyboard route needs Masamune's IME set as the system
 * default, the accessibility route needs the service and Android 13+ — so this option does not
 * relax [Requirement.Accessibility] on those blocks. It picks which of two unsatisfiable-by-
 * default mechanisms to try, and the donor documents that a disconnected mechanism takes the
 * NO path rather than failing, which is the only reason the choice can be made at edit time.
 */
internal val KEY_EVENT_METHOD: OptionSpec = OptionSpec(
    key = "method",
    label = "Method",
    choices = listOf(
        OptionSpec.Choice("input_method", "Input method"),
        OptionSpec.Choice("accessibility", "Accessibility"),
    ),
    defaultChoice = "input_method",
)

// -------------------------------------------------------------- requirements

/*
 * Shorthands for the gates. Named tersely on purpose: they appear on roughly a fifth of the
 * 418 rows, and a long name there pushes the row onto a second line and buries the block.
 */

/** An enabled AccessibilityService. */
internal val A11Y: Requirement = Requirement.Accessibility

/** The uid-2000 prefix reached through the Yojimbo server. */
internal val SHELL: Requirement = Requirement.Uid2000

/** An enabled NotificationListenerService. */
internal val NOTIF: Requirement = Requirement.NotificationListener

/** An active DeviceAdminReceiver. */
internal val ADMIN: Requirement = Requirement.DeviceAdmin

private fun perm(name: String) = Requirement.Permission("android.permission.$name")

internal val ACCESS_FINE_LOCATION: Requirement = perm("ACCESS_FINE_LOCATION")
internal val ACTIVITY_RECOGNITION: Requirement = perm("ACTIVITY_RECOGNITION")
internal val ANSWER_PHONE_CALLS: Requirement = perm("ANSWER_PHONE_CALLS")
internal val BLUETOOTH_CONNECT: Requirement = perm("BLUETOOTH_CONNECT")
internal val BLUETOOTH_SCAN: Requirement = perm("BLUETOOTH_SCAN")
internal val BODY_SENSORS: Requirement = perm("BODY_SENSORS")
internal val CALL_PHONE: Requirement = perm("CALL_PHONE")
internal val CAMERA: Requirement = perm("CAMERA")
internal val POST_NOTIFICATIONS: Requirement = perm("POST_NOTIFICATIONS")
internal val READ_CALENDAR: Requirement = perm("READ_CALENDAR")
internal val READ_CONTACTS: Requirement = perm("READ_CONTACTS")
internal val READ_PHONE_STATE: Requirement = perm("READ_PHONE_STATE")
internal val READ_SMS: Requirement = perm("READ_SMS")
internal val RECEIVE_SMS: Requirement = perm("RECEIVE_SMS")
internal val RECORD_AUDIO: Requirement = perm("RECORD_AUDIO")
internal val SEND_SMS: Requirement = perm("SEND_SMS")
internal val WRITE_CALENDAR: Requirement = perm("WRITE_CALENDAR")

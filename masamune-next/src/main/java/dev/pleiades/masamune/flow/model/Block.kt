package dev.pleiades.masamune.flow.model

/**
 * The graph grammar, ported from Automate (see `docs/donors/RE-automate.md`).
 *
 * Automate builds 418 blocks on exactly two shapes, and that austerity is the reason its
 * flows stay readable on a phone screen. We take the same two and add no third: a shape
 * that exists is a shape every editor, serializer, runtime and renderer must handle
 * forever.
 *
 * Conditional control flow comes from [Decision] rather than from a separate `IF` node fed
 * by an upstream node's output — the condition is a property of the block that observed it.
 * That is what lets `Location at` be one block instead of a trigger and a check.
 */
enum class BlockShape {
    /** One or more `IN`, exactly one outcome: [Port.OK]. */
    ACTION,

    /** One or more `IN`, two outcomes: [Port.YES] and [Port.NO]. */
    DECISION,
}

/**
 * An outgoing connector on a block. Incoming connections are not ports — a block has a
 * single logical `IN` that accepts any number of edges, so there is nothing to name.
 */
enum class Port {
    OK,
    YES,
    NO;

    companion object {
        fun of(shape: BlockShape): List<Port> = when (shape) {
            BlockShape.ACTION -> listOf(OK)
            BlockShape.DECISION -> listOf(YES, NO)
        }
    }
}

/**
 * What a block needs before it can do anything.
 *
 * This is the honest-gating mechanism, and it is stricter in the flow plane than elsewhere
 * in the suite for a specific reason: a block that silently no-ops does not fail visibly,
 * it makes every downstream block in the graph wrong. So an unsatisfied requirement
 * disables the block in the palette with the requirement named, and the editor refuses to
 * place it. There is no degraded mode.
 */
sealed class Requirement(val label: String) {
    /** An enabled AccessibilityService — the `Interact` / `Inspect *` / `Interface *` family. */
    data object Accessibility : Requirement("Accessibility service")

    /** The uid-2000 prefix at `/data/local/tmp/masamune`, reached via the Yojimbo server. */
    data object Uid2000 : Requirement("Privileged shell (uid 2000)")

    data object NotificationListener : Requirement("Notification access")

    data object DeviceAdmin : Requirement("Device admin")

    /** A named donor payload that is a build input, on the `libtailscale` pattern. */
    data class Payload(val name: String) : Requirement("Payload: $name")

    /** A runtime Android permission. */
    data class Permission(val androidPermission: String) :
        Requirement("Permission: ${androidPermission.substringAfterLast('.')}")
}

/**
 * A compile-time choice. Fixed when the user edits the flow; a running fiber never sees it
 * change. `Proceed` is one of these — see [ProceedMode].
 */
data class OptionSpec(
    val key: String,
    val label: String,
    val choices: List<Choice>,
    val defaultChoice: String,
) {
    data class Choice(val value: String, val label: String)
}

/**
 * The `Proceed` option, which is the highest-value organ taken from Automate.
 *
 * Other automation apps split "event trigger" from "condition check" and so carry two
 * palettes; Automate makes it a mode on one block. `Location at` with [IMMEDIATELY] asks
 * whether you are at a place now; the same block with [ON_ENTER] waits until you arrive.
 * The block is the subject and Proceed is the tense.
 *
 * Not every block offers every mode — [BlockSpec.proceedModes] narrows it, and a block
 * with no meaningful choice omits the option entirely.
 */
enum class ProceedMode(val label: String) {
    /** Evaluate the current state and continue at once. */
    IMMEDIATELY("Immediately"),

    /** Suspend the fiber until the block's task completes. */
    ON_COMPLETION("On completion"),

    /** Suspend until the observed condition becomes true. */
    ON_ENTER("When true"),

    /** Suspend until the observed condition becomes false. */
    ON_EXIT("When false"),

    /** Suspend until the condition changes in either direction. */
    ON_CHANGE("On any change"),
}

/**
 * A runtime input. Evaluated per fiber, may be an expression over variables.
 *
 * [optional] is the common case, not the exception: Automate leaves nearly every argument
 * blank-able with a documented default, which is what keeps a 418-block palette usable.
 * [defaultBlurb] is that documented default, shown in the editor — an argument whose
 * default behaviour is undocumented is one the user must guess at.
 */
data class ArgSpec(
    val key: String,
    val label: String,
    val type: ArgType,
    val optional: Boolean = true,
    val defaultBlurb: String? = null,
)

enum class ArgType { TEXT, NUMBER, BOOLEAN, ARRAY, DICT, ANY }

/**
 * A runtime output binding. Holds a bare variable *name* — never an expression.
 *
 * The asymmetry with [ArgSpec] is deliberate and load-bearing: inputs are expressions,
 * outputs are names. An editor that accepts an expression in an output field is one that
 * cannot bind a result to anything.
 */
data class OutSpec(
    val key: String,
    val label: String,
    val blurb: String? = null,
)

/**
 * The static description of one block type. The catalog holds 418 of these.
 *
 * [id] mirrors Automate's own class name from its documentation URL (`activity_start`,
 * `location_at`), which keeps a ported block traceable to its origin and gives flow files
 * a stable key that survives display-name changes.
 */
data class BlockSpec(
    val id: String,
    val name: String,
    val category: BlockCategory,
    val shape: BlockShape,
    val summary: String,
    val options: List<OptionSpec> = emptyList(),
    val proceedModes: List<ProceedMode> = emptyList(),
    val args: List<ArgSpec> = emptyList(),
    val outputs: List<OutSpec> = emptyList(),
    val requires: Set<Requirement> = emptySet(),
) {
    val ports: List<Port> get() = Port.of(shape)
}

/**
 * Automate's sixteen palette groups, in its own order and with its own headings.
 *
 * Rule 0 — port the donor faithfully first. The grouping is part of how the app *looks*,
 * and reordering or renaming these to something more logical is exactly the class of
 * well-meant drift that produced the earlier rounds' plausible-but-wrong surfaces.
 */
enum class BlockCategory(val id: String, val label: String) {
    APPS("apps", "Apps"),
    BATTERY_AND_POWER("battery_and_power", "Battery & power"),
    CAMERA_AND_SOUND("camera_and_sound", "Camera & sound"),
    CONCURRENCY("concurrency", "Concurrency"),
    CONNECTIVITY("connectivity", "Connectivity"),
    CONTENT("content", "Content"),
    DATE_AND_TIME("date_and_time", "Date & time"),
    STORAGE("storage", "File & storage"),
    FLOW("flow", "Flow"),
    GENERAL("general", "General"),
    INTERFACE("interface", "Interface"),
    LOCATION("location", "Location"),
    MESSAGING("messaging", "Messaging"),
    SENSOR("sensor", "Sensor"),
    SETTINGS("settings", "Settings"),
    TELEPHONY("telephony", "Telephony"),
}

package dev.pleiades.masamune.core.capability

/**
 * The capability taxonomy the gate keys on.
 *
 * A capability describes the *kind* of effect an operation has on the device, not a specific
 * operation. Grants are stored per (caller x capability); granting one never implies another.
 * Classification is conservative: when an operation spans two classes it takes the more
 * dangerous one, and anything unrecognised is [UNCLASSIFIED], which the gate denies.
 */
enum class Capability(val label: String, val blurb: String) {
    METADATA(
        "Metadata",
        "Pure lookups: which storage roots exist, whether a target app is installed.",
    ),
    FILE_READ(
        "Read files",
        "List directories and read file contents through any mounted filesystem.",
    ),
    FILE_WRITE(
        "Write files",
        "Create, edit, rename, move, copy and delete through any mounted filesystem.",
    ),
    SHELL(
        "Run shell commands",
        "Hand a command line to the shell backend and read back its output.",
    ),
    NETWORK(
        "Network",
        "Outbound HTTP. The chat providers are the only network callers in this build.",
    ),
    SYSTEM_READ("Read system state", "Device and app state that is not the filesystem."),
    SYSTEM_WRITE("Write system state", "Change device or app state outside our own storage."),
    CHAT_READ("Read chat", "Read stored conversations and messages."),
    CHAT_WRITE("Write chat", "Create, modify or delete stored conversations."),
    UNCLASSIFIED("Unclassified", "Anything not explicitly classified. Always denied.");
}

/**
 * Who is asking. Every gated call names one; the tag is what gets persisted and shown.
 *
 * `User` is a person tapping a control in this app. `AiAgent` is the model driving a surface.
 * `Plugin` exists so an out-of-process backend can be added later without changing the grant
 * store's shape — no plugin runtime ships in this build.
 */
sealed class Caller(val tag: String) {
    data object User : Caller("user")
    data object AiAgent : Caller("ai-agent")
    data class Plugin(val pluginId: String) : Caller("plugin:$pluginId")

    companion object {
        fun parse(value: String): Caller? = when {
            value == "user" -> User
            value == "ai-agent" -> AiAgent
            value.startsWith("plugin:") -> Plugin(value.removePrefix("plugin:"))
            else -> null
        }
    }
}

/** Outcome of a gate check. [Denied] carries text that is safe to render verbatim. */
sealed class GateDecision {
    data object Allowed : GateDecision()
    data class Denied(val message: String) : GateDecision()

    val isAllowed: Boolean get() = this is Allowed
}

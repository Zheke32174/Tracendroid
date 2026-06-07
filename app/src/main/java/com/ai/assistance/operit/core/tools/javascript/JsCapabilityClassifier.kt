package com.ai.assistance.operit.core.tools.javascript

/**
 * Capability classes used by the per-call JS plugin gate (§ 4.2).
 *
 * A capability describes the *kind* of effect a tool call has on the user's device, not
 * a specific tool. The gate's grant store keys on (pluginId, capability) — granting one
 * capability does not implicitly grant others. The classification is conservative: when
 * a tool's effect spans two classes the more dangerous class is used.
 */
enum class JsCapabilityClass {
    /** Pure compute / metadata. Listing imported packages, reading static resources, etc. */
    METADATA,

    /** Reads user files (documents, downloads, app sandbox files). */
    FILE_READ,

    /** Writes to user files (create/edit/delete). */
    FILE_WRITE,

    /** Runs shell commands or terminal sessions. */
    SHELL,

    /** Initiates network requests, opens browsers, fetches URLs. */
    NETWORK,

    /** Reads device / system state (running apps, system settings, sensors). */
    SYSTEM_READ,

    /** Writes device / system state (modify settings, send broadcasts, send intents). */
    SYSTEM_WRITE,

    /** Drives the UI through AccessibilityService or input simulation. */
    UI_AUTOMATION,

    /** Reads the chat / memory / conversation state. */
    CHAT_READ,

    /** Mutates chat / memory / conversation state. */
    CHAT_WRITE,

    /** Anything not yet classified — treated as the most-restrictive class. */
    UNCLASSIFIED;
}

/**
 * Maps the (toolType, toolName) pair that arrives at the JS bridge to a capability class.
 *
 * The map is intentionally explicit rather than pattern-matched: each tool's class is a
 * security decision and should not change accidentally when a new tool with a similar
 * name lands. Unknown tools are reported as UNCLASSIFIED, which the gate treats as the
 * most-restrictive class — defaulting to deny.
 */
object JsCapabilityClassifier {

    private val toolNameToClass: Map<String, JsCapabilityClass> = buildMap {
        // File I/O
        put("read_file", JsCapabilityClass.FILE_READ)
        put("list_dir", JsCapabilityClass.FILE_READ)
        put("file_info", JsCapabilityClass.FILE_READ)
        put("file_exists", JsCapabilityClass.FILE_READ)
        put("read_lines", JsCapabilityClass.FILE_READ)
        put("search_file", JsCapabilityClass.FILE_READ)
        put("zip_files", JsCapabilityClass.FILE_READ)
        put("unzip_files", JsCapabilityClass.FILE_WRITE)

        put("create_file", JsCapabilityClass.FILE_WRITE)
        put("edit_file", JsCapabilityClass.FILE_WRITE)
        put("apply_file", JsCapabilityClass.FILE_WRITE)
        put("delete_file", JsCapabilityClass.FILE_WRITE)
        put("move_file", JsCapabilityClass.FILE_WRITE)
        put("copy_file", JsCapabilityClass.FILE_WRITE)
        put("make_directory", JsCapabilityClass.FILE_WRITE)
        put("write_file", JsCapabilityClass.FILE_WRITE)

        // Shell + terminal
        put("execute_shell", JsCapabilityClass.SHELL)
        put("execute_intent", JsCapabilityClass.SYSTEM_WRITE)
        put("create_terminal_session", JsCapabilityClass.SHELL)
        put("execute_terminal_command", JsCapabilityClass.SHELL)
        put("close_terminal_session", JsCapabilityClass.SHELL)
        put("list_terminal_sessions", JsCapabilityClass.METADATA)

        // Network
        put("visit_web", JsCapabilityClass.NETWORK)
        put("various_search", JsCapabilityClass.NETWORK)
        put("http_request", JsCapabilityClass.NETWORK)
        put("browser_open", JsCapabilityClass.NETWORK)
        put("browser_session_open", JsCapabilityClass.NETWORK)
        put("browser_session_close", JsCapabilityClass.NETWORK)
        put("browser_session_execute_script", JsCapabilityClass.NETWORK)

        // UI automation
        put("tap", JsCapabilityClass.UI_AUTOMATION)
        put("long_press", JsCapabilityClass.UI_AUTOMATION)
        put("set_input_text", JsCapabilityClass.UI_AUTOMATION)
        put("swipe", JsCapabilityClass.UI_AUTOMATION)
        put("press_key", JsCapabilityClass.UI_AUTOMATION)
        put("capture_screenshot", JsCapabilityClass.UI_AUTOMATION)
        put("get_page_info", JsCapabilityClass.UI_AUTOMATION)
        put("ui_dump", JsCapabilityClass.UI_AUTOMATION)

        // System read / write
        put("device_info", JsCapabilityClass.SYSTEM_READ)
        put("list_apps", JsCapabilityClass.SYSTEM_READ)
        put("modify_software_settings", JsCapabilityClass.SYSTEM_WRITE)
        put("send_broadcast", JsCapabilityClass.SYSTEM_WRITE)
        put("send_sms", JsCapabilityClass.SYSTEM_WRITE)

        // Chat / memory
        put("memory_query", JsCapabilityClass.CHAT_READ)
        put("chat_history_read", JsCapabilityClass.CHAT_READ)
        put("chat_send", JsCapabilityClass.CHAT_WRITE)
        put("memory_write", JsCapabilityClass.CHAT_WRITE)

        // Metadata
        put("calculator", JsCapabilityClass.METADATA)
        put("string", JsCapabilityClass.METADATA)
        // Subscription-OAuth state probes (AUDIT_PLAN § 1.6, THREAT_MODEL
        // § 4.5). Read-only metadata; the dispatcher refuses any FILE_READ
        // claim against the underlying session files as defense in depth.
        put("subscription_account", JsCapabilityClass.METADATA)
        put("subscription_tier", JsCapabilityClass.METADATA)
        put("subscription_alive", JsCapabilityClass.METADATA)
    }

    fun classify(toolType: String, toolName: String): JsCapabilityClass {
        val key = toolName.trim().lowercase()
        toolNameToClass[key]?.let { return it }
        // Fallback heuristics — conservative: any miss is UNCLASSIFIED (= treated as deny).
        return JsCapabilityClass.UNCLASSIFIED
    }
}

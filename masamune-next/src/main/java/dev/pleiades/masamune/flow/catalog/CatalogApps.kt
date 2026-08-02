package dev.pleiades.masamune.flow.catalog

import dev.pleiades.masamune.flow.model.BlockCategory
import dev.pleiades.masamune.flow.model.BlockSpec

/**
 * Automate's largest reach into the rest of the device: launching activities, reading the
 * package manager, sending broadcasts and running shell commands.
 *
 * Almost every gate in the catalog that is not a runtime permission lands here. The
 * `App notifications *` and `AppOp mode *` families drive hidden system APIs that no ordinary
 * app process may touch, so they carry [SHELL] — and that is the one place where Masamune is
 * meaningfully better off than the donor, because uid 2000 at the Termux prefix reaches them
 * without the rooted device Automate would need.
 *
 * Blocks are listed in Automate's own palette order, which is the order this catalog and the
 * palette both render. See `docs/donors/RE-automate.md`.
 */
internal val APPS_BLOCKS: List<BlockSpec> = category(BlockCategory.APPS) {
    appsLaunchingAndInventory()
    appsShortcutsBroadcastsPluginsAndShell()
}

/** Starting activities and services, and reading what is installed. */
private fun Blocks.appsLaunchingAndInventory() {
    decision(
        "activity_start_result", "App decision",
        "Starts an app activity and waits for the result it returns.",
        args = listOf(
            text("packageName", "Package"),
            text("activityClass", "Activity class"),
            any("action", "Action"),
            text("uri", "Data URI"),
            text("mimeType", "MIME type"),
            arr("category", "Category"),
            dict("extras", "Extras"),
            any("flags", "Flags"),
            dict("activityOptions", "Launch options"),
            num("timeout", "Timeout", "no timeout"),
            any("notificationChannelId", "Notification channel", "the flow default or Flow"),
            flag("startActivity", "Show window"),
        ),
        outputs = listOf(
            out("varResultUri", "Result URI"),
            out("varResultExtras", "Result extras"),
        ),
    )
    action(
        "activity_start", "App start",
        "Starts an app activity.",
        args = listOf(
            text("packageName", "Package"),
            text("activityClass", "Activity class"),
            any("action", "Action"),
            text("uri", "Data URI"),
            text("mimeType", "MIME type"),
            arr("category", "Category"),
            dict("extras", "Extras"),
            any("flags", "Flags"),
            dict("activityOptions", "Launch options"),
            flag("chooser", "Chooser"),
        ),
    )
    action(
        "activity_start_voice", "App start voice",
        "Starts an app activity for \"voice interaction\", possibly without a visible UI.",
        args = listOf(
            text("packageName", "Package"),
            text("activityClass", "Activity class"),
            any("action", "Action"),
            text("uri", "Data URI"),
            text("mimeType", "MIME type"),
            arr("category", "Category"),
            dict("extras", "Extras"),
            any("flags", "Flags"),
        ),
    )
    action(
        "adb_protocol_set", "ADB protocol set",
        "Sets the protocol that the ADB daemon running on an Android device will use to " +
            "listen for client connections.",
        args = listOf(
            any("protocol", "Protocol", "USB"),
            num("tcpipPort", "TCP/IP port", "5555"),
            any("alias", "Keychain alias"),
            text("host", "Host or IP address", "localhost"),
            num("port", "Port", "5555"),
            flag("security", "Security", "no, i"),
        ),
        requires = setOf(SHELL),
    )
    action(
        "adb_shell_command", "ADB shell command",
        "Executes a shell command through ADB, on the local or a remote Android device.",
        args = listOf(
            text("command", "Command"),
            any("alias", "Keychain alias"),
            text("host", "Host or IP address", "localhost"),
            num("port", "Port", "5555"),
            flag("security", "Security", "no, i"),
        ),
        outputs = listOf(
            out("varStdout", "Standard output text"),
            out("varStderr", "Standard error text"),
            out("varExitCode", "Exit code"),
        ),
        requires = setOf(SHELL),
    )
    action(
        "alternative_launch", "Alternative launch",
        "Awaits a launch of the alternative Automate activity.",
        args = listOf(
            text("title", "Title", "the Flow beginning title"),
        ),
    )
    action(
        "app_clear_cache", "App clear cache",
        "Deletes all temporary files used by an app, clearing its cache.",
        args = listOf(
            text("packageName", "Package"),
        ),
        requires = setOf(SHELL),
    )
    decision(
        "app_foreground", "App foreground",
        "Checks which app activity that is currently displayed in the foreground.",
        proceed = WATCH,
        args = listOf(
            text("packageName", "Package"),
            text("activityClass", "Activity class"),
        ),
        outputs = listOf(
            out("varForegroundPackageName", "Foreground package"),
            out("varForegroundClassName", "Foreground activity class"),
        ),
        requires = setOf(A11Y),
    )
    decision(
        "app_installed", "App installed",
        "Checks if an app is installed, or not.",
        proceed = WATCH,
        args = listOf(
            text("packageName", "Package"),
        ),
        outputs = listOf(
            out("varPackageName", "Package"),
            out("varDisplayName", "Display name"),
            out("varVersionCode", "Version code"),
            out("varVersionName", "Version name"),
            out("varCacheSize", "Cache size"),
            out("varDataSize", "Data size"),
            out("varCodeSize", "Code size"),
            out("varSourceDirs", "APK paths"),
        ),
    )
    action(
        "app_kill", "App kill",
        "Terminates all processes of a running app.",
        args = listOf(
            text("packageName", "Package"),
        ),
        requires = setOf(SHELL),
    )
    action(
        "app_kill_background", "App kill background",
        "Terminates any running background processes (services) of an app.",
        args = listOf(
            text("packageName", "Package"),
        ),
        requires = setOf(SHELL),
    )
    action(
        "app_list", "App list",
        "Retrieves a list of apps on the device.",
        args = listOf(
            any("flagsInclude", "Flags include", "to include all, even uninstalled"),
            any("flagsExclude", "Flags exclude", "to exclude none, even uninstalled"),
            any("states", "States", "any"),
            any("categories", "Category"),
        ),
        outputs = listOf(
            out("varPackageNames", "Packages"),
            out("varDisplayNames", "Display names"),
        ),
    )
    decision(
        "app_notifications_enabled", "App notifications enabled",
        "Checks if an app are allowed to show notifications.",
        args = listOf(
            text("packageName", "Package"),
        ),
        requires = setOf(SHELL),
    )
    action(
        "app_notifications_set_state", "App notifications set state",
        "Enables or disables notifications for an app.",
        args = listOf(
            text("packageName", "Package"),
            flag("state", "Notifications", "On"),
        ),
        requires = setOf(SHELL),
    )
    action(
        "app_notifications_priority_get", "App notifications priority get",
        "Gets the maximum allowed priority for notifications shown by an app.",
        args = listOf(
            text("packageName", "Package"),
        ),
        outputs = listOf(
            out("varPriority", "Priority"),
        ),
        requires = setOf(SHELL),
    )
    action(
        "app_notifications_priority_set", "App notifications priority set",
        "Sets the maximum allowed priority for notifications shown by an app.",
        args = listOf(
            text("packageName", "Package"),
            any("priority", "Priority", "Maximum"),
        ),
        requires = setOf(SHELL),
    )
    action(
        "app_notifications_visibility_get", "App notifications visibility get",
        "Gets the visibility override for notifications shown by an app.",
        args = listOf(
            text("packageName", "Package"),
        ),
        outputs = listOf(
            out("varVisibility", "Visibility"),
        ),
        requires = setOf(SHELL),
    )
    action(
        "app_notifications_visibility_set", "App notifications visibility set",
        "Sets (override) the visibility for notifications shown by an app.",
        args = listOf(
            text("packageName", "Package"),
            any("visibility", "Visibility", "No override"),
        ),
        requires = setOf(SHELL),
    )
    decision(
        "app_pick", "App pick",
        "Lets the user choose an app package.",
        args = listOf(
            any("flagsInclude", "Flags include", "to include all, even uninstalled"),
            any("flagsExclude", "Flags exclude", "to exclude none, even uninstalled"),
            any("states", "States", "any"),
            num("timeout", "Timeout", "no timeout"),
            any("notificationChannelId", "Notification channel", "the flow default or Flow"),
            flag("startActivity", "Show window"),
        ),
        outputs = listOf(
            out("varPackageName", "Package"),
        ),
    )
}

/** Launcher shortcuts, broadcast traffic, third-party plug-ins and the shell tier. */
private fun Blocks.appsShortcutsBroadcastsPluginsAndShell() {
    action(
        "shortcut_pin", "App shortcut install",
        "Installs/pins an app shortcut in the default launcher home screen.",
        args = listOf(
            text("label", "Label", "the display name of resolved app activity"),
            text("iconUri", "Icon URI"),
            text("packageName", "Package"),
            text("activityClass", "Activity class"),
            any("action", "Action"),
            text("uri", "Data URI"),
            text("mimeType", "MIME type"),
            arr("category", "Category"),
            dict("extras", "Extras"),
            any("flags", "Flags"),
        ),
        outputs = listOf(
            out("varShortcutId", "Shortcut id"),
        ),
    )
    action(
        "shortcut_start", "App shortcut start",
        "Starts an app from a shortcut.",
    )
    action(
        "shortcut_update", "App shortcut update",
        "Updates an app shortcut in the default launcher home screen.",
        args = listOf(
            any("shortcutId", "Shortcut id"),
            text("label", "Label", "the display name of resolved app activity"),
            text("iconUri", "Icon URI"),
            text("packageName", "Package"),
            text("activityClass", "Activity class"),
            any("action", "Action"),
            text("uri", "Data URI"),
            text("mimeType", "MIME type"),
            arr("category", "Category"),
            dict("extras", "Extras"),
            any("flags", "Flags"),
        ),
    )
    action(
        "app_usage", "App usage",
        "Gets usage statistics for an app, or all apps, within an interval.",
        args = listOf(
            any("minTimestamp", "Minimum timestamp", "first recorded usage"),
            any("maxTimestamp", "Maximum timestamp", "last recorded usage"),
            num("interval", "Interval", "Best fit"),
            text("packageName", "Package", "for all apps"),
            any("statistic", "Statistic", "Foreground"),
        ),
        outputs = listOf(
            out("varUsageDuration", "Usage duration"),
            out("varLastUsedTimestamp", "Last used"),
            out("varStatsStartTimestamp", "Stats start"),
            out("varStatsEndTimestamp", "Stats end"),
        ),
    )
    action(
        "app_op_mode_set", "AppOp mode set",
        "Sets the mode for an \"application operation\" (run-time permission) for an app.",
        args = listOf(
            text("packageName", "Package"),
            any("opstr", "Operation"),
            any("mode", "Mode", "Default for Android 5+, Errored otherwise"),
        ),
        requires = setOf(SHELL),
    )
    action(
        "app_op_mode", "AppOp mode",
        "Checks the mode of an \"application operation\" (run-time permission) for an app.",
        args = listOf(
            text("packageName", "Package"),
            any("opstr", "Operation"),
            any("mode", "Mode", "Allowed"),
        ),
        requires = setOf(SHELL),
    )
    decision(
        "broadcast_send_ordered", "Broadcast decision",
        "Sends an app (ordered) broadcast and then await a result.",
        args = listOf(
            text("className", "Receiver class"),
            text("packageName", "Package"),
            any("action", "Action"),
            text("uri", "Data URI"),
            text("mimeType", "MIME type"),
            arr("category", "Category"),
            dict("extras", "Extras"),
            any("flags", "Flags"),
        ),
        outputs = listOf(
            out("varResultCode", "Result code"),
            out("varResultData", "Result data"),
            out("varResultExtras", "Result extras"),
        ),
    )
    action(
        "broadcast_receive", "Broadcast receive",
        "Awaits an app broadcast.",
        args = listOf(
            any("action", "Action"),
            text("uriScheme", "URI scheme"),
            text("uriAuthority", "URI authority"),
            text("uriPath", "URI path"),
            any("mimeType", "MIME type"),
            arr("category", "Category"),
            flag("useSticky", "Use sticky"),
        ),
        outputs = listOf(
            out("varBroadcastAction", "Broadcast action"),
            out("varBroadcastUri", "Broadcast URI"),
            out("varBroadcastMimeType", "Broadcast MIME type"),
            out("varBroadcastCategories", "Broadcast categories"),
            out("varBroadcastExtras", "Broadcast extras"),
        ),
    )
    action(
        "broadcast_send", "Broadcast send",
        "Sends an app broadcast.",
        args = listOf(
            text("className", "Receiver class"),
            text("packageName", "Package"),
            any("action", "Action"),
            text("uri", "Data URI"),
            text("mimeType", "MIME type"),
            arr("category", "Category"),
            dict("extras", "Extras"),
            any("flags", "Flags"),
        ),
    )
    action(
        "google_assistant_action", "Google Assistant action",
        "Awaits a voice action for Automate spoken into the Google (Assistant) app.",
        outputs = listOf(
            out("varSpokenText", "Spoken text"),
        ),
    )
    action(
        "log_await", "Log await",
        "Awaits a message logged by an app or system component.",
        args = listOf(
            text("log", "Log", "Main"),
            text("tag", "Tag", "any"),
            text("message", "Message", "any"),
            text("priority", "Priority", "any"),
            text("packageName", "Package", "any"),
        ),
        outputs = listOf(
            out("varLoggedMessage", "Logged message"),
            out("varLoggedTime", "Logged time"),
            out("varLoggingUidName", "Logging UID name"),
        ),
    )
    action(
        "plugin_setting", "Plug-in action",
        "Performs a \"setting\" action of an app which support the Tasker and Locale plug-in " +
            "architecture.",
        options = listOf(flagOption("ignoreTimeout", "Ignore timeout")),
    )
    decision(
        "plugin_condition", "Plug-in decision",
        "Checks a \"condition\" of an app which support the Tasker and Locale plug-in " +
            "architecture.",
        proceed = WATCH,
    )
    action(
        "plugin_event", "Plug-in event",
        "Awaits an \"event\" to be triggered by an app which support the Tasker plug-in " +
            "architecture.",
    )
    decision(
        "preferred_activity", "Preferred activity",
        "Gets the default app activity chosen for a particular task (intent).",
        args = listOf(
            text("packageName", "Package"),
            text("className", "Activity class"),
            any("action", "Action"),
            text("uri", "Data URI"),
            text("mimeType", "MIME type"),
            arr("category", "Category"),
        ),
        outputs = listOf(
            out("varPreferredPackageName", "Resolved package"),
            out("varPreferredClassName", "Resolved activity class"),
            out("varDisplayName", "Display name"),
        ),
    )
    decision(
        "profile_quiet_mode_enabled", "Profile quiet mode enabled",
        "Checks if a managed/work profile is in quiet mode, i.e. pausing apps.",
        proceed = WATCH,
    )
    decision(
        "profile_quiet_mode_request", "Profile quiet mode request",
        "Requests that a managed/work profile should enable or disable quiet mode, i.e. " +
            "pause apps.",
        args = listOf(
            flag("state", "Quiet mode"),
            flag("flags", "No credentials"),
        ),
    )
    decision(
        "resolve_activity", "Resolve activity",
        "Resolves an app activity.",
        proceed = AWAIT,
        args = listOf(
            text("packageName", "Package"),
            text("className", "Activity class"),
            any("action", "Action"),
            text("uri", "Data URI"),
            text("mimeType", "MIME type"),
            arr("category", "Category"),
            num("timeout", "Timeout", "no timeout"),
            any("notificationChannelId", "Notification channel", "the flow default or Flow"),
            flag("startActivity", "Show window"),
        ),
        outputs = listOf(
            out("varResolvedPackageName", "Resolved package"),
            out("varResolvedClassName", "Resolved activity class"),
            out("varDisplayName", "Display name"),
        ),
    )
    decision(
        "resolve_receiver", "Resolve receiver",
        "Resolves an app broadcast receiver.",
        proceed = AWAIT,
        args = listOf(
            text("packageName", "Package"),
            text("className", "Activity class"),
            any("action", "Action"),
            text("uri", "Data URI"),
            text("mimeType", "MIME type"),
            arr("category", "Category"),
            num("timeout", "Timeout", "no timeout"),
            any("notificationChannelId", "Notification channel", "the flow default or Flow"),
            flag("startActivity", "Show window"),
        ),
        outputs = listOf(
            out("varResolvedPackageName", "Resolved package"),
            out("varResolvedClassName", "Resolved receiver class"),
            out("varDisplayName", "Display name"),
        ),
    )
    decision(
        "resolve_service", "Resolve service",
        "Resolves an app service.",
        proceed = AWAIT,
        args = listOf(
            text("packageName", "Package"),
            text("className", "Activity class"),
            any("action", "Action"),
            text("uri", "Data URI"),
            text("mimeType", "MIME type"),
            arr("category", "Category"),
            num("timeout", "Timeout", "no timeout"),
            any("notificationChannelId", "Notification channel", "the flow default or Flow"),
            flag("startActivity", "Show window"),
        ),
        outputs = listOf(
            out("varResolvedPackageName", "Resolved package"),
            out("varResolvedClassName", "Resolved service class"),
            out("varDisplayName", "Display name"),
        ),
    )
    action(
        "service_start", "Service start",
        "Starts an app service.",
        args = listOf(
            text("packageName", "Package"),
            text("className", "Service class"),
            any("action", "Action"),
            text("uri", "Data URI"),
            text("mimeType", "MIME type"),
            arr("category", "Category"),
            dict("extras", "Extras"),
            any("flags", "Flags"),
            flag("foreground", "Foreground", "no"),
        ),
    )
    action(
        "shell_command", "Shell command",
        "Executes a shell command. Runs as Masamune's own app uid, so it reaches only what " +
            "the app sandbox reaches; use Shell command privileged for anything beyond that.",
        args = listOf(
            text("command", "Command"),
            text("wordDir", "Working directory", "external storage"),
        ),
        outputs = listOf(
            out("varStdout", "Standard output text"),
            out("varStderr", "Standard error text"),
            out("varExitCode", "Exit code"),
        ),
    )
    action(
        "shell_command_privileged", "Shell command privileged",
        "Executes a shell command as a privileged user, the user starting the privileged " +
            "service, i.e. superuser (root) or shell (ADB) user. Runs as whichever user started " +
            "the privileged service - uid 2000 via ADB, or root if a root daemon started it.",
        args = listOf(
            text("command", "Command"),
            text("wordDir", "Working directory", "external storage"),
        ),
        outputs = listOf(
            out("varStdout", "Standard output text"),
            out("varStderr", "Standard error text"),
            out("varExitCode", "Exit code"),
        ),
        requires = setOf(SHELL),
    )
    action(
        "shell_command_superuser", "Shell command superuser",
        "Executes a shell command as superuser (root). Full root is a separate and stricter " +
            "tier than the uid-2000 privileged shell this block is gated on: a device that " +
            "grants uid 2000 still cannot run it without a root daemon.",
        args = listOf(
            text("command", "Command"),
            text("wordDir", "Working directory", "external storage"),
        ),
        outputs = listOf(
            out("varStdout", "Standard output text"),
            out("varStderr", "Standard error text"),
            out("varExitCode", "Exit code"),
        ),
        requires = setOf(SHELL),
    )
}

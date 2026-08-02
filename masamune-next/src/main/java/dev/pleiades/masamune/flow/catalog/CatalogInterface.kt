package dev.pleiades.masamune.flow.catalog

import dev.pleiades.masamune.flow.model.BlockCategory
import dev.pleiades.masamune.flow.model.BlockSpec

/**
 * The largest category, and the one where honest gating does the most work.
 *
 * Three separate grants live here and they are not interchangeable. The interaction family —
 * `Interact`, `Inspect *`, `Key *` — needs an AccessibilityService. The notification family —
 * posted, interact, cancel, snooze — needs notification access, a different toggle in a
 * different settings screen. Locking the device needs device-admin, a third. A palette that
 * collapsed these into one "needs permission" state would send the user to the wrong screen.
 *
 * Note that the `Interface *` blocks are *not* in the accessibility family despite the name:
 * they drive Masamune's own custom UI, which needs no grant at all.
 *
 * Blocks are listed in Automate's own palette order, which is the order this catalog and the
 * palette both render. See `docs/donors/RE-automate.md`.
 */
internal val INTERFACE_BLOCKS: List<BlockSpec> = category(BlockCategory.INTERFACE) {
    deviceStateClipboardAndDialogs()
    displaysInspectionAndInteraction()
    notificationsScreenAndSystemUi()
}

/** Device docking, locking and unlock state, the clipboard, pickers and dialogs. */
private fun Blocks.deviceStateClipboardAndDialogs() {
    action(
        "accessibility_button", "Accessibility button",
        "Awaits a accessibility button click.",
        args = listOf(
            any("displayId", "Display id", "id of the primary display"),
        ),
        requires = setOf(A11Y),
    )
    action(
        "assist_request", "Assist request",
        "Awaits a user assist request.",
        args = listOf(
            text("title", "Title", "the Flow beginning title"),
            flag("visibility", "Visibility", "Private"),
        ),
        outputs = listOf(
            out("varPackageName", "Context package"),
            out("varActivityClassName", "Context activity class"),
            out("varIntentAction", "Context action"),
            out("varIntentUri", "Context data URI"),
            out("varIntentMimeType", "Context MIME type"),
            out("varIntentCategories", "Context categories"),
            out("varIntentExtras", "Context extras"),
            out("varWebUri", "Context web URL"),
        ),
    )
    action(
        "attention_light", "Attention light",
        "Turns the attention LED light on or off.",
        args = listOf(
            flag("state", "Light", "Off"),
            any("color", "Color", "white"),
        ),
        requires = setOf(SHELL),
    )
    decision(
        "car_mode_enabled", "Car mode enabled",
        "Checks if the car user interface mode is used, or not.",
        proceed = WATCH,
    )
    action(
        "car_mode_set_state", "Car mode set state",
        "Turns the car user interface mode on, or off.",
        args = listOf(
            flag("state", "Car mode", "Off"),
            flag("goHome", "Go home"),
        ),
    )
    action(
        "clipboard_get", "Clipboard get",
        "Gets the clipboard content.",
        proceed = WATCH_VALUE,
        outputs = listOf(
            out("varContent", "Text content"),
        ),
    )
    action(
        "clipboard_set", "Clipboard set",
        "Sets the clipboard content.",
        args = listOf(
            any("text", "Content text"),
            any("htmlText", "Content HTML"),
            text("uri", "Content URI"),
            any(
                "mimeType", "Content MIME type",
                "depends on the other content arguments, see above",
            ),
            text("label", "Label", "no label"),
            flag("sensitive", "Sensitive", "no"),
        ),
    )
    decision(
        "color_pick", "Color pick",
        "Lets the user choose a color.",
        args = listOf(
            flag("hideOpacity", "Opacity"),
            any("initialColor", "Initial color", "white"),
            num("timeout", "Timeout", "no timeout"),
            any("notificationChannelId", "Notification channel", "the flow default or Flow"),
            flag("startActivity", "Show window"),
        ),
        outputs = listOf(
            out("varColor", "Picked color"),
        ),
    )
    decision(
        "device_docked", "Device docked",
        "Checks if the device is docked, or undocked.",
        proceed = WATCH,
        args = listOf(
            any("modes", "Docked to"),
        ),
    )
    action(
        "device_lock", "Device lock",
        "Locks the device, as if the lock screen timeout expired. The user will be forced to " +
            "reenter the pattern, PIN or password when unlocking, use the Interact block with " +
            "action Lock screen to only engage the \"Smart lock\", i.e. fingerprint or face.",
        requires = setOf(ADMIN),
    )
    decision(
        "device_secure", "Device secure",
        "Checks if the device has configured a secure lock screen, or has a currently locked " +
            "SIM that requires a PIN.",
        args = listOf(
            flag("ignoreSimLock", "Ignore SIM lock", "to not ignore"),
        ),
    )
    decision(
        "device_unlocked", "Device unlocked",
        "Checks if the device is unlocked, or locked.",
        proceed = WATCH,
    )
    decision(
        "dialog_choice", "Dialog choice",
        "Lets the user select from a list of choices.",
        args = listOf(
            text("title", "Title", "no title"),
            text("choiceTitles", "Choice titles", "no choices"),
            num("choiceDescriptions", "Choice descriptions", "no descriptions"),
            arr("preselect", "Pre-select", "none"),
            flag("multiselect", "Multi-select", "false"),
            flag("noselect", "No selection"),
            flag("sort", "Sort", "true"),
            num("timeout", "Timeout", "no timeout"),
            any("notificationChannelId", "Notification channel", "the flow default or Flow"),
            flag("startActivity", "Show window"),
        ),
        outputs = listOf(
            out("varSelectedIndices", "Selected indices/keys"),
        ),
    )
    decision(
        "dialog_confirm", "Dialog confirm",
        "Shows a message and lets the user confirm.",
        args = listOf(
            text("title", "Title", "no title"),
            text("message", "Message"),
            text("linkify", "Linkify", "none"),
            any("positive", "Yes button", "\"OK\""),
            any("negative", "No button", "\"Cancel\""),
            num("timeout", "Timeout", "no timeout"),
            any("notificationChannelId", "Notification channel", "the flow default or Flow"),
            flag("startActivity", "Show window"),
        ),
    )
    decision(
        "dialog_input", "Dialog input",
        "Lets the user enter text.",
        args = listOf(
            text("title", "Title", "no title"),
            any("inputType", "Input type", "a single row of text without capitalization"),
            text("regex", "Regular expression", "(?s)"),
            any("hint", "Hint", "no hint"),
            any("prepopulate", "Pre-populate", "no initial text"),
            arr("suggestions", "Suggestions", "no suggestions"),
            num("timeout", "Timeout", "no timeout"),
            any("notificationChannelId", "Notification channel", "the flow default or Flow"),
            flag("startActivity", "Show window"),
        ),
        outputs = listOf(
            out("varResultText", "Text entered"),
        ),
    )
    action(
        "dialog_message", "Dialog message",
        "Shows a message.",
        args = listOf(
            text("title", "Title", "no title"),
            text("message", "Message"),
            text("linkify", "Linkify", "none"),
            any("dismiss", "Dismiss button", "\"Close\""),
            num("timeout", "Timeout", "no timeout"),
            any("notificationChannelId", "Notification channel", "the flow default or Flow"),
            flag("startActivity", "Show window"),
        ),
    )
    decision(
        "dialog_number", "Dialog number",
        "Lets the user select a numeric value within a range.",
        args = listOf(
            text("title", "Title", "no title"),
            any("style", "Style", "Wheel"),
            num("minValue", "Minimum value", "0"),
            num("maxValue", "Maximum value", "999"),
            num("initialValue", "Initial value", "minimum value"),
            num("timeout", "Timeout", "no timeout"),
            any("notificationChannelId", "Notification channel", "the flow default or Flow"),
            flag("startActivity", "Show window"),
        ),
        outputs = listOf(
            out("varResultValue", "Selected value"),
        ),
    )
    decision(
        "dialog_web", "Dialog web",
        "Lets the user view a web or HTML page.",
        args = listOf(
            text("url", "Page URL", "http://localhost, i"),
            any("page", "HTML page", "no page"),
            text("regex", "Regular expression", "always disabled"),
            text("account", "Basic authorization account", "no authorization"),
            any("userAgent", "User agent", "set by the system"),
            flag("viewport", "Viewport", "don't confine"),
            num("timeout", "Timeout", "no timeout"),
            any("notificationChannelId", "Notification channel", "the flow default or Flow"),
            flag("startActivity", "Show window"),
        ),
        outputs = listOf(
            out("varResultUrl", "Result page URL"),
            out("varResultTitle", "Result page title"),
        ),
    )
}

/** Displays, custom interfaces, and the accessibility-gated inspection family. */
private fun Blocks.displaysInspectionAndInteraction() {
    decision(
        "display_metrics_get", "Display metrics get",
        "Gets the metrics of a connected display.",
        proceed = WATCH,
        args = listOf(
            any("displayId", "Display id", "id of the primary display"),
        ),
        outputs = listOf(
            out("varBounds", "Bounds"),
            out("varDensity", "Density"),
            out("varRotation", "Rotation"),
            out("varRefreshRate", "Refresh rate"),
        ),
    )
    decision(
        "display_on", "Display on",
        "Checks if a display is turned on, or off.",
        proceed = WATCH,
        args = listOf(
            any("displayId", "Display id", "id of the primary display"),
        ),
    )
    decision(
        "display_query", "Display query",
        "Queries connected displays.",
        proceed = WATCH,
        args = listOf(
            text("name", "Device name", "any name"),
            any("flags", "Flags", "any flags"),
            any("connection", "Connection", "any kind of connection"),
        ),
        outputs = listOf(
            out("varDisplayIds", "Device ids"),
            out("varDisplayNames", "Device names"),
        ),
    )
    action(
        "feature_usage", "Feature usage",
        "Gets usage statistics for a system feature, e.g. screen on/off, locked/unlocked.",
        args = listOf(
            any("minTimestamp", "Minimum timestamp", "first recorded usage"),
            any("maxTimestamp", "Maximum timestamp", "last recorded usage"),
            num("interval", "Interval", "Best fit"),
            any("statistic", "Statistic", "Screen interactive"),
        ),
        outputs = listOf(
            out("varUsageCount", "Usage count"),
            out("varUsageDuration", "Usage duration"),
            out("varLastUsedTimestamp", "Last used"),
            out("varStatsStartTimestamp", "Stats start"),
            out("varStatsEndTimestamp", "Stats end"),
        ),
    )
    action(
        "fingerprint_gesture", "Fingerprint gesture",
        "Awaits a gesture performed on the fingerprint scanner.",
        args = listOf(
            any("gestures", "Gestures", "all"),
        ),
        outputs = listOf(
            out("varGesturePerformed", "Gesture performed"),
        ),
        requires = setOf(A11Y),
    )
    action(
        "floating_button_show", "Floating button show",
        "Shows a button in a floating toolbar until it's been clicked.",
        args = listOf(
            text("iconUri", "Icon URI"),
            any("color", "Color", "blue"),
        ),
    )
    decision(
        "fullscreen", "Fullscreen",
        "Awaits a change in status-bar or navigation-bar visibility.",
        args = listOf(
            any("visibility", "Visibility", "any"),
        ),
    )
    decision(
        "hardware_keyboard_visible", "Hardware keyboard visible",
        "Checks if the physical hardware keyboard is visible/extended, or not.",
        proceed = WATCH,
    )
    decision(
        "icon_pick", "Icon pick",
        "Lets the user choose an icon.",
        args = listOf(
            any("initialIconUri", "Initial icon", "none"),
            num("timeout", "Timeout", "no timeout"),
            any("notificationChannelId", "Notification channel", "the flow default or Flow"),
            flag("startActivity", "Show window"),
        ),
        outputs = listOf(
            out("varIconUri", "Icon URI"),
            out("varIconChar", "Icon ID"),
        ),
    )
    decision(
        "inspect_layout", "Inspect layout",
        "Inspects the User Interface (UI) shown on screen. The YES path is executed when the " +
            "evaluated result is a Number, a String, a Boolean true, a selected Node or a " +
            "non-empty Node-set.",
        proceed = WATCH,
        args = listOf(
            text("xpathExpression", "XPath expression", "'"),
            text("schema", "XML schema", "Active window layout"),
            text("packageName", "Package", "any"),
            any("displayId", "Display id", "id of the primary display"),
            text("resultType", "Result type", "Node"),
        ),
        outputs = listOf(
            out("varResult", "Result"),
        ),
        requires = setOf(A11Y),
    )
    action(
        "inspect_text_edit", "Inspect text edit",
        "Awaits changes in a text field.",
        args = listOf(
            any("inputType", "Input type", "a Text"),
            text("packageName", "Package", "any"),
        ),
        outputs = listOf(
            out("varNewText", "Text after change"),
            out("varOldText", "Text before change"),
            out("varSelectionStart", "Selection start"),
            out("varSelectionEnd", "Selection end"),
            out("varTextMaxLength", "Maximum text length"),
            out("varInputType", "Input type"),
            out("varPackageName", "Package"),
        ),
        requires = setOf(A11Y),
    )
    decision(
        "interact", "Interact",
        "Interacts with the User Interface (UI) shown on screen.",
        proceed = WATCH,
        args = listOf(
            any("action", "Action", "Inspect"),
            text("xpathExpression", "XPath expression", "the currently focused UI element"),
            text("schema", "XML schema", "Active window layout"),
            text("packageName", "Package", "any"),
            any("displayId", "Display id", "id of the primary display"),
            any("argX", "ArgX"),
            num("argY", "ArgY"),
        ),
        outputs = listOf(
            out("varContent", "Inspected content"),
        ),
        requires = setOf(A11Y),
    )
    decision(
        "interact_touch", "Interact touch",
        "Simulates a touchscreen gesture. The NO path is executed if the gesture failed to " +
            "be dispatched, or was cancelled/interrupted. Needed the privileged service only " +
            "before Android 7; on current Android the accessibility service is what it gates on.",
        args = listOf(
            any("gesture", "Action", "Click"),
            num("x0", "Screen X0"),
            num("y0", "Screen Y0"),
            num("x1", "Screen X1"),
            num("y1", "Screen Y1"),
            num("speed", "Pointer speed"),
            any("displayId", "Display id", "id of the primary display"),
        ),
        requires = setOf(A11Y),
    )
    decision(
        "interface_adapter_update", "Interface adapter update",
        "Updates an adapter UI elements' number of items and/or invalidate its items in a " +
            "custom interface. The NO path is executed if the interface has been dismissed.",
        args = listOf(
            text("interfaceUri", "Interface URI"),
            text("adapterViewId", "Adapter UI element id"),
            num("itemCount", "Item count", "to not update"),
            flag("invalidate", "Invalidate", "it no"),
        ),
        outputs = listOf(
            out("varItemCount", "Item count"),
        ),
    )
    decision(
        "interface_clicked", "Interface clicked",
        "Waits for an custom interface to be clicked. The NO path is executed if the " +
            "interface has been dismissed.",
        args = listOf(
            text("interfaceUri", "Interface URI"),
        ),
        outputs = listOf(
            out("varViewId", "UI element id"),
            out("varItemPosition", "Item position"),
            out("varChecked", "Checked"),
        ),
    )
    decision(
        "interface_item_request", "Interface item request",
        "Awaits a request for an item as displayed by an adapter element in a custom " +
            "interface. The NO path is executed if the interface has been dismissed.",
        args = listOf(
            text("interfaceUri", "Interface URI"),
            text("adapterViewId", "Adapter UI element id"),
        ),
        outputs = listOf(
            out("varItemPosition", "Item position"),
        ),
    )
    decision(
        "interface_layout_update", "Interface layout update",
        "Updates the layout of a custom interface, or of an adapter item therein. The NO " +
            "path is executed if the interface has been dismissed.",
        args = listOf(
            text("interfaceUri", "Interface URI"),
            any("layoutXml", "Layout XML"),
            text(
                "adapterViewId", "Adapter UI element id",
                "to update the interface layout not an item layout",
            ),
            any(
                "itemPosition", "Item position",
                "to update the interface layout not an item layout",
            ),
        ),
    )
    decision(
        "interface_request", "Interface request",
        "Awaits a request for an update to a custom interface. The NO path is executed if " +
            "the interface has been dismissed.",
        args = listOf(
            text("interfaceUri", "Interface URI"),
        ),
        outputs = listOf(
            out("varMinWidth", "Minimum width"),
            out("varMinHeight", "Minimum height"),
            out("varMaxWidth", "Maximum width"),
            out("varMaxHeight", "Maximum height"),
            out("varDisplayId", "Display id"),
        ),
    )
    decision(
        "key_pressed", "Key pressed",
        "Awaits a key/button press or release.",
        args = listOf(
            arr("keyCodes", "Key codes", "all"),
            any("modifiers", "Key modifiers", "to ignore modifiers"),
            any("flags", "Flags", "to ignore flags"),
            any("consume", "Consume"),
        ),
        outputs = listOf(
            out("varKeyCode", "Pressed key code"),
            out("varMetaState", "Pressed key modifiers"),
            out("varUnicodeChar", "Unicode character code"),
            out("varDeadChar", "Dead character code"),
        ),
        requires = setOf(A11Y),
    )
    decision(
        "key_send", "Key send",
        "Sends/simulates a key/button press or release.",
        options = listOf(KEY_EVENT_METHOD),
        args = listOf(
            any("action", "Action", "Down & up"),
            any("keyCode", "Key code"),
            any("modifiers", "Key modifiers", "no modifier"),
        ),
        requires = setOf(A11Y),
    )
    decision(
        "key_send_characters", "Key send characters",
        "Sends/simulates keyboard text input.",
        options = listOf(KEY_EVENT_METHOD),
        args = listOf(
            any("characters", "Characters", "an empty text"),
        ),
        requires = setOf(A11Y),
    )
}

/** Notifications, screen orientation and lock, screensavers, wallpapers and widgets. */
private fun Blocks.notificationsScreenAndSystemUi() {
    decision(
        "password_failed", "Login failed",
        "Awaits a failed or successful password unlock.",
        outputs = listOf(
            out("varAttempts", "Failed attempts"),
        ),
        requires = setOf(ADMIN),
    )
    action(
        "media_button", "Media button",
        "Awaits a media button press.",
        args = listOf(
            any("buttons", "Buttons", "all"),
            any("override", "Override"),
        ),
        outputs = listOf(
            out("varButtonPressed", "Button pressed"),
        ),
    )
    decision(
        "night_mode_enabled", "Night mode enabled",
        "Checks if the night user interface mode is used, or not.",
        proceed = WATCH,
    )
    action(
        "night_mode_set_state", "Night mode set state",
        "Sets the night user interface mode.",
        args = listOf(
            any("state", "Night mode", "Auto"),
        ),
    )
    decision(
        "notification_action", "Notification action",
        "Presents action buttons in a status bar notification and wait until the user clicks " +
            "any of them. The NO path is executed if the notification was cancelled or hidden " +
            "prior to or during this block, or the timeout expired.",
        args = listOf(
            any("primaryLabel", "Primary action label"),
            text("primaryIconUri", "Primary action icon URI"),
            num("secondaryLabel", "Secondary action label"),
            num("secondaryIconUri", "Secondary action icon URI"),
            any("tertiaryLabel", "Tertiary action label"),
            text("tertiaryIconUri", "Tertiary action icon URI"),
            num("timeout", "Timeout", "no timeout"),
        ),
        outputs = listOf(
            out("varActionIndex", "Action index"),
        ),
    )
    action(
        "notification_cancel", "Notification cancel",
        "Cancels a status bar notification.",
        args = listOf(
            any("key", "Notification id", "id of any notification shown by the fiber"),
        ),
        requires = setOf(NOTIF),
    )
    decision(
        "notification_channel_pick", "Notification channel pick",
        "Lets the user choose a notification channel.",
        args = listOf(
            num("timeout", "Timeout", "no timeout"),
            any("notificationChannelId", "Notification channel", "the flow default or Flow"),
            flag("startActivity", "Show window"),
        ),
        outputs = listOf(
            out("varChannelId", "Picked channel UUID"),
        ),
    )
    action(
        "notification_interact", "Notification interact",
        "Interacts with a status bar notification.",
        args = listOf(
            any("action", "Action"),
            any("key", "Notification id"),
            any("argX", "ArgX"),
        ),
        requires = setOf(NOTIF),
    )
    decision(
        "notification_posted", "Notification posted",
        "Checks if a status bar notification is posted.",
        proceed = WATCH,
        args = listOf(
            text("packageName", "Package", "any"),
            any("channelId", "Channel id", "any"),
            text("title", "Title", "any"),
            any("flagsExclude", "Exclude flags", "to exclude none"),
            any("visibility", "Visibility", "any"),
            any("picturePath", "Picture path", "to not save"),
            any("index", "Index", "0"),
        ),
        outputs = listOf(
            out("varPackageName", "Package"),
            out("varChannelId", "Channel id"),
            out("varTitle", "Title"),
            out("varMessage", "Message"),
            out("varTicker", "Ticker text"),
            out("varPersonUris", "Person URIs"),
            out("varCategory", "Category"),
            out("varWhen", "When timestamp"),
            out("varExtras", "Extras"),
            out("varActions", "Action labels"),
            out("varKey", "Notification id"),
            out("varRemoveReason", "Removal reason"),
            out("varAdditional", "Addition texts"),
        ),
        requires = setOf(NOTIF),
    )
    decision(
        "notification_show", "Notification show",
        "Shows a status bar notification.",
        proceed = AWAIT,
        args = listOf(
            text("title", "Title"),
            text("message", "Message"),
            text("shortCriticalText", "Short critical text"),
            text("smallIconUri", "Small icon URI"),
            text("largeIconUri", "Large icon URI", "none"),
            any("primaryLayoutXml", "Collapsed layout XML"),
            any("bigLayoutXml", "Expanded layout XML"),
            any("headsUpLayoutXml", "Heads-up layout XML"),
            any("color", "Accent color", "system default"),
            text("pictureUri", "Picture URI", "in none"),
            text("personUri", "Person URI"),
            any("channelId", "Channel", "the flow default or Flow"),
            flag("visibility", "Visibility"),
            any("groupKey", "Group key"),
            any("category", "Category"),
            any("when", "When timestamp", "not show"),
            any("progress", "Progress bar", "no progress bar"),
            flag("ongoing", "Ongoing"),
            flag("cancellable", "Cancellable"),
        ),
        outputs = listOf(
            out("varKey", "Notification id"),
            out("varInterfaceUri", "Interface URI"),
        ),
        requires = setOf(POST_NOTIFICATIONS),
    )
    action(
        "notification_snooze", "Notification snooze",
        "Snoozes a status bar notification.",
        args = listOf(
            any("key", "Notification id", "id of any notification shown by the fiber"),
            num("duration", "Duration", "1 hour"),
        ),
        requires = setOf(NOTIF),
    )
    decision(
        "process_text", "Process text selection",
        "Awaits text selected for processing from within another app. Automate gives this " +
            "block a SET and an OK dot; YES is the editable selection (the SET path, which must " +
            "reach a Process text set block) and NO the read-only one.",
        args = listOf(
            text("title", "Title", "the Flow beginning title"),
        ),
        outputs = listOf(
            out("varSelectedText", "Selected text"),
        ),
    )
    action(
        "process_text_result", "Process text set",
        "Replaces the text selected for processing.",
        args = listOf(
            text("replacementText", "Replacement text", "to keep the original text as is"),
        ),
    )
    decision(
        "quick_settings_tile_show", "Quick Settings tile show",
        "Shows an Quick Settings tile.",
        args = listOf(
            text("label", "Label"),
            text("iconUri", "Icon URI"),
            any("subtitle", "Subtitle"),
            flag("state", "State", "\"inactive\""),
            any("flags", "Flags", "none"),
        ),
        outputs = listOf(
            out("varFlags", "Flags"),
        ),
    )
    action(
        "screen_lock_set_state", "Screen lock set state",
        "Temporarily disables or reenables the screen lock (keyguard).",
        options = listOf(flagOption("secure", "Secure")),
        args = listOf(
            flag("state", "Lock screen", "disable"),
        ),
        requires = setOf(ADMIN),
    )
    decision(
        "screen_orientation", "Screen orientation",
        "Checks if the screen is in portrait or landscape orientation.",
        proceed = WATCH,
        args = listOf(
            any("orientation", "Orientation", "Portrait"),
        ),
    )
    action(
        "screen_orientation_set", "Screen orientation set",
        "Sets screen orientation.",
        args = listOf(
            any("orientation", "Orientation", "Unspecified"),
        ),
    )
    decision(
        "dream_created", "Screensaver created",
        "Waits for a new screensaver interface to be created. The NO path is executed if " +
            "another screensaver has been chosen.",
        outputs = listOf(
            out("varInterfaceUri", "Interface URI"),
            out("varFeatures", "Features"),
        ),
    )
    action(
        "dream_setup", "Screensaver setup",
        "Waits for the user to choose this block as the system screensaver.",
        args = listOf(
            text("title", "Title", "the Flow beginning title"),
            any("flags", "Flags"),
        ),
    )
    decision(
        "software_keyboard_visible", "Software keyboard visible",
        "Checks if the on-screen software keyboard is visible, or not.",
        proceed = WATCH,
        requires = setOf(A11Y),
    )
    decision(
        "split_screen_mode_enabled", "Split-screen mode enabled",
        "Checks if the UI is in split-screen mode.",
        proceed = WATCH,
    )
    action(
        "toast_posted", "Toast posted",
        "Awaits a \"toast\" message to be shown on screen.",
        args = listOf(
            text("packageName", "Package"),
            text("message", "Message", "any title, may contain glob pattern"),
        ),
        outputs = listOf(
            out("varPackageName", "Package"),
            out("varMessage", "Message"),
        ),
        requires = setOf(A11Y),
    )
    action(
        "toast_show", "Toast show",
        "Briefly shows a \"toast\" message on screen.",
        proceed = AWAIT,
        args = listOf(
            text("message", "Message"),
            num(
                "duration", "Duration",
                "Short for messages with less than 30 characters, Long otherwise",
            ),
        ),
    )
    decision(
        "wallpaper_created", "Wallpaper created",
        "Waits for a new wallpaper interface to be created. The NO path is executed if " +
            "another wallpaper has been chosen.",
        outputs = listOf(
            out("varInterfaceUri", "Interface URI"),
            out("varFeatures", "Features"),
        ),
    )
    action(
        "wallpaper_setup", "Wallpaper setup",
        "Waits for the user to choose this block as a wallpaper.",
        args = listOf(
            text("title", "Title", "the Flow beginning title"),
            flag("interactive", "Interactive"),
        ),
    )
    action(
        "appwidget_configure", "Widget configure",
        "Waits for the user to add this block as a home screen widget.",
        args = listOf(
            text("title", "Title", "the Flow beginning title"),
            any("hostCategories", "Host categories", "any kind of host"),
        ),
        outputs = listOf(
            out("varInterfaceUri", "Interface URI"),
            out("varHostCategory", "Host category"),
        ),
    )
    decision(
        "wired_headset", "Wired headset plugged",
        "Checks if a wired headset or headphone is plugged in or unplugged.",
        proceed = WATCH,
        args = listOf(
            flag("ignoreHeadphone", "Microphone", "no"),
        ),
        outputs = listOf(
            out("varDisplayName", "Display name"),
        ),
    )
}

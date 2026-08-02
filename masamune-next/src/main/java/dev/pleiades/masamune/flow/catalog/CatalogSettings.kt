package dev.pleiades.masamune.flow.catalog

import dev.pleiades.masamune.flow.model.BlockCategory
import dev.pleiades.masamune.flow.model.BlockSpec

/**
 * System settings, ringer and interruption policy, wallpaper and input method.
 *
 * The read/write asymmetry is the thing to notice: `System setting get` is ungated because
 * Android lets any app read most settings, while `System language set`, `Input method set` and
 * `Wallpaper live set` carry [SHELL] because writing them has no public API at all.
 *
 * Blocks are listed in Automate's own palette order, which is the order this catalog and the
 * palette both render. See `docs/donors/RE-automate.md`.
 */
internal val SETTINGS_BLOCKS: List<BlockSpec> = category(BlockCategory.SETTINGS) {
    decision(
        "cm_profile", "CyanogenMod profile",
        "Checks the active CyanogenMod profile.",
        proceed = WATCH,
        args = listOf(
            any("uuid", "Profile UUID"),
        ),
        outputs = listOf(
            out("varActiveUuid", "Active profile UUID"),
            out("varActiveName", "Active profile name"),
        ),
    )
    action(
        "cm_profile_set", "CyanogenMod profile set",
        "Activates a CyanogenMod profile.",
        args = listOf(
            any("uuid", "Profile UUID"),
        ),
    )
    decision(
        "input_method_pick", "Input method pick",
        "Lets the user choose an input method (soft keyboard).",
        args = listOf(
            flag("enabledOnly", "Enabled"),
            flag("showSubtypes", "Subtypes", "yes"),
            num("timeout", "Timeout", "no timeout"),
            any("notificationChannelId", "Notification channel", "the flow default or Flow"),
            flag("startActivity", "Show window"),
        ),
        outputs = listOf(
            out("varInputMethod", "Input method"),
            out("varInputMethodSubtype", "Input method subtype"),
        ),
    )
    action(
        "input_method_set", "Input method set",
        "Sets the current (default) input method (soft keyboard).",
        args = listOf(
            any("inputMethod", "Input method"),
            any("inputMethodSubtype", "Input method subtype"),
            flag("enable", "Enable"),
        ),
        requires = setOf(SHELL),
    )
    decision(
        "interruption_filter", "Interruptions",
        "Checks the interruptions (Do Not Disturb) setting.",
        proceed = WATCH,
        args = listOf(
            any("filter", "Interruptions"),
        ),
        outputs = listOf(
            out("varCurrentFilter", "Current interruptions"),
        ),
    )
    action(
        "interruption_filter_set", "Interruptions set",
        "Sets the interruptions (Do Not Disturb) setting.",
        args = listOf(
            any("state", "Interruptions", "Always"),
        ),
    )
    action(
        "notification_policy_get", "Notification policy get",
        "Gets the policy settings for \"priority\" notifications which bypass Do Not Disturb.",
        proceed = WATCH_VALUE,
        outputs = listOf(
            out("varCurrentReminders", "Current reminders policy"),
            out("varCurrentEvents", "Current events policy"),
            out("varCurrentMessages", "Current messages policy"),
            out("varCurrentCalls", "Current calls policy"),
            out("varCurrentRepeatCallers", "Current repeat caller policy"),
            out("varCurrentAlarms", "Current alarms policy"),
            out("varCurrentMedia", "Current media policy"),
            out("varCurrentSystem", "Current system policy"),
            out("varCurrentConversations", "Current conversations policy"),
            out("varCurrentSuppressedEffects", "Currently blocked disturbances"),
        ),
    )
    action(
        "notification_policy_set", "Notification policy set",
        "Sets the policy settings for \"priority\" notifications which bypass Do Not Disturb.",
        args = listOf(
            any("reminders", "Reminders", "to leave unchanged"),
            any("events", "Events", "to leave unchanged"),
            text("messages", "Messages", "to leave unchanged"),
            any("calls", "Calls", "to leave unchanged"),
            any("repeatCallers", "Repeat callers", "to leave unchanged"),
            any("alarms", "Alarms", "to leave unchanged"),
            any("media", "Media", "to leave unchanged"),
            any("system", "System", "to leave unchanged"),
            any("conversations", "Conversations", "to leave unchanged"),
            any("suppressedEffects", "Block disturbances", "to leave unchanged"),
        ),
    )
    decision(
        "ringer_mode", "Ringer mode",
        "Checks the phone call ringer mode.",
        proceed = WATCH,
        args = listOf(
            any("state", "Ringer mode", "Normal"),
        ),
        outputs = listOf(
            out("varCurrentMode", "Current ringer mode"),
        ),
    )
    action(
        "ringer_mode_set", "Ringer mode set",
        "Sets the phone call ringer mode.",
        args = listOf(
            any("state", "Ringer mode", "Normal"),
        ),
    )
    action(
        "ringtone_get", "Ringtone get",
        "Gets the sound used as a default ringtone.",
        args = listOf(
            any("ringtoneType", "Ringtone type", "Ringtone"),
        ),
        outputs = listOf(
            out("varSoundUri", "Sound URI"),
        ),
    )
    action(
        "ringtone_set", "Ringtone set",
        "Sets the sound used as a default ringtone.",
        args = listOf(
            any("ringtoneType", "Ringtone type", "Ringtone"),
            text("soundUri", "Sound URI"),
        ),
    )
    decision(
        "screen_brightness", "Screen brightness",
        "Checks the screen brightness settings.",
        proceed = WATCH,
        args = listOf(
            flag("auto", "Automatic"),
            num("minLevel", "Minimum brightness"),
            num("maxLevel", "Maximum brightness"),
            any("scale", "Scale", "Linear"),
        ),
        outputs = listOf(
            out("varAuto", "Current automatic"),
            out("varLevel", "Current brightness"),
            out("varAdjustment", "Current adjustment"),
        ),
    )
    action(
        "screen_brightness_set", "Screen brightness set",
        "Sets the screen brightness settings.",
        args = listOf(
            flag("auto", "Automatic"),
            num("level", "Brightness"),
            any("scale", "Scale", "Linear"),
            num("adjustment", "Adjustment"),
        ),
    )
    decision(
        "screen_off_timeout", "Screen off timeout",
        "Checks the screen off timeout setting.",
        proceed = WATCH,
        args = listOf(
            num("minLevel", "Minimum timeout"),
            num("maxLevel", "Maximum timeout"),
        ),
        outputs = listOf(
            out("varLevel", "Current timeout"),
        ),
    )
    action(
        "screen_off_timeout_set", "Screen off timeout set",
        "Sets the screen off timeout setting.",
        args = listOf(
            num("level", "Timeout"),
        ),
    )
    action(
        "system_language_get", "System language get",
        "Gets the system language.",
        proceed = WATCH_VALUE,
        outputs = listOf(
            out("varLanguage", "Language"),
        ),
    )
    action(
        "system_language_set", "System language set",
        "Sets the system language.",
        args = listOf(
            any("language", "Language", "current language, i"),
        ),
        requires = setOf(SHELL),
    )
    action(
        "system_property_get", "System property get",
        "Gets the value of a system property.",
        args = listOf(
            text("name", "Name"),
        ),
        outputs = listOf(
            out("varValue", "Current value"),
        ),
        requires = setOf(SHELL),
    )
    action(
        "system_setting_get", "System setting get",
        "Gets the value of a system setting.",
        proceed = WATCH_VALUE,
        args = listOf(
            any("category", "Category", "System"),
            text("name", "Name"),
        ),
        outputs = listOf(
            out("varValue", "Current value"),
        ),
    )
    action(
        "system_setting_set", "System setting set",
        "Sets the value of a system setting.",
        args = listOf(
            any("category", "Category", "System"),
            text("name", "Name"),
            any("value", "Value"),
        ),
    )
    action(
        "wallpaper_colors_get", "Wallpaper colors get",
        "Gets then sets the home screen background wallpaper to an image.",
        proceed = WATCH_VALUE,
        args = listOf(
            any("which", "Which", "System"),
        ),
        outputs = listOf(
            out("varColorModel", "Color model"),
            out("varPrimaryColorComponents", "Primary color components"),
            out("varSecondaryColorComponents", "Secondary color components"),
            out("varTertiaryColorComponents", "Tertiary color components"),
        ),
    )
    action(
        "wallpaper_image_set", "Wallpaper image set",
        "Sets the home screen background wallpaper to an image.",
        args = listOf(
            text("imageUri", "Image URI", "clear/no wallpaper"),
            any("which", "Which", "System"),
        ),
    )
    action(
        "wallpaper_live_set", "Wallpaper live set",
        "Sets a live home screen background wallpaper.",
        args = listOf(
            text("packageName", "Package"),
            text("className", "Service class"),
            any("which", "Which", "System"),
        ),
        requires = setOf(SHELL),
    )
}

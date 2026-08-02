package dev.pleiades.masamune.flow.catalog

import dev.pleiades.masamune.flow.model.BlockCategory
import dev.pleiades.masamune.flow.model.BlockSpec

/**
 * Charge state, doze, CPU speed and the blunt instruments - reboot, restart, shutdown.
 *
 * The read side is unprivileged and the write side almost entirely is not: Android exposes
 * battery and interactivity to any app but hands nobody a public API to reboot itself, so the
 * four device-lifecycle blocks and both CPU-speed blocks carry [SHELL].
 *
 * Blocks are listed in Automate's own palette order, which is the order this catalog and the
 * palette both render. See `docs/donors/RE-automate.md`.
 */
internal val BATTERY_AND_POWER_BLOCKS: List<BlockSpec> = category(BlockCategory.BATTERY_AND_POWER) {
    decision(
        "battery_charging", "Battery charging",
        "Checks if battery is charging or discharging.",
        proceed = WATCH,
        outputs = listOf(
            out("varUntilFullyCharged", "Time until fully charged"),
        ),
    )
    decision(
        "battery_level", "Battery level",
        "Checks battery charge level.",
        proceed = WATCH,
        args = listOf(
            num("minLevel", "Minimum level"),
            num("maxLevel", "Maximum level"),
        ),
        outputs = listOf(
            out("varLevel", "Current level"),
        ),
    )
    decision(
        "battery_properties", "Battery properties",
        "Gets battery properties.",
        outputs = listOf(
            out("varCapacity", "Capacity"),
            out("varRemainingPercent", "Remaining percent"),
            out("varRemainingCharge", "Remaining charge"),
            out("varRemainingEnergy", "Remaining energy"),
            out("varUsageCurrentNow", "Usage current"),
            out("varUsageCurrentAverage", "Usage current average"),
            out("varVoltage", "Voltage"),
            out("varTemperature", "Temperature"),
            out("varTechnology", "Technology"),
        ),
    )
    decision(
        "display_power_mode", "Display power mode?",
        "Checks the power mode for a physical display. The YES path is executed if the " +
            "current mode is any of the selected modes, otherwise the NO path is executed.",
        proceed = WATCH,
        args = listOf(
            any("modes", "Power modes", "any"),
            any("displayId", "Display id", "id of the primary display"),
        ),
        outputs = listOf(
            out("varCurrentMode", "Current power mode"),
        ),
    )
    action(
        "display_power_mode_set", "Display power mode set",
        "Sets the power mode for a physical display, e.g. to temporarily turn it off.",
        args = listOf(
            any("mode", "Power mode", "On"),
            any("displayId", "Display id", "id of the primary display"),
        ),
        requires = setOf(SHELL),
    )
    decision(
        "device_idle_mode_active", "Device doze mode active",
        "Checks if the device is in idle \"doze\" mode.",
        proceed = WATCH,
    )
    action(
        "device_idle_mode_set_state", "Device doze mode set state",
        "Activates or deactivates device idle \"doze\" mode.",
        args = listOf(
            flag("state", "Device doze mode", "deactivate"),
        ),
        requires = setOf(SHELL),
    )
    decision(
        "device_interactive", "Device interactive",
        "Checks if the device is in an \"interactive\" state, or not.",
        proceed = WATCH,
    )
    action(
        "device_keep_awake", "Device keep awake",
        "Temporarily keeps the CPU and/or Wi-Fi hardware awake.",
        args = listOf(
            flag("wakeState", "Processor", "Allow sleep"),
            flag("wifiState", "Wi-Fi hardware", "Allow sleep"),
            flag("wakeup", "Illumination", "true"),
        ),
    )
    action(
        "device_reboot", "Device reboot",
        "Reboots the device.",
        args = listOf(
            any("reason", "Mode"),
        ),
        requires = setOf(SHELL),
    )
    action(
        "device_restart", "Device restart",
        "Restarts (soft reboots) the device.",
        requires = setOf(SHELL),
    )
    action(
        "device_shutdown", "Device shutdown",
        "Shuts down (powers off) the device.",
        requires = setOf(SHELL),
    )
    action(
        "cpu_speed_get", "CPU speed get",
        "Gets the CPU governor and speed.",
        args = listOf(
            any("cpu", "Processor", "0"),
        ),
        outputs = listOf(
            out("varCpuCount", "Current level"),
            out("varAvailableGovernors", "Available governors"),
            out("varCurrentGovernor", "Current governor"),
            out("varMinSpeed", "Current minimum speed"),
            out("varMaxSpeed", "Current maximum speed"),
            out("varUserSpeed", "Current userspace speed"),
        ),
        requires = setOf(SHELL),
    )
    action(
        "cpu_speed_set", "CPU speed set",
        "Sets the CPU governor and speed.",
        args = listOf(
            any("cpu", "Processor", "all CPUs"),
            any("governor", "Governor"),
            any("minSpeed", "Minimum speed"),
            any("maxSpeed", "Maximum speed"),
            any("userSpeed", "Userspace speed"),
        ),
        requires = setOf(SHELL),
    )
    decision(
        "power_save_mode_enabled", "Power save mode enabled",
        "Checks if power save mode is enabled.",
        proceed = WATCH,
    )
    action(
        "power_save_mode_set_state", "Power save mode set state",
        "Enables or disables power save mode. Needed the privileged service only on Android " +
            "5, so it is not gated on it here.",
        args = listOf(
            flag("state", "Power save mode", "disable"),
        ),
    )
    decision(
        "power_source_plugged", "Power source plugged",
        "Checks if the device is plugged to an external power source.",
        proceed = WATCH,
        args = listOf(
            any("sources", "Power source", "any"),
        ),
        outputs = listOf(
            out("varCurrentSource", "Current power source"),
        ),
    )
}

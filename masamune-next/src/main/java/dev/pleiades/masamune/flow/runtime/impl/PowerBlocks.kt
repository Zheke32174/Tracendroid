package dev.pleiades.masamune.flow.runtime.impl

import dev.pleiades.masamune.apps.BatteryStatus
import dev.pleiades.masamune.apps.PowerSource
import dev.pleiades.masamune.apps.PowerState
import dev.pleiades.masamune.flow.expr.Value
import dev.pleiades.masamune.flow.model.FlowNode
import dev.pleiades.masamune.flow.model.Port
import dev.pleiades.masamune.flow.runtime.BlockImpl
import dev.pleiades.masamune.flow.runtime.Fiber
import dev.pleiades.masamune.flow.runtime.Outcome

/**
 * The Battery&Power category's **device-state read** slice — the organ an AI phone operator needs to
 * know how the device is powered: is the battery charging, at what percent, plugged into what, how hot
 * and at what voltage, and whether power-save, doze or interactivity are on.
 *
 * ### Why this subset and not the other twelve
 * Automate's Battery&Power category mixes an unprivileged read side with a write side that is almost
 * entirely privileged. The read side — battery and interactivity — Android exposes to any app; the
 * write side hands nobody a public API to reboot itself, so `device_reboot`/`_restart`/`_shutdown`,
 * both `cpu_speed_*` and `device_idle_mode_set_state`/`display_power_mode_set` each carry
 * `Requirement.SHELL` and stay behind the scheduler's gate. `power_save_mode_set_state` carries no
 * shell tag but still needs a signature/privileged permission (`DEVICE_POWER`) an ordinary app cannot
 * hold, so it is gated by omission (see [powerLookup]). What is left, and what runs here, is exactly
 * the part backed by `BatteryManager`, the sticky `ACTION_BATTERY_CHANGED` intent and `PowerManager`:
 * charge state, level, source, temperature, voltage, technology, power-save, doze and interactivity.
 * That is the power vocabulary the operator loop composes on top of the Settings and Apps blocks.
 *
 * ### The seam, copied from the Apps and Settings blocks
 * Every device call lives behind the injected [PowerState] — a narrow, `android.*`-free contract, the
 * exact shape [dev.pleiades.masamune.apps.AppInspector] gives the Apps blocks and
 * [dev.pleiades.masamune.apps.SystemSettings] gives the Settings blocks. Two consequences, both
 * deliberate:
 *
 *  1. **JVM-testable.** Each block reads its args as *plain data*, then calls the seam, so the whole
 *     file is unit-testable against a fake on an ordinary JVM — a device is needed to run these, never
 *     to test their branch logic.
 *  2. **Honest gate at run.** Every impl re-resolves [powerProvider] and fails with [POWER_ABSENT]
 *     when there is no seam (the app process is not wired in, or it dropped mid-run). A read that
 *     returns `null` becomes a named [Outcome.Fail] ("not present") — **never** a fabricated `0`,
 *     empty string or silent NO. A decision whose state cannot be read Fails rather than routing a
 *     misleading NO; a block that cannot act *says so*.
 *
 * ### WATCH decisions collapse to their one-shot form
 * The catalog marks the decisions WATCH-capable (test now, or suspend until the condition enters,
 * leaves or changes). The watching form needs the monitor subsystem this build does not have, so the
 * one-shot condition — "is it charging *now*", "is the level in range *now*" — is what runs, which is
 * exactly what a decision in a running flow evaluates. This mirrors `screen_brightness` in
 * [dev.pleiades.masamune.flow.runtime.impl.ScreenBrightnessBlock].
 *
 * The composition helper [powerLookup] mirrors [settingsLookup] and [appsLookup]: it returns the impls
 * keyed by spec id so a caller composes `powerLookup(provider)[id] ?: base.lookup(id)`.
 */

/** The sentence shown whenever a Battery&Power block cannot reach a power seam. Modelled on [SETTINGS_ABSENT]. */
internal val POWER_ABSENT: String =
    "This power block cannot act: no power-state seam is available, so Masamune cannot read the " +
        "device's battery or power state. The seam is wired only inside the Android app process; when " +
        "it is absent the block fails by name rather than reporting a device reading that never ran."

// --------------------------------------------------------------------------- shared arg readers

/** A text argument, trimmed to null when blank — distinct from the empty string a user typed. */
private fun Value?.asNonBlank(): String? = this.asTextOrNull()?.takeIf { it.isNotBlank() }

/** True when [level] sits within the optional `[min, max]` bounds — an unset bound is no constraint. */
private fun inRange(level: Double, min: Double?, max: Double?): Boolean =
    (min == null || level >= min) && (max == null || level <= max)

/**
 * The `sources` filter of `power_source_plugged`, parsed to the set of [PowerSource]s it names.
 *
 * The arg is `any(...)`: it arrives as an array of tokens, or a single scalar, or (constant mode) a
 * string that may list several separated by commas/spaces. Only the three plug sources map through;
 * `UNPLUGGED` is not a plug filter and is ignored. A `null` return means "no honest filter here" —
 * the caller distinguishes "no filter given" (any source is YES) from "a filter was given but named
 * nothing recognizable" (a mis-typed filter, which Fails visibly rather than silently matching none).
 */
private fun parsePowerSources(value: Value?): Set<PowerSource>? {
    val tokens: List<String> = when (value) {
        is Value.ArrayV -> value.items.mapNotNull { it.asNonBlank() }
        null, Value.Null -> emptyList()
        else -> value.asNonBlank()?.split(',', ' ', '\t')?.filter { it.isNotBlank() } ?: emptyList()
    }
    if (tokens.isEmpty()) return null // no filter → the caller reads this as "any source"
    return tokens.mapNotNull { token ->
        when (token.trim().lowercase()) {
            "ac" -> PowerSource.AC
            "usb" -> PowerSource.USB
            "wireless" -> PowerSource.WIRELESS
            else -> null
        }
    }.toSet()
}

// --------------------------------------------------------------------------- battery reads / decisions

/**
 * `battery_charging` (Battery charging) — is the battery charging right now?
 *
 * DECISION: the one-shot form of the catalog's WATCH decision. It reads the charge status and routes
 * YES only when the battery is actively [BatteryStatus.CHARGING]; every other honest state
 * (discharging, not-charging, full, unknown) is NO. A status the seam cannot read is an
 * [Outcome.Fail], **never** a silent NO — an unreadable charge state must be visible, not misreported
 * as "not charging". The catalog's `varUntilFullyCharged` output comes from
 * `BatteryManager.computeChargeTimeRemaining` (API 28+, and frequently `-1` "unknown"), a surface this
 * minimal seam does not expose, so it is left **unbound** (honest omission) rather than filled with a
 * guessed duration.
 */
internal class BatteryChargingBlock(
    private val powerProvider: () -> PowerState?,
) : BlockImpl {
    override val specId = "battery_charging"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val power = powerProvider() ?: return Outcome.Fail(POWER_ABSENT)
        val status = power.status()
            ?: return Outcome.Fail("battery_charging: the battery charge status could not be read.")
        return Outcome.Proceed(if (status == BatteryStatus.CHARGING) Port.YES else Port.NO)
    }
}

/**
 * `battery_level` (Battery level) — read the current charge percent.
 *
 * DECISION: the one-shot form of the WATCH decision, the exact shape of
 * [dev.pleiades.masamune.flow.runtime.impl.ScreenBrightnessBlock]. It binds `varLevel` from the seam,
 * then routes YES when the percent sits within the requested `[minLevel, maxLevel]` band (or no band
 * was given), NO otherwise. A percent the seam cannot read is a named [Outcome.Fail], never a
 * fabricated `0` — an unreadable level must not be mistaken for a flat battery.
 */
internal class BatteryLevelBlock(
    private val powerProvider: () -> PowerState?,
) : BlockImpl {
    override val specId = "battery_level"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val power = powerProvider() ?: return Outcome.Fail(POWER_ABSENT)
        val percent = power.batteryPercent()
            ?: return Outcome.Fail("battery_level: the current battery level could not be read.")
        val writes = LinkedHashMap<String, Value>()
        node.outputs["varLevel"]?.bind(writes, Value.Num(percent.toDouble()))
        val ok = inRange(percent.toDouble(), args["minLevel"].asNumOrNull(), args["maxLevel"].asNumOrNull())
        return Outcome.Proceed(if (ok) Port.YES else Port.NO, writes)
    }
}

/**
 * `battery_properties` (Battery properties) — read the battery's readable properties.
 *
 * DECISION: this build's honest one-shot binds every property the minimal seam can read —
 * `varRemainingPercent` (from the charge percent), `varVoltage` (millivolts), `varTemperature` (tenths
 * of a degree C) and `varTechnology` — and routes YES. When **nothing** is readable (no battery, no
 * sticky battery intent) it Fails by name rather than routing a misleading NO: the honest-gating rule
 * is "an unreadable state Fails, never a silent NO/0". The catalog's other outputs — `varCapacity`,
 * `varRemainingCharge`, `varRemainingEnergy`, `varUsageCurrentNow`, `varUsageCurrentAverage` — come
 * from `BatteryManager.getLongProperty(BATTERY_PROPERTY_*)`, which returns `Long.MIN_VALUE` on the many
 * devices that do not implement those counters; this minimal state seam does not expose them, so they
 * are left **unbound** (honest omission) rather than filled with a fabricated value. The NO port is
 * defined by the shape but never taken: a bare property read has no false condition to route to, only
 * "read it" or "could not read it" — the latter is the visible Fail above.
 */
internal class BatteryPropertiesBlock(
    private val powerProvider: () -> PowerState?,
) : BlockImpl {
    override val specId = "battery_properties"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val power = powerProvider() ?: return Outcome.Fail(POWER_ABSENT)
        val percent = power.batteryPercent()
        val voltage = power.voltageMv()
        val temperature = power.temperatureTenthsC()
        val technology = power.technology()
        if (percent == null && voltage == null && temperature == null && technology == null) {
            return Outcome.Fail("battery_properties: no battery properties could be read.")
        }
        val writes = LinkedHashMap<String, Value>()
        percent?.let { node.outputs["varRemainingPercent"]?.bind(writes, Value.Num(it.toDouble())) }
        voltage?.let { node.outputs["varVoltage"]?.bind(writes, Value.Num(it.toDouble())) }
        temperature?.let { node.outputs["varTemperature"]?.bind(writes, Value.Num(it.toDouble())) }
        technology?.let { node.outputs["varTechnology"]?.bind(writes, Value.Text(it)) }
        return Outcome.Proceed(Port.YES, writes)
    }
}

/**
 * `power_source_plugged` (Power source plugged) — is the device plugged into external power?
 *
 * DECISION: the one-shot form of the WATCH decision. It always binds `varCurrentSource` from the seam
 * (`ac`/`usb`/`wireless`/`unplugged`), then routes: NO when the device is running on battery
 * ([PowerSource.UNPLUGGED]); given a `sources` filter, YES only when the current source is one of the
 * named ones; given no filter, YES for any external source. A source the seam cannot read is a named
 * [Outcome.Fail], and a `sources` filter that names **nothing recognizable** Fails by name too — a
 * mis-typed comparison target must be visible, never silently treated as "no match", exactly as
 * `ringer_mode` treats an unknown mode string.
 */
internal class PowerSourcePluggedBlock(
    private val powerProvider: () -> PowerState?,
) : BlockImpl {
    override val specId = "power_source_plugged"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val power = powerProvider() ?: return Outcome.Fail(POWER_ABSENT)
        val source = power.plugged()
            ?: return Outcome.Fail("power_source_plugged: the current power source could not be read.")
        val writes = LinkedHashMap<String, Value>()
        node.outputs["varCurrentSource"]?.bind(writes, Value.Text(source.name.lowercase()))
        if (source == PowerSource.UNPLUGGED) return Outcome.Proceed(Port.NO, writes)
        // Plugged into something. A filter narrows which sources count as YES.
        val rawFilter = args["sources"]
        val hasFilter = rawFilter != null && rawFilter != Value.Null &&
            (rawFilter !is Value.Text || rawFilter.value.isNotBlank() && !rawFilter.value.equals("any", ignoreCase = true))
        if (!hasFilter) return Outcome.Proceed(Port.YES, writes) // no filter (or the "any" default): any source is YES
        val wanted = parsePowerSources(rawFilter)
        if (wanted.isNullOrEmpty()) {
            return Outcome.Fail("power_source_plugged: unrecognized power source filter (expected ac/usb/wireless).")
        }
        return Outcome.Proceed(if (source in wanted) Port.YES else Port.NO, writes)
    }
}

// --------------------------------------------------------------------------- power-manager decisions

/**
 * `power_save_mode_enabled` (Power save mode enabled) — is battery-saver on right now?
 *
 * DECISION: the one-shot form of the WATCH decision. YES when `PowerManager` reports power-save mode
 * on, NO when off. A state the seam cannot read is a named [Outcome.Fail], never a silent NO — an
 * unreadable power-save state must be visible, not misreported as "off". (The paired
 * `power_save_mode_set_state` block is gated by omission — see [powerLookup]'s KDoc — because turning
 * power-save on needs a privileged system permission an ordinary app cannot hold.)
 */
internal class PowerSaveModeEnabledBlock(
    private val powerProvider: () -> PowerState?,
) : BlockImpl {
    override val specId = "power_save_mode_enabled"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val power = powerProvider() ?: return Outcome.Fail(POWER_ABSENT)
        val on = power.isPowerSaveMode()
            ?: return Outcome.Fail("power_save_mode_enabled: the power-save state could not be read.")
        return Outcome.Proceed(if (on) Port.YES else Port.NO)
    }
}

/**
 * `device_idle_mode_active` (Device doze mode active) — is the device in idle "doze" mode?
 *
 * BUILT BUT NOT REGISTERED. `CatalogBatteryAndPower` tags this read `requires = setOf(SHELL)`, so —
 * exactly as with `system_property_get` in the Settings slice — the unprivileged seam does not claim
 * it: registering a no-shell impl for a block the catalog declares needs a shell would contradict the
 * catalog's own contract. The logic below is kept and unit-tested in isolation and goes live only
 * alongside a real shell tier that satisfies the SHELL requirement.
 *
 * DECISION: the one-shot form of the WATCH decision. YES when `PowerManager` reports doze active, NO
 * when not. A state the seam cannot read (e.g. below API 23, where doze does not exist) is a named
 * [Outcome.Fail], never a silent NO — the honest answer to "could not read" is a visible failure, not
 * a fabricated "not dozing".
 */
internal class DeviceIdleModeActiveBlock(
    private val powerProvider: () -> PowerState?,
) : BlockImpl {
    override val specId = "device_idle_mode_active"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val power = powerProvider() ?: return Outcome.Fail(POWER_ABSENT)
        val idle = power.isDeviceIdle()
            ?: return Outcome.Fail("device_idle_mode_active: the doze/idle state could not be read.")
        return Outcome.Proceed(if (idle) Port.YES else Port.NO)
    }
}

/**
 * `device_interactive` (Device interactive) — is the device in an interactive state?
 *
 * BUILT BUT NOT REGISTERED, for the same reason as `device_idle_mode_active`: `CatalogBatteryAndPower`
 * tags this block `requires = setOf(SHELL)`. Although `PowerManager.isInteractive` is itself an
 * unprivileged read, the catalog's declared contract governs gating, so the unprivileged seam does not
 * register it — the logic stays kept and unit-tested, to go live with a real shell tier.
 *
 * DECISION: the one-shot form of the WATCH decision. YES when `PowerManager` reports the device
 * interactive (roughly, the screen is on and usable), NO when not. A state the seam cannot read is a
 * named [Outcome.Fail], never a silent NO.
 */
internal class DeviceInteractiveBlock(
    private val powerProvider: () -> PowerState?,
) : BlockImpl {
    override val specId = "device_interactive"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val power = powerProvider() ?: return Outcome.Fail(POWER_ABSENT)
        val interactive = power.isInteractive()
            ?: return Outcome.Fail("device_interactive: the interactive state could not be read.")
        return Outcome.Proceed(if (interactive) Port.YES else Port.NO)
    }
}

// --------------------------------------------------------------------------- composition + helpers

/**
 * The five registered Battery&Power device-state impls, keyed by spec id, all sharing one
 * [powerProvider]. (`device_idle_mode_active` and `device_interactive` are built and unit-tested but
 * intentionally unregistered — the catalog tags both `requires=SHELL`; see their KDoc.)
 *
 * Mirrors [settingsLookup] and [appsLookup]: it always returns the map, and the honest gate is the
 * per-block gate-at-run (each fails with [POWER_ABSENT] when the provider yields no seam), so a caller
 * composes over its base registry exactly as the Settings and Apps blocks do:
 *
 * ```
 * val power = powerLookup(powerProvider)
 * fun lookup(id: String): BlockImpl? = power[id] ?: settings[id] ?: apps[id] ?: baseRegistry.lookup(id)
 * ```
 *
 * ### What stays gated by omission, and why
 * The category's remaining Battery&Power blocks are deliberately **not** here, so at run time the
 * scheduler finds no impl and gates them by the honest-by-omission mechanism the catalog's own
 * `requires` set expresses:
 *  - `device_idle_mode_active`, `device_interactive`, `device_reboot`, `device_restart`,
 *    `device_shutdown`, `cpu_speed_get`, `cpu_speed_set`, `device_idle_mode_set_state`,
 *    `display_power_mode_set` all carry `Requirement.SHELL` — they need the privileged shell tier this
 *    build does not take. Registering a no-shell impl would contradict the catalog's stated contract,
 *    even where an unprivileged API happens to exist (as it does for the idle/interactive reads).
 *  - `power_save_mode_set_state` carries no shell tag but turning power-save on/off needs the
 *    signature/privileged `DEVICE_POWER` permission (`PowerManager.setPowerSaveMode`), which an
 *    ordinary app cannot hold — there is no normal-API write, so it is gated rather than faked into a
 *    write that would always be refused.
 *  - `display_power_mode` reads a *physical display's* power mode via `SurfaceControl`, a hidden API
 *    outside this unprivileged state seam.
 *  - `device_keep_awake` acquires and holds a `WakeLock` across block boundaries — a stateful resource
 *    side-effect, not a state read this read-only seam can honestly model as a one-shot.
 */
fun powerLookup(provider: () -> PowerState?): Map<String, BlockImpl> = listOf(
    BatteryChargingBlock(provider),
    BatteryLevelBlock(provider),
    BatteryPropertiesBlock(provider),
    PowerSourcePluggedBlock(provider),
    PowerSaveModeEnabledBlock(provider),
    // device_idle_mode_active and device_interactive are built but NOT registered — the catalog tags
    // both requires=SHELL, so the unprivileged seam does not claim them (see their KDoc).
).associateBy { it.specId }

/** Bind [value] under this non-blank output-variable name into [writes]; a blank name binds nothing. */
private fun String.bind(writes: MutableMap<String, Value>, value: Value) {
    if (isNotBlank()) writes[this] = value
}

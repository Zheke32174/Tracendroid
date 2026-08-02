package dev.pleiades.masamune.flow.runtime.impl

import dev.pleiades.masamune.apps.RingerMode
import dev.pleiades.masamune.apps.SettingNamespace
import dev.pleiades.masamune.apps.SettingWrite
import dev.pleiades.masamune.apps.SystemSettings
import dev.pleiades.masamune.flow.expr.Value
import dev.pleiades.masamune.flow.model.FlowNode
import dev.pleiades.masamune.flow.model.Port
import dev.pleiades.masamune.flow.runtime.BlockImpl
import dev.pleiades.masamune.flow.runtime.Fiber
import dev.pleiades.masamune.flow.runtime.Outcome

/**
 * The Settings category's **read/write core** — the organ an AI phone operator needs to inspect and
 * adjust the device's own knobs: a named setting, brightness, the screen-off timeout, the ringer
 * mode, a system property, and the current locale.
 *
 * ### Why this subset and not the other twenty
 * Automate's Settings category spans three tables and a pile of policy surfaces. Most of the *write*
 * side has no public API at all — `system_language_set`, `input_method_set`, `wallpaper_live_set`
 * each carry `Requirement.SHELL` and stay behind the scheduler's gate — and the notification-policy,
 * interruption-filter, ringtone, wallpaper and CyanogenMod blocks reach into owner-only or vendor
 * surfaces this build cannot honestly drive. What is left, and what runs here, is the part backed by
 * `Settings.System/Secure/Global`, `AudioManager`, `SystemProperties` (read) and `Locale`: read a
 * setting, write one (WRITE_SETTINGS-gated), read/set brightness and timeout and ringer, read a
 * property, read the language. That is exactly the settings vocabulary the operator loop composes.
 *
 * ### The seam, copied from the Apps blocks
 * Every device call lives behind the injected [SystemSettings] — a narrow, `android.*`-free contract,
 * the exact shape [dev.pleiades.masamune.apps.AppInspector] gives the Apps blocks. Two consequences,
 * both deliberate:
 *
 *  1. **JVM-testable.** Each block reads its args as *plain data*, then calls the seam, so the whole
 *     file is unit-testable against a fake on an ordinary JVM — a device is needed to run these,
 *     never to test their branch logic.
 *  2. **Honest gate at run.** Every impl re-resolves [settingsProvider] and fails with
 *     [SETTINGS_ABSENT] when there is no seam (the app process is not wired in, or it dropped
 *     mid-run). A read that returns `null` becomes a named [Outcome.Fail] ("not present"), and a
 *     write the device refuses ([SettingWrite] `ok = false`) becomes a named [Outcome.Fail] carrying
 *     the reason — **never** a fabricated success. A block that cannot act *says so*; it never no-ops.
 *
 * ### The two honest failure shapes, in one place
 *  - **Get found nothing → Fail.** A `get` whose seam returns `null` Fails by name rather than
 *    binding an empty/zero value a downstream block would read as real. Binding a stale value is the
 *    exact silent lie this plane exists to remove.
 *  - **Set was refused → Fail with reason.** A write returning `SettingWrite(ok = false, reason=…)`
 *    (most often "WRITE_SETTINGS not granted") Fails carrying that reason. The denial is data the
 *    block routes on, never coerced into an OK the user would trust.
 *
 * The composition helper [settingsLookup] mirrors [appsLookup]: it returns the impls keyed by spec id
 * so a caller composes `settingsLookup(provider)[id] ?: base.lookup(id)`.
 */

/** The sentence shown whenever a Settings block cannot reach a settings seam. Modelled on [APPS_ABSENT]. */
internal val SETTINGS_ABSENT: String =
    "This settings block cannot act: no system-settings seam is available, so Masamune cannot read " +
        "or write device settings. The seam is wired only inside the Android app process; when it is " +
        "absent the block fails by name rather than reporting a setting change that never ran."

// --------------------------------------------------------------------------- shared arg readers

/** A text argument, trimmed to null when blank — distinct from the empty string a user typed. */
private fun Value?.asNonBlank(): String? = this.asTextOrNull()?.takeIf { it.isNotBlank() }

/**
 * The `category` arg mapped to its settings table. `Settings.System/Secure/Global` are genuinely
 * different tables, so the namespace is a real choice — but the donor default is "System", so an
 * absent or unrecognized category resolves to [SettingNamespace.SYSTEM] rather than failing: reading
 * the wrong table would be a lie, defaulting to the documented one is the donor's own behaviour.
 */
private fun namespaceFrom(category: Value?): SettingNamespace = when (category.asNonBlank()?.lowercase()) {
    "secure" -> SettingNamespace.SECURE
    "global" -> SettingNamespace.GLOBAL
    else -> SettingNamespace.SYSTEM
}

/**
 * Parse a ringer-mode string honestly: only the three names the enum has map through; anything else
 * is `null`, which the blocks turn into a named Fail. A mis-typed mode must be *visible*, never
 * silently coerced to NORMAL — a fabricated target is precisely the failure this plane removes.
 */
private fun parseRinger(text: String): RingerMode? = when (text.trim().lowercase()) {
    "normal" -> RingerMode.NORMAL
    "vibrate" -> RingerMode.VIBRATE
    "silent" -> RingerMode.SILENT
    else -> null
}

/** True when [level] sits within the optional `[min, max]` bounds — an unset bound is no constraint. */
private fun inRange(level: Double, min: Double?, max: Double?): Boolean =
    (min == null || level >= min) && (max == null || level <= max)

// --------------------------------------------------------------------------- read/write settings

/**
 * `system_setting_get` (System setting get) — read a named setting from one of the three tables.
 *
 * ACTION: OK when the setting is present, binding `varValue`. A seam that returns `null` means the
 * table has no such key — a `get` that found nothing Fails **by name** rather than binding an empty
 * string a downstream block would read as a real (blank) value. That is the "get missing → Fail"
 * choice the honest-gating rule asks for.
 */
internal class SystemSettingGetBlock(
    private val settingsProvider: () -> SystemSettings?,
) : BlockImpl {
    override val specId = "system_setting_get"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val settings = settingsProvider() ?: return Outcome.Fail(SETTINGS_ABSENT)
        val name = args["name"].asNonBlank()
            ?: return Outcome.Fail("system_setting_get needs a name.")
        val ns = namespaceFrom(args["category"])
        val value = settings.getSetting(ns, name)
            ?: return Outcome.Fail("system_setting_get: no '$name' setting in the ${ns.name.lowercase()} table.")
        val writes = LinkedHashMap<String, Value>()
        node.outputs["varValue"]?.bind(writes, Value.Text(value))
        return Outcome.Proceed(Port.OK, writes)
    }
}

/**
 * `system_setting_set` (System setting set) — write a named setting.
 *
 * ACTION: OK only when the seam reports the write took. A [SettingWrite] with `ok = false` (the real
 * impl's honest report that `WRITE_SETTINGS` is not granted, or that a `SECURE`/`GLOBAL` write was
 * refused) becomes an [Outcome.Fail] carrying the reason — never a fabricated success. A missing
 * `value` Fails rather than writing an empty string the user did not ask for.
 */
internal class SystemSettingSetBlock(
    private val settingsProvider: () -> SystemSettings?,
) : BlockImpl {
    override val specId = "system_setting_set"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val settings = settingsProvider() ?: return Outcome.Fail(SETTINGS_ABSENT)
        val name = args["name"].asNonBlank()
            ?: return Outcome.Fail("system_setting_set needs a name.")
        val value = args["value"].asTextOrNull()
            ?: return Outcome.Fail("system_setting_set needs a value.")
        val ns = namespaceFrom(args["category"])
        return settings.putSetting(ns, name, value).asOutcome("system_setting_set")
    }
}

// --------------------------------------------------------------------------- brightness

/**
 * `screen_brightness` (Screen brightness) — read the current brightness.
 *
 * DECISION: this is the one-shot form of the catalog's WATCH decision — "is brightness currently in
 * the requested `[minLevel, maxLevel]` band". It binds `varLevel` from the seam, then routes YES when
 * the level is in range (or no band was given), NO otherwise. A brightness the seam cannot read is an
 * [Outcome.Fail], never a fabricated `0`. The catalog's `varAuto`/`varAdjustment` outputs come from
 * `SCREEN_BRIGHTNESS_MODE` and the float-adjustment surface this minimal seam does not expose, so
 * they are left **unbound** (honest omission) rather than filled with a guessed value.
 */
internal class ScreenBrightnessBlock(
    private val settingsProvider: () -> SystemSettings?,
) : BlockImpl {
    override val specId = "screen_brightness"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val settings = settingsProvider() ?: return Outcome.Fail(SETTINGS_ABSENT)
        val level = settings.screenBrightness()
            ?: return Outcome.Fail("screen_brightness: the current brightness could not be read.")
        val writes = LinkedHashMap<String, Value>()
        node.outputs["varLevel"]?.bind(writes, Value.Num(level.toDouble()))
        val ok = inRange(level.toDouble(), args["minLevel"].asNumOrNull(), args["maxLevel"].asNumOrNull())
        return Outcome.Proceed(if (ok) Port.YES else Port.NO, writes)
    }
}

/**
 * `screen_brightness_set` (Screen brightness set) — set the brightness.
 *
 * ACTION, WRITE_SETTINGS-gated: OK only when the seam accepts the write. A denied write ([SettingWrite]
 * `ok = false`, typically "WRITE_SETTINGS not granted") Fails by name with the reason. A missing
 * `level` Fails rather than guessing one. The `auto`/`scale`/`adjustment` args model surfaces the
 * minimal seam does not drive, so they are honestly ignored rather than half-applied.
 */
internal class ScreenBrightnessSetBlock(
    private val settingsProvider: () -> SystemSettings?,
) : BlockImpl {
    override val specId = "screen_brightness_set"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val settings = settingsProvider() ?: return Outcome.Fail(SETTINGS_ABSENT)
        val level = args["level"].asNumOrNull()
            ?: return Outcome.Fail("screen_brightness_set needs a level.")
        return settings.setScreenBrightness(level.toInt()).asOutcome("screen_brightness_set")
    }
}

// --------------------------------------------------------------------------- screen-off timeout

/**
 * `screen_off_timeout` (Screen off timeout) — read the current timeout in milliseconds.
 *
 * DECISION: the one-shot form of the WATCH decision — binds `varLevel` from the seam and routes YES
 * when the timeout is within the requested `[minLevel, maxLevel]` band (or no band was given), NO
 * otherwise. An unreadable timeout is a named [Outcome.Fail], never a fabricated value.
 */
internal class ScreenOffTimeoutBlock(
    private val settingsProvider: () -> SystemSettings?,
) : BlockImpl {
    override val specId = "screen_off_timeout"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val settings = settingsProvider() ?: return Outcome.Fail(SETTINGS_ABSENT)
        val ms = settings.screenOffTimeoutMs()
            ?: return Outcome.Fail("screen_off_timeout: the current timeout could not be read.")
        val writes = LinkedHashMap<String, Value>()
        node.outputs["varLevel"]?.bind(writes, Value.Num(ms.toDouble()))
        val ok = inRange(ms.toDouble(), args["minLevel"].asNumOrNull(), args["maxLevel"].asNumOrNull())
        return Outcome.Proceed(if (ok) Port.YES else Port.NO, writes)
    }
}

/**
 * `screen_off_timeout_set` (Screen off timeout set) — set the timeout in milliseconds.
 *
 * ACTION, WRITE_SETTINGS-gated: OK only when the seam accepts it; a denied write Fails by name with
 * the reason. A missing `level` Fails rather than guessing a timeout.
 */
internal class ScreenOffTimeoutSetBlock(
    private val settingsProvider: () -> SystemSettings?,
) : BlockImpl {
    override val specId = "screen_off_timeout_set"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val settings = settingsProvider() ?: return Outcome.Fail(SETTINGS_ABSENT)
        val level = args["level"].asNumOrNull()
            ?: return Outcome.Fail("screen_off_timeout_set needs a level.")
        return settings.setScreenOffTimeoutMs(level.toLong()).asOutcome("screen_off_timeout_set")
    }
}

// --------------------------------------------------------------------------- ringer

/**
 * `ringer_mode` (Ringer mode) — read the current ringer mode (normal/vibrate/silent).
 *
 * DECISION: the one-shot form of the WATCH decision. It always binds `varCurrentMode` from the seam,
 * then routes on the optional `state` arg: given a target mode, YES when the current mode matches, NO
 * otherwise; given no target, a bare read is YES. An unreadable mode Fails by name, and an
 * **unrecognized** `state` string Fails by name too — a mis-typed comparison target must be visible,
 * never silently treated as "no match".
 */
internal class RingerModeBlock(
    private val settingsProvider: () -> SystemSettings?,
) : BlockImpl {
    override val specId = "ringer_mode"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val settings = settingsProvider() ?: return Outcome.Fail(SETTINGS_ABSENT)
        val mode = settings.ringerMode()
            ?: return Outcome.Fail("ringer_mode: the current ringer mode could not be read.")
        val writes = LinkedHashMap<String, Value>()
        node.outputs["varCurrentMode"]?.bind(writes, Value.Text(mode.name.lowercase()))
        val requested = args["state"].asNonBlank()
            ?: return Outcome.Proceed(Port.YES, writes) // bare read: no target to compare against
        val target = parseRinger(requested)
            ?: return Outcome.Fail("ringer_mode: unrecognized ringer mode '$requested' (expected normal/vibrate/silent).")
        return Outcome.Proceed(if (target == mode) Port.YES else Port.NO, writes)
    }
}

/**
 * `ringer_mode_set` (Ringer mode set) — set the ringer mode via the seam.
 *
 * ACTION: not WRITE_SETTINGS-gated (ringer is `AudioManager`, not the settings store), but a device
 * that refuses — e.g. a Do-Not-Disturb policy owns the ringer — surfaces as [SettingWrite] `ok =
 * false` and Fails by name with the reason. An **unrecognized** or absent `state` Fails rather than
 * silently defaulting to NORMAL: a write with no honest target is a mistake the user must see.
 */
internal class RingerModeSetBlock(
    private val settingsProvider: () -> SystemSettings?,
) : BlockImpl {
    override val specId = "ringer_mode_set"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val settings = settingsProvider() ?: return Outcome.Fail(SETTINGS_ABSENT)
        val requested = args["state"].asNonBlank()
            ?: return Outcome.Fail("ringer_mode_set needs a state (normal/vibrate/silent).")
        val mode = parseRinger(requested)
            ?: return Outcome.Fail("ringer_mode_set: unrecognized ringer mode '$requested' (expected normal/vibrate/silent).")
        return settings.setRingerMode(mode).asOutcome("ringer_mode_set")
    }
}

// --------------------------------------------------------------------------- property + language

/**
 * `system_property_get` (System property get) — BUILT BUT NOT REGISTERED.
 *
 * The block's logic is implemented and unit-tested in isolation, but it is deliberately left out of
 * [settingsLookup], so at run time the scheduler finds no impl and gates it by omission. The reason
 * is honest-gating against the catalog's own contract: `CatalogSettings` tags this block
 * `requires = setOf(SHELL)`, because the robust, cross-version way to read an arbitrary system
 * property is a `getprop` shell — `android.os.SystemProperties.get` is a hidden API the platform
 * greylists on Android 9+, so a reflective read is not a reliable substitute and this build takes no
 * user-facing shell. Registering a no-shell impl would let the block run while the catalog still
 * declares it needs a shell, which is exactly the contract contradiction the plane must not ship.
 * The class stays as a tested record of the intended behaviour; it goes live only alongside a real
 * shell tier that satisfies the SHELL requirement the catalog states.
 */
internal class SystemPropertyGetBlock(
    private val settingsProvider: () -> SystemSettings?,
) : BlockImpl {
    override val specId = "system_property_get"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val settings = settingsProvider() ?: return Outcome.Fail(SETTINGS_ABSENT)
        val name = args["name"].asNonBlank()
            ?: return Outcome.Fail("system_property_get needs a name.")
        val value = settings.systemProperty(name)
            ?: return Outcome.Fail("system_property_get: system property '$name' is not set.")
        val writes = LinkedHashMap<String, Value>()
        node.outputs["varValue"]?.bind(writes, Value.Text(value))
        return Outcome.Proceed(Port.OK, writes)
    }
}

/**
 * `system_language_get` (System language get) — read the current system language/locale tag.
 *
 * ACTION: OK, binding `varLanguage` (e.g. `en-US`). A locale the seam cannot read is a named
 * [Outcome.Fail], never a fabricated tag.
 */
internal class SystemLanguageGetBlock(
    private val settingsProvider: () -> SystemSettings?,
) : BlockImpl {
    override val specId = "system_language_get"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val settings = settingsProvider() ?: return Outcome.Fail(SETTINGS_ABSENT)
        val language = settings.systemLanguage()
            ?: return Outcome.Fail("system_language_get: the system language could not be read.")
        val writes = LinkedHashMap<String, Value>()
        node.outputs["varLanguage"]?.bind(writes, Value.Text(language))
        return Outcome.Proceed(Port.OK, writes)
    }
}

// --------------------------------------------------------------------------- composition + helpers

/**
 * The nine registered Settings read/write impls, keyed by spec id, all sharing one
 * [settingsProvider]. (`system_property_get` is built but intentionally unregistered — see its KDoc.)
 *
 * Mirrors [appsLookup]: it always returns the map, and the honest gate is the per-block gate-at-run
 * (each fails with [SETTINGS_ABSENT] when the provider yields no seam), so a caller composes over its
 * base registry exactly as the Apps blocks do:
 *
 * ```
 * val settings = settingsLookup(settingsProvider)
 * fun lookup(id: String): BlockImpl? = settings[id] ?: apps[id] ?: baseRegistry.lookup(id)
 * ```
 */
fun settingsLookup(provider: () -> SystemSettings?): Map<String, BlockImpl> = listOf(
    SystemSettingGetBlock(provider),
    SystemSettingSetBlock(provider),
    ScreenBrightnessBlock(provider),
    ScreenBrightnessSetBlock(provider),
    ScreenOffTimeoutBlock(provider),
    ScreenOffTimeoutSetBlock(provider),
    RingerModeBlock(provider),
    RingerModeSetBlock(provider),
    // system_property_get is deliberately NOT registered — see SystemPropertyGetBlock's KDoc.
    SystemLanguageGetBlock(provider),
).associateBy { it.specId }

/**
 * Turn a [SettingWrite] into the block's [Outcome]: OK when the device accepted the write, or a named
 * [Outcome.Fail] carrying the seam's honest reason (e.g. "WRITE_SETTINGS not granted") when it did
 * not. This is the single place the "a denied write is a visible Fail, never a fabricated OK" rule
 * lives, so every mutating block routes its result identically.
 */
private fun SettingWrite.asOutcome(blockId: String): Outcome =
    if (ok) Outcome.Proceed(Port.OK)
    else Outcome.Fail("$blockId: write refused — ${reason ?: "no reason given"}.")

/** Bind [value] under this non-blank output-variable name into [writes]; a blank name binds nothing. */
private fun String.bind(writes: MutableMap<String, Value>, value: Value) {
    if (isNotBlank()) writes[this] = value
}

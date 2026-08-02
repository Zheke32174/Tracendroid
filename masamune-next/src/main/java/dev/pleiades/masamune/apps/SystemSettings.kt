package dev.pleiades.masamune.apps

/**
 * The seam between the Settings-category block impls and the real device settings store.
 *
 * Every way a Settings block can *read* or *write* a system setting, brightness, timeout, ringer
 * mode, system property or locale is one method here, and — exactly like [AppInspector] does for the
 * Apps blocks and [dev.pleiades.masamune.operator.a11y.ScreenActuator] does for the operator blocks
 * — there is deliberately nothing `android.*` on this interface. That single constraint is what buys
 * the whole slice its JVM-testability: [dev.pleiades.masamune.flow.runtime.impl.SettingsBlocks]
 * depend on this plain-data contract, never on `Settings.System`, `AudioManager`, or `Locale`, so
 * every block and all its branch logic can be exercised against a fake on an ordinary unit-test JVM.
 * A device is needed to *run* these blocks, never to *test* their logic.
 *
 * ### The honest gate has one clean shape here too
 * When the app process (the only thing that can hand out a real [AndroidSystemSettings]) is not
 * wired in, there is simply no seam, and a block that cannot get one fails visibly by name
 * ([dev.pleiades.masamune.flow.runtime.impl.SETTINGS_ABSENT]) rather than reporting a write that
 * never happened.
 *
 * ### Two failure shapes, both honest, neither fabricated
 *  1. **Not present.** A read returns `null` — "the settings store has no entry for this name",
 *     "brightness could not be read". `null` is a real answer, distinct from the absent-seam case
 *     (which never reaches here — there is no seam to call at all). A `get` block routes it to a
 *     visible Fail rather than binding a stale or zero value a downstream block would trust.
 *  2. **Not permitted.** A write returns [SettingWrite] with `ok = false` and a `reason` — the real
 *     impl reports the missing `WRITE_SETTINGS` special access this way **without throwing**, so the
 *     block can Fail *by name* carrying that reason. A write is never coerced into a fabricated OK.
 *
 * Every method is `suspend` so the real impl may touch the framework off the main thread without the
 * contract changing shape; the fake simply returns.
 */
interface SystemSettings {

    /**
     * The value of [name] in [namespace], or `null` when the settings store has no such entry.
     *
     * `null` is the honest "not present" — a `system_setting_get` routes it to a named Fail rather
     * than binding an empty string a downstream block would read as a real (blank) value.
     */
    suspend fun getSetting(namespace: SettingNamespace, name: String): String?

    /**
     * Write [value] to [name] in [namespace]. Returns [SettingWrite]: `ok = true` when the store
     * accepted it, or `ok = false, reason = "WRITE_SETTINGS not granted"` when the write is not
     * permitted. The real impl checks `Settings.System.canWrite` and reports the denial as data —
     * it never throws for a missing permission — so the block Fails by name instead of pretending.
     */
    suspend fun putSetting(namespace: SettingNamespace, name: String, value: String): SettingWrite

    /** Current screen brightness (0..255 on the platform scale), or `null` when it cannot be read. */
    suspend fun screenBrightness(): Int?

    /** Set screen brightness. `ok = false` carries the WRITE_SETTINGS reason — never a fabricated OK. */
    suspend fun setScreenBrightness(value: Int): SettingWrite

    /** Current screen-off timeout in milliseconds, or `null` when it cannot be read. */
    suspend fun screenOffTimeoutMs(): Long?

    /** Set the screen-off timeout in milliseconds. `ok = false` carries the WRITE_SETTINGS reason. */
    suspend fun setScreenOffTimeoutMs(ms: Long): SettingWrite

    /** Current ringer mode, or `null` when it cannot be read. */
    suspend fun ringerMode(): RingerMode?

    /**
     * Set the ringer mode via the seam. Ringer mode is set through `AudioManager`, not the settings
     * store, so this does **not** carry the WRITE_SETTINGS gate — but the result is still a
     * [SettingWrite] so a device that refuses (e.g. a DND policy owns the ringer) Fails honestly.
     */
    suspend fun setRingerMode(mode: RingerMode): SettingWrite

    /**
     * The value of the system property [key], or `null` when it is not set. Read-only: system
     * properties are read via `android.os.SystemProperties.get` (reflection), which needs no shell —
     * only *writing* a property does, which is why this slice exposes the read alone.
     */
    suspend fun systemProperty(key: String): String?

    /** The current system language/locale tag (e.g. `en-US`), or `null` when it cannot be read. */
    suspend fun systemLanguage(): String?
}

/**
 * Which settings table a setting lives in — the `category` arg's three honest destinations.
 *
 * `Settings.System`, `Settings.Secure` and `Settings.Global` are genuinely different tables with
 * different permissions; collapsing them would read the wrong value or write to the wrong place, so
 * the namespace is carried as a real enum rather than guessed at the device boundary.
 */
enum class SettingNamespace { SYSTEM, SECURE, GLOBAL }

/** The three ringer modes, as plain data — no `AudioManager.RINGER_MODE_*` int leaks across the seam. */
enum class RingerMode { NORMAL, VIBRATE, SILENT }

/**
 * The result of a write through the seam — plain data, never a thrown exception for the ordinary
 * "not permitted" case.
 *
 * The load-bearing field is [reason]: when [ok] is `false`, the real impl fills it with the honest
 * cause (most often "WRITE_SETTINGS not granted"), and the block surfaces it verbatim in its Fail.
 * This is exactly how the slice keeps a denied write from ever becoming a silent success — the denial
 * is data the block must route on, not an error the runtime papers over.
 */
data class SettingWrite(val ok: Boolean, val reason: String? = null)

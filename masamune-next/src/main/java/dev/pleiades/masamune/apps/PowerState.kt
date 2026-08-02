package dev.pleiades.masamune.apps

/**
 * The seam between the Battery&Power-category block impls and the real device power/battery state.
 *
 * Every way a Battery&Power block can *read* the battery or the power manager — charge percent,
 * charge status, the plugged source, temperature, voltage, technology, power-save mode, doze/idle,
 * screen interactivity — is one method here, and — exactly like [AppInspector] does for the Apps
 * blocks and [SystemSettings] does for the Settings blocks — there is deliberately nothing `android.*`
 * on this interface. That single constraint is what buys the whole slice its JVM-testability:
 * [dev.pleiades.masamune.flow.runtime.impl.PowerBlocks] depend on this plain-data contract, never on
 * `BatteryManager`, the sticky `ACTION_BATTERY_CHANGED` intent, or `PowerManager`, so every block and
 * all its branch logic can be exercised against a fake on an ordinary unit-test JVM. A device is
 * needed to *run* these blocks, never to *test* their logic.
 *
 * ### The honest gate has one clean shape here too
 * When the app process (the only thing that can hand out a real [AndroidPowerState]) is not wired in,
 * there is simply no seam, and a block that cannot get one fails visibly by name
 * ([dev.pleiades.masamune.flow.runtime.impl.POWER_ABSENT]) rather than reporting a device reading it
 * never actually took.
 *
 * ### One honest failure shape: not present
 * Every method returns a nullable value, and `null` is the real answer "this reading is not
 * available" — the battery has no such property on this device, the sticky battery intent is absent,
 * `PowerManager` did not answer. `null` is distinct from the absent-seam case (which never reaches
 * here — there is no seam to call at all). A read that comes back `null` is routed by the block to a
 * visible [dev.pleiades.masamune.flow.runtime.Outcome.Fail] **by name**, never bound as a fabricated
 * `0`/empty/false a downstream block would trust. This slice is entirely read-only: everything it can
 * touch is unprivileged device *state*, so there is no write result-type here — the one battery-saver
 * *set* block the catalog carries needs a privileged system permission an ordinary app cannot hold and
 * is honestly gated by omission (see [dev.pleiades.masamune.flow.runtime.impl.powerLookup]'s KDoc).
 *
 * Every method is `suspend` so the real impl may touch the framework off the main thread without the
 * contract changing shape; the fake simply returns.
 */
interface PowerState {

    /**
     * The current battery charge as a whole-number percent `0..100`, computed by the real impl from
     * the sticky intent's `level * 100 / scale`, or `null` when the battery level cannot be read.
     *
     * `null` is the honest "not present" — a block routes it to a named Fail rather than binding a
     * fabricated `0` a downstream block would read as "battery empty".
     */
    suspend fun batteryPercent(): Int?

    /** The current charge status, or `null` when it cannot be read. Plain data — no `BATTERY_STATUS_*` int leaks across the seam. */
    suspend fun status(): BatteryStatus?

    /** Which external power source (if any) the device is plugged into, or `null` when it cannot be read. */
    suspend fun plugged(): PowerSource?

    /**
     * Battery temperature in **tenths of a degree Celsius** — `BatteryManager`'s own native unit,
     * carried across the seam unconverted so no precision is invented — or `null` when it cannot be
     * read. (A caller wanting degrees divides by ten; the seam does not guess a scale.)
     */
    suspend fun temperatureTenthsC(): Int?

    /** Battery voltage in **millivolts** (`BatteryManager`'s native unit), or `null` when it cannot be read. */
    suspend fun voltageMv(): Int?

    /** The battery technology string (e.g. `Li-ion`), or `null` when the device does not report one. */
    suspend fun technology(): String?

    /** Whether battery-saver / power-save mode is currently on, or `null` when `PowerManager` cannot answer. */
    suspend fun isPowerSaveMode(): Boolean?

    /** Whether the device is in idle "doze" mode, or `null` when it cannot be read (needs API 23; below it, honest `null`). */
    suspend fun isDeviceIdle(): Boolean?

    /** Whether the device is in an interactive state (roughly, screen on), or `null` when it cannot be read. */
    suspend fun isInteractive(): Boolean?
}

/**
 * The charge status, as plain data modelled on `BatteryManager.BATTERY_STATUS_*`.
 *
 * A real enum rather than a leaked int: the five states are genuinely different (a `battery_charging`
 * decision must tell CHARGING from FULL from DISCHARGING), and collapsing or guessing them would route
 * a flow on the wrong branch. [UNKNOWN] is the honest catch-all the platform itself defines for a
 * status it cannot classify — distinct from `null`, which means the status could not be read at all.
 */
enum class BatteryStatus { CHARGING, DISCHARGING, FULL, NOT_CHARGING, UNKNOWN }

/**
 * The external power source, as plain data modelled on `BatteryManager.BATTERY_PLUGGED_*`.
 *
 * [UNPLUGGED] is a real answer ("plugged extra is 0 — running on battery"), distinct from a `null`
 * read (the plugged state could not be determined). A `power_source_plugged` decision routes NO on
 * [UNPLUGGED] and can filter YES to a specific source, so the source is carried as a real enum rather
 * than guessed at the device boundary.
 */
enum class PowerSource { AC, USB, WIRELESS, UNPLUGGED }

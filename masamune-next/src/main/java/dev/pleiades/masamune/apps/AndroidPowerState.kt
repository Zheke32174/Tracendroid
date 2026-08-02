package dev.pleiades.masamune.apps

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager

/**
 * The real, device-backed [PowerState] — the Android glue that turns the plain-data contract into
 * reads of the sticky `ACTION_BATTERY_CHANGED` intent (level, scale, status, plugged, temperature,
 * voltage, technology) and of `PowerManager` (power-save, doze, interactive).
 *
 * This is the only file in the slice that touches `android.*`, and it is compile-only from the unit
 * tests' point of view: the blocks never see it, they see [PowerState]. Keeping every framework call
 * on this side of the seam is what lets [dev.pleiades.masamune.flow.runtime.impl.PowerBlocks] stay
 * JVM-testable against a fake.
 *
 * ### Honest boundaries — a missing reading is `null`, never a fabricated value
 *  - **The battery is read from the sticky intent.** `registerReceiver(null, ACTION_BATTERY_CHANGED)`
 *    returns the last broadcast battery state without registering a live receiver. When it is `null`
 *    (no battery, or the broadcast is not yet available) every battery read returns `null` — a named
 *    Fail downstream, never a fabricated `0`.
 *  - **Percent is computed, not assumed.** `level * 100 / scale` only when both extras are present and
 *    `scale > 0`; a missing level or a zero scale yields `null` rather than a divide-by-zero or a made-up
 *    percent.
 *  - **Sentinel extras become `null`.** `EXTRA_TEMPERATURE`/`EXTRA_VOLTAGE` default to `Int.MIN_VALUE`
 *    when absent, and `EXTRA_TECHNOLOGY` may be `null`/blank; each is surfaced as `null`, distinct from
 *    a real reading of `0`.
 *  - **Version-gated `PowerManager` reads fail closed.** Doze (`isDeviceIdleMode`) exists from API 23;
 *    below it the read returns `null` (honest "cannot read") rather than a fabricated `false`.
 */
class AndroidPowerState(private val context: Context) : PowerState {

    private val powerManager: PowerManager?
        get() = context.getSystemService(Context.POWER_SERVICE) as? PowerManager

    /** The last sticky battery broadcast, or `null` when none is available (no battery / not yet sent). */
    private fun batteryIntent(): Intent? =
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

    override suspend fun batteryPercent(): Int? {
        val intent = batteryIntent() ?: return null
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        return if (level >= 0 && scale > 0) level * 100 / scale else null
    }

    override suspend fun status(): BatteryStatus? {
        val intent = batteryIntent() ?: return null
        return when (intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
            BatteryManager.BATTERY_STATUS_CHARGING -> BatteryStatus.CHARGING
            BatteryManager.BATTERY_STATUS_DISCHARGING -> BatteryStatus.DISCHARGING
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> BatteryStatus.NOT_CHARGING
            BatteryManager.BATTERY_STATUS_FULL -> BatteryStatus.FULL
            BatteryManager.BATTERY_STATUS_UNKNOWN -> BatteryStatus.UNKNOWN
            else -> null // extra absent (-1): the status could not be read at all, distinct from UNKNOWN
        }
    }

    override suspend fun plugged(): PowerSource? {
        val intent = batteryIntent() ?: return null
        return when (intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)) {
            BatteryManager.BATTERY_PLUGGED_AC -> PowerSource.AC
            BatteryManager.BATTERY_PLUGGED_USB -> PowerSource.USB
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> PowerSource.WIRELESS
            0 -> PowerSource.UNPLUGGED // running on battery — a real answer, not "unknown"
            else -> null // extra absent (-1) or an unclassifiable plug code: not readable as a source
        }
    }

    override suspend fun temperatureTenthsC(): Int? {
        val intent = batteryIntent() ?: return null
        return intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            .takeIf { it != Int.MIN_VALUE }
    }

    override suspend fun voltageMv(): Int? {
        val intent = batteryIntent() ?: return null
        return intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, Int.MIN_VALUE)
            .takeIf { it != Int.MIN_VALUE }
    }

    override suspend fun technology(): String? {
        val intent = batteryIntent() ?: return null
        return intent.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY)?.takeIf { it.isNotBlank() }
    }

    override suspend fun isPowerSaveMode(): Boolean? = powerManager?.isPowerSaveMode

    override suspend fun isDeviceIdle(): Boolean? {
        // Doze exists from API 23; below it there is no idle mode to read — honest null, not false.
        val pm = powerManager ?: return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) pm.isDeviceIdleMode else null
    }

    @Suppress("DEPRECATION")
    override suspend fun isInteractive(): Boolean? {
        val pm = powerManager ?: return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) pm.isInteractive else pm.isScreenOn
    }
}

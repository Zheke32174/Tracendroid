package dev.pleiades.masamune.flow.runtime.impl

import dev.pleiades.masamune.apps.SensorChannel
import dev.pleiades.masamune.apps.SensorReader
import dev.pleiades.masamune.flow.expr.Value
import dev.pleiades.masamune.flow.model.FlowNode
import dev.pleiades.masamune.flow.model.Port
import dev.pleiades.masamune.flow.runtime.BlockImpl
import dev.pleiades.masamune.flow.runtime.Fiber
import dev.pleiades.masamune.flow.runtime.Outcome
import kotlin.math.abs

/**
 * The Sensor category's **device-state read** slice — the organ an AI phone operator needs to know what
 * the device's hardware is sensing right now: how bright, how warm, how much air pressure and humidity,
 * how hard it is accelerating, how strong the surrounding magnetic field, how close an object is, how
 * far a foldable is open, and which way it is facing.
 *
 * ### Why this subset and not the other six
 * Automate's Sensor category mixes unprivileged one-shot state reads with permission-gated, stateful,
 * over-time awaits:
 *  - The nine reads that run here — `ambient_light`, `ambient_temperature`, `atmospheric_pressure`,
 *    `device_acceleration`, `device_orientation`, `hinge_angle`, `magnetic_field_strength`, `proximity`
 *    and `relative_humidity` — need no runtime permission and are genuinely instantaneous: sample the
 *    sensor, answer now.
 *  - `heart_rate` needs the `BODY_SENSORS` permission; `pedometer`, `physical_activity` and
 *    `user_asleep` need `ACTIVITY_RECOGNITION` **and** are stateful accumulations/detections over time,
 *    not one-shot reads; `motion_gesture` and `significant_device_motion` are event awaits (wait for a
 *    shake / a significant motion), not state a read-only seam can sample. All six are gated by omission
 *    (see [sensorLookup]).
 *
 * ### The seam, copied from the Apps, Settings and Battery&Power blocks
 * Every device call lives behind the injected [SensorReader] — a narrow, `android.*`-free contract, the
 * exact shape [dev.pleiades.masamune.apps.AppInspector], [dev.pleiades.masamune.apps.SystemSettings] and
 * [dev.pleiades.masamune.apps.PowerState] give their categories. Two consequences, both deliberate:
 *
 *  1. **JVM-testable.** Each block reads its args as *plain data*, then calls the seam, so the whole
 *     file is unit-testable against a fake on an ordinary JVM — a device is needed to run these, never
 *     to test their branch logic.
 *  2. **Honest gate at run.** Every impl re-resolves its [SensorReader] provider and fails with
 *     [SENSOR_ABSENT] when there is no seam (the app process is not wired in, or it dropped mid-run). A
 *     read that returns `null` becomes a named [Outcome.Fail] ("could not be read") — **never** a
 *     fabricated `0` or a silent NO. A decision whose sensor cannot be read Fails rather than routing a
 *     misleading NO; a block that cannot read *says so*.
 *
 * ### WATCH decisions collapse to their one-shot form
 * The catalog marks these decisions WATCH-capable (test now, or suspend until the reading enters, leaves
 * or changes the band). The watching form needs the monitor subsystem this build does not have, so the
 * one-shot condition — "is the light in range *now*", "is the device facing this way *now*" — is what
 * runs, which is exactly what a decision in a running flow evaluates. This mirrors `screen_brightness`
 * in [dev.pleiades.masamune.flow.runtime.impl.ScreenBrightnessBlock] and the Battery&Power reads in
 * [dev.pleiades.masamune.flow.runtime.impl.BatteryLevelBlock].
 *
 * The composition helper [sensorLookup] mirrors [powerLookup], [settingsLookup] and [appsLookup]: it
 * returns the impls keyed by spec id so a caller composes `sensorLookup(provider)[id] ?: base.lookup(id)`.
 */

/** The sentence shown whenever a Sensor block cannot reach a sensor seam. Modelled on [POWER_ABSENT]. */
internal val SENSOR_ABSENT: String =
    "This sensor block cannot act: no sensor seam is available, so Masamune cannot read the device's " +
        "hardware sensors. The seam is wired only inside the Android app process; when it is absent the " +
        "block fails by name rather than reporting a device reading that never ran."

/** The default `tolerance` for `device_orientation`, matching the catalog's documented default of "30" degrees. */
private const val DEFAULT_ORIENTATION_TOLERANCE = 30.0

// --------------------------------------------------------------------------- shared helpers

/** True when [level] sits within the optional `[min, max]` bounds — an unset bound is no constraint. */
private fun inRange(level: Double, min: Double?, max: Double?): Boolean =
    (min == null || level >= min) && (max == null || level <= max)

/**
 * True when [current] is within [tolerance] degrees of [target], measured as the shortest angular
 * distance so the 0°/360° seam does not read as a 360° gap (359° is 2° from 1°, not 358°). Used for all
 * three orientation axes — every one is cyclic, and a plain subtraction would mis-route a decision near
 * the wrap point.
 */
private fun angleWithinTolerance(current: Double, target: Double, tolerance: Double): Boolean {
    val wrapped = ((current - target + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
    return abs(wrapped) <= tolerance
}

// --------------------------------------------------------------------------- scalar band decisions

/**
 * The shared shape of the eight single-scalar Sensor decisions — the catalog's own observation made
 * structural: "nearly every sensor block takes a minimum and a maximum and answers whether the reading
 * is inside the band". Each reads its [channel] through the seam, binds `varLevel` from the reading, and
 * routes YES when the value sits within the requested `[minLevel, maxLevel]` band (or no band was
 * given), NO otherwise.
 *
 * The honest-gating rule is enforced once, here, for all eight: an absent seam Fails with
 * [SENSOR_ABSENT], and a `null` reading (no such sensor, or it did not report) Fails by name — **never**
 * a fabricated `0` a downstream block would mistake for a real measurement, and **never** a silent NO
 * from an unreadable sensor. Thresholds are read via [asNumOrNull], exactly as
 * [dev.pleiades.masamune.flow.runtime.impl.BatteryLevelBlock] reads its band.
 */
internal abstract class ScalarBandSensorBlock(
    private val sensorProvider: () -> SensorReader?,
    private val channel: SensorChannel,
    /** The sensor's human name, for the "…could not be read" failure sentence. */
    private val sensorNoun: String,
) : BlockImpl {
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val sensor = sensorProvider() ?: return Outcome.Fail(SENSOR_ABSENT)
        val sample = sensor.readSensor(channel)
            ?: return Outcome.Fail("$specId: the $sensorNoun could not be read.")
        val writes = LinkedHashMap<String, Value>()
        node.outputs["varLevel"]?.bind(writes, Value.Num(sample.value))
        val ok = inRange(sample.value, args["minLevel"].asNumOrNull(), args["maxLevel"].asNumOrNull())
        return Outcome.Proceed(if (ok) Port.YES else Port.NO, writes)
    }
}

/**
 * `ambient_light` (Ambient light) — is the ambient light level (lux) inside the band?
 *
 * DECISION: the one-shot form of the catalog's WATCH decision. Binds `varLevel` from the light sensor,
 * routes YES within `[minLevel, maxLevel]`. An unreadable sensor Fails by name, never a silent NO.
 */
internal class AmbientLightBlock(provider: () -> SensorReader?) :
    ScalarBandSensorBlock(provider, SensorChannel.LIGHT, "ambient light sensor") {
    override val specId = "ambient_light"
}

/**
 * `ambient_temperature` (Ambient temperature) — is the ambient temperature (°C) inside the band?
 *
 * DECISION: the one-shot form of the WATCH decision. Binds `varLevel` from the thermometer, routes YES
 * within the band. Absent on the many devices with no ambient thermometer → a named Fail, never a
 * fabricated `0`.
 */
internal class AmbientTemperatureBlock(provider: () -> SensorReader?) :
    ScalarBandSensorBlock(provider, SensorChannel.AMBIENT_TEMPERATURE, "ambient temperature sensor") {
    override val specId = "ambient_temperature"
}

/**
 * `atmospheric_pressure` (Atmospheric pressure) — is the air pressure (hPa) inside the band?
 *
 * DECISION: the one-shot form of the WATCH decision. Binds `varLevel` from the barometer, routes YES
 * within the band. Absent on devices with no barometer → a named Fail.
 */
internal class AtmosphericPressureBlock(provider: () -> SensorReader?) :
    ScalarBandSensorBlock(provider, SensorChannel.PRESSURE, "atmospheric pressure sensor") {
    override val specId = "atmospheric_pressure"
}

/**
 * `device_acceleration` (Device acceleration) — is the acceleration magnitude (m/s²) inside the band?
 *
 * DECISION: the one-shot form of the WATCH decision. The seam reduces the accelerometer's `(x, y, z)` to
 * its vector magnitude — the acceleration *strength* the single `varLevel` output names — and this binds
 * it and routes YES within the band. An unreadable sensor Fails by name.
 */
internal class DeviceAccelerationBlock(provider: () -> SensorReader?) :
    ScalarBandSensorBlock(provider, SensorChannel.ACCELERATION, "acceleration sensor") {
    override val specId = "device_acceleration"
}

/**
 * `hinge_angle` (Device hinge angle) — is a foldable's hinge angle (degrees) inside the band?
 *
 * DECISION: the one-shot form of the WATCH decision. Binds `varLevel` from the hinge sensor, routes YES
 * within the band. Absent on non-foldables and below API 30 → a named Fail, never a fabricated angle —
 * the honest answer for a device with no hinge is "cannot read", not "0°".
 */
internal class HingeAngleBlock(provider: () -> SensorReader?) :
    ScalarBandSensorBlock(provider, SensorChannel.HINGE_ANGLE, "hinge angle sensor") {
    override val specId = "hinge_angle"
}

/**
 * `magnetic_field_strength` (Magnetic field strength) — is the field magnitude (µT) inside the band?
 *
 * DECISION: the one-shot form of the WATCH decision. The seam reduces the magnetometer's `(x, y, z)` to
 * its vector magnitude — the field *strength* the single `varLevel` output names — and this binds it and
 * routes YES within the band. An unreadable sensor Fails by name.
 */
internal class MagneticFieldStrengthBlock(provider: () -> SensorReader?) :
    ScalarBandSensorBlock(provider, SensorChannel.MAGNETIC_FIELD, "magnetic field sensor") {
    override val specId = "magnetic_field_strength"
}

/**
 * `proximity` (Proximity) — is the proximity distance (cm) inside the band?
 *
 * DECISION: the one-shot form of the WATCH decision. Binds `varLevel` from the proximity sensor, routes
 * YES within the band. (Many proximity sensors report only near/far as `0` or their max range; the seam
 * carries whatever the sensor honestly reports.) An unreadable sensor Fails by name, never a silent NO.
 */
internal class ProximityBlock(provider: () -> SensorReader?) :
    ScalarBandSensorBlock(provider, SensorChannel.PROXIMITY, "proximity sensor") {
    override val specId = "proximity"
}

/**
 * `relative_humidity` (Relative humidity) — is the relative humidity (%) inside the band?
 *
 * DECISION: the one-shot form of the WATCH decision. Binds `varLevel` from the humidity sensor, routes
 * YES within the band. Absent on the many devices with no humidity sensor → a named Fail.
 */
internal class RelativeHumidityBlock(provider: () -> SensorReader?) :
    ScalarBandSensorBlock(provider, SensorChannel.RELATIVE_HUMIDITY, "relative humidity sensor") {
    override val specId = "relative_humidity"
}

// --------------------------------------------------------------------------- orientation decision

/**
 * `device_orientation` (Device orientation) — is the device facing the requested way, within tolerance?
 *
 * DECISION: the one-shot form of the WATCH decision, and the one Sensor read that is not a single-scalar
 * band. It reads the fused orientation once, **always** binds `varCurrentAzimuth`, `varCurrentPitch` and
 * `varCurrentRoll` from the reading (so a flow can record where the device is pointing regardless of the
 * branch), then routes YES only when every *specified* target axis is within `tolerance` degrees of the
 * current angle. An unspecified axis (a blank `azimuth`/`pitch`/`roll` arg) is no constraint — exactly
 * the "an unset bound is no constraint" rule the scalar band uses — so a block with no targets is
 * vacuously YES, and a block that pins only azimuth ignores pitch and roll. The tolerance defaults to
 * [DEFAULT_ORIENTATION_TOLERANCE] (the catalog's documented "30"); each axis is compared as a cyclic
 * angle so the 0°/360° wrap does not mis-route. An orientation the seam cannot read is a named
 * [Outcome.Fail], never a silent NO or a fabricated `0`.
 */
internal class DeviceOrientationBlock(
    private val sensorProvider: () -> SensorReader?,
) : BlockImpl {
    override val specId = "device_orientation"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val sensor = sensorProvider() ?: return Outcome.Fail(SENSOR_ABSENT)
        val orientation = sensor.readOrientation()
            ?: return Outcome.Fail("device_orientation: the device orientation could not be read.")
        val writes = LinkedHashMap<String, Value>()
        node.outputs["varCurrentAzimuth"]?.bind(writes, Value.Num(orientation.azimuthDegrees))
        node.outputs["varCurrentPitch"]?.bind(writes, Value.Num(orientation.pitchDegrees))
        node.outputs["varCurrentRoll"]?.bind(writes, Value.Num(orientation.rollDegrees))
        val tolerance = args["tolerance"].asNumOrNull() ?: DEFAULT_ORIENTATION_TOLERANCE
        val azimuthTarget = args["azimuth"].asNumOrNull()
        val pitchTarget = args["pitch"].asNumOrNull()
        val rollTarget = args["roll"].asNumOrNull()
        val ok =
            (azimuthTarget == null || angleWithinTolerance(orientation.azimuthDegrees, azimuthTarget, tolerance)) &&
            (pitchTarget == null || angleWithinTolerance(orientation.pitchDegrees, pitchTarget, tolerance)) &&
            (rollTarget == null || angleWithinTolerance(orientation.rollDegrees, rollTarget, tolerance))
        return Outcome.Proceed(if (ok) Port.YES else Port.NO, writes)
    }
}

// --------------------------------------------------------------------------- composition + helpers

/**
 * The nine registered Sensor device-state impls, keyed by spec id, all sharing one [provider].
 *
 * Mirrors [powerLookup], [settingsLookup] and [appsLookup]: it always returns the map, and the honest
 * gate is the per-block gate-at-run (each fails with [SENSOR_ABSENT] when the provider yields no seam),
 * so a caller composes over its base registry exactly as the other categories do:
 *
 * ```
 * val sensors = sensorLookup(sensorProvider)
 * fun lookup(id: String): BlockImpl? = sensors[id] ?: power[id] ?: settings[id] ?: apps[id] ?: baseRegistry.lookup(id)
 * ```
 *
 * ### What stays gated by omission, and why
 * The category's remaining Sensor blocks are deliberately **not** here, so at run time the scheduler
 * finds no impl and gates them by the honest-by-omission mechanism the catalog's own `requires` set (or
 * the block's own shape) expresses. None of them carry `Requirement.SHELL`, so — unlike
 * `device_idle_mode_active`/`device_interactive` in the Battery&Power slice — there is nothing here that
 * is built-but-unregistered; the gated ones are omitted entirely, on two honest grounds:
 *  - **Permission-gated reads.** `heart_rate` carries `BODY_SENSORS`; `pedometer`, `physical_activity`
 *    and `user_asleep` carry `ACTIVITY_RECOGNITION`. These are dangerous runtime permissions an
 *    ordinary process is not assumed to hold, so — exactly as `power_save_mode_set_state` is gated in
 *    the Battery&Power slice — registering an impl would fake a read that would be refused.
 *  - **Not one-shot state reads at all.** `pedometer`, `physical_activity` and `user_asleep` are
 *    stateful accumulations/detections *over time* (steps counted, an activity classified across an
 *    interval, a sleep transition), and `motion_gesture` and `significant_device_motion` are event
 *    awaits (wait for a shake / a significant motion). None is a value a read-only seam can honestly
 *    sample as a single YES/NO-now, so they wait for the monitor/await subsystem this build does not
 *    have rather than being flattened into a dishonest one-shot.
 */
fun sensorLookup(provider: () -> SensorReader?): Map<String, BlockImpl> = listOf(
    AmbientLightBlock(provider),
    AmbientTemperatureBlock(provider),
    AtmosphericPressureBlock(provider),
    DeviceAccelerationBlock(provider),
    DeviceOrientationBlock(provider),
    HingeAngleBlock(provider),
    MagneticFieldStrengthBlock(provider),
    ProximityBlock(provider),
    RelativeHumidityBlock(provider),
).associateBy { it.specId }

/** Bind [value] under this non-blank output-variable name into [writes]; a blank name binds nothing. */
private fun String.bind(writes: MutableMap<String, Value>, value: Value) {
    if (isNotBlank()) writes[this] = value
}

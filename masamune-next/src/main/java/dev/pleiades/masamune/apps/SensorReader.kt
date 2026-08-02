package dev.pleiades.masamune.apps

/**
 * The seam between the Sensor-category block impls and the real device hardware sensors.
 *
 * Every way a Sensor block can *read* the hardware — ambient light, ambient temperature, atmospheric
 * pressure, device acceleration, hinge angle, magnetic-field strength, proximity, relative humidity,
 * and the fused device orientation — is one method here, and — exactly like [AppInspector] does for the
 * Apps blocks, [SystemSettings] does for the Settings blocks and [PowerState] does for the Battery&Power
 * blocks — there is deliberately nothing `android.*` on this interface. That single constraint is what
 * buys the whole slice its JVM-testability: [dev.pleiades.masamune.flow.runtime.impl.SensorBlocks]
 * depend on this plain-data contract, never on `SensorManager`, a `Sensor`, or a `SensorEventListener`,
 * so every block and all its branch logic can be exercised against a fake on an ordinary unit-test JVM.
 * A device is needed to *run* these blocks, never to *test* their logic.
 *
 * ### The honest gate has one clean shape here too
 * When the app process (the only thing that can hand out a real [AndroidSensorReader]) is not wired in,
 * there is simply no seam, and a block that cannot get one fails visibly by name
 * ([dev.pleiades.masamune.flow.runtime.impl.SENSOR_ABSENT]) rather than reporting a device reading it
 * never actually took.
 *
 * ### One honest failure shape: not present
 * Every method returns a nullable value, and `null` is the real answer "this reading is not available"
 * — the device has no such sensor, the sensor did not report within the one-shot window, or the sensor
 * kind does not exist on this API level. `null` is distinct from the absent-seam case (which never
 * reaches here — there is no seam to call at all). A read that comes back `null` is routed by the block
 * to a visible [dev.pleiades.masamune.flow.runtime.Outcome.Fail] **by name**, never bound as a
 * fabricated `0` a downstream block would trust as a real reading. This slice is entirely read-only:
 * everything it can touch is unprivileged sensor *state*, so there is no write result-type here.
 *
 * ### What the catalog carries that is *not* here, and why
 * `CatalogSensor` also declares `heart_rate` (needs the `BODY_SENSORS` runtime permission),
 * `pedometer`/`physical_activity`/`user_asleep` (all need `ACTIVITY_RECOGNITION` and are stateful
 * over-time awaits, not one-shot reads), and `motion_gesture`/`significant_device_motion` (event
 * awaits, not state reads). Those are gated by omission (see
 * [dev.pleiades.masamune.flow.runtime.impl.sensorLookup]'s KDoc), so this seam models only the
 * unprivileged one-shot state reads and gives each of those exactly one method.
 *
 * Every method is `suspend` because a hardware read is inherently asynchronous — the real impl arms a
 * one-shot listener and waits for the first event off the caller's thread without the contract changing
 * shape; the fake simply returns.
 */
interface SensorReader {

    /**
     * A one-shot scalar reading of [channel] in that channel's own native unit (see [SensorChannel]),
     * or `null` when the sensor is absent on this device / did not report / does not exist on this API
     * level.
     *
     * The catalog exposes exactly one `varLevel` output for every scalar sensor block, so the seam
     * returns exactly one reduced scalar per channel — the real impl computes it honestly (a raw axis
     * value for a single-axis sensor, the vector magnitude for a multi-axis sensor). `null` is the
     * honest "not present": a block routes it to a named Fail rather than binding a fabricated `0` a
     * downstream block would read as "the sensor said zero".
     */
    suspend fun readSensor(channel: SensorChannel): SensorSample?

    /**
     * A one-shot reading of the device's fused orientation as azimuth/pitch/roll in **degrees**, or
     * `null` when the rotation-vector sensor is absent or did not report.
     *
     * Separate from [readSensor] because orientation is not a single scalar in a band — the catalog's
     * `device_orientation` block matches three angles against three optional targets within a
     * tolerance, so the seam carries all three as real data rather than collapsing them.
     */
    suspend fun readOrientation(): OrientationSample?
}

/**
 * The unprivileged one-shot scalar sensor channels the Sensor blocks read, as plain data — one entry
 * per registered scalar block. A real enum rather than a leaked `Sensor.TYPE_*` int: the mapping from a
 * channel to an Android sensor type (and, for the multi-axis channels, from raw axes to a single
 * reduced level) lives entirely in [AndroidSensorReader], so nothing `android.*` crosses the seam.
 *
 * Each channel's native unit, carried across the seam unconverted so no precision is invented:
 *  - [LIGHT] — lux
 *  - [AMBIENT_TEMPERATURE] — degrees Celsius
 *  - [PRESSURE] — hectopascals (millibar)
 *  - [ACCELERATION] — metres per second squared, the **magnitude** of the acceleration vector
 *  - [HINGE_ANGLE] — degrees (foldable devices; absent, hence `null`, on non-foldables and below API 30)
 *  - [MAGNETIC_FIELD] — microtesla, the **magnitude** of the field vector
 *  - [PROXIMITY] — centimetres (many devices report only a near/far binary as 0 or the max range)
 *  - [RELATIVE_HUMIDITY] — percent
 */
enum class SensorChannel {
    LIGHT,
    AMBIENT_TEMPERATURE,
    PRESSURE,
    ACCELERATION,
    HINGE_ANGLE,
    MAGNETIC_FIELD,
    PROXIMITY,
    RELATIVE_HUMIDITY,
}

/**
 * One scalar sensor reading, reduced to the single [value] the catalog's `varLevel` output expects.
 *
 * A wrapper rather than a bare `Double?` so a present-but-zero reading (`SensorSample(0.0)`) is
 * unmistakably distinct from an absent one (`null`): the whole honest-gating rule of the slice is that
 * "the sensor reported 0" and "the sensor did not report" must never be conflated, and giving the two
 * different Kotlin shapes at the seam is what enforces it — a `null` cannot be silently read as a `0`.
 */
data class SensorSample(val value: Double)

/**
 * The device's fused orientation, all three angles in **degrees**, as plain data modelled on what
 * `SensorManager.getOrientation` yields (radians there, degrees here so the block compares against the
 * degree targets the catalog's `azimuth`/`pitch`/`roll` args carry).
 *
 * A real three-field value rather than three loose returns: `device_orientation` binds all three
 * current angles as outputs and matches each against its own optional target, so carrying them together
 * keeps a single honest read from being split into three that could disagree.
 */
data class OrientationSample(
    val azimuthDegrees: Double,
    val pitchDegrees: Double,
    val rollDegrees: Double,
)

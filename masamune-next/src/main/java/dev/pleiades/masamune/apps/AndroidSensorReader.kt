package dev.pleiades.masamune.apps

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.math.sqrt

/**
 * The real, device-backed [SensorReader] — the Android glue that turns the plain-data contract into a
 * one-shot `SensorManager` registration: for each read it arms a `SensorEventListener`, waits for the
 * first event (bounded by a short timeout), unregisters, and hands back the reduced value.
 *
 * This is the only file in the slice that touches `android.*`, and it is compile-only from the unit
 * tests' point of view: the blocks never see it, they see [SensorReader]. Keeping every framework call
 * on this side of the seam is what lets [dev.pleiades.masamune.flow.runtime.impl.SensorBlocks] stay
 * JVM-testable against a fake.
 *
 * ### Honest boundaries — a missing reading is `null`, never a fabricated value
 *  - **An absent sensor is `null`.** `getDefaultSensor(type)` returns `null` when the device has no such
 *    sensor; the read returns `null` (a named Fail downstream) rather than a fabricated `0`. This is the
 *    honest answer for the many devices that ship no barometer, no thermometer, no humidity sensor, no
 *    hinge sensor, etc.
 *  - **A silent sensor is `null`.** If no event arrives inside [ONE_SHOT_TIMEOUT_MS] the read returns
 *    `null` rather than blocking a fiber forever or inventing a last-known value it never saw. Proximity
 *    in particular only emits on change, so a bounded wait is the honest one-shot.
 *  - **A vector sensor is reduced to a real magnitude, not a guessed axis.** Acceleration and magnetic
 *    field arrive as `(x, y, z)`; the seam returns `sqrt(x²+y²+z²)`, which is the field/acceleration
 *    *strength* the catalog's single `varLevel` output names — not `x` alone, which would silently drop
 *    two thirds of the reading.
 *  - **Version-gated sensor kinds fail closed.** `TYPE_HINGE_ANGLE` exists from API 30; below it the
 *    channel maps to no sensor type and the read returns `null` (honest "cannot read"), never a
 *    fabricated angle.
 *  - **Orientation is fused, not faked.** `readOrientation` derives azimuth/pitch/roll from the
 *    rotation-vector sensor via `getRotationMatrixFromVector` + `getOrientation`, converting the
 *    framework's radians to the degrees the block compares against; an absent rotation-vector sensor is
 *    `null`.
 */
class AndroidSensorReader(private val context: Context) : SensorReader {

    private val sensorManager: SensorManager?
        get() = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    override suspend fun readSensor(channel: SensorChannel): SensorSample? {
        val type = channel.androidSensorType() ?: return null // kind not available on this API level
        val values = oneShot(type) ?: return null // no such sensor, or it did not report in time
        val level = channel.reduce(values) ?: return null
        return SensorSample(level)
    }

    override suspend fun readOrientation(): OrientationSample? {
        val values = oneShot(Sensor.TYPE_ROTATION_VECTOR) ?: return null
        val rotation = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(rotation, values)
        val orientation = FloatArray(3)
        SensorManager.getOrientation(rotation, orientation)
        return OrientationSample(
            azimuthDegrees = Math.toDegrees(orientation[0].toDouble()),
            pitchDegrees = Math.toDegrees(orientation[1].toDouble()),
            rollDegrees = Math.toDegrees(orientation[2].toDouble()),
        )
    }

    /** The Android `Sensor.TYPE_*` this channel reads, or `null` when the kind needs a newer API level. */
    private fun SensorChannel.androidSensorType(): Int? = when (this) {
        SensorChannel.LIGHT -> Sensor.TYPE_LIGHT
        SensorChannel.AMBIENT_TEMPERATURE -> Sensor.TYPE_AMBIENT_TEMPERATURE
        SensorChannel.PRESSURE -> Sensor.TYPE_PRESSURE
        SensorChannel.ACCELERATION -> Sensor.TYPE_ACCELEROMETER
        SensorChannel.MAGNETIC_FIELD -> Sensor.TYPE_MAGNETIC_FIELD
        SensorChannel.PROXIMITY -> Sensor.TYPE_PROXIMITY
        SensorChannel.RELATIVE_HUMIDITY -> Sensor.TYPE_RELATIVE_HUMIDITY
        SensorChannel.HINGE_ANGLE ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Sensor.TYPE_HINGE_ANGLE else null
    }

    /** Reduce a raw event's axes to the single scalar `varLevel` names: magnitude for vectors, axis 0 otherwise. */
    private fun SensorChannel.reduce(values: FloatArray): Double? {
        if (values.isEmpty()) return null
        return when (this) {
            SensorChannel.ACCELERATION, SensorChannel.MAGNETIC_FIELD -> magnitude(values)
            else -> values[0].toDouble()
        }
    }

    private fun magnitude(values: FloatArray): Double {
        var sumOfSquares = 0.0
        for (v in values) sumOfSquares += v.toDouble() * v.toDouble()
        return sqrt(sumOfSquares)
    }

    /**
     * Arm a one-shot listener for [sensorType], returning the first event's values, or `null` when the
     * device has no such sensor or none arrives within [ONE_SHOT_TIMEOUT_MS]. The listener is always
     * unregistered — on the event, on cancellation, and on a failed registration — so no read leaks a
     * live sensor subscription.
     */
    private suspend fun oneShot(sensorType: Int): FloatArray? {
        val manager = sensorManager ?: return null
        val sensor = manager.getDefaultSensor(sensorType) ?: return null // absent on this device — honest null
        return withTimeoutOrNull(ONE_SHOT_TIMEOUT_MS) {
            suspendCancellableCoroutine<FloatArray?> { cont ->
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        manager.unregisterListener(this)
                        if (cont.isActive) cont.resume(event.values.copyOf())
                    }

                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
                }
                cont.invokeOnCancellation { manager.unregisterListener(listener) }
                val registered = manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_FASTEST)
                if (!registered && cont.isActive) {
                    manager.unregisterListener(listener)
                    cont.resume(null) // the device declined to deliver this sensor — honest null
                }
            }
        }
    }

    private companion object {
        /** How long a one-shot read waits for the first sensor event before honestly giving up (`null`). */
        const val ONE_SHOT_TIMEOUT_MS = 2_000L
    }
}

package dev.pleiades.masamune.flow.runtime

import dev.pleiades.masamune.apps.OrientationSample
import dev.pleiades.masamune.apps.SensorChannel
import dev.pleiades.masamune.apps.SensorReader
import dev.pleiades.masamune.apps.SensorSample
import dev.pleiades.masamune.flow.expr.Value
import dev.pleiades.masamune.flow.model.FlowNode
import dev.pleiades.masamune.flow.model.Port
import dev.pleiades.masamune.flow.runtime.impl.AmbientLightBlock
import dev.pleiades.masamune.flow.runtime.impl.AmbientTemperatureBlock
import dev.pleiades.masamune.flow.runtime.impl.AtmosphericPressureBlock
import dev.pleiades.masamune.flow.runtime.impl.DeviceAccelerationBlock
import dev.pleiades.masamune.flow.runtime.impl.DeviceOrientationBlock
import dev.pleiades.masamune.flow.runtime.impl.HingeAngleBlock
import dev.pleiades.masamune.flow.runtime.impl.MagneticFieldStrengthBlock
import dev.pleiades.masamune.flow.runtime.impl.ProximityBlock
import dev.pleiades.masamune.flow.runtime.impl.RelativeHumidityBlock
import dev.pleiades.masamune.flow.runtime.impl.sensorLookup
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit proof that the Sensor device-state blocks branch and bind correctly — run against a
 * [FakeSensorReader] on the JVM, never a device, which is exactly what the `android.*`-free
 * [SensorReader] seam buys (the same seam shape the Apps, Settings and Battery&Power blocks use). Each
 * test drives a block the way the runtime does — an args map of resolved [Value]s and a [FlowNode]
 * carrying the output bindings — and asserts on the [Outcome] and its writes. The honest failure shape
 * is the point of the coverage: a read the device cannot answer is a visible [Outcome.Fail], never a
 * fabricated `0` and never a silent NO. The absent-seam path is checked for all nine blocks.
 */
class SensorReaderBlocksTest {

    /**
     * A fully scriptable fake standing in for the real hardware sensors. A channel absent from
     * [samples] reads back `null` — exactly as a device with no such sensor would answer — and the
     * block turns that `null` into a named Fail.
     */
    private class FakeSensorReader(
        private val samples: Map<SensorChannel, Double> = emptyMap(),
        private val orientation: OrientationSample? = null,
    ) : SensorReader {
        override suspend fun readSensor(channel: SensorChannel): SensorSample? =
            samples[channel]?.let { SensorSample(it) }

        override suspend fun readOrientation(): OrientationSample? = orientation
    }

    /** One scalar-band block, paired with the channel it reads, so one loop can drive all eight. */
    private data class ScalarCase(
        val specId: String,
        val channel: SensorChannel,
        val make: (() -> SensorReader?) -> BlockImpl,
    )

    private val scalarCases = listOf(
        ScalarCase("ambient_light", SensorChannel.LIGHT, ::AmbientLightBlock),
        ScalarCase("ambient_temperature", SensorChannel.AMBIENT_TEMPERATURE, ::AmbientTemperatureBlock),
        ScalarCase("atmospheric_pressure", SensorChannel.PRESSURE, ::AtmosphericPressureBlock),
        ScalarCase("device_acceleration", SensorChannel.ACCELERATION, ::DeviceAccelerationBlock),
        ScalarCase("hinge_angle", SensorChannel.HINGE_ANGLE, ::HingeAngleBlock),
        ScalarCase("magnetic_field_strength", SensorChannel.MAGNETIC_FIELD, ::MagneticFieldStrengthBlock),
        ScalarCase("proximity", SensorChannel.PROXIMITY, ::ProximityBlock),
        ScalarCase("relative_humidity", SensorChannel.RELATIVE_HUMIDITY, ::RelativeHumidityBlock),
    )

    private fun node(specId: String, vararg outputs: Pair<String, String>) =
        FlowNode("n", specId, 0f, 0f, outputs = outputs.toMap())

    private fun fiber() = Fiber("f", "flow")

    // ------------------------------------------------------------------ scalar band blocks (all eight)

    @Test fun everyScalarBlockBindsLevelAndBandsYes() = runTest {
        for (case in scalarCases) {
            val seam = FakeSensorReader(mapOf(case.channel to 42.0))
            val outcome = case.make { seam }.run(
                fiber(),
                node(case.specId, "varLevel" to "l"),
                mapOf("minLevel" to Value.Num(10.0), "maxLevel" to Value.Num(100.0)),
            )
            val proceed = outcome as Outcome.Proceed
            assertEquals("${case.specId} YES within band", Port.YES, proceed.port)
            assertEquals("${case.specId} binds the honest level", Value.Num(42.0), proceed.writes["l"])
        }
    }

    @Test fun everyScalarBlockBindsButRoutesNoBelowMin() = runTest {
        // A threshold read via asNumOrNull; below it routes NO but still binds the honest level.
        for (case in scalarCases) {
            val seam = FakeSensorReader(mapOf(case.channel to 15.0))
            val outcome = case.make { seam }.run(
                fiber(),
                node(case.specId, "varLevel" to "l"),
                mapOf("minLevel" to Value.Num(20.0)),
            )
            val proceed = outcome as Outcome.Proceed
            assertEquals("${case.specId} NO below min", Port.NO, proceed.port)
            assertEquals("${case.specId} still binds", Value.Num(15.0), proceed.writes["l"])
        }
    }

    @Test fun everyScalarBlockRoutesNoAboveMax() = runTest {
        for (case in scalarCases) {
            val seam = FakeSensorReader(mapOf(case.channel to 250.0))
            val outcome = case.make { seam }.run(
                fiber(),
                node(case.specId, "varLevel" to "l"),
                mapOf("maxLevel" to Value.Num(100.0)),
            )
            assertEquals("${case.specId} NO above max", Port.NO, (outcome as Outcome.Proceed).port)
        }
    }

    @Test fun everyScalarBlockYesWithoutBand() = runTest {
        for (case in scalarCases) {
            val seam = FakeSensorReader(mapOf(case.channel to 0.0)) // a real zero reading, not "absent"
            val outcome = case.make { seam }.run(
                fiber(),
                node(case.specId, "varLevel" to "l"),
                emptyMap(),
            )
            val proceed = outcome as Outcome.Proceed
            assertEquals("${case.specId} YES with no band", Port.YES, proceed.port)
            // A present 0 binds as 0 — distinct from the unreadable case below, which Fails.
            assertEquals("${case.specId} binds a real zero", Value.Num(0.0), proceed.writes["l"])
        }
    }

    @Test fun everyScalarBlockFailsWhenUnreadable() = runTest {
        // An empty fake means the sensor is absent: every block must Fail by name, never bind a 0.
        for (case in scalarCases) {
            val outcome = case.make { FakeSensorReader() }.run(
                fiber(),
                node(case.specId, "varLevel" to "l"),
                mapOf("minLevel" to Value.Num(10.0)),
            )
            assertTrue("${case.specId} must Fail when unreadable", outcome is Outcome.Fail)
            assertNull("${case.specId} binds nothing on Fail", (outcome as Outcome.Fail).writes["l"])
        }
    }

    // ------------------------------------------------------------------ device_orientation

    @Test fun deviceOrientationBindsAllThreeAndYesWithoutTargets() = runTest {
        val seam = FakeSensorReader(orientation = OrientationSample(90.0, -10.0, 5.0))
        val outcome = DeviceOrientationBlock { seam }.run(
            fiber(),
            node(
                "device_orientation",
                "varCurrentAzimuth" to "a",
                "varCurrentPitch" to "p",
                "varCurrentRoll" to "r",
            ),
            emptyMap(),
        )
        val proceed = outcome as Outcome.Proceed
        // No targets → no constraint → vacuously YES, but the current angles are always bound.
        assertEquals(Port.YES, proceed.port)
        assertEquals(Value.Num(90.0), proceed.writes["a"])
        assertEquals(Value.Num(-10.0), proceed.writes["p"])
        assertEquals(Value.Num(5.0), proceed.writes["r"])
    }

    @Test fun deviceOrientationYesWithinDefaultTolerance() = runTest {
        // No explicit tolerance → default 30°. Azimuth 100 is within 30 of target 90.
        val seam = FakeSensorReader(orientation = OrientationSample(100.0, 0.0, 0.0))
        val outcome = DeviceOrientationBlock { seam }.run(
            fiber(),
            node("device_orientation"),
            mapOf("azimuth" to Value.Num(90.0)),
        )
        assertEquals(Port.YES, (outcome as Outcome.Proceed).port)
    }

    @Test fun deviceOrientationNoWhenAnAxisOutsideTolerance() = runTest {
        // Azimuth matches, but pitch 60 is not within 30 of target 0 → NO.
        val seam = FakeSensorReader(orientation = OrientationSample(90.0, 60.0, 0.0))
        val outcome = DeviceOrientationBlock { seam }.run(
            fiber(),
            node("device_orientation"),
            mapOf(
                "azimuth" to Value.Num(90.0),
                "pitch" to Value.Num(0.0),
                "tolerance" to Value.Num(30.0),
            ),
        )
        assertEquals(Port.NO, (outcome as Outcome.Proceed).port)
    }

    @Test fun deviceOrientationMatchesAcrossTheZeroWrap() = runTest {
        // 359° is 2° from a target of 1° — the cyclic tolerance must not read it as a 358° gap.
        val seam = FakeSensorReader(orientation = OrientationSample(359.0, 0.0, 0.0))
        val outcome = DeviceOrientationBlock { seam }.run(
            fiber(),
            node("device_orientation"),
            mapOf("azimuth" to Value.Num(1.0), "tolerance" to Value.Num(5.0)),
        )
        assertEquals(Port.YES, (outcome as Outcome.Proceed).port)
    }

    @Test fun deviceOrientationHonorsCustomTolerance() = runTest {
        // Azimuth 90 vs target 90 is exact; a tiny tolerance still passes, and a wider gap needs a wider tolerance.
        val seam = FakeSensorReader(orientation = OrientationSample(120.0, 0.0, 0.0))
        val tight = DeviceOrientationBlock { seam }.run(
            fiber(), node("device_orientation"),
            mapOf("azimuth" to Value.Num(90.0), "tolerance" to Value.Num(10.0)),
        )
        assertEquals(Port.NO, (tight as Outcome.Proceed).port)
        val loose = DeviceOrientationBlock { seam }.run(
            fiber(), node("device_orientation"),
            mapOf("azimuth" to Value.Num(90.0), "tolerance" to Value.Num(45.0)),
        )
        assertEquals(Port.YES, (loose as Outcome.Proceed).port)
    }

    @Test fun deviceOrientationFailsWhenUnreadable() = runTest {
        val outcome = DeviceOrientationBlock { FakeSensorReader(orientation = null) }.run(
            fiber(),
            node("device_orientation", "varCurrentAzimuth" to "a"),
            mapOf("azimuth" to Value.Num(90.0)),
        )
        assertTrue(outcome is Outcome.Fail)
        assertNull((outcome as Outcome.Fail).writes["a"])
    }

    // ------------------------------------------------------------------ absent seam (all nine)

    @Test fun allBlocksFailByNameWhenSeamAbsent() = runTest {
        val absent: () -> SensorReader? = { null }
        val blocks = listOf(
            AmbientLightBlock(absent) to node("ambient_light"),
            AmbientTemperatureBlock(absent) to node("ambient_temperature"),
            AtmosphericPressureBlock(absent) to node("atmospheric_pressure"),
            DeviceAccelerationBlock(absent) to node("device_acceleration"),
            DeviceOrientationBlock(absent) to node("device_orientation"),
            HingeAngleBlock(absent) to node("hinge_angle"),
            MagneticFieldStrengthBlock(absent) to node("magnetic_field_strength"),
            ProximityBlock(absent) to node("proximity"),
            RelativeHumidityBlock(absent) to node("relative_humidity"),
        )
        for ((block, flowNode) in blocks) {
            val outcome = block.run(
                fiber(),
                flowNode,
                mapOf("minLevel" to Value.Num(1.0), "azimuth" to Value.Num(90.0)),
            )
            assertTrue("${block.specId} must Fail when the seam is absent", outcome is Outcome.Fail)
            assertTrue((outcome as Outcome.Fail).message.contains("sensor seam"))
        }
    }

    // ------------------------------------------------------------------ composition helper

    @Test fun sensorLookupExposesTheNineRegisteredBlocksBySpecId() {
        val lookup = sensorLookup { null }
        assertEquals(
            setOf(
                "ambient_light",
                "ambient_temperature",
                "atmospheric_pressure",
                "device_acceleration",
                "device_orientation",
                "hinge_angle",
                "magnetic_field_strength",
                "proximity",
                "relative_humidity",
            ),
            lookup.keys,
        )
        // Gated by omission — permission-gated reads (BODY_SENSORS / ACTIVITY_RECOGNITION) and
        // event/over-time awaits are not registered.
        assertNull(lookup["heart_rate"])
        assertNull(lookup["pedometer"])
        assertNull(lookup["physical_activity"])
        assertNull(lookup["user_asleep"])
        assertNull(lookup["motion_gesture"])
        assertNull(lookup["significant_device_motion"])
        // Mirrors the layers below: composes over a base registry via `sensorLookup(...)[id] ?: base`.
        assertNull(lookup["battery_level"])
        assertNull(lookup["ringer_mode"])
        assertNull(lookup["app_installed"])
        assertEquals("proximity", lookup["proximity"]!!.specId)
    }
}

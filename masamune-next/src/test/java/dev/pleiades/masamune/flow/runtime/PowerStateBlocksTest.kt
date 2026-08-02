package dev.pleiades.masamune.flow.runtime

import dev.pleiades.masamune.apps.BatteryStatus
import dev.pleiades.masamune.apps.PowerSource
import dev.pleiades.masamune.apps.PowerState
import dev.pleiades.masamune.flow.expr.Value
import dev.pleiades.masamune.flow.model.FlowNode
import dev.pleiades.masamune.flow.model.Port
import dev.pleiades.masamune.flow.runtime.impl.BatteryChargingBlock
import dev.pleiades.masamune.flow.runtime.impl.BatteryLevelBlock
import dev.pleiades.masamune.flow.runtime.impl.BatteryPropertiesBlock
import dev.pleiades.masamune.flow.runtime.impl.DeviceIdleModeActiveBlock
import dev.pleiades.masamune.flow.runtime.impl.DeviceInteractiveBlock
import dev.pleiades.masamune.flow.runtime.impl.PowerSaveModeEnabledBlock
import dev.pleiades.masamune.flow.runtime.impl.PowerSourcePluggedBlock
import dev.pleiades.masamune.flow.runtime.impl.powerLookup
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit proof that the Battery&Power device-state blocks branch and bind correctly — run against a
 * [FakePowerState] on the JVM, never a device, which is exactly what the `android.*`-free [PowerState]
 * seam buys (the same seam shape the Apps and Settings blocks use). Each test drives a block the way
 * the runtime does — an args map of resolved [Value]s and a [FlowNode] carrying the output bindings —
 * and asserts on the [Outcome] and its writes. The honest failure shape is the point of the coverage:
 * a read the device cannot answer is a visible [Outcome.Fail], never a fabricated `0`/`false` and
 * never a silent NO. The absent-seam path is checked for all seven blocks.
 */
class PowerStateBlocksTest {

    /**
     * A fully scriptable fake standing in for the real battery/power state. Every field is a nullable
     * reading; the block turns a `null` into a named Fail, exactly as it would on a device that could
     * not answer.
     */
    private class FakePowerState(
        private val percent: Int? = null,
        private val status: BatteryStatus? = null,
        private val plugged: PowerSource? = null,
        private val temperatureTenthsC: Int? = null,
        private val voltageMv: Int? = null,
        private val technology: String? = null,
        private val powerSave: Boolean? = null,
        private val idle: Boolean? = null,
        private val interactive: Boolean? = null,
    ) : PowerState {
        override suspend fun batteryPercent(): Int? = percent
        override suspend fun status(): BatteryStatus? = status
        override suspend fun plugged(): PowerSource? = plugged
        override suspend fun temperatureTenthsC(): Int? = temperatureTenthsC
        override suspend fun voltageMv(): Int? = voltageMv
        override suspend fun technology(): String? = technology
        override suspend fun isPowerSaveMode(): Boolean? = powerSave
        override suspend fun isDeviceIdle(): Boolean? = idle
        override suspend fun isInteractive(): Boolean? = interactive
    }

    private fun node(specId: String, vararg outputs: Pair<String, String>) =
        FlowNode("n", specId, 0f, 0f, outputs = outputs.toMap())

    private fun fiber() = Fiber("f", "flow")

    // ------------------------------------------------------------------ battery_charging

    @Test fun batteryChargingYesWhenCharging() = runTest {
        val seam = FakePowerState(status = BatteryStatus.CHARGING)
        val outcome = BatteryChargingBlock({ seam }).run(fiber(), node("battery_charging"), emptyMap())
        assertEquals(Port.YES, (outcome as Outcome.Proceed).port)
    }

    @Test fun batteryChargingNoWhenDischarging() = runTest {
        val seam = FakePowerState(status = BatteryStatus.DISCHARGING)
        val outcome = BatteryChargingBlock({ seam }).run(fiber(), node("battery_charging"), emptyMap())
        assertEquals(Port.NO, (outcome as Outcome.Proceed).port)
    }

    @Test fun batteryChargingNoWhenFullNotChargingOrUnknown() = runTest {
        for (status in listOf(BatteryStatus.FULL, BatteryStatus.NOT_CHARGING, BatteryStatus.UNKNOWN)) {
            val outcome = BatteryChargingBlock({ FakePowerState(status = status) })
                .run(fiber(), node("battery_charging"), emptyMap())
            assertEquals("$status must route NO", Port.NO, (outcome as Outcome.Proceed).port)
        }
    }

    @Test fun batteryChargingFailsWhenUnreadable() = runTest {
        // Unreadable status → visible Fail, never a silent NO.
        val outcome = BatteryChargingBlock({ FakePowerState(status = null) })
            .run(fiber(), node("battery_charging"), emptyMap())
        assertTrue(outcome is Outcome.Fail)
    }

    // ------------------------------------------------------------------ battery_level

    @Test fun batteryLevelBindsAndYesWithoutBounds() = runTest {
        val seam = FakePowerState(percent = 72)
        val outcome = BatteryLevelBlock({ seam }).run(
            fiber(), node("battery_level", "varLevel" to "l"), emptyMap(),
        )
        val proceed = outcome as Outcome.Proceed
        assertEquals(Port.YES, proceed.port)
        assertEquals(Value.Num(72.0), proceed.writes["l"])
    }

    @Test fun batteryLevelNoWhenBelowMinBoundStillBinds() = runTest {
        // A threshold read via asNumOrNull; below it routes NO but still binds the honest level.
        val seam = FakePowerState(percent = 15)
        val outcome = BatteryLevelBlock({ seam }).run(
            fiber(), node("battery_level", "varLevel" to "l"), mapOf("minLevel" to Value.Num(20.0)),
        )
        val proceed = outcome as Outcome.Proceed
        assertEquals(Port.NO, proceed.port)
        assertEquals(Value.Num(15.0), proceed.writes["l"])
    }

    @Test fun batteryLevelYesWithinBand() = runTest {
        val seam = FakePowerState(percent = 50)
        val outcome = BatteryLevelBlock({ seam }).run(
            fiber(),
            node("battery_level", "varLevel" to "l"),
            mapOf("minLevel" to Value.Num(20.0), "maxLevel" to Value.Num(80.0)),
        )
        assertEquals(Port.YES, (outcome as Outcome.Proceed).port)
    }

    @Test fun batteryLevelFailsWhenUnreadable() = runTest {
        val outcome = BatteryLevelBlock({ FakePowerState(percent = null) })
            .run(fiber(), node("battery_level", "varLevel" to "l"), emptyMap())
        assertTrue(outcome is Outcome.Fail)
    }

    // ------------------------------------------------------------------ battery_properties

    @Test fun batteryPropertiesBindsReadableOutputs() = runTest {
        val seam = FakePowerState(
            percent = 66, voltageMv = 4123, temperatureTenthsC = 305, technology = "Li-ion",
        )
        val outcome = BatteryPropertiesBlock({ seam }).run(
            fiber(),
            node(
                "battery_properties",
                "varRemainingPercent" to "p",
                "varVoltage" to "v",
                "varTemperature" to "t",
                "varTechnology" to "tech",
            ),
            emptyMap(),
        )
        val proceed = outcome as Outcome.Proceed
        assertEquals(Port.YES, proceed.port)
        assertEquals(Value.Num(66.0), proceed.writes["p"])
        assertEquals(Value.Num(4123.0), proceed.writes["v"])
        assertEquals(Value.Num(305.0), proceed.writes["t"])
        assertEquals(Value.Text("Li-ion"), proceed.writes["tech"])
    }

    @Test fun batteryPropertiesBindsOnlyWhatIsReadable() = runTest {
        // A partial battery: only voltage is present. The unreadable properties are left unbound
        // (honest omission), never fabricated to 0/"".
        val seam = FakePowerState(percent = null, voltageMv = 4000, temperatureTenthsC = null, technology = null)
        val outcome = BatteryPropertiesBlock({ seam }).run(
            fiber(),
            node(
                "battery_properties",
                "varRemainingPercent" to "p",
                "varVoltage" to "v",
                "varTemperature" to "t",
            ),
            emptyMap(),
        )
        val proceed = outcome as Outcome.Proceed
        assertEquals(Port.YES, proceed.port)
        assertNull(proceed.writes["p"])
        assertEquals(Value.Num(4000.0), proceed.writes["v"])
        assertNull(proceed.writes["t"])
    }

    @Test fun batteryPropertiesFailsWhenNothingReadable() = runTest {
        // No battery / no sticky intent → nothing readable → Fail, never a YES over an empty frame.
        val outcome = BatteryPropertiesBlock({ FakePowerState() })
            .run(fiber(), node("battery_properties", "varVoltage" to "v"), emptyMap())
        assertTrue(outcome is Outcome.Fail)
    }

    // ------------------------------------------------------------------ power_source_plugged

    @Test fun powerSourcePluggedNoAndBindsWhenUnplugged() = runTest {
        val seam = FakePowerState(plugged = PowerSource.UNPLUGGED)
        val outcome = PowerSourcePluggedBlock({ seam }).run(
            fiber(), node("power_source_plugged", "varCurrentSource" to "s"), emptyMap(),
        )
        val proceed = outcome as Outcome.Proceed
        assertEquals(Port.NO, proceed.port)
        assertEquals(Value.Text("unplugged"), proceed.writes["s"])
    }

    @Test fun powerSourcePluggedYesForAnySourceWithoutFilter() = runTest {
        val seam = FakePowerState(plugged = PowerSource.USB)
        val outcome = PowerSourcePluggedBlock({ seam }).run(
            fiber(), node("power_source_plugged", "varCurrentSource" to "s"), emptyMap(),
        )
        val proceed = outcome as Outcome.Proceed
        assertEquals(Port.YES, proceed.port)
        assertEquals(Value.Text("usb"), proceed.writes["s"])
    }

    @Test fun powerSourcePluggedAnyLiteralIsNoFilter() = runTest {
        // The catalog default blurb is "any"; a literal "any" is treated as no filter, not a token.
        val seam = FakePowerState(plugged = PowerSource.AC)
        val outcome = PowerSourcePluggedBlock({ seam }).run(
            fiber(),
            node("power_source_plugged", "varCurrentSource" to "s"),
            mapOf("sources" to Value.Text("any")),
        )
        assertEquals(Port.YES, (outcome as Outcome.Proceed).port)
    }

    @Test fun powerSourcePluggedFilterYesWhenMatchesNoWhenNot() = runTest {
        val seam = FakePowerState(plugged = PowerSource.AC)
        val yes = PowerSourcePluggedBlock({ seam }).run(
            fiber(),
            node("power_source_plugged", "varCurrentSource" to "s"),
            mapOf("sources" to Value.Text("ac, wireless")),
        )
        assertEquals(Port.YES, (yes as Outcome.Proceed).port)
        val no = PowerSourcePluggedBlock({ seam }).run(
            fiber(),
            node("power_source_plugged", "varCurrentSource" to "s"),
            mapOf("sources" to Value.ArrayV(listOf(Value.Text("usb"), Value.Text("wireless")))),
        )
        assertEquals(Port.NO, (no as Outcome.Proceed).port)
    }

    @Test fun powerSourcePluggedFailsOnUnrecognizedFilter() = runTest {
        // A mis-typed filter that names nothing recognizable Fails visibly, never silently NO.
        val seam = FakePowerState(plugged = PowerSource.AC)
        val outcome = PowerSourcePluggedBlock({ seam }).run(
            fiber(),
            node("power_source_plugged", "varCurrentSource" to "s"),
            mapOf("sources" to Value.Text("solar")),
        )
        assertTrue(outcome is Outcome.Fail)
    }

    @Test fun powerSourcePluggedFailsWhenUnreadable() = runTest {
        val outcome = PowerSourcePluggedBlock({ FakePowerState(plugged = null) })
            .run(fiber(), node("power_source_plugged", "varCurrentSource" to "s"), emptyMap())
        assertTrue(outcome is Outcome.Fail)
    }

    // ------------------------------------------------------------------ power_save_mode_enabled

    @Test fun powerSaveModeYesWhenOnNoWhenOff() = runTest {
        assertEquals(
            Port.YES,
            (PowerSaveModeEnabledBlock({ FakePowerState(powerSave = true) })
                .run(fiber(), node("power_save_mode_enabled"), emptyMap()) as Outcome.Proceed).port,
        )
        assertEquals(
            Port.NO,
            (PowerSaveModeEnabledBlock({ FakePowerState(powerSave = false) })
                .run(fiber(), node("power_save_mode_enabled"), emptyMap()) as Outcome.Proceed).port,
        )
    }

    @Test fun powerSaveModeFailsWhenUnreadable() = runTest {
        val outcome = PowerSaveModeEnabledBlock({ FakePowerState(powerSave = null) })
            .run(fiber(), node("power_save_mode_enabled"), emptyMap())
        assertTrue(outcome is Outcome.Fail)
    }

    // ------------------------------------------------------------------ device_idle_mode_active

    @Test fun deviceIdleYesWhenDozingNoWhenNot() = runTest {
        assertEquals(
            Port.YES,
            (DeviceIdleModeActiveBlock({ FakePowerState(idle = true) })
                .run(fiber(), node("device_idle_mode_active"), emptyMap()) as Outcome.Proceed).port,
        )
        assertEquals(
            Port.NO,
            (DeviceIdleModeActiveBlock({ FakePowerState(idle = false) })
                .run(fiber(), node("device_idle_mode_active"), emptyMap()) as Outcome.Proceed).port,
        )
    }

    @Test fun deviceIdleFailsWhenUnreadable() = runTest {
        val outcome = DeviceIdleModeActiveBlock({ FakePowerState(idle = null) })
            .run(fiber(), node("device_idle_mode_active"), emptyMap())
        assertTrue(outcome is Outcome.Fail)
    }

    // ------------------------------------------------------------------ device_interactive

    @Test fun deviceInteractiveYesWhenOnNoWhenOff() = runTest {
        assertEquals(
            Port.YES,
            (DeviceInteractiveBlock({ FakePowerState(interactive = true) })
                .run(fiber(), node("device_interactive"), emptyMap()) as Outcome.Proceed).port,
        )
        assertEquals(
            Port.NO,
            (DeviceInteractiveBlock({ FakePowerState(interactive = false) })
                .run(fiber(), node("device_interactive"), emptyMap()) as Outcome.Proceed).port,
        )
    }

    @Test fun deviceInteractiveFailsWhenUnreadable() = runTest {
        val outcome = DeviceInteractiveBlock({ FakePowerState(interactive = null) })
            .run(fiber(), node("device_interactive"), emptyMap())
        assertTrue(outcome is Outcome.Fail)
    }

    // ------------------------------------------------------------------ absent seam (all seven)

    @Test fun allBlocksFailByNameWhenSeamAbsent() = runTest {
        val absent: () -> PowerState? = { null }
        val blocks = listOf(
            BatteryChargingBlock(absent) to node("battery_charging"),
            BatteryLevelBlock(absent) to node("battery_level"),
            BatteryPropertiesBlock(absent) to node("battery_properties"),
            PowerSourcePluggedBlock(absent) to node("power_source_plugged"),
            PowerSaveModeEnabledBlock(absent) to node("power_save_mode_enabled"),
            DeviceIdleModeActiveBlock(absent) to node("device_idle_mode_active"),
            DeviceInteractiveBlock(absent) to node("device_interactive"),
        )
        for ((block, flowNode) in blocks) {
            val outcome = block.run(
                fiber(),
                flowNode,
                mapOf("minLevel" to Value.Num(1.0), "sources" to Value.Text("ac")),
            )
            assertTrue("${block.specId} must Fail when the seam is absent", outcome is Outcome.Fail)
            assertTrue((outcome as Outcome.Fail).message.contains("power-state seam"))
        }
    }

    // ------------------------------------------------------------------ composition helper

    @Test fun powerLookupExposesTheFiveRegisteredBlocksBySpecId() {
        val lookup = powerLookup { null }
        assertEquals(
            // device_idle_mode_active and device_interactive are built but NOT registered — the
            // catalog tags both requires=SHELL, so the unprivileged seam does not claim them.
            setOf(
                "battery_charging",
                "battery_level",
                "battery_properties",
                "power_source_plugged",
                "power_save_mode_enabled",
            ),
            lookup.keys,
        )
        assertNull(lookup["device_idle_mode_active"])
        assertNull(lookup["device_interactive"])
        // Gated by omission — privileged/shell writes and hidden-API reads are not registered.
        assertNull(lookup["power_save_mode_set_state"])
        assertNull(lookup["device_reboot"])
        assertNull(lookup["cpu_speed_get"])
        assertNull(lookup["display_power_mode"])
        assertNull(lookup["device_keep_awake"])
        // Mirrors the layers below: composes over a base registry via `powerLookup(...)[id] ?: base`.
        assertNull(lookup["ringer_mode"])
        assertNull(lookup["app_installed"])
        assertEquals("battery_level", lookup["battery_level"]!!.specId)
    }
}

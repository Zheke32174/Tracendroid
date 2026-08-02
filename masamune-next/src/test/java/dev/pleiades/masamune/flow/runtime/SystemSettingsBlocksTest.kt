package dev.pleiades.masamune.flow.runtime

import dev.pleiades.masamune.apps.RingerMode
import dev.pleiades.masamune.apps.SettingNamespace
import dev.pleiades.masamune.apps.SettingWrite
import dev.pleiades.masamune.apps.SystemSettings
import dev.pleiades.masamune.flow.expr.Value
import dev.pleiades.masamune.flow.model.FlowNode
import dev.pleiades.masamune.flow.model.Port
import dev.pleiades.masamune.flow.runtime.impl.RingerModeBlock
import dev.pleiades.masamune.flow.runtime.impl.RingerModeSetBlock
import dev.pleiades.masamune.flow.runtime.impl.ScreenBrightnessBlock
import dev.pleiades.masamune.flow.runtime.impl.ScreenBrightnessSetBlock
import dev.pleiades.masamune.flow.runtime.impl.ScreenOffTimeoutBlock
import dev.pleiades.masamune.flow.runtime.impl.ScreenOffTimeoutSetBlock
import dev.pleiades.masamune.flow.runtime.impl.SystemLanguageGetBlock
import dev.pleiades.masamune.flow.runtime.impl.SystemPropertyGetBlock
import dev.pleiades.masamune.flow.runtime.impl.SystemSettingGetBlock
import dev.pleiades.masamune.flow.runtime.impl.SystemSettingSetBlock
import dev.pleiades.masamune.flow.runtime.impl.settingsLookup
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit proof that the Settings read/write blocks branch and bind correctly — run against a
 * [FakeSystemSettings] on the JVM, never a device, which is exactly what the `android.*`-free
 * [SystemSettings] seam buys (the same seam shape the Apps blocks use). Each test drives a block the
 * way the runtime does — an args map of resolved [Value]s and a [FlowNode] carrying the output
 * bindings — and asserts on the [Outcome] and its writes. The two honest failure shapes are the point
 * of the coverage: a `get` that found nothing is a visible [Outcome.Fail], and a write the device
 * refused ([SettingWrite] `ok = false`) is a visible [Outcome.Fail] carrying the reason — never a
 * silent no-op, never a fabricated success. The absent-seam path is checked for all ten blocks.
 */
class SystemSettingsBlocksTest {

    /**
     * A fully scriptable fake standing in for the real settings store. Reads come from its maps and
     * fields; writes either mutate them (`writeAllowed`) or return the denial the real impl would.
     */
    private class FakeSystemSettings(
        private val settings: MutableMap<String, String> = mutableMapOf(),
        private val writeAllowed: Boolean = true,
        private val writeReason: String? = null,
        var brightness: Int? = null,
        var timeoutMs: Long? = null,
        var ringer: RingerMode? = null,
        private val properties: Map<String, String> = emptyMap(),
        private val language: String? = null,
    ) : SystemSettings {
        private fun key(ns: SettingNamespace, name: String) = "${ns.name}:$name"
        private fun denied() = SettingWrite(ok = false, reason = writeReason)

        override suspend fun getSetting(namespace: SettingNamespace, name: String): String? =
            settings[key(namespace, name)]

        override suspend fun putSetting(namespace: SettingNamespace, name: String, value: String): SettingWrite {
            if (!writeAllowed) return denied()
            settings[key(namespace, name)] = value
            return SettingWrite(ok = true)
        }

        override suspend fun screenBrightness(): Int? = brightness

        override suspend fun setScreenBrightness(value: Int): SettingWrite {
            if (!writeAllowed) return denied()
            brightness = value
            return SettingWrite(ok = true)
        }

        override suspend fun screenOffTimeoutMs(): Long? = timeoutMs

        override suspend fun setScreenOffTimeoutMs(ms: Long): SettingWrite {
            if (!writeAllowed) return denied()
            timeoutMs = ms
            return SettingWrite(ok = true)
        }

        override suspend fun ringerMode(): RingerMode? = ringer

        override suspend fun setRingerMode(mode: RingerMode): SettingWrite {
            if (!writeAllowed) return denied()
            ringer = mode
            return SettingWrite(ok = true)
        }

        override suspend fun systemProperty(key: String): String? = properties[key]

        override suspend fun systemLanguage(): String? = language
    }

    private fun node(specId: String, vararg outputs: Pair<String, String>) =
        FlowNode("n", specId, 0f, 0f, outputs = outputs.toMap())

    private fun fiber() = Fiber("f", "flow")

    // ------------------------------------------------------------------ system_setting_get

    @Test fun systemSettingGetBindsValueWhenPresent() = runTest {
        val seam = FakeSystemSettings(settings = mutableMapOf("SYSTEM:accelerometer_rotation" to "1"))
        val outcome = SystemSettingGetBlock({ seam }).run(
            fiber(),
            node("system_setting_get", "varValue" to "v"),
            mapOf("category" to Value.Text("System"), "name" to Value.Text("accelerometer_rotation")),
        )
        assertTrue(outcome is Outcome.Proceed)
        val proceed = outcome as Outcome.Proceed
        assertEquals(Port.OK, proceed.port)
        assertEquals(Value.Text("1"), proceed.writes["v"])
    }

    @Test fun systemSettingGetReadsTheNamedNamespace() = runTest {
        // The category arg picks a real table: Secure here, distinct from a same-named System key.
        val seam = FakeSystemSettings(settings = mutableMapOf("SECURE:location_mode" to "3"))
        val outcome = SystemSettingGetBlock({ seam }).run(
            fiber(),
            node("system_setting_get", "varValue" to "v"),
            mapOf("category" to Value.Text("Secure"), "name" to Value.Text("location_mode")),
        )
        assertEquals(Value.Text("3"), (outcome as Outcome.Proceed).writes["v"])
    }

    @Test fun systemSettingGetFailsWhenMissing() = runTest {
        // Get found nothing → Fail by name, never a bound empty string a downstream block would trust.
        val seam = FakeSystemSettings()
        val outcome = SystemSettingGetBlock({ seam }).run(
            fiber(),
            node("system_setting_get", "varValue" to "v"),
            mapOf("category" to Value.Text("System"), "name" to Value.Text("no_such_key")),
        )
        assertTrue(outcome is Outcome.Fail)
        assertTrue((outcome as Outcome.Fail).message.contains("no_such_key"))
    }

    // ------------------------------------------------------------------ system_setting_set

    @Test fun systemSettingSetOkWhenGranted() = runTest {
        val seam = FakeSystemSettings(writeAllowed = true)
        val outcome = SystemSettingSetBlock({ seam }).run(
            fiber(),
            node("system_setting_set"),
            mapOf(
                "category" to Value.Text("System"),
                "name" to Value.Text("screen_brightness"),
                "value" to Value.Text("120"),
            ),
        )
        assertTrue(outcome is Outcome.Proceed)
        assertEquals(Port.OK, (outcome as Outcome.Proceed).port)
        // The write actually landed in the fake store, not a fabricated OK over a no-op.
        assertEquals("120", seam.getSetting(SettingNamespace.SYSTEM, "screen_brightness"))
    }

    @Test fun systemSettingSetFailsWithReasonWhenDenied() = runTest {
        // ok=false is how the real impl reports missing WRITE_SETTINGS without throwing — Fail by name.
        val seam = FakeSystemSettings(writeAllowed = false, writeReason = "WRITE_SETTINGS not granted")
        val outcome = SystemSettingSetBlock({ seam }).run(
            fiber(),
            node("system_setting_set"),
            mapOf("name" to Value.Text("screen_brightness"), "value" to Value.Text("120")),
        )
        assertTrue(outcome is Outcome.Fail)
        assertTrue((outcome as Outcome.Fail).message.contains("WRITE_SETTINGS not granted"))
    }

    @Test fun systemSettingSetFailsWithoutValue() = runTest {
        val seam = FakeSystemSettings()
        val outcome = SystemSettingSetBlock({ seam }).run(
            fiber(), node("system_setting_set"), mapOf("name" to Value.Text("x")),
        )
        assertTrue(outcome is Outcome.Fail)
    }

    // ------------------------------------------------------------------ screen_brightness (read)

    @Test fun screenBrightnessBindsLevelAndYesWithoutBounds() = runTest {
        val seam = FakeSystemSettings(brightness = 120)
        val outcome = ScreenBrightnessBlock({ seam }).run(
            fiber(), node("screen_brightness", "varLevel" to "l"), emptyMap(),
        )
        val proceed = outcome as Outcome.Proceed
        assertEquals(Port.YES, proceed.port)
        assertEquals(Value.Num(120.0), proceed.writes["l"])
    }

    @Test fun screenBrightnessNoWhenBelowMinBound() = runTest {
        val seam = FakeSystemSettings(brightness = 120)
        val outcome = ScreenBrightnessBlock({ seam }).run(
            fiber(),
            node("screen_brightness", "varLevel" to "l"),
            mapOf("minLevel" to Value.Num(200.0)),
        )
        val proceed = outcome as Outcome.Proceed
        assertEquals(Port.NO, proceed.port)
        // Still binds the honest current level even on the NO branch.
        assertEquals(Value.Num(120.0), proceed.writes["l"])
    }

    @Test fun screenBrightnessFailsWhenUnreadable() = runTest {
        val seam = FakeSystemSettings(brightness = null)
        val outcome = ScreenBrightnessBlock({ seam }).run(
            fiber(), node("screen_brightness", "varLevel" to "l"), emptyMap(),
        )
        assertTrue(outcome is Outcome.Fail)
    }

    // ------------------------------------------------------------------ screen_brightness_set

    @Test fun screenBrightnessSetOkAndApplies() = runTest {
        val seam = FakeSystemSettings(brightness = 10)
        val outcome = ScreenBrightnessSetBlock({ seam }).run(
            fiber(), node("screen_brightness_set"), mapOf("level" to Value.Num(200.0)),
        )
        assertEquals(Port.OK, (outcome as Outcome.Proceed).port)
        assertEquals(200, seam.brightness)
    }

    @Test fun screenBrightnessSetFailsWhenDenied() = runTest {
        val seam = FakeSystemSettings(writeAllowed = false, writeReason = "WRITE_SETTINGS not granted")
        val outcome = ScreenBrightnessSetBlock({ seam }).run(
            fiber(), node("screen_brightness_set"), mapOf("level" to Value.Num(200.0)),
        )
        assertTrue(outcome is Outcome.Fail)
        assertTrue((outcome as Outcome.Fail).message.contains("WRITE_SETTINGS not granted"))
    }

    // ------------------------------------------------------------------ screen_off_timeout

    @Test fun screenOffTimeoutBindsAndYesWithoutBounds() = runTest {
        val seam = FakeSystemSettings(timeoutMs = 30_000L)
        val outcome = ScreenOffTimeoutBlock({ seam }).run(
            fiber(), node("screen_off_timeout", "varLevel" to "l"), emptyMap(),
        )
        val proceed = outcome as Outcome.Proceed
        assertEquals(Port.YES, proceed.port)
        assertEquals(Value.Num(30_000.0), proceed.writes["l"])
    }

    @Test fun screenOffTimeoutSetOkAndApplies() = runTest {
        val seam = FakeSystemSettings(timeoutMs = 15_000L)
        val outcome = ScreenOffTimeoutSetBlock({ seam }).run(
            fiber(), node("screen_off_timeout_set"), mapOf("level" to Value.Num(60_000.0)),
        )
        assertEquals(Port.OK, (outcome as Outcome.Proceed).port)
        assertEquals(60_000L, seam.timeoutMs)
    }

    // ------------------------------------------------------------------ ringer_mode (read)

    @Test fun ringerModeReadsAndBindsCurrentMode() = runTest {
        val seam = FakeSystemSettings(ringer = RingerMode.VIBRATE)
        val outcome = RingerModeBlock({ seam }).run(
            fiber(), node("ringer_mode", "varCurrentMode" to "m"), emptyMap(),
        )
        val proceed = outcome as Outcome.Proceed
        assertEquals(Port.YES, proceed.port) // bare read, no target: YES
        assertEquals(Value.Text("vibrate"), proceed.writes["m"])
    }

    @Test fun ringerModeYesWhenStateMatchesNoWhenNot() = runTest {
        val seam = FakeSystemSettings(ringer = RingerMode.SILENT)
        val yes = RingerModeBlock({ seam }).run(
            fiber(), node("ringer_mode", "varCurrentMode" to "m"), mapOf("state" to Value.Text("silent")),
        )
        assertEquals(Port.YES, (yes as Outcome.Proceed).port)
        val no = RingerModeBlock({ seam }).run(
            fiber(), node("ringer_mode", "varCurrentMode" to "m"), mapOf("state" to Value.Text("normal")),
        )
        assertEquals(Port.NO, (no as Outcome.Proceed).port)
        // Even on NO the honest current mode is bound.
        assertEquals(Value.Text("silent"), no.writes["m"])
    }

    @Test fun ringerModeFailsOnUnknownStateString() = runTest {
        val seam = FakeSystemSettings(ringer = RingerMode.NORMAL)
        val outcome = RingerModeBlock({ seam }).run(
            fiber(), node("ringer_mode", "varCurrentMode" to "m"), mapOf("state" to Value.Text("loud")),
        )
        assertTrue(outcome is Outcome.Fail)
        assertTrue((outcome as Outcome.Fail).message.contains("loud"))
    }

    @Test fun ringerModeFailsWhenUnreadable() = runTest {
        val seam = FakeSystemSettings(ringer = null)
        val outcome = RingerModeBlock({ seam }).run(
            fiber(), node("ringer_mode", "varCurrentMode" to "m"), emptyMap(),
        )
        assertTrue(outcome is Outcome.Fail)
    }

    // ------------------------------------------------------------------ ringer_mode_set

    @Test fun ringerModeSetRoundTrips() = runTest {
        val seam = FakeSystemSettings(ringer = RingerMode.NORMAL)
        val outcome = RingerModeSetBlock({ seam }).run(
            fiber(), node("ringer_mode_set"), mapOf("state" to Value.Text("silent")),
        )
        assertEquals(Port.OK, (outcome as Outcome.Proceed).port)
        assertEquals(RingerMode.SILENT, seam.ringer)
    }

    @Test fun ringerModeSetFailsOnUnknownState() = runTest {
        val seam = FakeSystemSettings(ringer = RingerMode.NORMAL)
        val outcome = RingerModeSetBlock({ seam }).run(
            fiber(), node("ringer_mode_set"), mapOf("state" to Value.Text("loud")),
        )
        assertTrue(outcome is Outcome.Fail)
        assertTrue((outcome as Outcome.Fail).message.contains("loud"))
    }

    @Test fun ringerModeSetFailsWithReasonWhenRefused() = runTest {
        val seam = FakeSystemSettings(writeAllowed = false, writeReason = "not permitted to set ringer mode (DND policy?)")
        val outcome = RingerModeSetBlock({ seam }).run(
            fiber(), node("ringer_mode_set"), mapOf("state" to Value.Text("silent")),
        )
        assertTrue(outcome is Outcome.Fail)
        assertTrue((outcome as Outcome.Fail).message.contains("DND policy"))
    }

    // ------------------------------------------------------------------ system_property_get

    @Test fun systemPropertyGetBindsValue() = runTest {
        val seam = FakeSystemSettings(properties = mapOf("ro.build.version.sdk" to "34"))
        val outcome = SystemPropertyGetBlock({ seam }).run(
            fiber(),
            node("system_property_get", "varValue" to "v"),
            mapOf("name" to Value.Text("ro.build.version.sdk")),
        )
        val proceed = outcome as Outcome.Proceed
        assertEquals(Port.OK, proceed.port)
        assertEquals(Value.Text("34"), proceed.writes["v"])
    }

    @Test fun systemPropertyGetFailsWhenUnset() = runTest {
        val seam = FakeSystemSettings()
        val outcome = SystemPropertyGetBlock({ seam }).run(
            fiber(), node("system_property_get", "varValue" to "v"),
            mapOf("name" to Value.Text("ro.absent.prop")),
        )
        assertTrue(outcome is Outcome.Fail)
    }

    // ------------------------------------------------------------------ system_language_get

    @Test fun systemLanguageGetBindsLanguage() = runTest {
        val seam = FakeSystemSettings(language = "en-US")
        val outcome = SystemLanguageGetBlock({ seam }).run(
            fiber(), node("system_language_get", "varLanguage" to "lang"), emptyMap(),
        )
        val proceed = outcome as Outcome.Proceed
        assertEquals(Port.OK, proceed.port)
        assertEquals(Value.Text("en-US"), proceed.writes["lang"])
    }

    // ------------------------------------------------------------------ absent seam (all ten)

    @Test fun allBlocksFailByNameWhenSeamAbsent() = runTest {
        val absent: () -> SystemSettings? = { null }
        val blocks = listOf(
            SystemSettingGetBlock(absent) to node("system_setting_get"),
            SystemSettingSetBlock(absent) to node("system_setting_set"),
            ScreenBrightnessBlock(absent) to node("screen_brightness"),
            ScreenBrightnessSetBlock(absent) to node("screen_brightness_set"),
            ScreenOffTimeoutBlock(absent) to node("screen_off_timeout"),
            ScreenOffTimeoutSetBlock(absent) to node("screen_off_timeout_set"),
            RingerModeBlock(absent) to node("ringer_mode"),
            RingerModeSetBlock(absent) to node("ringer_mode_set"),
            SystemPropertyGetBlock(absent) to node("system_property_get"),
            SystemLanguageGetBlock(absent) to node("system_language_get"),
        )
        for ((block, flowNode) in blocks) {
            val outcome = block.run(
                fiber(),
                flowNode,
                mapOf(
                    "name" to Value.Text("x"), "value" to Value.Text("y"),
                    "state" to Value.Text("silent"), "level" to Value.Num(1.0),
                ),
            )
            assertTrue("${block.specId} must Fail when the seam is absent", outcome is Outcome.Fail)
            assertTrue((outcome as Outcome.Fail).message.contains("settings seam"))
        }
    }

    // ------------------------------------------------------------------ composition helper

    @Test fun settingsLookupExposesTheNineRegisteredBlocksBySpecId() {
        val lookup = settingsLookup { null }
        assertEquals(
            // system_property_get is built but NOT registered (catalog tags it SHELL); see its KDoc.
            setOf(
                "system_setting_get", "system_setting_set",
                "screen_brightness", "screen_brightness_set",
                "screen_off_timeout", "screen_off_timeout_set",
                "ringer_mode", "ringer_mode_set",
                "system_language_get",
            ),
            lookup.keys,
        )
        assertNull(lookup["system_property_get"])
        // Mirrors the Apps layer: composes over a base registry via `settingsLookup(...)[id] ?: base`.
        assertNull(lookup["app_installed"])
        assertNull(lookup["file_read"])
        assertEquals("ringer_mode", lookup["ringer_mode"]!!.specId)
    }
}

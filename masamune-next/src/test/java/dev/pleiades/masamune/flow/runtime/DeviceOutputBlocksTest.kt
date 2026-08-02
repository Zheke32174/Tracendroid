package dev.pleiades.masamune.flow.runtime

import dev.pleiades.masamune.apps.DeviceOutput
import dev.pleiades.masamune.apps.OutputWrite
import dev.pleiades.masamune.flow.expr.Value
import dev.pleiades.masamune.flow.model.FlowNode
import dev.pleiades.masamune.flow.model.Port
import dev.pleiades.masamune.flow.runtime.impl.deviceOutputLookup
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit proof that the CameraAndSound device-output effect blocks parse their args and apply effects
 * correctly — run against a [FakeDeviceOutput] on the JVM, never a device, which is exactly what the
 * `android.*`-free [DeviceOutput] seam buys (the same seam shape the Interface device-UI, Apps, Settings,
 * Battery&Power, Sensor, Location, Connectivity, Telephony, CameraAndSound-audio and Content blocks use).
 * Each test drives a block the way the runtime does — an args map of resolved [Value]s and a [FlowNode] —
 * and asserts on the [Outcome] and the recorded seam call. The honest failure shape is the point of the
 * coverage: a refused effect ([OutputWrite] `ok = false`) is a visible [Outcome.Fail] carrying the reason,
 * never a fabricated OK; a required arg missing is a Fail with NO seam call; and the absent-seam path is a
 * Fail by name for all four blocks.
 */
class DeviceOutputBlocksTest {

    /**
     * A fully scriptable fake standing in for the real device-output stack. Effects return a scripted
     * [OutputWrite] and record their call so a test can assert both the [Outcome] and that the seam was
     * asked to apply the change (never a fabricated OK).
     */
    private class FakeDeviceOutput(
        private val vibrateResult: OutputWrite = OutputWrite(ok = true),
        private val cancelVibrationResult: OutputWrite = OutputWrite(ok = true),
        private val speakResult: OutputWrite = OutputWrite(ok = true),
        private val stopSpeakingResult: OutputWrite = OutputWrite(ok = true),
    ) : DeviceOutput {
        val vibrates = mutableListOf<Pair<List<Long>, Boolean>>()
        val cancelVibrations = mutableListOf<Unit>()
        val speaks = mutableListOf<String>()
        val stopSpeaks = mutableListOf<Unit>()

        override suspend fun vibrate(pattern: List<Long>, repeat: Boolean): OutputWrite {
            vibrates += pattern to repeat
            return vibrateResult
        }

        override suspend fun cancelVibration(): OutputWrite {
            cancelVibrations += Unit
            return cancelVibrationResult
        }

        override suspend fun speak(message: String): OutputWrite {
            speaks += message
            return speakResult
        }

        override suspend fun stopSpeaking(): OutputWrite {
            stopSpeaks += Unit
            return stopSpeakingResult
        }
    }

    private fun node(specId: String) = FlowNode("n", specId, 0f, 0f)

    private fun fiber() = Fiber("f", "flow")

    /** Fetch a single registered impl from the lookup composed over [seam]. */
    private fun block(specId: String, seam: DeviceOutput?): BlockImpl =
        deviceOutputLookup { seam }[specId] ?: error("no registered block for $specId")

    // ------------------------------------------------------------------ vibrate_start (effect)

    @Test fun vibrateStartAppliesAndOk() = runTest {
        val seam = FakeDeviceOutput()
        val outcome = block("vibrate_start", seam).run(
            fiber(), node("vibrate_start"),
            mapOf(
                "pattern" to Value.ArrayV(listOf(Value.Num(0.0), Value.Num(200.0), Value.Num(100.0))),
                "repeat" to Value.Text("true"),
            ),
        )
        assertEquals(Port.OK, (outcome as Outcome.Proceed).port)
        assertEquals(listOf(listOf(0L, 200L, 100L) to true), seam.vibrates)
    }

    @Test fun vibrateStartDefaultsRepeatFalse() = runTest {
        val seam = FakeDeviceOutput()
        block("vibrate_start", seam).run(
            fiber(), node("vibrate_start"),
            mapOf("pattern" to Value.ArrayV(listOf(Value.Num(500.0)))),
        )
        assertEquals(listOf(listOf(500L) to false), seam.vibrates)
    }

    @Test fun vibrateStartFailsWhenRefused() = runTest {
        val seam = FakeDeviceOutput(vibrateResult = OutputWrite(ok = false, reason = "no vibrator hardware"))
        val outcome = block("vibrate_start", seam).run(
            fiber(), node("vibrate_start"),
            mapOf("pattern" to Value.ArrayV(listOf(Value.Num(200.0)))),
        )
        assertTrue(outcome is Outcome.Fail)
        assertTrue((outcome as Outcome.Fail).message.contains("no vibrator hardware"))
        assertEquals(listOf(listOf(200L) to false), seam.vibrates)
    }

    @Test fun vibrateStartFailsWithoutPattern() = runTest {
        val seam = FakeDeviceOutput()
        val outcome = block("vibrate_start", seam).run(
            fiber(), node("vibrate_start"), mapOf("repeat" to Value.Text("true")),
        )
        assertTrue(outcome is Outcome.Fail)
        assertTrue("no fabricated vibration is issued", seam.vibrates.isEmpty())
    }

    // ------------------------------------------------------------------ vibrate_stop (effect)

    @Test fun vibrateStopAppliesAndOk() = runTest {
        val seam = FakeDeviceOutput()
        val outcome = block("vibrate_stop", seam).run(fiber(), node("vibrate_stop"), emptyMap())
        assertEquals(Port.OK, (outcome as Outcome.Proceed).port)
        assertEquals(1, seam.cancelVibrations.size)
    }

    @Test fun vibrateStopFailsWhenRefused() = runTest {
        val seam = FakeDeviceOutput(
            cancelVibrationResult = OutputWrite(ok = false, reason = "no vibrator hardware"),
        )
        val outcome = block("vibrate_stop", seam).run(fiber(), node("vibrate_stop"), emptyMap())
        assertTrue(outcome is Outcome.Fail)
        assertTrue((outcome as Outcome.Fail).message.contains("no vibrator hardware"))
        assertEquals(1, seam.cancelVibrations.size)
    }

    // ------------------------------------------------------------------ speak_play (effect)

    @Test fun speakPlayAppliesAndOk() = runTest {
        val seam = FakeDeviceOutput()
        val outcome = block("speak_play", seam).run(
            fiber(), node("speak_play"), mapOf("message" to Value.Text("hello there")),
        )
        assertEquals(Port.OK, (outcome as Outcome.Proceed).port)
        assertEquals(listOf("hello there"), seam.speaks)
    }

    @Test fun speakPlayFailsWhenRefused() = runTest {
        val seam = FakeDeviceOutput(
            speakResult = OutputWrite(ok = false, reason = "text-to-speech engine is not ready"),
        )
        val outcome = block("speak_play", seam).run(
            fiber(), node("speak_play"), mapOf("message" to Value.Text("hi")),
        )
        assertTrue(outcome is Outcome.Fail)
        assertTrue((outcome as Outcome.Fail).message.contains("engine is not ready"))
        assertEquals(listOf("hi"), seam.speaks)
    }

    @Test fun speakPlayFailsWithoutMessage() = runTest {
        val seam = FakeDeviceOutput()
        val outcome = block("speak_play", seam).run(
            fiber(), node("speak_play"), emptyMap(),
        )
        assertTrue(outcome is Outcome.Fail)
        assertTrue("no fabricated speech is issued", seam.speaks.isEmpty())
    }

    // ------------------------------------------------------------------ speak_stop (effect)

    @Test fun speakStopAppliesAndOk() = runTest {
        val seam = FakeDeviceOutput()
        val outcome = block("speak_stop", seam).run(fiber(), node("speak_stop"), emptyMap())
        assertEquals(Port.OK, (outcome as Outcome.Proceed).port)
        assertEquals(1, seam.stopSpeaks.size)
    }

    @Test fun speakStopFailsWhenRefused() = runTest {
        val seam = FakeDeviceOutput(
            stopSpeakingResult = OutputWrite(ok = false, reason = "no text-to-speech engine to stop"),
        )
        val outcome = block("speak_stop", seam).run(fiber(), node("speak_stop"), emptyMap())
        assertTrue(outcome is Outcome.Fail)
        assertTrue((outcome as Outcome.Fail).message.contains("no text-to-speech engine"))
        assertEquals(1, seam.stopSpeaks.size)
    }

    // ------------------------------------------------------------------ absent seam (all four)

    @Test fun allBlocksFailByNameWhenSeamAbsent() = runTest {
        val lookup = deviceOutputLookup { null }
        for ((id, impl) in lookup) {
            val outcome = impl.run(fiber(), node(id), emptyMap())
            assertTrue("$id must Fail when the seam is absent", outcome is Outcome.Fail)
            assertTrue((outcome as Outcome.Fail).message.contains("device-output seam"))
        }
    }

    // ------------------------------------------------------------------ composition helper

    @Test fun deviceOutputLookupExposesExactlyTheFourRegisteredBlocks() {
        val lookup = deviceOutputLookup { null }
        assertEquals(
            setOf("vibrate_start", "vibrate_stop", "speak_play", "speak_stop"),
            lookup.keys,
        )
        // speak_to_file writes an audio FILE — a capture/write, gated by omission (not here).
        assertNull(lookup["speak_to_file"])
        // Capture / recording / media / file-writing CameraAndSound blocks — gated by omission.
        assertNull(lookup["capture_image"])
        assertNull(lookup["take_picture"])
        assertNull(lookup["audio_record_start"])
        assertNull(lookup["video_record_start"])
        assertNull(lookup["sound_play"])
        assertNull(lookup["tone_play"])
        assertNull(lookup["speech_recognition"])
        assertNull(lookup["screenshot"])
        assertNull(lookup["image_write"])
        // Audio state-read + volume/mode effects are served by audioLookup, never here.
        assertNull(lookup["audio_volume"])
        assertNull(lookup["audio_stream_set_mute"])
        assertNull(lookup["speakerphone_set_state"])
        // Composes over the layers below via `deviceOutputLookup(...)[id] ?: base`.
        assertNull(lookup["clipboard_get"]) // Interface device-UI
        assertNull(lookup["roaming"]) // Telephony
        assertNull(lookup["battery_level"]) // Battery & power
        assertEquals("vibrate_start", lookup["vibrate_start"]!!.specId)
    }
}

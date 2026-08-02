package dev.pleiades.masamune.flow.runtime

import dev.pleiades.masamune.apps.AudioController
import dev.pleiades.masamune.apps.AudioStream
import dev.pleiades.masamune.apps.AudioWrite
import dev.pleiades.masamune.flow.expr.Value
import dev.pleiades.masamune.flow.model.FlowNode
import dev.pleiades.masamune.flow.model.Port
import dev.pleiades.masamune.flow.runtime.impl.audioLookup
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit proof that the CameraAndSound audio state/volume blocks branch, bind and apply effects correctly —
 * run against a [FakeAudioController] on the JVM, never a device, which is exactly what the `android.*`-free
 * [AudioController] seam buys (the same seam shape the Apps, Settings, Battery&Power, Sensor, Location,
 * Connectivity and Telephony blocks use). Each test drives a block the way the runtime does — an args map of
 * resolved [Value]s and a [FlowNode] carrying the output bindings — and asserts on the [Outcome], its writes
 * and (for effects) the recorded seam call. The honest failure shape is the point of the coverage: an audio
 * state the device cannot read is a visible [Outcome.Fail], never a fabricated `false`/`0`; a refused effect
 * ([AudioWrite] `ok = false`) is a visible Fail carrying the reason, never a fabricated OK; a real "not muted
 * / off / 0" is a NO/level, distinct from an unreadable state (a Fail). The absent-seam path is checked for
 * all eight blocks.
 */
class AudioControllerBlocksTest {

    /**
     * A fully scriptable fake standing in for the real audio stack. A `null` reading is exactly what a device
     * with no `AudioManager` would answer, and the block turns that `null` into a named Fail. Effects return a
     * scripted [AudioWrite] and record their call so a test can assert both the [Outcome] and that the seam
     * was asked to apply the change (never a fabricated OK). [volume]/[streamMuted] are keyed by stream so a
     * test can vary the read per stream.
     */
    private class FakeAudioController(
        private val volume: Map<AudioStream, Int?> = emptyMap(),
        private val streamMuted: Map<AudioStream, Boolean?> = emptyMap(),
        private val micMuted: Boolean? = null,
        private val speakerphone: Boolean? = null,
        private val setVolumeResult: AudioWrite = AudioWrite(ok = true),
        private val setStreamMuteResult: AudioWrite = AudioWrite(ok = true),
        private val setMicMuteResult: AudioWrite = AudioWrite(ok = true),
        private val setSpeakerphoneResult: AudioWrite = AudioWrite(ok = true),
    ) : AudioController {
        val volumeSets = mutableListOf<Pair<AudioStream, Int>>()
        val streamMuteSets = mutableListOf<Pair<AudioStream, Boolean>>()
        val micMuteSets = mutableListOf<Boolean>()
        val speakerphoneSets = mutableListOf<Boolean>()

        override suspend fun streamVolume(stream: AudioStream): Int? = volume[stream]
        override suspend fun isStreamMuted(stream: AudioStream): Boolean? = streamMuted[stream]
        override suspend fun isMicrophoneMuted(): Boolean? = micMuted
        override suspend fun isSpeakerphoneOn(): Boolean? = speakerphone
        override suspend fun setStreamVolume(stream: AudioStream, level: Int): AudioWrite {
            volumeSets += stream to level
            return setVolumeResult
        }
        override suspend fun setStreamMuted(stream: AudioStream, muted: Boolean): AudioWrite {
            streamMuteSets += stream to muted
            return setStreamMuteResult
        }
        override suspend fun setMicrophoneMuted(muted: Boolean): AudioWrite {
            micMuteSets += muted
            return setMicMuteResult
        }
        override suspend fun setSpeakerphoneOn(on: Boolean): AudioWrite {
            speakerphoneSets += on
            return setSpeakerphoneResult
        }
    }

    private fun node(specId: String, vararg outputs: Pair<String, String>) =
        FlowNode("n", specId, 0f, 0f, outputs = outputs.toMap())

    private fun fiber() = Fiber("f", "flow")

    /** Fetch a single registered impl from the lookup composed over [seam]. */
    private fun block(specId: String, seam: AudioController?): BlockImpl =
        audioLookup { seam }[specId] ?: error("no registered block for $specId")

    // ------------------------------------------------------------------ audio_stream_muted

    @Test fun audioStreamMutedYesWhenMuted() = runTest {
        // Default stream is Ring; a muted Ring stream → YES.
        val seam = FakeAudioController(streamMuted = mapOf(AudioStream.RING to true))
        val outcome = block("audio_stream_muted", seam).run(fiber(), node("audio_stream_muted"), emptyMap())
        assertEquals(Port.YES, (outcome as Outcome.Proceed).port)
    }

    @Test fun audioStreamMutedNoWhenNotMuted() = runTest {
        // A real false is NO, not a Fail — "not muted" is a successful read.
        val seam = FakeAudioController(streamMuted = mapOf(AudioStream.MEDIA to false))
        val outcome = block("audio_stream_muted", seam).run(
            fiber(), node("audio_stream_muted"), mapOf("stream" to Value.Text("media")),
        )
        assertEquals(Port.NO, (outcome as Outcome.Proceed).port)
    }

    @Test fun audioStreamMutedFailsOnUnrecognizedStream() = runTest {
        val seam = FakeAudioController(streamMuted = mapOf(AudioStream.RING to true))
        val outcome = block("audio_stream_muted", seam).run(
            fiber(), node("audio_stream_muted"), mapOf("stream" to Value.Text("subwoofer")),
        )
        assertTrue("an unrecognized stream Fails by name", outcome is Outcome.Fail)
    }

    @Test fun audioStreamMutedFailsWhenUnreadable() = runTest {
        // No stream-mute read (e.g. pre-API-23 → null from the seam) → Fail, never a fabricated NO.
        val seam = FakeAudioController(streamMuted = mapOf(AudioStream.RING to null))
        val outcome = block("audio_stream_muted", seam).run(fiber(), node("audio_stream_muted"), emptyMap())
        assertTrue(outcome is Outcome.Fail)
    }

    // ------------------------------------------------------------------ audio_volume (band)

    @Test fun audioVolumeBindsAndYesWithinBand() = runTest {
        val seam = FakeAudioController(volume = mapOf(AudioStream.MEDIA to 7))
        val outcome = block("audio_volume", seam).run(
            fiber(),
            node("audio_volume", "varLevel" to "lvl"),
            mapOf("stream" to Value.Text("media"), "minLevel" to Value.Num(5.0), "maxLevel" to Value.Num(10.0)),
        )
        val proceed = outcome as Outcome.Proceed
        assertEquals(Port.YES, proceed.port)
        assertEquals(Value.Num(7.0), proceed.writes["lvl"])
    }

    @Test fun audioVolumeNoOutsideBandButStillBinds() = runTest {
        // Default stream is Voice call; level below the min → NO, but varLevel still binds the real reading.
        val seam = FakeAudioController(volume = mapOf(AudioStream.VOICE_CALL to 1))
        val outcome = block("audio_volume", seam).run(
            fiber(),
            node("audio_volume", "varLevel" to "lvl"),
            mapOf("minLevel" to Value.Num(3.0)),
        )
        val proceed = outcome as Outcome.Proceed
        assertEquals("outside the band is NO", Port.NO, proceed.port)
        assertEquals("varLevel binds the real reading regardless of branch", Value.Num(1.0), proceed.writes["lvl"])
    }

    @Test fun audioVolumeBindsRealZero() = runTest {
        // A real 0 is a successful read bound to varLevel, never confused with an unreadable null.
        val seam = FakeAudioController(volume = mapOf(AudioStream.VOICE_CALL to 0))
        val outcome = block("audio_volume", seam).run(
            fiber(), node("audio_volume", "varLevel" to "lvl"), emptyMap(),
        )
        val proceed = outcome as Outcome.Proceed
        assertEquals(Port.YES, proceed.port) // no band constraints → in-band
        assertEquals(Value.Num(0.0), proceed.writes["lvl"])
    }

    @Test fun audioVolumeFailsWhenUnreadable() = runTest {
        val seam = FakeAudioController(volume = mapOf(AudioStream.VOICE_CALL to null))
        val outcome = block("audio_volume", seam).run(
            fiber(), node("audio_volume", "varLevel" to "lvl"), emptyMap(),
        )
        assertTrue(outcome is Outcome.Fail)
        assertNull((outcome as Outcome.Fail).writes["lvl"])
    }

    // ------------------------------------------------------------------ audio_volume_set (effect)

    @Test fun audioVolumeSetAppliesAndOk() = runTest {
        val seam = FakeAudioController(setVolumeResult = AudioWrite(ok = true))
        val outcome = block("audio_volume_set", seam).run(
            fiber(), node("audio_volume_set"),
            mapOf("stream" to Value.Text("alarm"), "level" to Value.Num(4.0)),
        )
        assertEquals(Port.OK, (outcome as Outcome.Proceed).port)
        assertEquals("the seam was asked to apply the set", listOf(AudioStream.ALARM to 4), seam.volumeSets)
    }

    @Test fun audioVolumeSetFailsWhenRefused() = runTest {
        // A denied write is a visible Fail carrying the reason, never a fabricated OK.
        val seam = FakeAudioController(setVolumeResult = AudioWrite(ok = false, reason = "DND owns Ring"))
        val outcome = block("audio_volume_set", seam).run(
            fiber(), node("audio_volume_set"),
            mapOf("stream" to Value.Text("ring"), "level" to Value.Num(2.0)),
        )
        assertTrue(outcome is Outcome.Fail)
        assertTrue((outcome as Outcome.Fail).message.contains("DND owns Ring"))
        assertEquals(listOf(AudioStream.RING to 2), seam.volumeSets)
    }

    @Test fun audioVolumeSetFailsWithoutLevel() = runTest {
        // A volume set with no level is a mistake the user must see — Fail, and the seam is never called.
        val seam = FakeAudioController()
        val outcome = block("audio_volume_set", seam).run(
            fiber(), node("audio_volume_set"), mapOf("stream" to Value.Text("alarm")),
        )
        assertTrue(outcome is Outcome.Fail)
        assertTrue("no fabricated set is issued", seam.volumeSets.isEmpty())
    }

    // ------------------------------------------------------------------ audio_stream_set_mute (effect)

    @Test fun audioStreamSetMuteAppliesAndOk() = runTest {
        val seam = FakeAudioController()
        val outcome = block("audio_stream_set_mute", seam).run(
            fiber(), node("audio_stream_set_mute"),
            mapOf("stream" to Value.Text("media"), "state" to Value.Text("true")),
        )
        assertEquals(Port.OK, (outcome as Outcome.Proceed).port)
        assertEquals(listOf(AudioStream.MEDIA to true), seam.streamMuteSets)
    }

    @Test fun audioStreamSetMuteFailsWhenRefused() = runTest {
        val seam = FakeAudioController(
            setStreamMuteResult = AudioWrite(ok = false, reason = "needs Android 6.0+"),
        )
        // Default stream is Ring; state false → unmute request, but the device refuses → Fail.
        val outcome = block("audio_stream_set_mute", seam).run(
            fiber(), node("audio_stream_set_mute"), mapOf("state" to Value.Text("false")),
        )
        assertTrue(outcome is Outcome.Fail)
        assertTrue((outcome as Outcome.Fail).message.contains("needs Android 6.0+"))
        assertEquals(listOf(AudioStream.RING to false), seam.streamMuteSets)
    }

    // ------------------------------------------------------------------ microphone_muted

    @Test fun microphoneMutedYesWhenMuted() = runTest {
        val outcome = block("microphone_muted", FakeAudioController(micMuted = true)).run(
            fiber(), node("microphone_muted"), emptyMap(),
        )
        assertEquals(Port.YES, (outcome as Outcome.Proceed).port)
    }

    @Test fun microphoneMutedNoWhenNotMuted() = runTest {
        val outcome = block("microphone_muted", FakeAudioController(micMuted = false)).run(
            fiber(), node("microphone_muted"), emptyMap(),
        )
        assertEquals(Port.NO, (outcome as Outcome.Proceed).port)
    }

    @Test fun microphoneMutedFailsWhenUnreadable() = runTest {
        val outcome = block("microphone_muted", FakeAudioController(micMuted = null)).run(
            fiber(), node("microphone_muted"), emptyMap(),
        )
        assertTrue(outcome is Outcome.Fail)
    }

    // ------------------------------------------------------------------ microphone_set_mute (effect)

    @Test fun microphoneSetMuteAppliesAndOk() = runTest {
        val seam = FakeAudioController()
        val outcome = block("microphone_set_mute", seam).run(
            fiber(), node("microphone_set_mute"), mapOf("state" to Value.Text("true")),
        )
        assertEquals(Port.OK, (outcome as Outcome.Proceed).port)
        assertEquals(listOf(true), seam.micMuteSets)
    }

    @Test fun microphoneSetMuteFailsWhenRefused() = runTest {
        val seam = FakeAudioController(setMicMuteResult = AudioWrite(ok = false, reason = "no audio manager"))
        val outcome = block("microphone_set_mute", seam).run(
            fiber(), node("microphone_set_mute"), mapOf("state" to Value.Text("false")),
        )
        assertTrue(outcome is Outcome.Fail)
        assertTrue((outcome as Outcome.Fail).message.contains("no audio manager"))
        assertEquals(listOf(false), seam.micMuteSets)
    }

    // ------------------------------------------------------------------ speakerphone_on

    @Test fun speakerphoneOnYesWhenOn() = runTest {
        val outcome = block("speakerphone_on", FakeAudioController(speakerphone = true)).run(
            fiber(), node("speakerphone_on"), emptyMap(),
        )
        assertEquals(Port.YES, (outcome as Outcome.Proceed).port)
    }

    @Test fun speakerphoneOnNoWhenOff() = runTest {
        val outcome = block("speakerphone_on", FakeAudioController(speakerphone = false)).run(
            fiber(), node("speakerphone_on"), emptyMap(),
        )
        assertEquals(Port.NO, (outcome as Outcome.Proceed).port)
    }

    @Test fun speakerphoneOnFailsWhenUnreadable() = runTest {
        val outcome = block("speakerphone_on", FakeAudioController(speakerphone = null)).run(
            fiber(), node("speakerphone_on"), emptyMap(),
        )
        assertTrue(outcome is Outcome.Fail)
    }

    // ------------------------------------------------------------------ speakerphone_set_state (effect)

    @Test fun speakerphoneSetStateAppliesAndOk() = runTest {
        val seam = FakeAudioController()
        val outcome = block("speakerphone_set_state", seam).run(
            fiber(), node("speakerphone_set_state"), mapOf("state" to Value.Text("true")),
        )
        assertEquals(Port.OK, (outcome as Outcome.Proceed).port)
        assertEquals(listOf(true), seam.speakerphoneSets)
    }

    @Test fun speakerphoneSetStateFailsWhenRefused() = runTest {
        val seam = FakeAudioController(
            setSpeakerphoneResult = AudioWrite(ok = false, reason = "the speakerphone could not be set"),
        )
        val outcome = block("speakerphone_set_state", seam).run(
            fiber(), node("speakerphone_set_state"), mapOf("state" to Value.Text("false")),
        )
        assertTrue(outcome is Outcome.Fail)
        assertEquals(listOf(false), seam.speakerphoneSets)
    }

    // ------------------------------------------------------------------ absent seam (all eight)

    @Test fun allBlocksFailByNameWhenSeamAbsent() = runTest {
        val lookup = audioLookup { null }
        for ((id, impl) in lookup) {
            val outcome = impl.run(fiber(), node(id), emptyMap())
            assertTrue("$id must Fail when the seam is absent", outcome is Outcome.Fail)
            assertTrue((outcome as Outcome.Fail).message.contains("audio seam"))
        }
    }

    // ------------------------------------------------------------------ composition helper

    @Test fun audioLookupExposesExactlyTheEightRegisteredBlocks() {
        val lookup = audioLookup { null }
        assertEquals(
            setOf(
                "audio_stream_muted",
                "audio_volume",
                "audio_volume_set",
                "audio_stream_set_mute",
                "microphone_muted",
                "microphone_set_mute",
                "speakerphone_on",
                "speakerphone_set_state",
            ),
            lookup.keys,
        )
        // Gated by omission — camera / photo / video capture (device-action tier).
        assertNull(lookup["camera_available"])
        assertNull(lookup["capture_image"])
        assertNull(lookup["capture_video"])
        assertNull(lookup["take_picture"]) // CAMERA
        assertNull(lookup["video_record_start"]) // CAMERA + RECORD_AUDIO, AWAIT
        assertNull(lookup["video_record_stop"])
        assertNull(lookup["flashlight_enabled"])
        assertNull(lookup["flashlight_set_state"])
        // Audio recording / microphone capture (RECORD_AUDIO).
        assertNull(lookup["audio_record_start"]) // AWAIT
        assertNull(lookup["audio_record_stop"])
        assertNull(lookup["audio_device_recording"])
        assertNull(lookup["sound_level"]) // RECORD_AUDIO
        assertNull(lookup["speech_recognition"]) // RECORD_AUDIO
        assertNull(lookup["hotword_detected"]) // RECORD_AUDIO, AWAIT
        // Screenshot.
        assertNull(lookup["screenshot"])
        // Media / tone / sound playback (loads a file/URI, or an AWAIT player).
        assertNull(lookup["sound_play"]) // AWAIT
        assertNull(lookup["sound_stop"])
        assertNull(lookup["tone_play"]) // AWAIT
        assertNull(lookup["tone_stop"])
        assertNull(lookup["audio_player_control"])
        // Media-session read.
        assertNull(lookup["media_playing"])
        // Text-to-speech.
        assertNull(lookup["speak_play"]) // AWAIT
        assertNull(lookup["speak_stop"])
        assertNull(lookup["speak_to_file"])
        // Vibration.
        assertNull(lookup["vibrate_start"])
        assertNull(lookup["vibrate_stop"])
        // Bluetooth audio routing (writes / permissioned).
        assertNull(lookup["bluetooth_device_active_set"]) // BLUETOOTH_CONNECT
        assertNull(lookup["bluetooth_sco_set_state"])
        // Audio device enumeration + picker.
        assertNull(lookup["audio_device_connected"])
        assertNull(lookup["ringtone_pick"]) // picker
        // ringer_mode is owned by SettingsBlocks and is not declared in this catalog — never here.
        assertNull(lookup["ringer_mode"])
        assertNull(lookup["ringer_mode_set"])
        // Composes over the layers below via `audioLookup(...)[id] ?: base`.
        assertNull(lookup["roaming"]) // Telephony
        assertNull(lookup["network_type"]) // Connectivity
        assertNull(lookup["battery_level"]) // Battery & power
        assertEquals("audio_volume", lookup["audio_volume"]!!.specId)
    }
}

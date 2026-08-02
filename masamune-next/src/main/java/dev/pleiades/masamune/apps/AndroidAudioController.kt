package dev.pleiades.masamune.apps

import android.content.Context
import android.media.AudioManager
import android.os.Build

/**
 * The real, device-backed [AudioController] — the Android glue that turns the plain-data contract into
 * reads and effects on `AudioManager`.
 *
 * This is the only file in the slice that touches `android.*`, and it is compile-only from the unit tests'
 * point of view: the blocks never see it, they see [AudioController]. Keeping every framework call on this
 * side of the seam is what lets [dev.pleiades.masamune.flow.runtime.impl.audioLookup]'s blocks stay
 * JVM-testable against a fake.
 *
 * ### STATE READS + SIMPLE VOLUME/MODE EFFECTS ONLY
 * There is deliberately no way to capture a photo/video, record audio, take a screenshot, play a
 * sound/tone/media file, speak via TTS, vibrate, load/write a bitmap, read a media session's metadata, route
 * Bluetooth audio, or drive a picker from here. This class reads unprivileged audio *state* and applies
 * simple volume/mute/speakerphone *effects* and nothing else; every capture, recording, playback, TTS,
 * vibration, image, media-session, Bluetooth-routing and picker block is gated by omission in
 * [dev.pleiades.masamune.flow.runtime.impl.audioLookup] and has no glue here.
 *
 * ### Honest boundaries — a missing reading is `null`, a refused effect is `AudioWrite(ok = false)`
 *  - **No `AudioManager` is `null` for a read / `AudioWrite(ok = false)` for an effect, never a guess.** A
 *    device with no audio service returns `null`/a refused write, which the block routes to a named Fail —
 *    never a fabricated `0`/`false`/OK.
 *  - **A pre-API-23 stream-mute read/effect is `null`/`AudioWrite(ok = false)`.** The stream-mute APIs
 *    (`isStreamMute`, `adjustStreamVolume(ADJUST_MUTE)`) are API 23+; below that the block Fails by name
 *    rather than pretending to know or apply the state.
 *  - **A refused effect is `AudioWrite(ok = false, reason = …)`.** A `SecurityException` (a Do-Not-Disturb
 *    policy owns the ring/notification volume) or a level out of the stream's `0..max` range surfaces as a
 *    refusal the block Fails by name on — never a fabricated OK.
 *  - **A real "no / 0" is the value, not `null`.** A stream genuinely at volume `0`, a stream really not
 *    muted, a microphone really not muted, a speakerphone really off — these are successful reads the block
 *    routes to a level/NO, kept distinct from the unreadable `null`.
 */
class AndroidAudioController(private val context: Context) : AudioController {

    private val audioManager: AudioManager?
        get() = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    /** The Android `AudioManager.STREAM_*` constant for [stream]; the only place the mapping lives. */
    private fun AudioStream.toAndroid(): Int = when (this) {
        AudioStream.VOICE_CALL -> AudioManager.STREAM_VOICE_CALL
        AudioStream.SYSTEM -> AudioManager.STREAM_SYSTEM
        AudioStream.RING -> AudioManager.STREAM_RING
        AudioStream.MEDIA -> AudioManager.STREAM_MUSIC
        AudioStream.ALARM -> AudioManager.STREAM_ALARM
        AudioStream.NOTIFICATION -> AudioManager.STREAM_NOTIFICATION
        AudioStream.DTMF -> AudioManager.STREAM_DTMF
        AudioStream.ACCESSIBILITY -> AudioManager.STREAM_ACCESSIBILITY
    }

    override suspend fun streamVolume(stream: AudioStream): Int? {
        val am = audioManager ?: return null
        return try {
            am.getStreamVolume(stream.toAndroid())
        } catch (_: RuntimeException) {
            null // an unreadable stream volume — honest null, the block Fails by name
        }
    }

    override suspend fun isStreamMuted(stream: AudioStream): Boolean? {
        val am = audioManager ?: return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null // isStreamMute is API 23+
        return try {
            am.isStreamMute(stream.toAndroid())
        } catch (_: RuntimeException) {
            null
        }
    }

    override suspend fun isMicrophoneMuted(): Boolean? {
        val am = audioManager ?: return null
        return am.isMicrophoneMute
    }

    override suspend fun isSpeakerphoneOn(): Boolean? {
        val am = audioManager ?: return null
        @Suppress("DEPRECATION")
        return am.isSpeakerphoneOn
    }

    override suspend fun setStreamVolume(stream: AudioStream, level: Int): AudioWrite {
        val am = audioManager ?: return AudioWrite(ok = false, reason = "no audio manager")
        val id = stream.toAndroid()
        return try {
            val max = am.getStreamMaxVolume(id)
            if (level < 0 || level > max) {
                AudioWrite(ok = false, reason = "volume $level is out of range 0..$max for ${stream.label}")
            } else {
                am.setStreamVolume(id, level, 0)
                AudioWrite(ok = true)
            }
        } catch (e: SecurityException) {
            // A Do-Not-Disturb policy owns the ring/notification volume, or the change is otherwise refused.
            AudioWrite(ok = false, reason = "not permitted (${e.message ?: "a policy may own this stream"})")
        } catch (e: RuntimeException) {
            AudioWrite(ok = false, reason = e.message ?: "the volume could not be set")
        }
    }

    override suspend fun setStreamMuted(stream: AudioStream, muted: Boolean): AudioWrite {
        val am = audioManager ?: return AudioWrite(ok = false, reason = "no audio manager")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return AudioWrite(ok = false, reason = "muting a stream needs Android 6.0+")
        }
        val direction = if (muted) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE
        return try {
            am.adjustStreamVolume(stream.toAndroid(), direction, 0)
            AudioWrite(ok = true)
        } catch (e: SecurityException) {
            AudioWrite(ok = false, reason = "not permitted (${e.message ?: "a policy may own this stream"})")
        } catch (e: RuntimeException) {
            AudioWrite(ok = false, reason = e.message ?: "the stream mute could not be set")
        }
    }

    override suspend fun setMicrophoneMuted(muted: Boolean): AudioWrite {
        val am = audioManager ?: return AudioWrite(ok = false, reason = "no audio manager")
        return try {
            am.isMicrophoneMute = muted
            AudioWrite(ok = true)
        } catch (e: RuntimeException) {
            AudioWrite(ok = false, reason = e.message ?: "the microphone mute could not be set")
        }
    }

    override suspend fun setSpeakerphoneOn(on: Boolean): AudioWrite {
        val am = audioManager ?: return AudioWrite(ok = false, reason = "no audio manager")
        return try {
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = on
            AudioWrite(ok = true)
        } catch (e: RuntimeException) {
            AudioWrite(ok = false, reason = e.message ?: "the speakerphone could not be set")
        }
    }
}

package dev.pleiades.masamune.apps

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The real, device-backed [DeviceOutput] — the Android glue that turns the plain-data contract into calls
 * on `Vibrator`/`VibratorManager` (a `VibrationEffect` waveform on API 26+) and `android.speech.tts
 * .TextToSpeech`.
 *
 * This is the only file in the slice that touches `android.*`, and it is compile-only from the unit tests'
 * point of view: the blocks never see it, they see [DeviceOutput]. Keeping every framework call on this
 * side of the seam is what lets [dev.pleiades.masamune.flow.runtime.impl.deviceOutputLookup]'s blocks stay
 * JVM-testable against a fake.
 *
 * ### OUTPUT EFFECTS ONLY
 * There is deliberately no way to capture audio, record, take a picture, play media, or write a file from
 * here. This class starts/cancels a vibration and enqueues/stops speech and nothing else; every capture,
 * recording, media-playback and file-writing block is gated by omission in
 * [dev.pleiades.masamune.flow.runtime.impl.deviceOutputLookup] and has no glue here.
 *
 * ### Honest boundaries — a refused effect is `OutputWrite(ok = false)`, never a throw-as-success
 *  - **No vibrator hardware is `OutputWrite(ok = false, reason = …)`.** A device with no vibrator (or a
 *    vibrator that reports `!hasVibrator()`) returns a refused write the block routes to a named Fail —
 *    never a fabricated OK for a buzz that never happened.
 *  - **The TTS engine absent or not yet ready is `OutputWrite(ok = false, reason = …)`.** `TextToSpeech`
 *    initializes asynchronously; until its init callback reports success, `speak` refuses honestly rather
 *    than dropping the utterance on the floor and claiming OK. An empty utterance is likewise refused.
 *  - **A framework throw becomes a refusal, not a crash.** Any `RuntimeException` from the vibrator or TTS
 *    is caught and returned as `OutputWrite(ok = false, reason = …)`, so the block Fails by name.
 *
 * The [TextToSpeech] instance is created lazily on first speech and reused; its async init flips
 * [ttsReady] once the engine reports success.
 */
class AndroidDeviceOutput(private val context: Context) : DeviceOutput {

    private val vibratorService: Vibrator?
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    private val ttsReady = AtomicBoolean(false)

    @Volatile
    private var tts: TextToSpeech? = null

    // ---- vibration ---------------------------------------------------------

    override suspend fun vibrate(pattern: List<Long>, repeat: Boolean): OutputWrite {
        val vibrator = vibratorService ?: return OutputWrite(ok = false, reason = "no vibrator hardware")
        if (!vibrator.hasVibrator()) return OutputWrite(ok = false, reason = "device has no vibrator")
        if (pattern.isEmpty()) return OutputWrite(ok = false, reason = "empty vibration pattern")
        val timings = pattern.map { it.coerceAtLeast(0L) }.toLongArray()
        val repeatIndex = if (repeat) 0 else -1
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(timings, repeatIndex))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(timings, repeatIndex)
            }
            OutputWrite(ok = true)
        } catch (e: RuntimeException) {
            OutputWrite(ok = false, reason = e.message ?: "the vibration could not be started")
        }
    }

    override suspend fun cancelVibration(): OutputWrite {
        val vibrator = vibratorService ?: return OutputWrite(ok = false, reason = "no vibrator hardware")
        return try {
            vibrator.cancel()
            OutputWrite(ok = true)
        } catch (e: RuntimeException) {
            OutputWrite(ok = false, reason = e.message ?: "the vibration could not be cancelled")
        }
    }

    // ---- text-to-speech ----------------------------------------------------

    override suspend fun speak(message: String): OutputWrite {
        if (message.isEmpty()) return OutputWrite(ok = false, reason = "empty utterance")
        val engine = ensureTts()
        if (!ttsReady.get()) {
            return OutputWrite(ok = false, reason = "text-to-speech engine is not ready")
        }
        return try {
            val utteranceId = UUID.randomUUID().toString()
            val result = engine.speak(message, TextToSpeech.QUEUE_ADD, null, utteranceId)
            if (result == TextToSpeech.SUCCESS) {
                OutputWrite(ok = true)
            } else {
                OutputWrite(ok = false, reason = "the utterance could not be enqueued")
            }
        } catch (e: RuntimeException) {
            OutputWrite(ok = false, reason = e.message ?: "the utterance could not be spoken")
        }
    }

    override suspend fun stopSpeaking(): OutputWrite {
        val engine = tts ?: return OutputWrite(ok = false, reason = "no text-to-speech engine to stop")
        return try {
            engine.stop()
            OutputWrite(ok = true)
        } catch (e: RuntimeException) {
            OutputWrite(ok = false, reason = e.message ?: "speech could not be stopped")
        }
    }

    /**
     * Return the shared [TextToSpeech], creating it on first use. The engine initializes asynchronously;
     * [ttsReady] flips once its init callback reports success, and until then [speak] refuses honestly.
     */
    private fun ensureTts(): TextToSpeech {
        tts?.let { return it }
        return synchronized(this) {
            tts ?: TextToSpeech(context) { status ->
                ttsReady.set(status == TextToSpeech.SUCCESS)
            }.also { tts = it }
        }
    }
}

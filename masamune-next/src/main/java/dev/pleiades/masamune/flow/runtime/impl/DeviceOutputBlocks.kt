package dev.pleiades.masamune.flow.runtime.impl

import dev.pleiades.masamune.apps.DeviceOutput
import dev.pleiades.masamune.apps.OutputWrite
import dev.pleiades.masamune.flow.expr.Value
import dev.pleiades.masamune.flow.model.FlowNode
import dev.pleiades.masamune.flow.model.Port
import dev.pleiades.masamune.flow.runtime.BlockImpl
import dev.pleiades.masamune.flow.runtime.Fiber
import dev.pleiades.masamune.flow.runtime.Outcome

/**
 * The CameraAndSound category's **"act on the phone" output-effect** slice — the organ an AI phone
 * operator needs to make the device *do* something the user can feel or hear right now: make it buzz
 * (start a vibration pattern, cancel an ongoing one) and make it speak (enqueue a text-to-speech
 * utterance, stop ongoing speech). It applies simple output effects only; it does not capture audio,
 * record, take pictures, play media/sounds/tones, or write any file.
 *
 * ### Why this subset and not the whole (large) category
 * `CatalogCameraAndSound` is dominated by *capture*, *media* and *file-writing* blocks that have no place
 * in a headless effect seam — the camera/still-image family (`capture_image`, `take_picture`,
 * `image_*`, …), audio/video recording (`audio_record_*`, `video_record_*`, `sound_level`,
 * `speech_recognition`), media playback and tags (`media_playing`, `sound_play`, `tone_play`,
 * `media_tags_read`, …), barcode/QR/OCR, and the audio state-read + volume/mode effects already served by
 * [audioLookup]. Only the two pure *output effects* — vibration and text-to-speech — can be expressed
 * through the [DeviceOutput] seam as one-shot actions, and only those run here:
 *  - **Vibration (2):** `vibrate_start` (start a vibration pattern) and `vibrate_stop` (cancel it).
 *  - **Text-to-speech (2):** `speak_play` (enqueue an utterance) and `speak_stop` (stop speaking).
 *  - Everything else is gated by omission (see [deviceOutputLookup]).
 *
 * ### The seam, copied from the prior categories
 * Every device call lives behind the injected [DeviceOutput] — a narrow, `android.*`-free contract, the
 * exact shape [dev.pleiades.masamune.apps.DeviceUi] and the ten readers give their categories. Two
 * consequences, both deliberate:
 *  1. **JVM-testable.** Each block reads its args as *plain data*, then calls the seam, so the whole file
 *     is unit-testable against a fake on an ordinary JVM — a device is needed to run these, never to test
 *     their arg-parsing logic.
 *  2. **Honest gate at run.** Every impl re-resolves its [DeviceOutput] provider and fails with
 *     [DEVICE_OUTPUT_ABSENT] when there is no seam (the app process is not wired in, or it dropped
 *     mid-run). An effect the device refuses ([OutputWrite] `ok = false`) Fails **by name** carrying the
 *     reason — never a fabricated OK.
 *
 * ### AWAIT collapses to its one-shot enqueue form
 * The catalog marks `speak_play` AWAIT — fire the utterance and suspend until it has finished speaking.
 * The awaiting-until-done form needs the monitor subsystem this build does not have, so the one-shot
 * "start speaking" condition — enqueue the utterance and continue OK once it is enqueued — is what runs,
 * which is exactly what an action in a running flow evaluates. This mirrors the Audio, Connectivity and
 * Interface one-shot collapses. `vibrate_start` is likewise fire-and-continue.
 *
 * ### Honest simplifications of over-rich effect arguments (documented, not faked)
 * `speak_play` carries a large voice surface — `rate`, `language`, `engine`, `offline`, `stream`,
 * `volume`, `focus`, `notificationChannelId`. This headless slice speaks the `message` with the engine's
 * own defaults and does not model the voice/stream/focus tuning, exactly as Audio ignores
 * `showPopup`/`playSound` and the Interface slice ignores `notification_show`'s custom layout surface: an
 * argument with no faithful headless meaning is documented as not-modelled, not faked. `vibrate_start`
 * reads the `pattern` (millisecond on/off timings) and the `repeat` flag, which is the block's whole
 * declared surface.
 *
 * The composition helper [deviceOutputLookup] mirrors [deviceUiLookup], [audioLookup] and the others: it
 * returns the impls keyed by spec id so a caller composes `deviceOutputLookup(provider)[id] ?: base[id]`.
 */

/** The sentence shown whenever a device-output block cannot reach an output seam. Modelled on [DEVICE_UI_ABSENT]. */
internal val DEVICE_OUTPUT_ABSENT: String =
    "This CameraAndSound block cannot act: no device-output seam is available, so Masamune cannot vibrate " +
        "the device or speak through text-to-speech. The seam is wired only inside the Android app " +
        "process; when it is absent the block fails by name rather than claiming an effect that never was " +
        "applied."

// --------------------------------------------------------------------------- vibrate_start (effect)

/**
 * `vibrate_start` (Vibrate) — start a vibration pattern.
 *
 * ACTION / EFFECT: it requires a `pattern` argument — a vibration with no timings is a mistake the user
 * must see, so an absent/empty `pattern` Fails **by name** and the seam is never called. It parses the
 * `pattern` into a list of millisecond on/off durations, reads the `repeat` flag (default no), starts the
 * vibration through the seam and routes the [OutputWrite]: OK on accept, a named Fail carrying the reason
 * (no vibrator hardware) on refusal — never a fabricated OK.
 */
internal class VibrateStartBlock(
    private val provider: () -> DeviceOutput?,
) : BlockImpl {
    override val specId = "vibrate_start"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val output = provider() ?: return Outcome.Fail(DEVICE_OUTPUT_ABSENT)
        val pattern = args["pattern"].asMillisPattern()
            ?: return Outcome.Fail("vibrate_start needs a vibration pattern of millisecond durations.")
        val repeat = args["repeat"].asFlag()
        return output.vibrate(pattern, repeat).asOutcome("vibrate_start")
    }
}

// --------------------------------------------------------------------------- vibrate_stop (effect)

/**
 * `vibrate_stop` (Vibrate stop) — cancel any ongoing vibration.
 *
 * ACTION / EFFECT: it takes no arguments; it cancels through the seam and routes the [OutputWrite]: OK on
 * accept, a named Fail on refusal (no vibrator hardware) — never a fabricated OK.
 */
internal class VibrateStopBlock(
    private val provider: () -> DeviceOutput?,
) : BlockImpl {
    override val specId = "vibrate_stop"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val output = provider() ?: return Outcome.Fail(DEVICE_OUTPUT_ABSENT)
        return output.cancelVibration().asOutcome("vibrate_stop")
    }
}

// --------------------------------------------------------------------------- speak_play (effect)

/**
 * `speak_play` (Speak) — speak a message through text-to-speech.
 *
 * ACTION / EFFECT: it requires a `message` argument — speech with no words is a mistake the user must
 * see, so an absent/empty `message` Fails **by name** and the seam is never called. It enqueues the
 * utterance through the seam and routes the [OutputWrite]: OK once enqueued, a named Fail carrying the
 * reason (the TTS engine is absent or not ready) on refusal — never a fabricated OK.
 *
 * The catalog marks this block AWAIT (suspend until the utterance has finished speaking); the
 * awaiting-until-done form needs the monitor subsystem this build lacks, so the one-shot "start speaking"
 * form runs: enqueue and proceed OK. The `rate`/`language`/`engine`/`offline`/`stream`/`volume`/`focus`/
 * `notificationChannelId` voice surface is not modelled (see file KDoc).
 */
internal class SpeakPlayBlock(
    private val provider: () -> DeviceOutput?,
) : BlockImpl {
    override val specId = "speak_play"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val output = provider() ?: return Outcome.Fail(DEVICE_OUTPUT_ABSENT)
        val message = args["message"].asTextOrNull()
            ?: return Outcome.Fail("speak_play needs a message to speak.")
        return output.speak(message).asOutcome("speak_play")
    }
}

// --------------------------------------------------------------------------- speak_stop (effect)

/**
 * `speak_stop` (Speak stop) — stop any ongoing text-to-speech.
 *
 * ACTION / EFFECT: it takes no arguments; it stops speech through the seam and routes the [OutputWrite]:
 * OK on accept, a named Fail on refusal (no TTS engine to stop) — never a fabricated OK.
 */
internal class SpeakStopBlock(
    private val provider: () -> DeviceOutput?,
) : BlockImpl {
    override val specId = "speak_stop"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val output = provider() ?: return Outcome.Fail(DEVICE_OUTPUT_ABSENT)
        return output.stopSpeaking().asOutcome("speak_stop")
    }
}

// --------------------------------------------------------------------------- composition + helpers

/**
 * The four registered CameraAndSound device-output impls, keyed by spec id, all sharing one [provider].
 *
 * Mirrors [deviceUiLookup], [audioLookup] and the lookups below them: it always returns the map, and the
 * honest gate is the per-block gate-at-run (each fails with [DEVICE_OUTPUT_ABSENT] when the provider yields
 * no seam), so a caller composes over its base registry exactly as the other categories do:
 *
 * ```
 * val output = deviceOutputLookup(deviceOutput)
 * fun lookup(id: String): BlockImpl? =
 *     output[id] ?: device[id] ?: content[id] ?: … ?: base.lookup(id)
 * ```
 *
 * ### What stays gated by omission, and why
 * Every other CameraAndSound block is deliberately **not** here, so at run time the scheduler finds no impl
 * and gates it by the honest-by-omission mechanism the catalog's own `requires` set (or the block's own
 * shape) expresses. Because the [DeviceOutput] seam is a pure vibration + text-to-speech output seam, every
 * gated block needs a capture device, a file write, a media/audio subsystem, or a grant this seam cannot
 * host. They are omitted on these honest grounds, grouped:
 *  - **Capture / recording (need a camera/microphone and often a grant).** `capture_image`, `capture_video`,
 *    `take_picture` (`CAMERA`), `video_record_start`/`_stop` (`CAMERA` + `RECORD_AUDIO`),
 *    `audio_record_start`/`_stop`, `sound_level`, `speech_recognition`, `hotword_detected` (`RECORD_AUDIO`),
 *    `screenshot`.
 *  - **File-writing speech / images (a capture/write, not an output effect).** `speak_to_file` (writes an
 *    audio file), `image_write`, `qrcode_generate`, `image_crop`/`_flip`/`_load`/`_rescale`/`_rotate`/
 *    `_sample_color`/`_unload`, `barcode_scan`, `text_recognition`, `media_tags_read`.
 *  - **Media / sound / tone playback (need the media subsystem, mostly AWAIT).** `media_playing`,
 *    `media_store_add`/`_remove`, `sound_play`/`sound_stop`, `tone_play`/`tone_stop`, `ringtone_pick`
 *    (a picker needing a UI surface).
 *  - **Audio state-read + volume/mode effects (served by [audioLookup], never here).** `audio_stream_muted`,
 *    `audio_volume`, `audio_volume_set`, `audio_stream_set_mute`, `microphone_muted`, `microphone_set_mute`,
 *    `speakerphone_on`, `speakerphone_set_state` — this lookup must not collide with them.
 *  - **Other audio-routing / camera-state reads.** `audio_device_connected`, `audio_device_recording`,
 *    `audio_player_control`, `bluetooth_device_active_set`, `bluetooth_sco_set_state`, `camera_available`,
 *    `flashlight_enabled`, `flashlight_set_state`.
 *
 * Note on collision avoidance: none of the four registered ids overlaps another `*Lookup`; the audio ids
 * above are asserted absent from this lookup in the tests, and these four are asserted absent from
 * [audioLookup] in `AudioControllerBlocksTest`.
 */
fun deviceOutputLookup(provider: () -> DeviceOutput?): Map<String, BlockImpl> = listOf(
    VibrateStartBlock(provider),
    VibrateStopBlock(provider),
    SpeakPlayBlock(provider),
    SpeakStopBlock(provider),
).associateBy { it.specId }

/**
 * Turn an [OutputWrite] into the block's [Outcome]: OK when the device accepted the effect, or a named
 * [Outcome.Fail] carrying the seam's honest reason (no vibrator, the TTS engine not ready) when it did not.
 * This is the single place the "a refused effect is a visible Fail, never a fabricated OK" rule lives for
 * this slice — modelled on the Interface slice's `UiWrite.asOutcome`.
 */
private fun OutputWrite.asOutcome(blockId: String): Outcome =
    if (ok) Outcome.Proceed(Port.OK)
    else Outcome.Fail("$blockId: effect refused — ${reason ?: "no reason given"}.")

/**
 * A `pattern` argument parsed to a list of millisecond durations, or `null` when absent/empty (which the
 * caller turns into a visible Fail, never a silent no-op vibration). An array binds each numeric item in
 * order; a lone number is a single-duration pattern; a constant-mode string lists durations separated by
 * commas/spaces. Non-numeric items are dropped, and a pattern that names no timing at all reads as `null`.
 */
private fun Value?.asMillisPattern(): List<Long>? {
    val nums: List<Double> = when (this) {
        is Value.ArrayV -> items.mapNotNull { it.asNumOrNull() }
        is Value.Num, is Value.BigInt -> listOfNotNull(this.asNumOrNull())
        is Value.Text -> value.split(',', ' ', '\t', '\n').mapNotNull { it.trim().toDoubleOrNull() }
        null, Value.Null, is Value.DictV -> return null
    }
    if (nums.isEmpty()) return null
    return nums.map { it.toLong() }
}

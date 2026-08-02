package dev.pleiades.masamune.apps

/**
 * The seam between the CameraAndSound category's **"act on the phone" output effects** — vibration and
 * text-to-speech — and the real device output stack.
 *
 * These are the two ways a one-shot CameraAndSound block *acts on the operator's body-of-the-phone*: it
 * can make the device buzz (start a vibration pattern, cancel an ongoing one) and it can make the device
 * speak (enqueue a text-to-speech utterance, stop ongoing speech). Exactly like [DeviceUi] does for the
 * Interface clipboard/toast/notification effects, [AudioController] for the CameraAndSound audio slice,
 * and the other readers for their categories, there is deliberately nothing `android.*` on this interface.
 * That single constraint is what buys the whole slice its JVM-testability:
 * [dev.pleiades.masamune.flow.runtime.impl.deviceOutputLookup]'s blocks depend on this plain-data
 * contract, never on `Vibrator`/`VibratorManager`/`VibrationEffect`/`TextToSpeech`, so every block and all
 * its arg-parsing logic can be exercised against a fake on an ordinary unit-test JVM. A device is needed to
 * *run* these blocks, never to *test* their logic.
 *
 * ### The honest gate has one clean shape here too
 * When the app process (the only thing that can hand out a real [AndroidDeviceOutput]) is not wired in,
 * there is simply no seam, and a block that cannot get one fails visibly by name
 * ([dev.pleiades.masamune.flow.runtime.impl.DEVICE_OUTPUT_ABSENT]) rather than claiming an effect it never
 * applied.
 *
 * ### Honest failure shape: refused vs. applied
 * Every method models one genuinely different "negative" case beyond the absent-seam one:
 *  - **An effect's [OutputWrite] carries `ok` + a `reason`.** An effect the device cannot run — no
 *    vibrator hardware, the text-to-speech engine is absent or not yet ready, an empty utterance — returns
 *    `OutputWrite(ok = false, reason = …)`, exactly like Audio's `AudioWrite` and the Interface slice's
 *    `UiWrite`, and the block Fails **by name** carrying that reason rather than fabricating success. An
 *    effect never throws-as-success: a hardware refusal is data on the way back, not an exception the block
 *    swallows.
 *
 * Every method is `suspend` because an output effect can touch a blocking system service or an
 * asynchronously-initialized engine; the real impl does so without the contract changing shape, and the
 * fake simply records the call and returns.
 */
interface DeviceOutput {

    /**
     * Start a vibration described by [pattern] — a list of on/off durations in milliseconds, alternating
     * off, on, off, on … from index 0, exactly as Android's own waveform pattern reads. When [repeat] is
     * set the pattern loops from its start until [cancelVibration]; otherwise it plays once. Returns
     * [OutputWrite]: `ok = false` with a `reason` when there is no vibrator hardware or the pattern could
     * not be started, so the block Fails **by name** rather than faking a buzz that never happened.
     */
    suspend fun vibrate(pattern: List<Long>, repeat: Boolean): OutputWrite

    /**
     * Cancel any vibration this seam started. Returns [OutputWrite]: `ok = false` with a `reason` when
     * there is no vibrator hardware, so the block Fails **by name** rather than faking OK.
     */
    suspend fun cancelVibration(): OutputWrite

    /**
     * Enqueue [message] to be spoken through text-to-speech and return once it is enqueued — the one-shot
     * "start speaking" form. Returns [OutputWrite]: `ok = false` with a `reason` when the TTS engine is
     * absent or not yet ready, or [message] is empty, so the block Fails **by name** rather than claiming
     * speech that was never enqueued. This does **not** wait for the utterance to finish (see the block's
     * KDoc on why the awaiting-until-done form is out of scope for this build).
     */
    suspend fun speak(message: String): OutputWrite

    /**
     * Stop any ongoing or queued text-to-speech started by [speak]. Returns [OutputWrite]: `ok = false`
     * with a `reason` when there is no TTS engine to stop, so the block Fails **by name** rather than
     * faking OK.
     */
    suspend fun stopSpeaking(): OutputWrite
}

/**
 * The result of a device-output *effect* (start/cancel a vibration, speak/stop speech), as plain data —
 * modelled on Audio's `AudioWrite` and the Interface slice's `UiWrite`. `ok = true` is a device-accepted
 * effect; `ok = false` with a [reason] is an honest refusal (no vibrator, the TTS engine not ready, an
 * empty utterance) the block turns into a visible Fail carrying [reason] — never a fabricated success.
 */
data class OutputWrite(val ok: Boolean, val reason: String? = null)

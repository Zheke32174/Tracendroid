package dev.pleiades.masamune.apps

/**
 * The seam between the CameraAndSound category's **audio state/volume** block impls and the real device
 * audio stack.
 *
 * Every way an unprivileged, one-shot audio block can *read* the device's audio state — the current volume
 * of a stream, whether a stream is muted, whether the microphone is muted, whether the speakerphone is on —
 * and every *simple audio-effect* it can apply — set a stream's volume, mute/unmute a stream, mute/unmute
 * the microphone, turn the speakerphone on/off — is one method here, and — exactly like [AppInspector] does
 * for the Apps blocks, [SystemSettings] for the Settings blocks, [PowerState] for the Battery&Power blocks,
 * [SensorReader] for the Sensor blocks, [LocationReader] for the Location blocks, [ConnectivityReader] for
 * the Connectivity blocks and [TelephonyReader] for the Telephony blocks — there is deliberately nothing
 * `android.*` on this interface. That single constraint is what buys the whole slice its JVM-testability:
 * [dev.pleiades.masamune.flow.runtime.impl.audioLookup]'s blocks depend on this plain-data contract, never
 * on `AudioManager`, so every block and all its branch logic can be exercised against a fake on an ordinary
 * unit-test JVM. A device is needed to *run* these blocks, never to *test* their logic.
 *
 * ### The honest gate has one clean shape here too
 * When the app process (the only thing that can hand out a real [AndroidAudioController]) is not wired in,
 * there is simply no seam, and a block that cannot get one fails visibly by name
 * ([dev.pleiades.masamune.flow.runtime.impl.AUDIO_ABSENT]) rather than reporting an audio state it never
 * actually read or claiming an effect it never applied.
 *
 * ### Honest failure shapes: not-readable vs. a real "no", and refused vs. applied
 * The reads model two genuinely different "negative" cases, and the effects model a third:
 *  - **A read's `null` means "could not be read".** There is no `AudioManager` on the device, or the read
 *    needs an API level this device predates (stream-mute is API 23+). The block routes `null` to a visible
 *    [dev.pleiades.masamune.flow.runtime.Outcome.Fail] **by name** — it never fabricates a `false`/`0` a
 *    downstream block would trust as a real reading.
 *  - **A read's real `false` / `0` means "read fine, and the answer is not-muted / speaker-off / silent".**
 *    A stream genuinely at volume `0`, or a microphone that is really not muted, is a successful read with a
 *    NO/level answer, distinct from a state that could not be determined at all.
 *  - **An effect's [AudioWrite] carries `ok` + a `reason`.** A set that the device refuses — an absent
 *    stream, a Do-Not-Disturb policy that owns the ring/notification volume, a level out of range, or a
 *    pre-API-23 stream-mute — returns `AudioWrite(ok = false, reason = …)`, exactly like Settings'
 *    `SettingWrite`, and the block Fails **by name** carrying that reason rather than fabricating success.
 *
 * ### Permissions shape the *run-time* failure, never keep a block unregistered
 * The registered reads and effects rest on `MODIFY_AUDIO_SETTINGS` — a *normal* permission auto-granted at
 * install — so they *are* registered: the honest gate for any device refusal (a DND policy owning a stream,
 * a level out of range) is [AudioWrite] `ok = false` and the block failing **by name**, not a fabricated
 * value and not leaving the block unregistered. Every CameraAndSound block that needs a *device-action* tier
 * — `CAMERA`, `RECORD_AUDIO`, a media-session/notification-listener grant, a picker/UI, or an AWAIT — has no
 * method here and is gated by omission (see [dev.pleiades.masamune.flow.runtime.impl.audioLookup]'s KDoc).
 *
 * This slice touches unprivileged audio *state* and *volume/mode effects* only: there is no capture, no
 * recording, no media-file playback, no text-to-speech, no tone/sound/vibration player and no picker
 * result-type here. Every catalog block that *takes a photo/video*, *records audio*, *takes a screenshot*,
 * *plays a sound/tone/media file or URI*, *speaks via TTS*, *vibrates*, *loads/writes an image bitmap*,
 * *reads a media session's metadata*, *routes Bluetooth audio*, or *drives a ringtone picker* has no method
 * here and is gated by omission — a volume/mode seam does not capture, play, speak or pick.
 *
 * Every method is `suspend` because an audio read/effect can touch a blocking system service; the real impl
 * does so without the contract changing shape, and the fake simply returns.
 */
interface AudioController {

    /**
     * The current volume level of [stream] as the integer step the `audio_volume` band compares (0..the
     * stream's max), or `null` when it cannot be read (no `AudioManager`). `null` routes a named Fail; a real
     * level is bound to `varLevel` and compared against the requested band — a real `0` is a successful read,
     * never confused with the unreadable `null`.
     */
    suspend fun streamVolume(stream: AudioStream): Int?

    /**
     * Whether [stream] is currently muted, or `null` when it cannot be read (no `AudioManager`, or the
     * device predates the API 23 stream-mute read). `false` is a real "not muted" (NO); `null` is "could not
     * read" (a named Fail).
     */
    suspend fun isStreamMuted(stream: AudioStream): Boolean?

    /**
     * Whether the microphone is currently muted, or `null` when there is no `AudioManager` to ask. `false`
     * is a real "not muted" (NO); `null` routes a named Fail.
     */
    suspend fun isMicrophoneMuted(): Boolean?

    /**
     * Whether the speakerphone is currently on, or `null` when there is no `AudioManager` to ask. `false` is
     * a real "off" (NO); `null` routes a named Fail.
     */
    suspend fun isSpeakerphoneOn(): Boolean?

    /**
     * Set [stream]'s volume to [level]. Returns [AudioWrite]: `ok = true` when the device applied it, or
     * `ok = false` with a `reason` when it refused — the stream is absent, [level] is out of the stream's
     * `0..max` range, or a Do-Not-Disturb policy owns the stream. The block Fails **by name** on a refusal,
     * never a fabricated OK.
     */
    suspend fun setStreamVolume(stream: AudioStream, level: Int): AudioWrite

    /**
     * Mute or unmute [stream] per [muted]. Returns [AudioWrite]: `ok = false` with a `reason` when the
     * device refuses (a DND policy owns the stream, or the device predates the API 23 stream-mute effect),
     * so the block Fails **by name** rather than pretending it took.
     */
    suspend fun setStreamMuted(stream: AudioStream, muted: Boolean): AudioWrite

    /**
     * Mute or unmute the microphone per [muted]. Returns [AudioWrite]: `ok = false` with a `reason` when
     * there is no `AudioManager` or the set throws, so the block Fails **by name** rather than faking OK.
     */
    suspend fun setMicrophoneMuted(muted: Boolean): AudioWrite

    /**
     * Turn the speakerphone on or off per [on]. Returns [AudioWrite]: `ok = false` with a `reason` when
     * there is no `AudioManager` or the set throws, so the block Fails **by name** rather than faking OK.
     */
    suspend fun setSpeakerphoneOn(on: Boolean): AudioWrite
}

/**
 * An audio stream the volume/mute blocks target, as plain data — a real enum rather than a leaked
 * `AudioManager.STREAM_*` int. The mapping from this enum to an Android stream constant lives entirely in
 * [AndroidAudioController], so nothing `android.*` crosses the seam. The [label] is how a block's `stream`
 * argument names the choice and is matched against (case-insensitively, with the obvious synonyms and the
 * numeric `STREAM_*` ids), mirroring how [CallState]'s label names the Telephony `state` choice.
 */
enum class AudioStream(val label: String) {
    VOICE_CALL("Voice call"),
    SYSTEM("System"),
    RING("Ring"),
    MEDIA("Media"),
    ALARM("Alarm"),
    NOTIFICATION("Notification"),
    DTMF("DTMF"),
    ACCESSIBILITY("Accessibility"),
}

/**
 * The result of an audio *effect* (set volume / set mute / set microphone / set speakerphone), as plain
 * data — modelled on Settings' `SettingWrite`. `ok = true` is a device-accepted effect; `ok = false` with a
 * [reason] is an honest refusal (absent stream, level out of range, a DND policy owning the stream, no
 * `AudioManager`, or a pre-API-23 stream-mute) the block turns into a visible Fail carrying [reason] — never
 * a fabricated success.
 */
data class AudioWrite(val ok: Boolean, val reason: String? = null)

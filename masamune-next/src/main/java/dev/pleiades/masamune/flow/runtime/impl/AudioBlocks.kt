package dev.pleiades.masamune.flow.runtime.impl

import dev.pleiades.masamune.apps.AudioController
import dev.pleiades.masamune.apps.AudioStream
import dev.pleiades.masamune.apps.AudioWrite
import dev.pleiades.masamune.flow.expr.Value
import dev.pleiades.masamune.flow.model.FlowNode
import dev.pleiades.masamune.flow.model.Port
import dev.pleiades.masamune.flow.runtime.BlockImpl
import dev.pleiades.masamune.flow.runtime.Fiber
import dev.pleiades.masamune.flow.runtime.Outcome

/**
 * The CameraAndSound category's **unprivileged audio state / volume-effect** slice — the organ an AI phone
 * operator needs to know and nudge the device's audio right now: what a stream's volume is, whether a stream
 * or the microphone is muted, whether the speakerphone is on, and the simple effects that set each. It reads
 * and lightly adjusts audio *state*; it does not capture, record, play, speak, vibrate or route.
 *
 * ### Why this subset and not the whole (large) category
 * `CatalogCameraAndSound` is dominated by *device-action tier* blocks — capturing photos and video, recording
 * audio, screenshots, playing sounds/tones/media files, text-to-speech, vibration, in-memory image editing,
 * media-session reads, Bluetooth audio routing and pickers. Only the unprivileged audio state reads and
 * simple `MODIFY_AUDIO_SETTINGS` volume/mode effects can be expressed through the [AudioController] seam, and
 * only those run here:
 *  - **Reads / decisions (4):** `audio_stream_muted`, `audio_volume` (binds `varLevel`), `microphone_muted`
 *    and `speakerphone_on`. Each is a single read routed YES/NO (or a bound level over a band).
 *  - **Effects / actions (4):** `audio_volume_set`, `audio_stream_set_mute`, `microphone_set_mute` and
 *    `speakerphone_set_state`. Each applies one `AudioManager` change and routes an [AudioWrite]: OK on
 *    accept, a named Fail carrying the reason on refusal — never a fabricated OK.
 *  - Everything else — capture/recording/screenshot, media/tone/sound playback, TTS, vibration, image
 *    editing, media-session reads, `RECORD_AUDIO`/`CAMERA`-gated blocks, Bluetooth routing and pickers — is
 *    gated by omission (see [audioLookup]).
 *
 * ### The seam, copied from the seven prior categories
 * Every device call lives behind the injected [AudioController] — a narrow, `android.*`-free contract, the
 * exact shape [dev.pleiades.masamune.apps.AppInspector], [dev.pleiades.masamune.apps.SystemSettings],
 * [dev.pleiades.masamune.apps.PowerState], [dev.pleiades.masamune.apps.SensorReader],
 * [dev.pleiades.masamune.apps.LocationReader], [dev.pleiades.masamune.apps.ConnectivityReader] and
 * [dev.pleiades.masamune.apps.TelephonyReader] give their categories. Two consequences, both deliberate:
 *
 *  1. **JVM-testable.** Each block reads its args as *plain data*, then calls the seam, so the whole file is
 *     unit-testable against a fake on an ordinary JVM — a device is needed to run these, never to test their
 *     branch logic.
 *  2. **Honest gate at run.** Every impl re-resolves its [AudioController] provider and fails with
 *     [AUDIO_ABSENT] when there is no seam (the app process is not wired in, or it dropped mid-run). A read
 *     that returns `null` becomes a named [Outcome.Fail] ("could not be read") — **never** a fabricated
 *     `false`/`0` or a silent NO. A real stream at `0` / a stream really not muted / a speakerphone really
 *     off is a successful read routed to a level/NO; only an unreadable state Fails. An effect the device
 *     refuses ([AudioWrite] `ok = false`) Fails **by name** carrying the reason — never a fabricated OK.
 *
 * ### WATCH decisions collapse to their one-shot form
 * The catalog marks `audio_volume`, `microphone_muted` and `speakerphone_on` WATCH-capable (test now, or
 * suspend until the state changes). The watching form needs the monitor subsystem this build does not have,
 * so the one-shot condition — "is the mic muted *now*", "is the volume in-band *now*" — is what runs, which
 * is exactly what a decision in a running flow evaluates. This mirrors the Connectivity/Telephony boolean
 * decisions and the Sensor scalar bands.
 *
 * ### The `showPopup`/`playSound` UI hints are not modelled
 * `audio_volume_set` and `audio_stream_set_mute` carry `showPopup` and `playSound` flags asking the platform
 * to flash the volume UI and play a test tick. This headless one-shot slice applies the state change and does
 * not drive the volume-panel UI or play a confirmation tone — the honest "set the value", never a fabricated
 * UI side effect. This is the same honest simplification by which the Telephony blocks ignore the
 * `subscriptionId` filter: an argument with no faithful headless meaning is documented as ignored, not faked.
 *
 * The composition helper [audioLookup] mirrors [telephonyLookup], [connectivityLookup], [locationLookup],
 * [sensorLookup], [powerLookup], [settingsLookup] and [appsLookup]: it returns the impls keyed by spec id so
 * a caller composes `audioLookup(provider)[id] ?: base.lookup(id)`.
 */

/** The sentence shown whenever an audio block cannot reach an audio seam. Modelled on [TELEPHONY_ABSENT]. */
internal val AUDIO_ABSENT: String =
    "This audio block cannot act: no audio seam is available, so Masamune cannot read or change the " +
        "device's stream volume, stream/microphone mute state or speakerphone. The seam is wired only " +
        "inside the Android app process; when it is absent the block fails by name rather than reporting " +
        "an audio state that never was read or an effect that never was applied."

// --------------------------------------------------------------------------- audio_stream_muted

/**
 * `audio_stream_muted` (Audio stream muted) — is the requested audio stream muted right now?
 *
 * DECISION: it parses the `stream` argument (default Ring, per the catalog), reads whether that stream is
 * muted through the seam, and routes YES when muted, NO otherwise. A `false` is a *real* "not muted" routed
 * to NO; a state the seam cannot read (no `AudioManager`, or the device predates the API 23 stream-mute read)
 * Fails **by name**, never a silent NO. An unrecognized `stream` string Fails by name, exactly as
 * `call_state` fails on an unrecognized state.
 */
internal class AudioStreamMutedBlock(
    private val audioProvider: () -> AudioController?,
) : BlockImpl {
    override val specId = "audio_stream_muted"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val audio = audioProvider() ?: return Outcome.Fail(AUDIO_ABSENT)
        val stream = args["stream"].asAudioStreamOrDefault(AudioStream.RING)
            ?: return Outcome.Fail("audio_stream_muted: unrecognized audio stream.")
        val muted = audio.isStreamMuted(stream)
            ?: return Outcome.Fail("audio_stream_muted: the ${stream.label} stream mute state could not be read.")
        return Outcome.Proceed(if (muted) Port.YES else Port.NO)
    }
}

// --------------------------------------------------------------------------- audio_volume (band)

/**
 * `audio_volume` (Audio volume) — is the stream's volume within the requested band?
 *
 * DECISION: the one-shot form of the catalog's WATCH band decision, the direct analogue of Telephony's
 * `cell_signal_level` and Connectivity's `wifi_signal_level`. It parses `stream` (default Voice call), reads
 * the current volume through the seam, **always** binds `varLevel` from it, and routes YES when the level
 * sits within `[minLevel, maxLevel]` (an unset bound is no constraint), NO otherwise. A volume the seam
 * cannot read (no `AudioManager`) Fails **by name**, never a fabricated `0` or a silent NO. A real `0` is a
 * successful read bound to `varLevel`.
 */
internal class AudioVolumeBlock(
    private val audioProvider: () -> AudioController?,
) : BlockImpl {
    override val specId = "audio_volume"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val audio = audioProvider() ?: return Outcome.Fail(AUDIO_ABSENT)
        val stream = args["stream"].asAudioStreamOrDefault(AudioStream.VOICE_CALL)
            ?: return Outcome.Fail("audio_volume: unrecognized audio stream.")
        val level = audio.streamVolume(stream)
            ?: return Outcome.Fail("audio_volume: the ${stream.label} stream volume could not be read.")
        val writes = LinkedHashMap<String, Value>()
        node.outputs["varLevel"]?.bind(writes, Value.Num(level.toDouble()))
        val min = args["minLevel"].asNumOrNull()
        val max = args["maxLevel"].asNumOrNull()
        val within = (min == null || level >= min) && (max == null || level <= max)
        return Outcome.Proceed(if (within) Port.YES else Port.NO, writes)
    }
}

// --------------------------------------------------------------------------- audio_volume_set (effect)

/**
 * `audio_volume_set` (Audio volume set) — set an audio stream's volume.
 *
 * ACTION / EFFECT: it parses `stream` (default Voice call) and requires a numeric `level` — a volume set with
 * no level is a mistake the user must see, so an absent/non-numeric level Fails **by name** rather than
 * defaulting silently. It applies the change through the seam and routes the [AudioWrite]: OK on accept, a
 * named Fail carrying the reason (absent stream, level out of range, or a Do-Not-Disturb policy owning the
 * stream) on refusal — never a fabricated OK. The `showPopup`/`playSound` UI flags are not modelled (see file
 * KDoc).
 */
internal class AudioVolumeSetBlock(
    private val audioProvider: () -> AudioController?,
) : BlockImpl {
    override val specId = "audio_volume_set"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val audio = audioProvider() ?: return Outcome.Fail(AUDIO_ABSENT)
        val stream = args["stream"].asAudioStreamOrDefault(AudioStream.VOICE_CALL)
            ?: return Outcome.Fail("audio_volume_set: unrecognized audio stream.")
        val level = args["level"].asNumOrNull()
            ?: return Outcome.Fail("audio_volume_set needs a numeric volume level.")
        return audio.setStreamVolume(stream, level.toInt()).asOutcome("audio_volume_set")
    }
}

// --------------------------------------------------------------------------- audio_stream_set_mute (effect)

/**
 * `audio_stream_set_mute` (Audio stream set mute) — mute or unmute an audio stream.
 *
 * ACTION / EFFECT: it parses `stream` (default Ring) and reads the `state` flag (mute when set), then applies
 * the change through the seam and routes the [AudioWrite]: OK on accept, a named Fail carrying the reason (a
 * DND policy owning the stream, or a device predating the API 23 stream-mute effect) on refusal — never a
 * fabricated OK. The `showPopup`/`playSound` UI flags are not modelled (see file KDoc).
 */
internal class AudioStreamSetMuteBlock(
    private val audioProvider: () -> AudioController?,
) : BlockImpl {
    override val specId = "audio_stream_set_mute"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val audio = audioProvider() ?: return Outcome.Fail(AUDIO_ABSENT)
        val stream = args["stream"].asAudioStreamOrDefault(AudioStream.RING)
            ?: return Outcome.Fail("audio_stream_set_mute: unrecognized audio stream.")
        val muted = args["state"].asFlag()
        return audio.setStreamMuted(stream, muted).asOutcome("audio_stream_set_mute")
    }
}

// --------------------------------------------------------------------------- microphone_muted

/**
 * `microphone_muted` (Microphone muted) — is the microphone muted right now?
 *
 * DECISION: the one-shot form of the catalog's WATCH decision. It reads the microphone mute state through the
 * seam and routes YES when muted, NO otherwise. A `false` is a *real* "not muted" routed to NO; only a `null`
 * (no `AudioManager`) Fails **by name** — never a fabricated `false` a downstream block would mistake for a
 * real "not muted".
 */
internal class MicrophoneMutedBlock(
    private val audioProvider: () -> AudioController?,
) : BlockImpl {
    override val specId = "microphone_muted"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val audio = audioProvider() ?: return Outcome.Fail(AUDIO_ABSENT)
        val muted = audio.isMicrophoneMuted()
            ?: return Outcome.Fail("microphone_muted: the microphone mute state could not be read.")
        return Outcome.Proceed(if (muted) Port.YES else Port.NO)
    }
}

// --------------------------------------------------------------------------- microphone_set_mute (effect)

/**
 * `microphone_set_mute` (Microphone set mute) — mute or unmute the microphone.
 *
 * ACTION / EFFECT: it reads the `state` flag (mute when set), applies the change through the seam and routes
 * the [AudioWrite]: OK on accept, a named Fail carrying the reason (no `AudioManager`, or the set threw) on
 * refusal — never a fabricated OK.
 */
internal class MicrophoneSetMuteBlock(
    private val audioProvider: () -> AudioController?,
) : BlockImpl {
    override val specId = "microphone_set_mute"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val audio = audioProvider() ?: return Outcome.Fail(AUDIO_ABSENT)
        val muted = args["state"].asFlag()
        return audio.setMicrophoneMuted(muted).asOutcome("microphone_set_mute")
    }
}

// --------------------------------------------------------------------------- speakerphone_on

/**
 * `speakerphone_on` (Speakerphone on) — is the speakerphone on right now?
 *
 * DECISION: the one-shot form of the catalog's WATCH decision. It reads the speakerphone state through the
 * seam and routes YES when on, NO otherwise. A `false` is a *real* "off" routed to NO; only a `null` (no
 * `AudioManager`) Fails **by name** — never a fabricated `false`.
 */
internal class SpeakerphoneOnBlock(
    private val audioProvider: () -> AudioController?,
) : BlockImpl {
    override val specId = "speakerphone_on"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val audio = audioProvider() ?: return Outcome.Fail(AUDIO_ABSENT)
        val on = audio.isSpeakerphoneOn()
            ?: return Outcome.Fail("speakerphone_on: the speakerphone state could not be read.")
        return Outcome.Proceed(if (on) Port.YES else Port.NO)
    }
}

// --------------------------------------------------------------------------- speakerphone_set_state (effect)

/**
 * `speakerphone_set_state` (Speakerphone set state) — turn the speakerphone on or off.
 *
 * ACTION / EFFECT: it reads the `state` flag (on when set), applies the change through the seam and routes the
 * [AudioWrite]: OK on accept, a named Fail carrying the reason (no `AudioManager`, or the set threw) on
 * refusal — never a fabricated OK.
 */
internal class SpeakerphoneSetStateBlock(
    private val audioProvider: () -> AudioController?,
) : BlockImpl {
    override val specId = "speakerphone_set_state"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val audio = audioProvider() ?: return Outcome.Fail(AUDIO_ABSENT)
        val on = args["state"].asFlag()
        return audio.setSpeakerphoneOn(on).asOutcome("speakerphone_set_state")
    }
}

// --------------------------------------------------------------------------- composition + helpers

/**
 * The eight registered CameraAndSound audio impls, keyed by spec id, all sharing one [provider].
 *
 * Mirrors [telephonyLookup], [connectivityLookup], [locationLookup], [sensorLookup], [powerLookup],
 * [settingsLookup] and [appsLookup]: it always returns the map, and the honest gate is the per-block
 * gate-at-run (each fails with [AUDIO_ABSENT] when the provider yields no seam), so a caller composes over
 * its base registry exactly as the other categories do:
 *
 * ```
 * val audio = audioLookup(audioController)
 * fun lookup(id: String): BlockImpl? =
 *     audio[id] ?: telephony[id] ?: connectivity[id] ?: … ?: base.lookup(id)
 * ```
 *
 * ### What stays gated by omission, and why
 * The category's many remaining blocks are deliberately **not** here, so at run time the scheduler finds no
 * impl and gates them by the honest-by-omission mechanism the catalog's own `requires` set (or the block's
 * own shape) expresses. Because the [AudioController] seam is a volume/mode read+effect seam, every gated
 * block is a *capture*, a *recording*, a *screenshot*, a *media/tone/sound playback*, a *text-to-speech*, a
 * *vibration*, an *image bitmap op*, a *media-session read*, a *Bluetooth-audio route*, or a *picker* — none
 * of which a volume/mode seam can host. They are omitted on these honest grounds, grouped:
 *  - **Camera / photo / video capture (device-action tier).** `camera_available` (camera hardware read),
 *    `capture_image`, `capture_video` (hand off to a Camera app, produce a file), `take_picture`
 *    (`CAMERA`, in-process capture), `video_record_start` (`CAMERA`, `RECORD_AUDIO`, AWAIT),
 *    `video_record_stop`, `flashlight_enabled` and `flashlight_set_state` (camera flash) — camera-tier, not
 *    audio state.
 *  - **Audio recording / microphone capture (`RECORD_AUDIO`).** `audio_record_start` (AWAIT),
 *    `audio_record_stop`, `audio_device_recording` (recording-state read), `sound_level` (mic level),
 *    `speech_recognition` and `hotword_detected` (AWAIT) — capture-tier, produce audio/transcripts.
 *  - **Screenshot.** `screenshot` captures the screen to a file — a screen capture, not an audio read.
 *  - **Media / tone / sound playback (loads a file/URI, or an AWAIT player).** `sound_play` (AWAIT, plays a
 *    URI), `sound_stop`, `tone_play` (AWAIT), `tone_stop`, and `audio_player_control` (media-transport
 *    control) — playback a volume/mode seam cannot model.
 *  - **Media-session reads.** `media_playing` reads an active media session's rich metadata
 *    (title/album/artist/artwork/duration/position) — a notification-listener/media-session grant this
 *    unprivileged `AudioManager` seam cannot honestly serve (`isMusicActive` is a bare boolean with none of
 *    that metadata, so registering it here would fabricate the bound outputs).
 *  - **Text-to-speech.** `speak_play` (AWAIT), `speak_stop` and `speak_to_file` synthesize speech — a TTS
 *    engine action, not an audio state read.
 *  - **Vibration.** `vibrate_start` and `vibrate_stop` drive the haptics actuator — not an audio read.
 *  - **Image bitmap / codegen ops (load/write files, in-memory bitmap).** `barcode_scan`, `image_crop`,
 *    `image_flip`, `image_load`, `image_rescale`, `image_rotate`, `image_sample_color`, `image_unload`,
 *    `image_write`, `qrcode_generate` and `text_recognition` — image processing, not audio.
 *  - **Media store.** `media_store_add`, `media_store_remove` and `media_tags_read` touch the media store /
 *    file tags — not an audio state.
 *  - **Bluetooth audio routing (writes / permissioned).** `bluetooth_device_active_set`
 *    (`BLUETOOTH_CONNECT`) and `bluetooth_sco_set_state` route Bluetooth audio — a routing tier this slice
 *    does not model.
 *  - **Audio device enumeration.** `audio_device_connected` reads/filters connected audio devices (type /
 *    mode / brand-glob / address) — a device-enumeration read outside this bounded volume/mode slice.
 *  - **Picker (UI).** `ringtone_pick` drives a user-facing ringtone picker — not a one-shot state read.
 *
 * Note on collision avoidance: the ringer-mode read/set that a phone operator might expect here lives in the
 * Settings category (`ringer_mode` / `ringer_mode_set` in [settingsLookup]) and is **not** declared in
 * `CatalogCameraAndSound`, so it is neither registered nor duplicated by this lookup.
 */
fun audioLookup(provider: () -> AudioController?): Map<String, BlockImpl> = listOf(
    AudioStreamMutedBlock(provider),
    AudioVolumeBlock(provider),
    AudioVolumeSetBlock(provider),
    AudioStreamSetMuteBlock(provider),
    MicrophoneMutedBlock(provider),
    MicrophoneSetMuteBlock(provider),
    SpeakerphoneOnBlock(provider),
    SpeakerphoneSetStateBlock(provider),
).associateBy { it.specId }

/** Bind [value] under this non-blank output-variable name into [writes]; a blank name binds nothing. */
private fun String.bind(writes: MutableMap<String, Value>, value: Value) {
    if (isNotBlank()) writes[this] = value
}

/**
 * Turn an [AudioWrite] into the block's [Outcome]: OK when the device accepted the effect, or a named
 * [Outcome.Fail] carrying the seam's honest reason (an absent stream, a level out of range, a DND policy
 * owning the stream, no `AudioManager`) when it did not. This is the single place the "a refused effect is a
 * visible Fail, never a fabricated OK" rule lives, so every audio effect routes its result identically —
 * modelled on Settings' `SettingWrite.asOutcome`.
 */
private fun AudioWrite.asOutcome(blockId: String): Outcome =
    if (ok) Outcome.Proceed(Port.OK)
    else Outcome.Fail("$blockId: effect refused — ${reason ?: "no reason given"}.")

/**
 * A `stream` argument parsed to an [AudioStream]: [default] when blank/absent, the named/numeric stream when
 * recognized (the label, the obvious synonyms, and the Android `STREAM_*` numeric ids), or `null` when a
 * non-blank value names no known stream — which the caller turns into a visible Fail, never a silent default.
 */
private fun Value?.asAudioStreamOrDefault(default: AudioStream): AudioStream? {
    val text = this.asTextOrNull()?.trim()
    if (text.isNullOrEmpty()) return default
    return when (text.lowercase()) {
        "voice call", "voice_call", "voicecall", "call", "0" -> AudioStream.VOICE_CALL
        "system", "1" -> AudioStream.SYSTEM
        "ring", "2" -> AudioStream.RING
        "media", "music", "3" -> AudioStream.MEDIA
        "alarm", "4" -> AudioStream.ALARM
        "notification", "5" -> AudioStream.NOTIFICATION
        "dtmf", "8" -> AudioStream.DTMF
        "accessibility", "10" -> AudioStream.ACCESSIBILITY
        else -> null
    }
}

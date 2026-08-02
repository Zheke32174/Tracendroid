package dev.pleiades.masamune.flow.catalog

import dev.pleiades.masamune.flow.model.BlockCategory
import dev.pleiades.masamune.flow.model.BlockSpec

/**
 * Audio routing and volume, the camera, still images, speech and vibration.
 *
 * The permission split here is finer than it looks. `Capture image` and `Capture video` hand
 * off to whatever camera app the user has and so need no camera permission at all, while
 * `Take picture` and `Video record` open the camera in-process and do. Copying one gate onto
 * the other would either block a working block or let a broken one into a flow.
 *
 * Blocks are listed in Automate's own palette order, which is the order this catalog and the
 * palette both render. See `docs/donors/RE-automate.md`.
 */
internal val CAMERA_AND_SOUND_BLOCKS: List<BlockSpec> = category(BlockCategory.CAMERA_AND_SOUND) {
    audioDevicesStreamsAndVolume()
    cameraFlashlightAndImages()
    mediaSpeechVibrationAndRecording()
}

/** Audio device routing, stream mute state and volume. */
private fun Blocks.audioDevicesStreamsAndVolume() {
    decision(
        "audio_device_connected", "Audio device connected",
        "Checks if an audio device is connected, or disconnected.",
        proceed = WATCH,
        args = listOf(
            any("deviceType", "Device type", "any"),
            any("deviceMode", "Device mode", "any"),
            text("deviceBrand", "Device brand", "any, may contain glob pattern"),
            text("deviceAddress", "Device address", "any"),
        ),
        outputs = listOf(
            out("varConnectedDeviceId", "Connected device id"),
            out("varConnectedDeviceType", "Connected device type"),
            out("varConnectedDeviceMode", "Connected device mode"),
            out("varConnectedDeviceBrand", "Connected device brand"),
            out("varConnectedDeviceAddress", "Connected device address"),
        ),
    )
    decision(
        "audio_device_recording", "Audio device recording",
        "Checks if an audio device is recording.",
        proceed = WATCH,
        args = listOf(
            any("deviceType", "Device type", "any"),
            text("deviceBrand", "Device brand", "any, may contain glob pattern"),
            any("audioSource", "Audio source", "any"),
        ),
        outputs = listOf(
            out("varRecordingDeviceType", "Recording device type"),
            out("varRecordingDeviceBrand", "Recording device brand"),
            out("varRecordedAudioSource", "Recorded audio stream"),
        ),
    )
    action(
        "audio_player_control", "Audio player control",
        "Controls an audio player.",
        args = listOf(
            text("command", "Command", "Play/Pause"),
            text("position", "Seek position", "to beginning of clip"),
            any("method", "Method", "Broadcast"),
            text("packageName", "Package", "any"),
            text("className", "Receiver class"),
        ),
    )
    action(
        "audio_record_start", "Audio record",
        "Records audio.",
        proceed = AWAIT,
        args = listOf(
            any("source", "Audio source", "Default"),
            any("audioDeviceId", "Audio device", "depends on source"),
            any("encoding", "Audio encoding", "AAC-LC"),
            any("quality", "Audio quality", "100%"),
            num("maxDuration", "Maximum duration", "no limit"),
            text("targetPath", "Destination path", "a file in the \"Notifications\" directory"),
            any("focus", "Audio focus", "Transient exclusive"),
            any("notificationChannelId", "Notification channel", "no notification"),
        ),
        outputs = listOf(
            out("varAudioFile", "Audio file"),
        ),
        requires = setOf(RECORD_AUDIO),
    )
    action(
        "audio_record_stop", "Audio record stop",
        "Stops any ongoing audio recordings started by the Audio record block.",
    )
    decision(
        "audio_stream_muted", "Audio stream muted",
        "Checks if an audio stream is currently muted.",
        args = listOf(
            any("stream", "Audio stream", "Ring"),
        ),
    )
    action(
        "audio_stream_set_mute", "Audio stream set mute",
        "Mutes or unmute an audio stream.",
        args = listOf(
            flag("state", "State"),
            any("stream", "Audio stream", "Ring"),
            flag("showPopup", "Popup"),
            flag("playSound", "Test sound"),
        ),
    )
    decision(
        "audio_volume", "Audio volume",
        "Checks the volume setting for an audio stream.",
        proceed = WATCH,
        args = listOf(
            num("minLevel", "Minimum volume"),
            num("maxLevel", "Maximum volume"),
            any("stream", "Audio stream", "Voice call"),
        ),
        outputs = listOf(
            out("varLevel", "Current volume"),
        ),
    )
    action(
        "audio_volume_set", "Audio volume set",
        "Changes the volume setting for an audio stream.",
        args = listOf(
            any("level", "Volume"),
            any("stream", "Audio stream", "Voice call"),
            flag("showPopup", "Popup"),
            flag("playSound", "Test sound"),
        ),
    )
    action(
        "barcode_scan", "Barcode scan",
        "Scans barcodes in an image.",
        args = listOf(
            text("uri", "Image URI"),
            any("format", "Format", "all formats"),
        ),
        outputs = listOf(
            out("varRawValues", "Content"),
            out("varFormats", "Formats"),
            out("varBoundingBoxes", "Bounding boxes"),
        ),
    )
    decision(
        "bluetooth_device_active_set", "Bluetooth device active set",
        "Sets or clear the active device for a particular Bluetooth profile, e.g. as used " +
            "for audio routing. The NO path is followed if the device was not found, activation " +
            "was unsuccessful, or Bluetooth was disabled.",
        args = listOf(
            text("deviceAddress", "Device address"),
            text("deviceName", "Device name"),
            any("profile", "Device profile", "Headset"),
        ),
        requires = setOf(BLUETOOTH_CONNECT),
    )
    action(
        "bluetooth_sco_set_state", "Bluetooth SCO set state",
        "Enables or disables Bluetooth SCO audio routing.",
        args = listOf(
            flag("state", "Bluetooth SCO"),
            flag("reenable", "Keep enabled"),
        ),
    )
}

/** Camera availability and capture, the flashlight, and in-memory image editing. */
private fun Blocks.cameraFlashlightAndImages() {
    decision(
        "camera_available", "Camera available",
        "Checks if a camera is available.",
        proceed = WATCH,
        args = listOf(
            any("cameraId", "Camera", "any camera"),
        ),
        outputs = listOf(
            out("varCameraId", "Camera id"),
        ),
    )
    decision(
        "capture_image", "Capture image",
        "Starts a Camera app and lets the user take a picture.",
        args = listOf(
            text("targetPath", "Destination path", "a JPEG file in the \"DCIM\" directory"),
            text("packageName", "Package", "system preferred Camera app"),
            num("timeout", "Timeout", "no timeout"),
            any("notificationChannelId", "Notification channel", "the flow default or Flow"),
            flag("startActivity", "Show window"),
        ),
        outputs = listOf(
            out("varImageFile", "Image file"),
        ),
    )
    decision(
        "capture_video", "Capture video",
        "Starts a Camera app and lets the user record a video.",
        args = listOf(
            any("quality", "Video quality", "decided by Camera app"),
            num("maxDuration", "Maximum duration", "decided by Camera app"),
            num("maxFileSize", "Maximum file size", "decided by Camera app"),
            text("targetPath", "Destination path", "a MP4 file in the \"DCIM\" directory"),
            text("packageName", "Package", "system preferred Camera app"),
            num("timeout", "Timeout", "no timeout"),
            any("notificationChannelId", "Notification channel", "the flow default or Flow"),
            flag("startActivity", "Show window"),
        ),
        outputs = listOf(
            out("varVideoFile", "Video file"),
        ),
    )
    decision(
        "flashlight_enabled", "Flashlight enabled",
        "Checks if the camera flash LED light is turned on or off.",
        proceed = WATCH,
        args = listOf(
            any("cameraId", "Camera", "likely the back-facing camera"),
        ),
    )
    action(
        "flashlight_set_state", "Flashlight set state",
        "Turns the camera flash LED light on or off.",
        args = listOf(
            flag("state", "Flashlight"),
            any("cameraId", "Camera", "likely the back-facing camera"),
            num("strength", "Strength", "the default for camera"),
        ),
    )
    action(
        "hotword_detected", "Hotword detected",
        "Awaits a spoken hotword.",
        requires = setOf(RECORD_AUDIO),
    )
    action(
        "image_crop", "Image crop",
        "Crops an image.",
        args = listOf(
            num("croppedLeft", "Crop left", "0"),
            num("croppedTop", "Crop top", "0"),
            num("croppedWidth", "Cropped width", "all"),
            num("croppedHeight", "Cropped height", "all"),
        ),
        outputs = listOf(
            out("varResultWidth", "Result width"),
            out("varResultHeight", "Result height"),
        ),
    )
    action(
        "image_flip", "Image flip",
        "Flips an image.",
        args = listOf(
            flag("axis", "Flip", "no flip"),
        ),
    )
    action(
        "image_load", "Image load",
        "Loads an image file into a memory bitmap.",
        args = listOf(
            text("uri", "Content URI"),
        ),
        outputs = listOf(
            out("varWidth", "Width"),
            out("varHeight", "Height"),
            out("varMimeType", "MIME type"),
        ),
    )
    action(
        "image_rescale", "Image rescale",
        "Rescales an image.",
        args = listOf(
            num("scaledWidth", "Scaled width", "current bitmap width, i"),
            num("scaledHeight", "Scaled height", "current bitmap height i"),
        ),
        outputs = listOf(
            out("varResultWidth", "Result width"),
            out("varResultHeight", "Result height"),
        ),
    )
    action(
        "image_rotate", "Image rotate",
        "Rotates an image.",
        args = listOf(
            num("rotation", "Rotation", "0, i"),
        ),
        outputs = listOf(
            out("varResultWidth", "Result width"),
            out("varResultHeight", "Result height"),
        ),
    )
    action(
        "image_sample_color", "Image sample color",
        "Samples the color of pixels in an image.",
        args = listOf(
            num("centerX", "Sample X"),
            num("centerY", "Sample Y"),
            num("sampleSize", "Sample size", "1"),
        ),
        outputs = listOf(
            out("varColorModel", "Color model"),
            out("varSampledComponents", "Sampled color components"),
            out("varSampledAlpha", "Sampled alpha"),
        ),
    )
    action(
        "image_unload", "Image unload",
        "Frees the memory used by an loaded bitmap.",
    )
    action(
        "image_write", "Image write",
        "Writes an image file of the bitmap help in memory.",
        args = listOf(
            text("targetPath", "Destination path", "a file in the \"DCIM\" directory"),
            any("mimeType", "MIME type", "that of the loaded image"),
            num("quality", "Quality", "100%"),
        ),
        outputs = listOf(
            out("varImageFile", "Image file"),
        ),
    )
}

/** Media playback and tags, speech, tones, vibration and recording. */
private fun Blocks.mediaSpeechVibrationAndRecording() {
    decision(
        "media_playing", "Media playing",
        "Checks audio or video playback. The YES path is followed when playback starts or, " +
            "if the player report it, a track was skipped.",
        proceed = WATCH,
        args = listOf(
            text("packageName", "Media player package"),
        ),
        outputs = listOf(
            out("varTitle", "Media title"),
            out("varAlbum", "Media album"),
            out("varArtist", "Media artist"),
            out("varArtworkUri", "Media artwork URI"),
            out("varDuration", "Media duration"),
            out("varPosition", "Current position"),
            out("varPackageName", "Media player package"),
        ),
    )
    action(
        "media_store_add", "Media store add",
        "Adds files to the media store so they show up in the Gallery and Music app.",
        args = listOf(
            text("path", "Path"),
        ),
    )
    action(
        "media_store_remove", "Media store remove",
        "Removes files from the media store, so they don't show up in the Gallery and Music " +
            "app.",
        args = listOf(
            text("path", "Path"),
        ),
    )
    action(
        "media_tags_read", "Media tags read",
        "Reads metadata tags from media content such as audio, video or image.",
        args = listOf(
            text("uri", "Content URI"),
        ),
        outputs = listOf(
            out("varTitle", "Media title"),
            out("varAlbum", "Media album"),
            out("varArtist", "Media artist"),
            out("varGenre", "Media genre"),
            out("varDuration", "Media duration"),
            out("varTrackNumber", "Media track number"),
            out("varReleaseDate", "Media release date"),
            out("varLatitude", "Media latitude"),
            out("varLongitude", "Media longitude"),
            out("varOrientation", "Media orientation"),
        ),
    )
    decision(
        "microphone_muted", "Microphone muted",
        "Checks if the microphone is currently muted.",
        proceed = WATCH,
    )
    action(
        "microphone_set_mute", "Microphone set mute",
        "Mutes or unmute the microphone.",
        args = listOf(
            flag("state", "Microphone"),
        ),
    )
    action(
        "qrcode_generate", "QR code generate",
        "Generates an image of a QR code.",
        args = listOf(
            any("content", "Content"),
            any("errorCorrectionLevel", "Error correction", "Medium"),
            num("padding", "Padding", "1"),
            any("mimeType", "MIME type", "an PNG image"),
            text("targetPath", "Destination path", "a file in the \"DCIM\" directory"),
        ),
        outputs = listOf(
            out("varImageFile", "Image file"),
        ),
    )
    decision(
        "ringtone_pick", "Ringtone pick",
        "Lets the user choose a ringtone sound.",
        args = listOf(
            any("ringtoneTypes", "Ringtone types", "all"),
            flag("showSilence", "Silence"),
            num("timeout", "Timeout", "no timeout"),
            any("notificationChannelId", "Notification channel", "the flow default or Flow"),
            flag("startActivity", "Show window"),
        ),
        outputs = listOf(
            out("varSoundUri", "Sound URI"),
        ),
    )
    action(
        "screenshot", "Screenshot",
        "Takes a screenshot, a capture of the current screen. Needed the privileged service " +
            "only on Android 4 and lower, so it is not gated on it here.",
        args = listOf(
            any("displayId", "Display id", "id of the primary display"),
            text("targetPath", "Destination path", "a PNG file in the \"DCIM\" directory"),
        ),
        outputs = listOf(
            out("varImageFile", "Image file"),
        ),
    )
    decision(
        "sound_level", "Sound level",
        "Checks the surrounding or internal mixers sound level.",
        proceed = WATCH,
        args = listOf(
            any("source", "Audio source", "Default"),
            any("audioDeviceId", "Audio device", "depends on source"),
            num("minLevel", "Minimum level"),
            num("maxLevel", "Maximum level"),
        ),
        outputs = listOf(
            out("varLevel", "Current level"),
        ),
        requires = setOf(RECORD_AUDIO),
    )
    action(
        "sound_play", "Sound play",
        "Plays a sound.",
        proceed = AWAIT,
        args = listOf(
            text("uri", "Sound URI"),
            any("stream", "Audio stream", "Notification"),
            any("volume", "Volume", "100%"),
            any("speed", "Speed", "100%"),
            any("pitch", "Pitch", "100%"),
            any("position", "Start position", "to beginning of clip"),
            flag("repeat", "Repeat"),
            any("focus", "Audio focus", "Normal for Music stream, otherwise Transient may duck"),
            any("notificationChannelId", "Notification channel", "no notification"),
        ),
    )
    action(
        "sound_stop", "Sound stop",
        "Stops any ongoing sound playback started by the Sound play block.",
        outputs = listOf(
            out("varStopPosition", "Stop position"),
        ),
    )
    action(
        "speak_play", "Speak",
        "Speaks a message using text-to-speech (TTS) to an audio stream.",
        proceed = AWAIT,
        args = listOf(
            text("message", "Message"),
            any("rate", "Rate", "100%"),
            text("language", "Language", "the device language"),
            any("engine", "Engine", "the built-in engine"),
            flag("offline", "Offline"),
            any("stream", "Audio stream", "Notification"),
            any("volume", "Volume", "100%"),
            any("focus", "Audio focus", "Transient may duck"),
            any("notificationChannelId", "Notification channel", "no notification"),
        ),
    )
    action(
        "speak_stop", "Speak stop",
        "Stops any ongoing text-to-speech playback started by the Speak block.",
    )
    action(
        "speak_to_file", "Speak to file",
        "Speaks a message using text-to-speech (TTS) to an audio file.",
        args = listOf(
            text("message", "Message"),
            any("rate", "Rate", "100%"),
            text("language", "Language", "the device language"),
            any("engine", "Engine", "the built-in engine"),
            flag("offline", "Offline"),
            text("path", "Path", "a WAV file in the \"Notifications\" directory"),
        ),
        outputs = listOf(
            out("varAudioFile", "Audio file"),
        ),
    )
    decision(
        "speakerphone_on", "Speakerphone on",
        "Checks if the speakerphone is currently turned on.",
        proceed = WATCH,
    )
    action(
        "speakerphone_set_state", "Speakerphone set state",
        "Enables or disable the speakerphone.",
        args = listOf(
            flag("state", "Speakerphone"),
        ),
    )
    action(
        "speech_recognition", "Speech recognition",
        "Records your speech and transcribes the spoken word into text.",
        args = listOf(
            any("language", "Language", "the device language"),
            any("model", "Model", "Free form"),
            flag("formatting", "Formatting", "no"),
            num("silenceDuration", "Maximum silence duration", "depends on engine (~0"),
            flag("offline", "Offline", "no"),
        ),
        outputs = listOf(
            out("varSpokenTexts", "Spoken texts"),
            out("varConfidenceScores", "Confidence scores"),
        ),
        requires = setOf(RECORD_AUDIO),
    )
    action(
        "take_picture", "Take picture",
        "Takes a picture without user interaction.",
        args = listOf(
            any("camera", "Camera", "likely the back-facing camera"),
            any("pictureSize", "Picture size"),
            num("quality", "JPEG quality"),
            num("rotation", "Rotation", "screen orientation"),
            num("zoom", "Zoom", "0%"),
            any("flashMode", "Flash mode"),
            any("focusMode", "Focus mode"),
            any("sceneMode", "Scene mode"),
            any("whiteBalance", "White balance"),
            any("colorEffect", "Color effect"),
            num("shutterDelay", "Shutter delay", "no delay"),
            num("exifLatitude", "Exif latitude"),
            num("exifLongitude", "Exif longitude"),
            text("targetPath", "Destination path", "a JPEG file in the \"DCIM\" directory"),
            flag("quiet", "Quiet", "to play sound"),
        ),
        outputs = listOf(
            out("varImageFile", "Image file"),
        ),
        requires = setOf(CAMERA),
    )
    action(
        "text_recognition", "Text recognition",
        "Recognizes text in an image, Optical Character Recognition (OCR).",
        args = listOf(
            text("uri", "Image URI"),
            any("language", "Language hint", "the device language"),
        ),
        outputs = listOf(
            out("varTextBlocks", "Text blocks"),
            out("varConfidenceScores", "Confidence scores"),
            out("varRecognizedLanguages", "Recognized languages"),
            out("varBoundingBoxes", "Bounding boxes"),
        ),
    )
    action(
        "tone_play", "Tone play",
        "Plays a tone.",
        proceed = AWAIT,
        args = listOf(
            any("tone", "Tone", "Proprietary beep"),
            any("stream", "Audio stream", "DTMF"),
            any("volume", "Volume", "100%"),
            num("duration", "Duration", "tone length"),
        ),
    )
    action(
        "tone_stop", "Tone stop",
        "Stops any ongoing tone playback started by the Tone play block.",
    )
    action(
        "vibrate_start", "Vibrate",
        "Vibrates the device.",
        args = listOf(
            arr("pattern", "Vibration pattern"),
            flag("repeat", "Repeat"),
        ),
    )
    action(
        "vibrate_stop", "Vibrate stop",
        "Stops any ongoing vibration started by the Vibrate block.",
    )
    action(
        "video_record_start", "Video record",
        "Records video.",
        proceed = AWAIT,
        args = listOf(
            any("camera", "Camera"),
            any("audioSource", "Audio source", "Camcorder"),
            any("profile", "Camcorder profile", "High"),
            num("rotation", "Rotation", "screen orientation"),
            num("zoom", "Zoom", "100%"),
            any("flashMode", "Flash mode"),
            any("focusMode", "Focus mode"),
            any("whiteBalance", "White balance"),
            any("colorEffect", "Color effect"),
            flag("stabilization", "Stabilization"),
            num("maxDuration", "Maximum duration", "no limit"),
            num("maxFileSize", "Maximum file size", "no limit"),
            text(
                "targetPath", "Destination path",
                "stored in the Android \"DCIM\" directory, file type depends on the profile",
            ),
            any("audioFocus", "Audio focus", "Transient exclusive"),
            any("notificationChannelId", "Notification channel", "no notification"),
            flag("quiet", "Quiet", "to play sound"),
        ),
        outputs = listOf(
            out("varVideoFile", "Video file"),
        ),
        requires = setOf(CAMERA, RECORD_AUDIO),
    )
    action(
        "video_record_stop", "Video record stop",
        "Stops any ongoing audio recordings started by the Video record block.",
    )
}

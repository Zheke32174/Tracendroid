package com.ai.assistance.operit.core.avatar.impl.dragonbones.control

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.ai.assistance.operit.core.avatar.common.control.AvatarController
import com.ai.assistance.operit.core.avatar.common.control.AvatarSettingKeys
import com.ai.assistance.operit.core.avatar.common.state.AvatarEmotion
import com.ai.assistance.operit.core.avatar.common.state.AvatarMoodTypes
import com.ai.assistance.operit.core.avatar.common.state.AvatarState
import com.dragonbones.JniBridge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.dragonbones.DragonBonesController as DragonBonesLibController

/**
 * A concrete implementation of [AvatarController] for DragonBones avatars.
 * It wraps the library-specific [DragonBonesLibController] to expose
 * a standardized API for avatar control.
 *
 * @param libController The underlying controller from the DragonBones rendering library.
 */
class DragonBonesAvatarController(
    val libController: DragonBonesLibController
) : AvatarController {

    private val _state = MutableStateFlow(AvatarState())
    override val state: StateFlow<AvatarState> = _state.asStateFlow()

    override val availableAnimations: List<String>
        get() = libController.animationNames

    private var emotionAnimationMapping: Map<AvatarEmotion, String> = emptyMap()
    private var triggerAnimationMapping: Map<String, String> = emptyMap()

    /**
     * Normalized emotion intensity (0f subtle .. 1f emphatic), driven from an upstream
     * `<mood weight>` value via [setEmotionIntensity]. Applied to the fade-in time of
     * subsequent emotion/trigger playback so higher intensity looks snappier / more
     * emphatic. Defaults to a neutral mid value so behavior is unchanged until a caller
     * sets it.
     */
    private var emotionIntensity: Float = DEFAULT_EMOTION_INTENSITY

    /** Tracks whether the mouth bone is currently displaced, so we only reset when needed. */
    private var mouthIsOpen: Boolean = false

    override fun setEmotion(newEmotion: AvatarEmotion) {
        playEmotion(newEmotion, loop = 0)
    }

    override fun playEmotion(emotion: AvatarEmotion, loop: Int) {
        val animationName = resolveAnimationForEmotion(emotion) ?: return

        // Play on the base layer with an intensity-scaled fade-in so a high-intensity mood
        // snaps in and a subtle one eases in. loop maps 1:1 onto fadeInAnimation (0 = infinite).
        libController.fadeInAnimation(
            name = animationName,
            layer = 0,
            loop = loop,
            fadeInTime = intensityFadeInSeconds()
        )
        _state.value = _state.value.copy(
            emotion = emotion,
            currentAnimation = animationName,
            isLooping = loop == 0
        )
    }

    override fun playTrigger(triggerName: String, loop: Int): Boolean {
        val normalizedTrigger = AvatarMoodTypes.normalizeKey(triggerName)
        val animationName = resolveAnimationForTrigger(normalizedTrigger) ?: return false

        libController.fadeInAnimation(
            name = animationName,
            layer = 0,
            loop = loop,
            fadeInTime = intensityFadeInSeconds()
        )
        _state.value = _state.value.copy(
            emotion =
                AvatarMoodTypes.builtInFallbackEmotion(normalizedTrigger)
                    ?: resolveEmotionFromAnimationName(animationName)
                    ?: _state.value.emotion,
            currentAnimation = animationName,
            isLooping = loop == 0
        )
        return true
    }

    override fun estimateEmotionDurationMillis(emotion: AvatarEmotion): Long? {
        val animationName = resolveAnimationForEmotion(emotion) ?: return null
        val durationSeconds = JniBridge.getAnimationDuration(animationName)
        if (!durationSeconds.isFinite() || durationSeconds <= 0f) {
            return null
        }

        return (durationSeconds * 1000f).toLong().coerceAtLeast(1L)
    }

    override fun estimateTriggerDurationMillis(triggerName: String): Long? {
        val animationName =
            resolveAnimationForTrigger(AvatarMoodTypes.normalizeKey(triggerName)) ?: return null
        val durationSeconds = JniBridge.getAnimationDuration(animationName)
        if (!durationSeconds.isFinite() || durationSeconds <= 0f) {
            return null
        }

        return (durationSeconds * 1000f).toLong().coerceAtLeast(1L)
    }

    override fun playAnimation(animationName: String, loop: Int) {
        if (availableAnimations.contains(animationName)) {
            libController.playAnimation(animationName, loop.toFloat())
            _state.value = _state.value.copy(
                currentAnimation = animationName,
                isLooping = loop == 0
            )
        }
    }

    /**
     * Gaze / look-at. Reuses the model's IK target bone (the same [IK_TARGET_BONE_NAME]
     * bone the drag gesture drives) so the head/eyes track a normalized point without any
     * new native entry points. Coordinates are normalized (-1..1); (0,0) recenters the
     * gaze. If the model has no such bone, [DragonBonesController.overrideBonePosition]
     * queues a native call that is a harmless no-op, so this never crashes.
     *
     * @param x Normalized horizontal target, -1 (left) .. 1 (right).
     * @param y Normalized vertical target, -1 (up) .. 1 (down).
     */
    override fun lookAt(x: Float, y: Float) {
        try {
            val clampedX = x.coerceIn(-1f, 1f)
            val clampedY = y.coerceIn(-1f, 1f)
            if (clampedX == 0f && clampedY == 0f) {
                libController.resetBone(IK_TARGET_BONE_NAME)
                return
            }
            // Map normalized coords to a modest bone displacement around the rig origin.
            libController.overrideBonePosition(
                IK_TARGET_BONE_NAME,
                clampedX * GAZE_RANGE_PX,
                clampedY * GAZE_RANGE_PX
            )
        } catch (_: Exception) {
            // Bone may not exist for this model; ignore rather than crash.
        }
    }

    /**
     * Lip-sync. Displaces a mouth bone by [openAmount] so the mouth opens while speaking
     * and closes when done. Tries a small list of common mouth-bone names and stops at the
     * first that the native side accepts; when none exist the native calls are no-ops, so
     * this is safe on any rig. Amplitude-accurate lip-sync (driving [openAmount] from live
     * audio RMS) is a documented follow-up — v1 is a speaking on/off drive.
     *
     * @param openAmount 0f (closed) .. 1f (fully open).
     */
    override fun lipSync(openAmount: Float) {
        try {
            val amount = openAmount.coerceIn(0f, 1f)
            if (amount <= MOUTH_CLOSED_EPSILON) {
                if (mouthIsOpen) {
                    MOUTH_BONE_CANDIDATES.forEach { bone ->
                        libController.resetBone(bone)
                    }
                    mouthIsOpen = false
                }
                return
            }
            // Open the mouth: push candidate mouth bones downward proportionally.
            val offsetY = amount * MOUTH_OPEN_RANGE_PX
            MOUTH_BONE_CANDIDATES.forEach { bone ->
                libController.overrideBonePosition(bone, 0f, offsetY)
            }
            mouthIsOpen = true
        } catch (_: Exception) {
            // Mouth bone may not exist for this model; ignore rather than crash.
        }
    }

    /**
     * Emotion intensity. Stores a normalized 0f..1f value used to modulate the fade-in
     * time of subsequent emotion / trigger playback: higher intensity yields a snappier,
     * more emphatic transition. Non-finite values fall back to the neutral default.
     */
    override fun setEmotionIntensity(intensity: Float) {
        emotionIntensity =
            if (intensity.isFinite()) intensity.coerceIn(0f, 1f) else DEFAULT_EMOTION_INTENSITY
    }

    /**
     * Maps the current [emotionIntensity] to a fade-in time in seconds: a high-intensity
     * mood snaps in quickly (short fade), a subtle one eases in (longer fade). Used by the
     * intensity-aware playback path so callers get emphatic transitions without any new
     * native capability.
     */
    private fun intensityFadeInSeconds(): Float {
        // intensity 1f -> MIN fade (snappy); intensity 0f -> MAX fade (gentle).
        return MAX_FADE_IN_SECONDS -
            (emotionIntensity.coerceIn(0f, 1f) * (MAX_FADE_IN_SECONDS - MIN_FADE_IN_SECONDS))
    }

    override fun updateSettings(settings: Map<String, Any>) {
        settings[AvatarSettingKeys.SCALE]
            ?.let { it as? Number }
            ?.toFloat()
            ?.let { scale ->
                val normalizedScale =
                    if (scale.isFinite()) {
                        scale.coerceIn(0.1f, 5.0f)
                    } else {
                        0.5f
                    }
                libController.scale = normalizedScale
            }

        settings[AvatarSettingKeys.TRANSLATE_X]
            ?.let { it as? Number }
            ?.toFloat()
            ?.let { translateX ->
                if (translateX.isFinite()) {
                    libController.translationX = translateX.coerceIn(-2000f, 2000f)
                }
            }

        settings[AvatarSettingKeys.TRANSLATE_Y]
            ?.let { it as? Number }
            ?.toFloat()
            ?.let { translateY ->
                if (translateY.isFinite()) {
                    libController.translationY = translateY.coerceIn(-2000f, 2000f)
                }
            }
    }

    override fun updateEmotionAnimationMapping(mapping: Map<AvatarEmotion, String>) {
        emotionAnimationMapping = mapping
            .mapValues { (_, animationName) -> animationName.trim() }
            .filterValues { animationName -> animationName.isNotBlank() }
    }

    override fun updateTriggerAnimationMapping(mapping: Map<String, String>) {
        triggerAnimationMapping =
            mapping.entries.mapNotNull { (rawKey, rawAnimationName) ->
                val key = AvatarMoodTypes.normalizeKey(rawKey)
                val animationName = rawAnimationName.trim()
                if (key.isBlank() || animationName.isBlank()) {
                    return@mapNotNull null
                }
                key to animationName
            }.toMap()
    }

    private fun resolveAnimationForEmotion(emotion: AvatarEmotion): String? {
        val preferred = emotionAnimationMapping[emotion]
        if (!preferred.isNullOrBlank() && availableAnimations.contains(preferred)) {
            return preferred
        }

        val directName = emotion.name.lowercase()
        if (availableAnimations.contains(directName)) {
            return directName
        }

        if (emotion != AvatarEmotion.IDLE) {
            val idleFallback = emotionAnimationMapping[AvatarEmotion.IDLE]
            if (!idleFallback.isNullOrBlank() && availableAnimations.contains(idleFallback)) {
                return idleFallback
            }

            val idleName = AvatarEmotion.IDLE.name.lowercase()
            if (availableAnimations.contains(idleName)) {
                return idleName
            }
        }

        return null
    }

    private fun resolveAnimationForTrigger(triggerName: String): String? {
        val preferred = triggerAnimationMapping[triggerName]
        if (!preferred.isNullOrBlank() && availableAnimations.contains(preferred)) {
            return preferred
        }

        if (availableAnimations.contains(triggerName)) {
            return triggerName
        }

        return null
    }

    private fun resolveEmotionFromAnimationName(animationName: String): AvatarEmotion? {
        return AvatarEmotion.values().firstOrNull { emotion ->
            emotion.name.lowercase() == animationName
        }
    }

    private companion object {
        /** IK target bone reused for gaze; matches the drag gesture's bone name. */
        const val IK_TARGET_BONE_NAME = "ik_target"

        /** Candidate mouth-bone names, tried in order for lip-sync. Missing ones no-op. */
        val MOUTH_BONE_CANDIDATES = listOf("mouth", "ik_mouth", "jaw", "lip")

        /** Below this openAmount the mouth is treated as closed. */
        const val MOUTH_CLOSED_EPSILON = 0.05f

        /** Max downward bone displacement (rig units) at fully-open mouth. */
        const val MOUTH_OPEN_RANGE_PX = 40f

        /** Max bone displacement (rig units) for a full-deflection gaze. */
        const val GAZE_RANGE_PX = 120f

        /** Neutral emotion intensity used until a caller sets one. */
        const val DEFAULT_EMOTION_INTENSITY = 0.5f

        /** Fade-in bounds (seconds) mapped from emotion intensity. */
        const val MIN_FADE_IN_SECONDS = 0.08f
        const val MAX_FADE_IN_SECONDS = 0.45f
    }
}

/**
 * A Composable function to create and remember a [DragonBonesAvatarController].
 * This follows the standard pattern for creating controllers in Jetpack Compose.
 * It ensures the controller instance is preserved across recompositions.
 *
 * @return An instance of [DragonBonesAvatarController].
 */
@Composable
fun rememberDragonBonesAvatarController(): DragonBonesAvatarController {
    val libController = com.dragonbones.rememberDragonBonesController()
    return remember { DragonBonesAvatarController(libController) }
} 

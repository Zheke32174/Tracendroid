package com.ai.assistance.operit.core.avatar.common.control

import com.ai.assistance.operit.core.avatar.common.state.AvatarActivity
import com.ai.assistance.operit.core.avatar.common.state.AvatarAnimationState
import com.ai.assistance.operit.core.avatar.common.state.AvatarEmotion
import com.ai.assistance.operit.core.avatar.common.state.AvatarState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A universal interface for controlling an avatar's state and behavior.
 * This abstracts away the specific implementation details of the rendering engine.
 */
interface AvatarController {

    /** A flow representing the current state of the avatar. UI components can collect this flow to react to state changes. */
    val state: StateFlow<AvatarState>

    /**
     * The living-portrait animation intent (idle / thinking / listening / talking + expression).
     *
     * This is the signal the AI/chat layer pushes into via [onSpeakStart]/[onSpeakEnd]/
     * [onThinking]/[onListening]/[onIdle], and that renderers collect to drive
     * speech-reactive motion on a flat avatar. Controllers that do not implement a
     * living-portrait layer (e.g. skeletal 3D renderers with their own animations)
     * can simply ignore these hooks; the default here keeps a constant IDLE so callers
     * never crash.
     *
     * HONEST: on a single-frame photo this drives reactive motion (breathing / blink /
     * speaking bob / glow), NOT rigged phoneme lip-sync.
     */
    val animationState: StateFlow<AvatarAnimationState>
        get() = DEFAULT_IDLE_ANIMATION_STATE

    /**
     * Signals that TTS started speaking. Renderers should switch to a speaking motion.
     * @param intensity Optional 0f..1f drive amplitude for the speaking motion.
     */
    fun onSpeakStart(intensity: Float = DEFAULT_SPEAK_INTENSITY) {}

    /** Signals that TTS stopped speaking. Renderers should relax back toward idle. */
    fun onSpeakEnd() {}

    /** Signals that the AI is generating / tool-running (contemplative motion). */
    fun onThinking() {}

    /** Signals that the STT layer is actively listening to the user (attentive motion). */
    fun onListening() {}

    /** Signals a return to the resting state (breathing + blink only). */
    fun onIdle() {}

    /**
     * Sets the current expression key used by the living-portrait layer and as the
     * extension point for future per-expression image frames. Typically an
     * [AvatarEmotion] name lowercased or a custom mood key.
     */
    fun setExpression(expression: String) {}

    /** For skeletal models, this provides a list of all available animation names. For other types, it may be empty. */
    val availableAnimations: List<String>

    /**
     * Sets the avatar's emotional state.
     * The controller's implementation is responsible for selecting and playing an appropriate animation
     * that corresponds to this emotion.
     *
     * @param newEmotion The new emotional state to set.
     */
    fun setEmotion(newEmotion: AvatarEmotion)

    /**
     * Plays the animation mapped from a high-level emotion.
     *
     * @param emotion The target emotion.
     * @param loop The number of times to loop the mapped animation. Use 0 for infinite looping.
     */
    fun playEmotion(emotion: AvatarEmotion, loop: Int = 0) {
        setEmotion(emotion)
    }

    /**
     * Directly plays a specific animation by name.
     * This is primarily useful for skeletal animation systems that have a rich set of named animations.
     *
     * @param animationName The name of the animation to play.
     * @param loop The number of times to loop the animation. Use 0 for infinite looping.
     */
    fun playAnimation(animationName: String, loop: Int = 1)

    /**
     * Returns the estimated duration, in milliseconds, of the animation mapped from the given emotion.
     * Controllers can return null when they cannot determine a precise duration.
     */
    fun estimateEmotionDurationMillis(emotion: AvatarEmotion): Long? = null

    /**
     * Plays an animation trigger identified by an arbitrary string key, such as a custom
     * `<mood>` tag value returned by the model.
     *
     * @return true if the trigger could be resolved and playback started.
     */
    fun playTrigger(triggerName: String, loop: Int = 0): Boolean = false

    /**
     * Returns the estimated duration for a trigger-based animation, in milliseconds.
     */
    fun estimateTriggerDurationMillis(triggerName: String): Long? = null

    /**
     * Instructs the avatar to look at a specific point on the screen.
     * This is an advanced feature that may only be supported by certain avatar types.
     * Implementations for unsupported types should do nothing.
     *
     * @param x The normalized x-coordinate (-1 to 1).
     * @param y The normalized y-coordinate (-1 to 1).
     */
    fun lookAt(x: Float, y: Float)
    
    /**
     * Updates avatar-specific settings, such as scale or position.
     * Each controller implementation should handle the settings relevant to it.
     *
     * @param settings A map of setting keys to values.
     */
    fun updateSettings(settings: Map<String, Any>) {}

    /**
     * Updates an optional mapping from high-level emotions to model-specific animation names.
     * Controllers that don't need this behavior can ignore it.
     */
    fun updateEmotionAnimationMapping(mapping: Map<AvatarEmotion, String>) {}

    /**
     * Updates an optional mapping from arbitrary trigger keys (for example, `<mood>sleepy</mood>`)
     * to model-specific animation names.
     */
    fun updateTriggerAnimationMapping(mapping: Map<String, String>) {}

    companion object {
        /** Default speaking drive amplitude when a caller has no real amplitude signal. */
        const val DEFAULT_SPEAK_INTENSITY = 0.6f

        /**
         * Shared constant IDLE animation flow for controllers that don't implement a
         * living-portrait layer. Constant so it allocates once.
         */
        val DEFAULT_IDLE_ANIMATION_STATE: StateFlow<AvatarAnimationState> =
            MutableStateFlow(AvatarAnimationState(activity = AvatarActivity.IDLE)).asStateFlow()
    }
}

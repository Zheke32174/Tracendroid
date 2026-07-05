package com.ai.assistance.operit.core.avatar.common.control

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * A small, renderer-agnostic hook that lets the chat / AI layer signal liveness to the
 * avatar without holding a concrete controller reference.
 *
 * Provide it near the avatar surface via [LocalAvatarAnimator] and call [onSpeakStart] /
 * [onSpeakEnd] / [onThinking] / [onListening] / [onIdle] / [setExpression] from wherever
 * the real TTS / AI-state events are observed. The default provided value is [NoOp] so
 * screens that don't host an avatar never crash.
 *
 * HONEST: on a flat single-frame avatar these drive speech-REACTIVE living-portrait motion
 * (breathing / blink / speaking bob / glow), NOT rigged phoneme lip-sync. A rigged
 * Live2D / VRM / DragonBones model is the next tier for real mouth-shape lip-sync.
 */
interface AvatarAnimator {
    fun onSpeakStart(intensity: Float = AvatarController.DEFAULT_SPEAK_INTENSITY)
    fun onSpeakEnd()
    fun onThinking()
    fun onListening()
    fun onIdle()
    fun setExpression(expression: String)

    /** An animator that ignores every signal. Safe default for surfaces without an avatar. */
    object NoOp : AvatarAnimator {
        override fun onSpeakStart(intensity: Float) {}
        override fun onSpeakEnd() {}
        override fun onThinking() {}
        override fun onListening() {}
        override fun onIdle() {}
        override fun setExpression(expression: String) {}
    }

    companion object {
        /**
         * Adapts any [AvatarController] into an [AvatarAnimator]. This is the bridge the
         * avatar surface uses to expose its controller's liveness hooks to the AI layer.
         */
        fun of(controller: AvatarController?): AvatarAnimator {
            if (controller == null) return NoOp
            return object : AvatarAnimator {
                override fun onSpeakStart(intensity: Float) = controller.onSpeakStart(intensity)
                override fun onSpeakEnd() = controller.onSpeakEnd()
                override fun onThinking() = controller.onThinking()
                override fun onListening() = controller.onListening()
                override fun onIdle() = controller.onIdle()
                override fun setExpression(expression: String) = controller.setExpression(expression)
            }
        }
    }
}

/**
 * Composition-local carrying the current [AvatarAnimator]. Defaults to [AvatarAnimator.NoOp].
 * The avatar surface should `CompositionLocalProvider(LocalAvatarAnimator provides
 * AvatarAnimator.of(controller)) { ... }` so descendant chat/AI UI can drive the avatar.
 */
val LocalAvatarAnimator = staticCompositionLocalOf<AvatarAnimator> { AvatarAnimator.NoOp }

package com.ai.assistance.operit.core.avatar.common.state

/**
 * High-level "liveness" intents that drive the living-portrait animation layer.
 *
 * IMPORTANT (honesty note): For a flat single-frame avatar (e.g. Vesper's
 * `vesper_face.webp`) these do NOT produce rigged phoneme lip-sync or a 3D
 * VTuber. They drive *speech-reactive living-portrait motion* — a subtle
 * breathing sway, a periodic blink-like dip, a gentle speaking bob and an
 * expression-tinted glow. Real mouth-shape lip-sync is the next tier and
 * requires a rigged model (Live2D / VRM / DragonBones) or a per-phoneme frame
 * sequence. See [AvatarAnimator].
 *
 * This is intentionally separate from [AvatarEmotion]: emotion selects *which
 * asset/expression* is shown, while this selects *how the portrait moves*. The
 * two are composed together by the renderer.
 */
enum class AvatarActivity {
    /** Resting. Gentle breathing sway + periodic blink only. */
    IDLE,

    /** The AI is generating / tool-running. A slow, contemplative bob. */
    THINKING,

    /** The STT layer is actively capturing the user's voice. A soft attentive lean. */
    LISTENING,

    /** TTS is actively speaking. A rhythmic speaking bob whose intensity follows speech. */
    TALKING
}

/**
 * A snapshot of the avatar's animation intent, consumed by the living-portrait
 * renderer. Immutable so it can flow through a [kotlinx.coroutines.flow.StateFlow]
 * and be collected in Compose.
 *
 * @param activity The current liveness intent (idle / thinking / listening / talking).
 * @param expression The expression key (typically an [AvatarEmotion] name, lowercased,
 *   or a custom mood key). Reserved as the extension point that future per-expression
 *   image frames map from — see the `expression -> asset` mapping in the model.
 * @param intensity A 0f..1f drive amplitude. For TALKING this scales the speaking
 *   bob/glow; callers that have a real amplitude signal (e.g. an AudioTrack analyser
 *   or STT `volumeLevelFlow`) can feed it, otherwise a sensible default is used.
 */
data class AvatarAnimationState(
    val activity: AvatarActivity = AvatarActivity.IDLE,
    val expression: String = AvatarEmotion.IDLE.name.lowercase(),
    val intensity: Float = 0f
)

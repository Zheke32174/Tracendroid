package com.ai.assistance.operit.core.avatar.impl.webp.view

import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.Animatable2
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import com.ai.assistance.operit.util.AppLogger
import android.widget.ImageView
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.ai.assistance.operit.core.avatar.common.control.AvatarController
import com.ai.assistance.operit.core.avatar.common.state.AvatarActivity
import com.ai.assistance.operit.core.avatar.common.state.AvatarAnimationState
import com.ai.assistance.operit.core.avatar.impl.webp.control.WebPAvatarController
import com.ai.assistance.operit.core.avatar.impl.webp.model.WebPAvatarModel
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer

/**
 * A Composable function responsible for rendering a WebP avatar.
 * It uses Android's ImageDecoder to display animated WebP files from assets.
 *
 * Cornerstone #5 (Vesper live avatar): on top of the static/animated-WEBP image this
 * renderer overlays a *living-portrait* animation layer — a subtle continuous breathing
 * sway, a periodic blink-like dip, a speech-reactive bob and an expression-tinted glow —
 * driven by [AvatarController.animationState]. This keeps a flat single-frame photo (e.g.
 * Vesper's `vesper_face.webp`) from ever being a frozen image and makes her look alive
 * while she talks/thinks/listens.
 *
 * HONESTY / CEILING: this is speech-REACTIVE living-portrait motion, NOT rigged phoneme
 * lip-sync and NOT a 3D VTuber. There is no mouth rig, so the "talking" effect is a
 * rhythmic bob + glow gated on TTS speaking start/stop, not per-phoneme mouth shapes.
 * Real mouth-shape lip-sync is the next tier and needs a Live2D / VRM / DragonBones rig
 * or a per-phoneme frame sequence (the DragonBones/MMD/glTF renderers in this package are
 * the intended home for that). Per-expression image frames drop in via the model's
 * `emotionToFileMap` extension point (see [WebPAvatarController.expressionAssetFor]).
 *
 * @param modifier The modifier to be applied to the view.
 * @param model The frame sequence avatar model containing the animation path.
 * @param controller The avatar controller that manages the avatar's state. It must be a
 *   [WebPAvatarController] for this renderer to function.
 * @param onError A callback for handling rendering errors.
 */
@Composable
fun WebPRenderer(
    modifier: Modifier,
    model: WebPAvatarModel,
    controller: AvatarController,
    onError: (String) -> Unit
) {
    // This renderer requires a specific controller implementation
    val webpController = controller as? WebPAvatarController
        ?: throw IllegalArgumentException("WebPRenderer requires a WebPAvatarController")

    val context = LocalContext.current

    // Listen to controller state changes to get the current animation path
    val controllerState by webpController.state.collectAsState()

    // Listen to transform properties
    val scale by webpController.scale.collectAsState()
    val translateX by webpController.translateX.collectAsState()
    val translateY by webpController.translateY.collectAsState()

    // Living-portrait animation intent (idle / thinking / listening / talking + expression).
    val animationState by webpController.animationState.collectAsState()

    val animationName = controllerState.currentAnimation
    val animationPath = model.animationPathFor(animationName)
    val animationKey =
        animationName
            ?: model.animationFileForEmotion(controllerState.emotion)
            ?: model.availableFiles.firstOrNull()

    val drawableState = remember(animationPath) {
        mutableStateOf<android.graphics.drawable.Drawable?>(null)
    }

    // Decode animation when path changes
    DisposableEffect(animationPath, controllerState.isLooping, controllerState.playbackNonce) {
        if (animationPath.isBlank()) {
            drawableState.value = null
            return@DisposableEffect onDispose { }
        }

        val assets = context.assets
        AppLogger.d("WebPRenderer", "Decode start: $animationPath")
        var animationCallback: Animatable2.AnimationCallback? = null

        try {
            if (Build.VERSION.SDK_INT >= 28) {
                // Always decode from bytes to support compressed assets in APK
                val inputStream = if (File(animationPath).isAbsolute) {
                    FileInputStream(animationPath)
                } else {
                    assets.open(animationPath)
                }
                val bytes = inputStream.use { it.readBytes() }
                val src = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
                val drawable = ImageDecoder.decodeDrawable(src)
                drawableState.value = drawable

                if (drawable is AnimatedImageDrawable) {
                    drawable.repeatCount = if (controllerState.isLooping) {
                        AnimatedImageDrawable.REPEAT_INFINITE
                    } else {
                        0
                    }
                    if (!controllerState.isLooping) {
                        animationCallback =
                            object : Animatable2.AnimationCallback() {
                                override fun onAnimationEnd(drawable: Drawable?) {
                                    animationKey?.let(webpController::onAnimationPlaybackCompleted)
                                }
                            }
                        drawable.registerAnimationCallback(animationCallback)
                    }
                    drawable.start()
                    AppLogger.d("WebPRenderer", "Animated start: $animationPath")
                } else {
                    AppLogger.d("WebPRenderer", "Decoded non-animated drawable for: $animationPath")
                }
            } else {
                // API < 28: show first frame as static
                val inputStream = if (File(animationPath).isAbsolute) {
                    FileInputStream(animationPath)
                } else {
                    assets.open(animationPath)
                }
                val bmp = inputStream.use { BitmapFactory.decodeStream(it) }
                drawableState.value = BitmapDrawable(context.resources, bmp)
                AppLogger.d("WebPRenderer", "Static fallback (API<28): $animationPath")
            }
        } catch (e: Exception) {
            AppLogger.e("WebPRenderer", "Decode error for $animationPath: ${e.message}", e)
            drawableState.value = null
            onError("Failed to load animation: ${e.message}")
        }

        onDispose {
            try {
                val d = drawableState.value
                if (Build.VERSION.SDK_INT >= 28 && d is AnimatedImageDrawable) {
                    animationCallback?.let { d.unregisterAnimationCallback(it) }
                    d.stop()
                }
            } catch (_: Exception) {}
        }
    }

    val drawable = drawableState.value
    if (drawable != null) {
        // Compute the living-portrait transform + glow for this frame.
        val living = rememberLivingPortrait(animationState)

        Box(
            modifier = modifier,
            contentAlignment = Alignment.Center
        ) {
            // Expression / speaking glow behind the portrait. Subtle by default; brighter
            // and warmer while talking or happy. Purely decorative — never blocks input.
            if (living.glowAlpha > 0.001f) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val radius = size.minDimension * 0.62f * living.glowScale
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                living.glowColor.copy(alpha = living.glowAlpha),
                                living.glowColor.copy(alpha = living.glowAlpha * 0.4f),
                                Color.Transparent
                            ),
                            center = Offset(size.width / 2f, size.height / 2f),
                            radius = radius
                        ),
                        radius = radius,
                        center = Offset(size.width / 2f, size.height / 2f)
                    )
                }
            }

            AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    // User-configured transform first (scale/position), then the living-portrait
                    // breathing/blink/speaking motion composed on top via graphicsLayer.
                    .offset(x = translateX.dp, y = translateY.dp)
                    .graphicsLayer {
                        scaleX = scale * living.scaleX
                        scaleY = scale * living.scaleY
                        translationY = living.translationYPx
                    },
                factory = { ctx ->
                    ImageView(ctx).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        setImageDrawable(drawable)
                    }
                },
                update = { imageView ->
                    imageView.setImageDrawable(drawable)
                }
            )
        }
    }
}

/**
 * The per-frame result of the living-portrait animation: a composed scale/translate and a
 * glow. All values are relative multipliers/offsets to be composed with the user's own
 * scale/position so we never fight the avatar's configured transform.
 */
private data class LivingPortrait(
    val scaleX: Float,
    val scaleY: Float,
    val translationYPx: Float,
    val glowColor: Color,
    val glowAlpha: Float,
    val glowScale: Float
)

/**
 * Builds the living-portrait motion for the current [AvatarAnimationState].
 *
 * Composed effects (all subtle + performant, GPU-friendly graphicsLayer / one Canvas circle):
 *  - Idle breathing: a slow sinusoidal scale sway so she's never a frozen image.
 *  - Blink-like dip: a brief periodic vertical squash, faked (no eyelid rig) via scaleY.
 *  - Speaking bob: while TALKING, a faster rhythmic vertical bob + scale pulse whose
 *    amplitude follows [AvatarAnimationState.intensity]. HONEST: reactive bob, not lip-sync.
 *  - Thinking: a slow contemplative bob. Listening: a small attentive lean.
 *  - Glow: expression-tinted radial glow that brightens while talking / happy.
 */
@Composable
private fun rememberLivingPortrait(state: AvatarAnimationState): LivingPortrait {
    val transition = rememberInfiniteTransition(label = "living_portrait")

    // Continuous breathing phase (always running so idle is never static).
    val breathe by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathe"
    )

    // Periodic blink driver: mostly ~1f, dipping briefly near the top of the cycle.
    val blinkPhase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "blink"
    )

    // Fast speaking oscillator for the reactive talking bob.
    val speakPhase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 320, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "speak"
    )

    // Blink: dip scaleY briefly. blinkPhase in [0.94, 1.0] => squash, else ~1f.
    val blinkSquash = if (blinkPhase > 0.94f) {
        // Map the last 6% of the cycle to a quick down/up dip.
        val t = (blinkPhase - 0.94f) / 0.06f // 0..1
        val dip = 1f - kotlin.math.sin(t * Math.PI).toFloat() // 1 -> 0 -> 1
        0.06f * (1f - dip)
    } else {
        0f
    }

    // Base breathing sway (very small).
    val breatheScale = 1f + (breathe - 0.5f) * 0.024f // ~ +/-1.2%
    val breatheLiftPx = (breathe - 0.5f) * 6f // gentle vertical drift, px

    // Activity-specific motion.
    val talkDrive = state.intensity.coerceIn(0f, 1f).let { if (it <= 0f) 0.55f else it }
    val speaking = state.activity == AvatarActivity.TALKING
    val thinking = state.activity == AvatarActivity.THINKING
    val listening = state.activity == AvatarActivity.LISTENING

    val speakBobPx = if (speaking) (speakPhase - 0.5f) * 26f * talkDrive else 0f
    val speakScalePulse = if (speaking) (speakPhase - 0.5f) * 0.05f * talkDrive else 0f
    val thinkBobPx = if (thinking) (breathe - 0.5f) * 18f else 0f
    val listenLeanPx = if (listening) -4f else 0f

    val scaleX = breatheScale + speakScalePulse
    val scaleY = breatheScale + speakScalePulse - blinkSquash
    val translationYPx = breatheLiftPx + speakBobPx + thinkBobPx + listenLeanPx

    // Expression glow: base ambient glow, brighter/warmer when talking or happy.
    val expression = state.expression.lowercase()
    val glowColor = when {
        speaking -> Color(0xFF7FD8FF) // cool speaking sheen
        expression == "happy" -> Color(0xFFFFD27F) // warm happy glow
        expression == "sad" || expression == "cry" -> Color(0xFF9FB4FF)
        thinking -> Color(0xFFB79CFF)
        listening -> Color(0xFF9CFFC7)
        else -> Color(0xFFB9C6FF)
    }
    val targetGlowAlpha = when {
        speaking -> 0.18f + 0.16f * talkDrive * (0.5f + speakPhase * 0.5f)
        expression == "happy" -> 0.20f
        thinking -> 0.14f
        listening -> 0.12f
        else -> 0.08f
    }
    // Smooth the glow so activity changes don't pop.
    val glowAlpha by animateFloatAsState(
        targetValue = targetGlowAlpha,
        animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
        label = "glow_alpha"
    )
    val glowScale = 1f + (breathe - 0.5f) * 0.06f + (if (speaking) speakPhase * 0.08f * talkDrive else 0f)

    return LivingPortrait(
        scaleX = scaleX,
        scaleY = scaleY,
        translationYPx = translationYPx,
        glowColor = glowColor,
        glowAlpha = glowAlpha,
        glowScale = glowScale
    )
}

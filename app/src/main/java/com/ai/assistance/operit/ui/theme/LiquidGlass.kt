package com.ai.assistance.operit.ui.theme

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// The com.kyant.backdrop "liquid glass" refraction/blur was removed: its
// Compose-typed API cannot be processed by KAPT stub generation (this module
// runs Room/ObjectBox annotation processing through KAPT, not KSP), which broke
// the whole app build ("cannot access Backdrop"). liquidGlass() now always
// renders the built-in fallback (shadow + border + translucent tint + gloss) —
// which was already the designed no-backdrop path, so every caller is unchanged.
// LocalLiquidGlassBackdrop is retained as a nullable Any? for source
// compatibility with Theme.kt. Restoring the true blur needs a KSP migration.
val LocalLiquidGlassBackdrop = compositionLocalOf<Any?> { null }

private const val LiquidGlassMinApi = Build.VERSION_CODES.TIRAMISU

fun isLiquidGlassSupported(): Boolean = Build.VERSION.SDK_INT >= LiquidGlassMinApi

@Composable
fun Modifier.liquidGlass(
    enabled: Boolean,
    shape: CornerBasedShape = RoundedCornerShape(0.dp),
    containerColor: Color,
    shadowElevation: Dp = 14.dp,
    borderWidth: Dp = 1.dp,
    blurRadius: Dp = 10.dp,
    overlayAlphaBoost: Float = 0f,
    enableLens: Boolean = true,
): Modifier {
    if (!enabled) {
        return this
    }

    val isLightGlass = containerColor.luminance() >= 0.5f
    val fallbackBorderColor =
        if (isLightGlass) {
            Color.White.copy(alpha = 0.28f)
        } else {
            Color.White.copy(alpha = 0.16f)
        }
    val fallbackShadow = shadowElevation.coerceAtLeast(10.dp)
    val fallbackSurfaceTint =
        if (isLightGlass) {
            containerColor.copy(alpha = (0.16f + overlayAlphaBoost).coerceIn(0f, 0.48f))
        } else {
            containerColor.copy(alpha = (0.24f + overlayAlphaBoost).coerceIn(0f, 0.48f))
        }
    val fallbackGloss =
        if (isLightGlass) {
            Color.White.copy(alpha = 0.12f)
        } else {
            Color.White.copy(alpha = 0.06f)
        }

    return this
        .shadow(
            elevation = fallbackShadow,
            shape = shape,
            clip = false,
            ambientColor = Color.Black.copy(alpha = if (isLightGlass) 0.10f else 0.18f),
            spotColor = Color.Black.copy(alpha = if (isLightGlass) 0.10f else 0.18f),
        )
        .border(width = borderWidth.coerceAtLeast(0.6.dp), color = fallbackBorderColor, shape = shape)
        .background(color = fallbackSurfaceTint, shape = shape)
        .drawWithContent {
            drawContent()
            drawRect(fallbackGloss)
        }
}

package dev.pleiades.masamune.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Suite look, replicated locally.
 *
 * The Understory suite's design system lives in `common-security`, which sits in a DIFFERENT
 * git repository (understory-genji / -yojimbo / -firewall) and is not on this build's module
 * path. So this file mirrors that palette's tokens — the same Ink/Fog neutrals, the same
 * semantic trio, the same 4dp spacing scale and shape scale — rather than pretending to
 * depend on a module that is not here. If common-security is ever published to this build,
 * this file is a drop-in replacement target: the accessor shape (`MasamuneTheme.colors /
 * .semantic / .spacing`) matches `UnderstoryTheme`.
 */

// --- Neutrals: green-biased near-black ("forest floor"), laddered.
private val Ink900 = Color(0xFF0E1512)
private val Ink800 = Color(0xFF151E19)
private val Ink700 = Color(0xFF1C2721)
private val Ink600 = Color(0xFF2A3A32)
private val Fog500 = Color(0xFF7E9084)
private val Fog300 = Color(0xFF9EB2A7)
private val Fog100 = Color(0xFFE7F0EA)

// --- Semantic ---
private val Danger = Color(0xFFEF5350)
private val Caution = Color(0xFFFFB74D)
private val Success = Color(0xFF81C784)

// --- Light neutrals ---
private val Paper50 = Color(0xFFFAFAFA)
private val Paper100 = Color(0xFFF2F2F2)
private val Slate900 = Color(0xFF1A1A1A)
private val Slate600 = Color(0xFF5A5A5A)
private val LightOutline = Color(0xFFCFCFCF)

/** Masamune's accent seed. Electric teal — the harness is a control plane. */
val MasamuneSeed = Color(0xFF2FD3C3)

private fun darkColors(seed: Color): ColorScheme = darkColorScheme(
    primary = seed,
    onPrimary = Ink900,
    primaryContainer = seed.copy(alpha = 0.16f).compositeOver(Ink800),
    onPrimaryContainer = Fog100,
    secondary = seed,
    onSecondary = Ink900,
    background = Ink900,
    onBackground = Fog100,
    surface = Ink800,
    onSurface = Fog100,
    surfaceVariant = Ink700,
    onSurfaceVariant = Fog300,
    outline = Ink600,
    outlineVariant = Ink700,
    error = Danger,
    onError = Ink900,
    errorContainer = Danger.copy(alpha = 0.14f).compositeOver(Ink800),
    onErrorContainer = Fog100,
    scrim = Color(0xCC000000),
)

private fun lightColors(seed: Color): ColorScheme = lightColorScheme(
    primary = seed,
    onPrimary = Paper50,
    primaryContainer = seed.copy(alpha = 0.18f).compositeOver(Paper100),
    onPrimaryContainer = Slate900,
    secondary = seed,
    onSecondary = Paper50,
    background = Paper50,
    onBackground = Slate900,
    surface = Paper100,
    onSurface = Slate900,
    surfaceVariant = Paper100,
    onSurfaceVariant = Slate600,
    outline = LightOutline,
    outlineVariant = Paper100,
    error = Danger,
    onError = Paper50,
    errorContainer = Danger.copy(alpha = 0.14f).compositeOver(Paper100),
    onErrorContainer = Slate900,
    scrim = Color(0x99000000),
)

/** Material3 has no warning/success role; the suite carries them alongside. */
@Immutable
data class SemanticColors(
    val warning: Color,
    val warningContainer: Color,
    val success: Color,
    val successContainer: Color,
    val dim: Color,
)

private val DarkSemantic = SemanticColors(
    warning = Caution,
    warningContainer = Caution.copy(alpha = 0.16f).compositeOver(Ink800),
    success = Success,
    successContainer = Success.copy(alpha = 0.16f).compositeOver(Ink800),
    dim = Fog500,
)

private val LightSemantic = SemanticColors(
    warning = Color(0xFF9A6B00),
    warningContainer = Caution.copy(alpha = 0.22f).compositeOver(Paper100),
    success = Color(0xFF2E7D46),
    successContainer = Success.copy(alpha = 0.22f).compositeOver(Paper100),
    dim = Slate600,
)

@Immutable
data class Spacing(
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 12.dp,
    val lg: Dp = 16.dp,
    val xl: Dp = 24.dp,
    val xxl: Dp = 32.dp,
)

val LocalSemanticColors = staticCompositionLocalOf { DarkSemantic }
val LocalSpacing = staticCompositionLocalOf { Spacing() }

val MasamuneShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

val MasamuneTypography = Typography(
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp),
)

/** Monospace style used everywhere a path, a command or a raw payload is rendered. */
val MonoStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 18.sp)

@Composable
fun MasamuneTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val scheme = if (darkTheme) darkColors(MasamuneSeed) else lightColors(MasamuneSeed)
    val semantic = if (darkTheme) DarkSemantic else LightSemantic
    CompositionLocalProvider(
        LocalSemanticColors provides semantic,
        LocalSpacing provides Spacing(),
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = MasamuneTypography,
            shapes = MasamuneShapes,
            content = content,
        )
    }
}

object MasamuneTheme {
    val colors: ColorScheme
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme
    val semantic: SemanticColors
        @Composable @ReadOnlyComposable get() = LocalSemanticColors.current
    val spacing: Spacing
        @Composable @ReadOnlyComposable get() = LocalSpacing.current
}

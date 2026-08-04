package com.batterycast.quant.core.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = Mint,
    onPrimary = MintInk,
    primaryContainer = MintDeep,
    onPrimaryContainer = Color(0xFFCFF9E7),
    inversePrimary = MintDeep,

    secondary = Forecast,
    onSecondary = ForecastInk,
    secondaryContainer = Color(0xFF1B3355),
    onSecondaryContainer = Color(0xFFCFE0FF),

    tertiary = Amber,
    onTertiary = AmberInk,
    tertiaryContainer = Color(0xFF3A2C0C),
    onTertiaryContainer = Color(0xFFFFE6B0),

    background = Navy900,
    onBackground = InkPrimary,
    surface = Navy900,
    onSurface = InkPrimary,
    surfaceVariant = Navy600,
    onSurfaceVariant = InkSecondary,

    surfaceContainerLowest = Navy850,
    surfaceContainerLow = Navy800,
    surfaceContainer = Navy700,
    surfaceContainerHigh = Navy600,
    surfaceContainerHighest = Navy500,

    outline = Navy400,
    outlineVariant = Navy500,

    error = Coral,
    onError = CoralInk,
    errorContainer = Color(0xFF48201F),
    onErrorContainer = Color(0xFFFFD9D9),

    scrim = Color(0xCC04090F),
)

private val LightColorScheme = lightColorScheme(
    primary = MintOnPaper,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC8F3E2),
    onPrimaryContainer = Color(0xFF00382A),
    inversePrimary = Mint,

    secondary = ForecastOnPaper,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD8E5FF),
    onSecondaryContainer = Color(0xFF0B2B60),

    tertiary = AmberOnPaper,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFCE9C2),
    onTertiaryContainer = Color(0xFF43300A),

    background = Paper050,
    onBackground = PaperInkPrimary,
    surface = Paper050,
    onSurface = PaperInkPrimary,
    surfaceVariant = Paper100,
    onSurfaceVariant = PaperInkSecondary,

    surfaceContainerLowest = Paper000,
    surfaceContainerLow = Color(0xFFFAFCFE),
    surfaceContainer = Paper000,
    surfaceContainerHigh = Paper100,
    surfaceContainerHighest = Paper200,

    outline = Paper300,
    outlineVariant = Paper200,

    error = CoralOnPaper,
    onError = Color.White,
    errorContainer = Color(0xFFFBDDDB),
    onErrorContainer = Color(0xFF48100D),
)

val LocalSemanticColors = staticCompositionLocalOf { DarkSemanticColors }

/**
 * The app theme.
 *
 * Dynamic colour is deliberately **not** applied to the brand hues. Mint is simultaneously the
 * forecast line, the "you're covered" signal and the ring that carries the app's whole identity;
 * letting the wallpaper recolour it would break the association between the number and the chart
 * it came from. Neutral surfaces are the app's own navy for the same reason — an instrument reads
 * as an instrument only if its chassis is consistent.
 */
@Composable
fun BatteryCastTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val semanticColors = if (darkTheme) DarkSemanticColors else LightSemanticColors

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    // The screenshots that drove this redesign were taken at a large system display size, where
    // headings wrapped into three lines and chips overflowed. The user's preference is respected —
    // text still scales — but the ceiling stops the layout from breaking outright at the extremes.
    val density = LocalDensity.current
    val cappedDensity = androidx.compose.ui.unit.Density(
        density = density.density,
        fontScale = density.fontScale.coerceAtMost(MAX_FONT_SCALE),
    )

    CompositionLocalProvider(
        LocalSemanticColors provides semanticColors,
        LocalDensity provides cappedDensity,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = BatteryCastTypography,
            shapes = BatteryCastShapes,
            content = content,
        )
    }
}

/** Convenience accessor mirroring `MaterialTheme.colorScheme`. */
object BatteryCastTheme {
    val semanticColors: BatteryCastSemanticColors
        @Composable get() = LocalSemanticColors.current
}

/** Beyond this the hero ring and the bottom bar cannot hold their layout. */
private const val MAX_FONT_SCALE = 1.3f

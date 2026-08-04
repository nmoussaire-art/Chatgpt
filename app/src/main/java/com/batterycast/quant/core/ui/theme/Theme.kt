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
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColorScheme = lightColorScheme(
    primary = EmeraldInk,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCDF3E4),
    onPrimaryContainer = Color(0xFF00301F),
    secondary = TextSecondaryLight,
    onSecondary = Color.White,
    secondaryContainer = PaperPanel2,
    onSecondaryContainer = TextPrimaryLight,
    tertiary = AmberInk,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFCE9C2),
    onTertiaryContainer = Color(0xFF4A3200),
    background = PaperCanvas,
    onBackground = TextPrimaryLight,
    surface = PaperCanvas,
    onSurface = TextPrimaryLight,
    surfaceVariant = PaperPanel2,
    onSurfaceVariant = TextSecondaryLight,
    surfaceContainer = PaperPanel,
    surfaceContainerHigh = PaperPanel2,
    surfaceContainerHighest = PaperTrack,
    surfaceContainerLow = PaperPanel,
    surfaceContainerLowest = Color.White,
    outline = TextTertiaryLight,
    outlineVariant = HairlineLight,
    error = CrimsonInk,
    onError = Color.White,
    errorContainer = Color(0xFFFBD9DE),
    onErrorContainer = Color(0xFF5C0A18),
)

private val DarkColorScheme = darkColorScheme(
    primary = Emerald,
    onPrimary = Canvas0,
    primaryContainer = Color(0xFF00382A),
    onPrimaryContainer = Emerald,
    secondary = TextSecondaryDark,
    onSecondary = Canvas0,
    secondaryContainer = Panel2,
    onSecondaryContainer = TextPrimaryDark,
    tertiary = Amber,
    onTertiary = Canvas0,
    tertiaryContainer = Panel2,
    onTertiaryContainer = Amber,
    background = Canvas0,
    onBackground = TextPrimaryDark,
    surface = Canvas0,
    onSurface = TextPrimaryDark,
    surfaceVariant = Panel2,
    onSurfaceVariant = TextSecondaryDark,
    surfaceContainer = Panel1,
    surfaceContainerHigh = Panel2,
    surfaceContainerHighest = Track,
    surfaceContainerLow = Panel1,
    surfaceContainerLowest = Canvas0,
    outline = TextTertiaryDark,
    outlineVariant = HairlineDark,
    error = Crimson,
    onError = Canvas0,
    errorContainer = Panel2,
    onErrorContainer = Crimson,
)

val LocalSemanticColors = staticCompositionLocalOf { DarkSemanticColors }

/**
 * The app theme.
 *
 * Dynamic colour is deliberately *not* used. The three status hues are the app's entire visual
 * vocabulary — emerald is simultaneously the forecast line, the probability figure and the "you're
 * covered" signal — and letting the wallpaper recolour them would break the association between a
 * number and the chart it came from.
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

    CompositionLocalProvider(LocalSemanticColors provides semanticColors) {
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

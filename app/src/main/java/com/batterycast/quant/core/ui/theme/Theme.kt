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
    primary = Mint40,
    onPrimary = Color.White,
    primaryContainer = Mint90,
    onPrimaryContainer = Mint10,
    secondary = Steel50,
    onSecondary = Color.White,
    secondaryContainer = Steel90,
    onSecondaryContainer = Steel10,
    tertiary = Amber40,
    onTertiary = Color.White,
    tertiaryContainer = Amber90,
    onTertiaryContainer = Amber30,
    background = Steel99,
    onBackground = Steel10,
    surface = Steel99,
    onSurface = Steel10,
    surfaceVariant = Steel95,
    onSurfaceVariant = Steel50,
    surfaceContainer = Color(0xFFF1F5F9),
    surfaceContainerHigh = Color(0xFFE9EEF4),
    surfaceContainerHighest = Color(0xFFE2E8F0),
    surfaceContainerLow = Color(0xFFF6F9FB),
    surfaceContainerLowest = Color.White,
    outline = Steel60,
    outlineVariant = Steel80,
    error = Clay40,
    onError = Color.White,
    errorContainer = Clay90,
    onErrorContainer = Clay30,
)

private val DarkColorScheme = darkColorScheme(
    primary = Mint70,
    onPrimary = Mint20,
    primaryContainer = Mint30,
    onPrimaryContainer = Mint95,
    secondary = Steel80,
    onSecondary = Steel20,
    secondaryContainer = Steel30,
    onSecondaryContainer = Steel90,
    tertiary = Amber70,
    onTertiary = Amber30,
    tertiaryContainer = Amber30,
    onTertiaryContainer = Amber90,
    background = Steel10,
    onBackground = Steel95,
    surface = Steel10,
    onSurface = Steel95,
    surfaceVariant = Steel25,
    onSurfaceVariant = Steel60,
    surfaceContainer = Steel20,
    surfaceContainerHigh = Steel25,
    surfaceContainerHighest = Steel30,
    surfaceContainerLow = Steel15,
    surfaceContainerLowest = Color(0xFF070C16),
    outline = Steel50,
    outlineVariant = Steel30,
    error = Clay70,
    onError = Clay30,
    errorContainer = Clay30,
    onErrorContainer = Clay90,
)

val LocalSemanticColors = staticCompositionLocalOf { LightSemanticColors }

/**
 * The app theme.
 *
 * Dynamic colour is deliberately *not* used. The mint accent is the app's one piece of visual
 * vocabulary — it is the median forecast line, the probability figure and the confidence chip all
 * at once — and letting the wallpaper recolour it would break the association between the number
 * and the chart it came from.
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

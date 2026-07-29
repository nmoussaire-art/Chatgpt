package com.deadlineguardian.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/**
 * Urgency colours, kept out of the Material scheme because they carry meaning rather
 * than branding: red never means "primary action" here, it means "you are losing money".
 */
object Urgent {
    val Red = Color(0xFFE5484D)
    val RedContainerDark = Color(0xFF3B1418)
    val RedContainerLight = Color(0xFFFDECEE)

    val Amber = Color(0xFFE0932B)
    val AmberContainerDark = Color(0xFF33230A)
    val AmberContainerLight = Color(0xFFFDF3E3)

    val Green = Color(0xFF2FA96B)
    val GreenContainerDark = Color(0xFF10281C)
    val GreenContainerLight = Color(0xFFE8F6EE)

    val Slate = Color(0xFF7C8794)
}

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7EC8FF),
    onPrimary = Color(0xFF00344F),
    primaryContainer = Color(0xFF004B6F),
    onPrimaryContainer = Color(0xFFCBE7FF),
    background = Color(0xFF101418),
    onBackground = Color(0xFFE3E6E9),
    surface = Color(0xFF161B21),
    onSurface = Color(0xFFE3E6E9),
    surfaceVariant = Color(0xFF1F262E),
    onSurfaceVariant = Color(0xFFB9C2CC),
    outline = Color(0xFF3A434D),
    error = Urgent.Red
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF11628F),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFCBE7FF),
    onPrimaryContainer = Color(0xFF001E30),
    background = Color(0xFFF7F9FB),
    onBackground = Color(0xFF161B21),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF161B21),
    surfaceVariant = Color(0xFFEDF1F5),
    onSurfaceVariant = Color(0xFF48525C),
    outline = Color(0xFFD3DAE2),
    error = Urgent.Red
)

private val AppTypography = Typography(
    headlineLarge = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
    headlineSmall = TextStyle(fontSize = 21.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.5.sp, lineHeight = 17.sp),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.6.sp)
)

@Composable
fun DeadlineGuardianTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colors = if (darkTheme) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }
    MaterialTheme(colorScheme = colors, typography = AppTypography, content = content)
}

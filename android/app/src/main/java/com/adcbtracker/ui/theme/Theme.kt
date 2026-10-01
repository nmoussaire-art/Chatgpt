package com.adcbtracker.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Emerald = Color(0xFF2FD8A4)
val EmeraldBright = Color(0xFF57E7BC)
val EmeraldDim = Color(0xFF123A2E)
val EmeraldContainerText = Color(0xFF8FEBCC)
val Coral = Color(0xFFFF6B6B)
val CoralDim = Color(0xFF3A1B1E)
val CoralText = Color(0xFFFFB8B8)
val Ink0 = Color(0xFF0A0B0D)
val Ink1 = Color(0xFF141619)
val Ink2 = Color(0xFF1D2025)
val Ink3 = Color(0xFF272B31)
val TextHigh = Color(0xFFF6F7F9)
val TextMid = Color(0xFF9BA1AD)
val Amber = Color(0xFFFFC857)

private val DarkColors = darkColorScheme(
    primary = Emerald,
    onPrimary = Color(0xFF04120D),
    primaryContainer = EmeraldDim,
    onPrimaryContainer = EmeraldContainerText,
    secondary = EmeraldBright,
    onSecondary = Color(0xFF04120D),
    secondaryContainer = Ink2,
    onSecondaryContainer = TextHigh,
    tertiary = Emerald,
    background = Ink0,
    onBackground = TextHigh,
    surface = Ink1,
    onSurface = TextHigh,
    surfaceVariant = Ink2,
    onSurfaceVariant = TextMid,
    surfaceContainer = Ink1,
    surfaceContainerHigh = Ink2,
    surfaceContainerLow = Ink1,
    error = Coral,
    onError = Color(0xFF120404),
    errorContainer = CoralDim,
    onErrorContainer = CoralText,
    outline = Ink3,
    outlineVariant = Color(0xFF1B1E23),
)

private val AppTypography = Typography(
    headlineLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 30.sp, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 26.sp, letterSpacing = (-0.4).sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    bodyLarge = TextStyle(fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    bodySmall = TextStyle(fontSize = 12.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp),
)

@Composable
fun AdcbTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, typography = AppTypography, content = content)
}

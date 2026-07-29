package com.loopguard.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.loopguard.app.data.ThemeMode

// LoopGuard's own palette: indigo for structure, teal for resolution,
// amber for pressure, rose for overdue.
private val Indigo = Color(0xFF4B5BD6)
private val IndigoDark = Color(0xFFB4BEFF)
private val Teal = Color(0xFF12876E)
private val TealDark = Color(0xFF6FDDC0)
private val Amber = Color(0xFF9A6200)
private val AmberDark = Color(0xFFFFC46B)

private val LightColors = lightColorScheme(
    primary = Indigo,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0E2FF),
    onPrimaryContainer = Color(0xFF0C1258),
    secondary = Teal,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFB8F2E0),
    onSecondaryContainer = Color(0xFF00201A),
    tertiary = Amber,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDDB3),
    onTertiaryContainer = Color(0xFF2C1700),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    background = Color(0xFFFBFAFF),
    onBackground = Color(0xFF1A1B26),
    surface = Color(0xFFFBFAFF),
    onSurface = Color(0xFF1A1B26),
    surfaceVariant = Color(0xFFE3E1EC),
    onSurfaceVariant = Color(0xFF46464F),
    outline = Color(0xFF777680),
    outlineVariant = Color(0xFFC7C5D0),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF5F3FB),
    surfaceContainer = Color(0xFFEFEDF5),
    surfaceContainerHigh = Color(0xFFE9E7F0),
    surfaceContainerHighest = Color(0xFFE3E1EA),
)

private val DarkColors = darkColorScheme(
    primary = IndigoDark,
    onPrimary = Color(0xFF1A2178),
    primaryContainer = Color(0xFF323A9A),
    onPrimaryContainer = Color(0xFFE0E2FF),
    secondary = TealDark,
    onSecondary = Color(0xFF003830),
    secondaryContainer = Color(0xFF005143),
    onSecondaryContainer = Color(0xFFB8F2E0),
    tertiary = AmberDark,
    onTertiary = Color(0xFF4A2800),
    tertiaryContainer = Color(0xFF6A3B00),
    onTertiaryContainer = Color(0xFFFFDDB3),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
    background = Color(0xFF0F1017),
    onBackground = Color(0xFFE5E1E9),
    surface = Color(0xFF0F1017),
    onSurface = Color(0xFFE5E1E9),
    surfaceVariant = Color(0xFF46464F),
    onSurfaceVariant = Color(0xFFC7C5D0),
    outline = Color(0xFF918F9A),
    outlineVariant = Color(0xFF46464F),
    surfaceContainerLowest = Color(0xFF0A0B11),
    surfaceContainerLow = Color(0xFF171821),
    surfaceContainer = Color(0xFF1B1C25),
    surfaceContainerHigh = Color(0xFF262730),
    surfaceContainerHighest = Color(0xFF31323B),
)

/** Semantic colours for pressure states, resolved per theme. */
data class LoopGuardColors(
    val critical: Color,
    val criticalContainer: Color,
    val high: Color,
    val highContainer: Color,
    val medium: Color,
    val mediumContainer: Color,
    val calm: Color,
    val calmContainer: Color,
)

private val LightSemantics = LoopGuardColors(
    critical = Color(0xFFB3261E), criticalContainer = Color(0xFFFFDAD6),
    high = Color(0xFF9A4A00), highContainer = Color(0xFFFFDCC4),
    medium = Color(0xFF7A5B00), mediumContainer = Color(0xFFFFEEC2),
    calm = Color(0xFF0F6B57), calmContainer = Color(0xFFBFF2E3),
)

private val DarkSemantics = LoopGuardColors(
    critical = Color(0xFFFFB4AB), criticalContainer = Color(0xFF6E2B26),
    high = Color(0xFFFFB77C), highContainer = Color(0xFF6A3B12),
    medium = Color(0xFFF2D48A), mediumContainer = Color(0xFF5A4715),
    calm = Color(0xFF7BE0C4), calmContainer = Color(0xFF11453A),
)

val LocalLoopGuardColors = staticCompositionLocalOf { LightSemantics }

private val AppTypography = Typography().let { base ->
    base.copy(
        displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Bold),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
        labelSmall = TextStyle(
            fontFamily = FontFamily.Default,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            letterSpacing = 0.8.sp,
        ),
    )
}

@Composable
fun LoopGuardTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColour: Boolean = true,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val context = LocalContext.current
    val colors = when {
        dynamicColour && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }

    CompositionLocalProvider(
        LocalLoopGuardColors provides if (dark) DarkSemantics else LightSemantics,
    ) {
        MaterialTheme(
            colorScheme = colors,
            typography = AppTypography,
            content = content,
        )
    }
}

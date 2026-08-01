package com.ontimequant.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.ontimequant.data.prefs.ThemeMode

/**
 * Visual identity.
 *
 * Deep navy and a muted blue-green, on soft neutral surfaces. Deliberately quiet: this app
 * delivers probabilistic bad news occasionally, and a screen that shouts makes a 72%
 * forecast feel like an emergency. Amber carries "worth noticing", muted red is reserved
 * for genuinely high risk, and green is used sparingly so that it still means something.
 *
 * Dynamic colour is not used. The risk palette has to mean the same thing on every device;
 * a wallpaper-derived scheme that turned "elevated" green would be actively misleading.
 */
object Brand {
    val Navy = Color(0xFF0E1720)
    val NavyElevated = Color(0xFF16232F)
    val NavySurface = Color(0xFF111C26)
    val Teal = Color(0xFF2E9E92)
    val TealBright = Color(0xFF54C7B8)
    val TealDeep = Color(0xFF17635C)
    val Sand = Color(0xFFF6F7F6)
    val SandDim = Color(0xFFE7EBEA)
    val Slate = Color(0xFF44575F)
    val SlateLight = Color(0xFF9CB0B6)

    val Amber = Color(0xFFC98A16)
    val AmberBright = Color(0xFFE8B65B)
    val Rust = Color(0xFFB3453C)
    val RustBright = Color(0xFFE08379)
    val Moss = Color(0xFF2E7D5B)
    val MossBright = Color(0xFF6FC49B)
}

private val LightColors = lightColorScheme(
    primary = Brand.Teal,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCDE9E4),
    onPrimaryContainer = Color(0xFF0B3A35),
    secondary = Brand.Slate,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD9E3E6),
    onSecondaryContainer = Color(0xFF1A282D),
    tertiary = Brand.Amber,
    onTertiary = Color.White,
    background = Brand.Sand,
    onBackground = Brand.Navy,
    surface = Color.White,
    onSurface = Brand.Navy,
    surfaceVariant = Brand.SandDim,
    onSurfaceVariant = Color(0xFF41535A),
    outline = Color(0xFFA9B8BC),
    outlineVariant = Color(0xFFD3DCDE),
    error = Brand.Rust,
    onError = Color.White,
    errorContainer = Color(0xFFF6DAD7),
    onErrorContainer = Color(0xFF48120E),
)

private val DarkColors = darkColorScheme(
    primary = Brand.TealBright,
    onPrimary = Color(0xFF00312C),
    primaryContainer = Brand.TealDeep,
    onPrimaryContainer = Color(0xFFB9EFE7),
    secondary = Brand.SlateLight,
    onSecondary = Color(0xFF15242A),
    secondaryContainer = Color(0xFF2A3B43),
    onSecondaryContainer = Color(0xFFD3E3E8),
    tertiary = Brand.AmberBright,
    onTertiary = Color(0xFF3A2600),
    background = Brand.Navy,
    onBackground = Color(0xFFE6EDEC),
    surface = Brand.NavySurface,
    onSurface = Color(0xFFE6EDEC),
    surfaceVariant = Brand.NavyElevated,
    onSurfaceVariant = Color(0xFFB4C4C8),
    outline = Color(0xFF56686E),
    outlineVariant = Color(0xFF2B3B44),
    error = Brand.RustBright,
    onError = Color(0xFF3D0906),
    errorContainer = Color(0xFF5F1B15),
    onErrorContainer = Color(0xFFFBDAD6),
)

/** Semantic colours that Material's scheme has no slot for. */
data class RiskColors(
    val low: Color,
    val moderate: Color,
    val elevated: Color,
    val high: Color,
    val lowContainer: Color,
    val moderateContainer: Color,
    val elevatedContainer: Color,
    val highContainer: Color,
    val curveFill: Color,
    val curveLine: Color,
    val threshold: Color,
    val gridline: Color,
)

val LocalRiskColors = compositionLocalOf {
    RiskColors(
        low = Brand.Moss, moderate = Brand.Teal, elevated = Brand.Amber, high = Brand.Rust,
        lowContainer = Color(0xFFD8ECE1), moderateContainer = Color(0xFFD6EAE7),
        elevatedContainer = Color(0xFFF7EAD0), highContainer = Color(0xFFF6DAD7),
        curveFill = Brand.Teal.copy(alpha = 0.16f), curveLine = Brand.Teal,
        threshold = Brand.Slate, gridline = Color(0xFFD3DCDE),
    )
}

/** Whether the user has asked for reduced motion; animations check this. */
val LocalReducedMotion = compositionLocalOf { false }

private val AppTypography = Typography().let { base ->
    val tight = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.None,
    )
    base.copy(
        displayLarge = base.displayLarge.copy(
            fontWeight = FontWeight.Medium,
            letterSpacing = (-1.5).sp,
            lineHeightStyle = tight,
        ),
        displayMedium = base.displayMedium.copy(fontWeight = FontWeight.Medium, letterSpacing = (-1).sp),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp),
        labelSmall = base.labelSmall.copy(letterSpacing = 0.6.sp, fontWeight = FontWeight.Medium),
        bodyLarge = base.bodyLarge.copy(lineHeight = 24.sp),
        bodyMedium = base.bodyMedium.copy(lineHeight = 21.sp),
    )
}

/** The departure time itself — the one number the whole product exists to produce. */
val DepartureNumeralStyle = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.Light,
    fontSize = 68.sp,
    lineHeight = 72.sp,
    letterSpacing = (-2).sp,
)

@Composable
fun OnTimeQuantTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    reducedMotion: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colors = if (dark) DarkColors else LightColors
    val risk = if (dark) {
        RiskColors(
            low = Brand.MossBright, moderate = Brand.TealBright,
            elevated = Brand.AmberBright, high = Brand.RustBright,
            lowContainer = Color(0xFF1D3D30), moderateContainer = Color(0xFF14403C),
            elevatedContainer = Color(0xFF44340F), highContainer = Color(0xFF4A1D18),
            curveFill = Brand.TealBright.copy(alpha = 0.20f), curveLine = Brand.TealBright,
            threshold = Brand.SlateLight, gridline = Color(0xFF2B3B44),
        )
    } else {
        LocalRiskColors.current
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        val context = LocalContext.current
        SideEffect {
            (context as? Activity)?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
        }
    }

    CompositionLocalProvider(
        LocalRiskColors provides risk,
        LocalReducedMotion provides reducedMotion,
    ) {
        MaterialTheme(
            colorScheme = colors,
            typography = AppTypography,
            content = content,
        )
    }
}

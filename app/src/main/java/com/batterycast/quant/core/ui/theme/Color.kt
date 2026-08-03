package com.batterycast.quant.core.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The palette.
 *
 * The app is an instrument, so it reads like one: a deep, slightly blue graphite for dark mode,
 * a cool paper white for light, and a single mint accent that carries every probability, forecast
 * line and confidence indicator. Risk is expressed in amber and a muted clay rather than alarm
 * red — the honest message when a forecast is poor is "plan for this", not "emergency".
 */

// --- Mint: the forecast accent. ---
internal val Mint10 = Color(0xFF00201A)
internal val Mint20 = Color(0xFF00382C)
internal val Mint30 = Color(0xFF005142)
internal val Mint40 = Color(0xFF006B58)
internal val Mint60 = Color(0xFF2FC79B)
internal val Mint70 = Color(0xFF5EE9B5)
internal val Mint80 = Color(0xFF88F5CC)
internal val Mint90 = Color(0xFFA9F2DD)
internal val Mint95 = Color(0xFFCFFCEE)

// --- Steel: secondary structure, chart axes, supporting text. ---
internal val Steel10 = Color(0xFF0B1220)
internal val Steel15 = Color(0xFF0F1725)
internal val Steel20 = Color(0xFF16202F)
internal val Steel25 = Color(0xFF1D2939)
internal val Steel30 = Color(0xFF2A3849)
internal val Steel50 = Color(0xFF64748B)
internal val Steel60 = Color(0xFF8593A8)
internal val Steel80 = Color(0xFFC3CDDA)
internal val Steel90 = Color(0xFFDDE4EC)
internal val Steel95 = Color(0xFFEEF2F7)
internal val Steel99 = Color(0xFFFAFBFD)

// --- Amber: caution, used sparingly. ---
internal val Amber30 = Color(0xFF6B4B00)
internal val Amber40 = Color(0xFF8C6400)
internal val Amber70 = Color(0xFFF0B846)
internal val Amber80 = Color(0xFFF7D08A)
internal val Amber90 = Color(0xFFFCE8C2)

// --- Clay: risk. Deliberately muted; this app does not shout. ---
internal val Clay30 = Color(0xFF6B2F2A)
internal val Clay40 = Color(0xFF8E3F38)
internal val Clay70 = Color(0xFFE59389)
internal val Clay80 = Color(0xFFF2B7B0)
internal val Clay90 = Color(0xFFFBDCD8)

/**
 * Colours that carry meaning rather than decoration.
 *
 * Kept outside the Material scheme because they are semantic: [probabilityHigh] is not "the
 * primary colour", it is what a high probability looks like, and it must stay legible whichever
 * scheme is active.
 */
data class BatteryCastSemanticColors(
    val probabilityHigh: Color,
    val probabilityMedium: Color,
    val probabilityLow: Color,
    /** Innermost forecast band (50 % interval). */
    val band50: Color,
    /** Middle band (80 %). */
    val band80: Color,
    /** Outer band (90 %). */
    val band90: Color,
    val medianLine: Color,
    val thresholdLine: Color,
    val chargingAccent: Color,
    val gridLine: Color,
    val preliminaryAccent: Color,
    val positive: Color,
    val caution: Color,
    val risk: Color,
) {
    /** Colour for a probability in 0..1, using the same thresholds as the wording. */
    fun forProbability(probability: Double): Color = when {
        probability >= 0.8 -> probabilityHigh
        probability >= 0.55 -> probabilityMedium
        else -> probabilityLow
    }
}

internal val LightSemanticColors = BatteryCastSemanticColors(
    probabilityHigh = Mint40,
    probabilityMedium = Amber40,
    probabilityLow = Clay40,
    band50 = Mint40.copy(alpha = 0.28f),
    band80 = Mint40.copy(alpha = 0.16f),
    band90 = Mint40.copy(alpha = 0.09f),
    medianLine = Mint40,
    thresholdLine = Steel50.copy(alpha = 0.55f),
    chargingAccent = Mint60,
    gridLine = Steel80.copy(alpha = 0.5f),
    preliminaryAccent = Amber40,
    positive = Mint40,
    caution = Amber40,
    risk = Clay40,
)

internal val DarkSemanticColors = BatteryCastSemanticColors(
    probabilityHigh = Mint70,
    probabilityMedium = Amber70,
    probabilityLow = Clay70,
    band50 = Mint70.copy(alpha = 0.30f),
    band80 = Mint70.copy(alpha = 0.17f),
    band90 = Mint70.copy(alpha = 0.09f),
    medianLine = Mint70,
    thresholdLine = Steel60.copy(alpha = 0.6f),
    chargingAccent = Mint80,
    gridLine = Steel30,
    preliminaryAccent = Amber70,
    positive = Mint70,
    caution = Amber70,
    risk = Clay70,
)

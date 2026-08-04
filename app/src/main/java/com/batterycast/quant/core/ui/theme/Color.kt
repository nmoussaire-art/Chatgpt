package com.batterycast.quant.core.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The palette.
 *
 * A modern dark-mode utility palette: an OLED-deep charcoal canvas with panels lifted off it by a
 * single step of value and a hairline stroke, rather than by shadow. Meaning is carried by exactly
 * three status hues — emerald, amber, crimson — and everything else is neutral, so a coloured pixel
 * on screen always means something about the forecast.
 *
 * A light scheme carries the same structure on paper. The status hues are darkened there so they
 * hold contrast against white; the *mapping* is identical, so a green number means the same thing
 * in either scheme.
 */

// --- Canvas and panels. -----------------------------------------------------------------------

/** The OLED canvas. Deep enough to switch pixels off, not so deep that panels lose their edge. */
internal val Canvas0 = Color(0xFF0B0D12)

/** The standard panel. One step of value above the canvas — the whole elevation story. */
internal val Panel1 = Color(0xFF141721)

/** A panel nested inside another panel, or a pressed state. */
internal val Panel2 = Color(0xFF1A1E2A)

/** Inactive control tracks: slider rails, progress troughs, bar backgrounds. */
internal val Track = Color(0xFF202634)

/** The hairline that separates a panel from the canvas, in place of a shadow. */
internal val HairlineDark = Color(0x14FFFFFF)
internal val HairlineLight = Color(0x14000000)

// --- Status hues. Three, each with exactly one meaning. ----------------------------------------

/** Safe: on track, high probability, better than baseline. */
internal val Emerald = Color(0xFF00E699)
internal val EmeraldInk = Color(0xFF00996B)

/** Warning: tight margin, moderate probability, provisional evidence. */
internal val Amber = Color(0xFFFFB800)
internal val AmberInk = Color(0xFFB37A00)

/** Critical: unlikely to make it, worse than baseline. */
internal val Crimson = Color(0xFFFF4560)
internal val CrimsonInk = Color(0xFFD92D4B)

// --- Text. ------------------------------------------------------------------------------------

internal val TextPrimaryDark = Color(0xFFFFFFFF)
internal val TextSecondaryDark = Color(0xFF8F96A3)
internal val TextTertiaryDark = Color(0xFF5B6270)

internal val PaperCanvas = Color(0xFFF6F7F9)
internal val PaperPanel = Color(0xFFFFFFFF)
internal val PaperPanel2 = Color(0xFFF1F3F6)
internal val PaperTrack = Color(0xFFE3E7ED)
internal val TextPrimaryLight = Color(0xFF0B0D12)
internal val TextSecondaryLight = Color(0xFF5A616D)
internal val TextTertiaryLight = Color(0xFF8C93A0)

/**
 * Colours that carry meaning rather than decoration.
 *
 * Kept outside the Material scheme because they are semantic: [probabilityHigh] is not "the primary
 * colour", it is what a high probability looks like, and it must stay legible whichever scheme is
 * active.
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
    /** Faint horizontal guides. There are no vertical gridlines anywhere in this app. */
    val gridLine: Color,
    val preliminaryAccent: Color,
    val positive: Color,
    val caution: Color,
    val risk: Color,
    /** The hairline that defines a panel's edge. */
    val cardStroke: Color,
    /** Inactive rail behind any bar, slider or meter. */
    val trackInactive: Color,
    /** The scrubber's vertical crosshair. */
    val crosshair: Color,
) {
    /**
     * Colour for a probability in 0..1.
     *
     * Three bands, matching the status hues exactly: above 70 % is safe, 30–70 % is a warning, and
     * below 30 % is critical. The wording elsewhere uses the same cuts, so the colour and the
     * sentence never disagree.
     */
    fun forProbability(probability: Double): Color = when {
        probability > 0.70 -> probabilityHigh
        probability >= 0.30 -> probabilityMedium
        else -> probabilityLow
    }
}

internal val LightSemanticColors = BatteryCastSemanticColors(
    probabilityHigh = EmeraldInk,
    probabilityMedium = AmberInk,
    probabilityLow = CrimsonInk,
    band50 = EmeraldInk.copy(alpha = 0.30f),
    band80 = EmeraldInk.copy(alpha = 0.15f),
    band90 = EmeraldInk.copy(alpha = 0.05f),
    medianLine = EmeraldInk,
    thresholdLine = Color(0x33000000),
    chargingAccent = EmeraldInk,
    gridLine = Color(0x0D000000),
    preliminaryAccent = AmberInk,
    positive = EmeraldInk,
    caution = AmberInk,
    risk = CrimsonInk,
    cardStroke = HairlineLight,
    trackInactive = PaperTrack,
    crosshair = Color(0x66000000),
)

internal val DarkSemanticColors = BatteryCastSemanticColors(
    probabilityHigh = Emerald,
    probabilityMedium = Amber,
    probabilityLow = Crimson,
    // The fan's three fills, at the opacities that keep them distinguishable when stacked on a
    // near-black canvas: 30 % / 15 % / 5 %.
    band50 = Emerald.copy(alpha = 0.30f),
    band80 = Emerald.copy(alpha = 0.15f),
    band90 = Emerald.copy(alpha = 0.05f),
    medianLine = Emerald,
    thresholdLine = Color(0x33FFFFFF),
    chargingAccent = Emerald,
    gridLine = Color(0x0DFFFFFF),
    preliminaryAccent = Amber,
    positive = Emerald,
    caution = Amber,
    risk = Crimson,
    cardStroke = HairlineDark,
    trackInactive = Track,
    crosshair = Color(0x99FFFFFF),
)

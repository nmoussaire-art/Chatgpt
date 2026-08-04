package com.batterycast.quant.core.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * "Predictive Energy" — the v2 palette.
 *
 * Five colours, each with exactly one job. The failure of v1 was that mint meant "link", "chart",
 * "positive" and "slider" at once, while amber meant "preliminary", "headline number", "every
 * progress bar" and "caution" — so the whole app read as a warning even when the message was
 * "you're fine". Here each hue carries a single meaning and nothing else is allowed to borrow it.
 *
 * | Hue   | Means                                          |
 * |-------|------------------------------------------------|
 * | Mint  | healthy, sufficient, observed, complete         |
 * | Blue  | prediction, information, neutral selection      |
 * | Amber | uncertain, tight margin, charge recommended     |
 * | Coral | unlikely to reach the target                    |
 * | Slate | unavailable, still learning                     |
 */

// --- Dark: deep, almost-black navy. The app's home ground. ---
internal val Navy900 = Color(0xFF07111F) // main background
internal val Navy850 = Color(0xFF0B1728) // background gradient tail
internal val Navy800 = Color(0xFF0E1A2B) // lowest container
internal val Navy700 = Color(0xFF111E30) // elevated surface
internal val Navy600 = Color(0xFF17263A) // secondary surface
internal val Navy500 = Color(0xFF1F3149) // highest container / track
internal val Navy400 = Color(0xFF2A3E58) // outline variant

internal val Mint = Color(0xFF5DE4B0)
internal val MintBright = Color(0xFF7BF0C4)
internal val MintDeep = Color(0xFF2FA37B)
internal val MintInk = Color(0xFF04231A)

internal val Forecast = Color(0xFF78A9FF)
internal val ForecastDeep = Color(0xFF3D6FCC)
internal val ForecastInk = Color(0xFF041428)

internal val Amber = Color(0xFFFFC857)
internal val AmberDeep = Color(0xFFB88420)
internal val AmberInk = Color(0xFF261A03)

internal val Coral = Color(0xFFFF8F8F)
internal val CoralDeep = Color(0xFFC25A5A)
internal val CoralInk = Color(0xFF2A0E0E)

internal val InkPrimary = Color(0xFFF6F8FC)
internal val InkSecondary = Color(0xFFA8B3C7)
internal val InkMuted = Color(0xFF718096)

// --- Light: the same identity on paper. Mint and blue darken enough to hold contrast on white. ---
internal val Paper000 = Color(0xFFFFFFFF)
internal val Paper050 = Color(0xFFF7F9FC)
internal val Paper100 = Color(0xFFEEF2F8)
internal val Paper200 = Color(0xFFE2E8F2)
internal val Paper300 = Color(0xFFCBD5E5)

internal val MintOnPaper = Color(0xFF0E9B70)
internal val ForecastOnPaper = Color(0xFF2E6BD6)
internal val AmberOnPaper = Color(0xFF9A6B08)
internal val CoralOnPaper = Color(0xFFC4453F)

internal val PaperInkPrimary = Color(0xFF0B1524)
internal val PaperInkSecondary = Color(0xFF4A5568)
internal val PaperInkMuted = Color(0xFF8494AB)

/**
 * How a survival probability should feel, and what the app should say about it.
 *
 * Deliberately four steps rather than a continuous gradient: a forecast that slides imperceptibly
 * from green to amber teaches the user nothing. A named tone with a sentence attached does.
 */
enum class RiskTone {
    /** Comfortably above the target. */
    SECURE,

    /** Likely to make it, but without much room. */
    TIGHT,

    /** Roughly even. Worth acting on. */
    AT_RISK,

    /** Will probably not make it without charging. */
    CRITICAL,
    ;

    companion object {
        fun forProbability(probability: Double): RiskTone = when {
            probability >= 0.85 -> SECURE
            probability >= 0.60 -> TIGHT
            probability >= 0.35 -> AT_RISK
            else -> CRITICAL
        }
    }
}

/**
 * Colours that carry meaning rather than decoration.
 *
 * Kept outside the Material scheme because they are semantic: [healthy] is not "the primary
 * colour", it is what "enough battery" looks like, and it must stay legible in either scheme.
 */
data class BatteryCastSemanticColors(
    val healthy: Color,
    val forecast: Color,
    val caution: Color,
    val risk: Color,
    val learning: Color,

    /** Hero surface gradient, top-left to bottom-right. */
    val heroSurfaceTop: Color,
    val heroSurfaceBottom: Color,

    /** The ring: unfilled track, the part you keep, the part you are expected to spend. */
    val ringTrack: Color,
    val ringSpend: Color,

    /** Median forecast line and the fill beneath it. */
    val chartLine: Color,
    val chartFillTop: Color,
    val chartFillBottom: Color,
    /** The band shown by default (80%). */
    val chartBand: Color,
    /** Optional wider/narrower bands, only when the user turns them on. */
    val chartBandFaint: Color,
    val chartGrid: Color,
    val chartThreshold: Color,

    val onHero: Color,
    val onHeroMuted: Color,
) {
    fun forTone(tone: RiskTone): Color = when (tone) {
        RiskTone.SECURE -> healthy
        RiskTone.TIGHT -> caution
        RiskTone.AT_RISK -> caution
        RiskTone.CRITICAL -> risk
    }

    fun forProbability(probability: Double): Color = forTone(RiskTone.forProbability(probability))
}

internal val DarkSemanticColors = BatteryCastSemanticColors(
    healthy = Mint,
    forecast = Forecast,
    caution = Amber,
    risk = Coral,
    learning = InkMuted,
    heroSurfaceTop = Color(0xFF14243A),
    heroSurfaceBottom = Color(0xFF0B1524),
    ringTrack = Navy500,
    ringSpend = Color(0x385DE4B0),
    chartLine = MintBright,
    chartFillTop = Color(0x385DE4B0),
    chartFillBottom = Color(0x005DE4B0),
    chartBand = Color(0x2E78A9FF),
    chartBandFaint = Color(0x1478A9FF),
    chartGrid = Color(0x1AA8B3C7),
    chartThreshold = Color(0x66A8B3C7),
    onHero = InkPrimary,
    onHeroMuted = InkSecondary,
)

internal val LightSemanticColors = BatteryCastSemanticColors(
    healthy = MintOnPaper,
    forecast = ForecastOnPaper,
    caution = AmberOnPaper,
    risk = CoralOnPaper,
    learning = PaperInkMuted,
    heroSurfaceTop = Color(0xFFFFFFFF),
    heroSurfaceBottom = Color(0xFFEDF3FA),
    ringTrack = Paper200,
    ringSpend = Color(0x2E0E9B70),
    chartLine = MintOnPaper,
    chartFillTop = Color(0x330E9B70),
    chartFillBottom = Color(0x000E9B70),
    chartBand = Color(0x2E2E6BD6),
    chartBandFaint = Color(0x142E6BD6),
    chartGrid = Color(0x1A4A5568),
    chartThreshold = Color(0x664A5568),
    onHero = PaperInkPrimary,
    onHeroMuted = PaperInkSecondary,
)

package com.batterycast.quant.forecasting.scenario

import com.batterycast.quant.forecasting.ForecastContext
import com.batterycast.quant.forecasting.ForecastEngine
import com.batterycast.quant.forecasting.ForecastRequest
import com.batterycast.quant.forecasting.model.UsageRegime
import com.batterycast.quant.forecasting.shrinkage.SupportLevel
import com.batterycast.quant.forecasting.sim.RegimeSchedule
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A what-if the user can ask about.
 *
 * [regime] names the behaviour the scenario imposes; [durationMs] is how long it lasts before the
 * phone returns to its ordinary learned pattern.
 */
enum class Scenario(
    val displayName: String,
    val description: String,
    val regime: UsageRegime?,
    val durationMs: Long,
) {
    NORMAL(
        displayName = "Normal use",
        description = "Your ordinary pattern for this time of day.",
        regime = null,
        durationMs = 0L,
    ),
    VIDEO_ONE_HOUR(
        displayName = "1 hour of video",
        description = "An hour of media-like use, then back to normal.",
        regime = UsageRegime.MEDIA_LIKE,
        durationMs = 60 * 60 * 1000L,
    ),
    GAMING_THIRTY_MINUTES(
        displayName = "30 minutes of gaming",
        description = "Half an hour of gaming-like use, then back to normal.",
        regime = UsageRegime.GAMING_LIKE,
        durationMs = 30 * 60 * 1000L,
    ),
    NAVIGATION_FORTY_FIVE(
        displayName = "45 minutes of navigation",
        description = "A 45-minute navigation trip, then back to normal.",
        regime = UsageRegime.NAVIGATION_LIKE,
        durationMs = 45 * 60 * 1000L,
    ),
    HOTSPOT_ONE_HOUR(
        displayName = "1 hour of hotspot",
        description = "An hour of sustained heavy radio use, then back to normal.",
        regime = UsageRegime.HEAVY_USE,
        durationMs = 60 * 60 * 1000L,
    ),
    STANDBY(
        displayName = "Left alone",
        description = "Screen off for the rest of the forecast.",
        regime = UsageRegime.STANDBY,
        durationMs = 24 * 60 * 60 * 1000L,
    ),
    REDUCED_SCREEN(
        displayName = "Reduced screen use",
        description = "Light use only for the rest of the forecast.",
        regime = UsageRegime.LIGHT_USE,
        durationMs = 24 * 60 * 60 * 1000L,
    ),
    POWER_SAVING(
        displayName = "Power saving on",
        description = "Your ordinary pattern with battery saver enabled.",
        regime = null,
        durationMs = 0L,
    ),
    AIRPLANE_MODE(
        displayName = "Airplane mode",
        description = "Radios off, screen mostly off — as on a flight.",
        regime = UsageRegime.STANDBY,
        durationMs = 24 * 60 * 60 * 1000L,
    ),
}

/** How much of a scenario's answer rests on this user's own observations. */
enum class ScenarioConfidence(val label: String) {
    /** The exact regime has been observed on this device enough times to model directly. */
    OBSERVED("Based on your phone's own behaviour"),

    /** Observed, but only a handful of times. */
    LIMITED("Based on limited observations"),

    /** Substituted from the closest regime that has been observed, with a widened interval. */
    SUBSTITUTED("Estimated from the closest behaviour observed"),

    /** Not enough has been observed to answer at all. */
    UNAVAILABLE("Not enough observed behaviour yet"),
}

data class ScenarioOutcome(
    val scenario: Scenario,
    val confidence: ScenarioConfidence,
    /** P(above reserve at the target time). Null when the scenario cannot be answered. */
    val survivalProbability: Double?,
    val medianPercentAtTarget: Double?,
    val conservativePercentAtTarget: Double?,
    val medianTimeToReserveMs: Long?,
    /** Which regime actually drove the simulation, when a substitution was made. */
    val substitutedFrom: UsageRegime?,
    val note: String?,
)

/**
 * Compares the baseline forecast with what-if scenarios.
 *
 * The honesty rule here is specific and load-bearing. Scenario rates come from the user's own
 * history whenever the regime has been observed. Where it has not — a phone that has never been
 * used for navigation, say — the app does **not** fall back to a plausible-sounding universal
 * figure for what navigation costs. It substitutes the closest regime it *has* seen, says so, and
 * widens the interval. A scenario the device cannot speak to at all returns
 * [ScenarioConfidence.UNAVAILABLE] rather than a number.
 */
@Singleton
class ScenarioEngine @Inject constructor(
    private val forecastEngine: ForecastEngine,
) {

    fun evaluate(
        context: ForecastContext,
        scenario: Scenario,
        targetMs: Long,
        reservePercent: Double,
    ): ScenarioOutcome {
        val horizonMs = (targetMs - context.nowMs).coerceAtLeast(MIN_HORIZON_MS)

        if (scenario == Scenario.POWER_SAVING) {
            return evaluatePowerSaving(context, targetMs, reservePercent, horizonMs)
        }

        if (scenario == Scenario.NORMAL) {
            val result = forecastEngine.simulate(
                context,
                ForecastRequest(horizonMs = horizonMs, reservePercent = reservePercent),
            )
            return ScenarioOutcome(
                scenario = scenario,
                confidence = confidenceFor(context, context.currentRegime, substituted = false),
                survivalProbability = result.probabilityAbove(targetMs, reservePercent),
                medianPercentAtTarget = result.pointAt(targetMs)?.median,
                conservativePercentAtTarget = result.pointAt(targetMs)?.p10,
                medianTimeToReserveMs = result.medianTimeToThresholdMs(nearestThreshold(reservePercent)),
                substitutedFrom = null,
                note = null,
            )
        }

        val requested = scenario.regime ?: return unavailable(scenario)
        val resolved = resolveRegime(context, requested) ?: return unavailable(scenario)
        val substituted = resolved != requested

        val schedule = RegimeSchedule(
            listOf(RegimeSchedule.Entry(resolved, minOf(scenario.durationMs, horizonMs))),
        )

        val result = forecastEngine.simulate(
            context,
            ForecastRequest(
                horizonMs = horizonMs,
                reservePercent = reservePercent,
                regimeOverride = schedule,
                // A substituted regime is a weaker claim, so the drain is widened rather than
                // presented at the same confidence as an observed one.
                drainMultiplier = if (substituted) SUBSTITUTION_INFLATION else 1.0,
            ),
        )

        val note = when {
            substituted && scenario == Scenario.HOTSPOT_ONE_HOUR ->
                "Android does not expose hotspot power draw to apps, so this uses the heaviest " +
                    "usage pattern actually observed on this phone, with a wider interval."
            substituted && scenario == Scenario.AIRPLANE_MODE ->
                "Airplane mode has not been observed on this phone, so this uses your observed " +
                    "screen-off behaviour, with a wider interval. Real airplane mode is usually " +
                    "a little better than this."
            substituted ->
                "${requested.displayName} has not been observed on this phone yet, so this uses " +
                    "${resolved.displayName.lowercase()} instead, with a wider interval."
            else -> null
        }

        return ScenarioOutcome(
            scenario = scenario,
            confidence = confidenceFor(context, resolved, substituted),
            survivalProbability = result.probabilityAbove(targetMs, reservePercent),
            medianPercentAtTarget = result.pointAt(targetMs)?.median,
            conservativePercentAtTarget = result.pointAt(targetMs)?.p10,
            medianTimeToReserveMs = result.medianTimeToThresholdMs(nearestThreshold(reservePercent)),
            substitutedFrom = if (substituted) requested else null,
            note = note,
        )
    }

    fun evaluateAll(
        context: ForecastContext,
        targetMs: Long,
        reservePercent: Double,
    ): List<ScenarioOutcome> = Scenario.entries.map { evaluate(context, it, targetMs, reservePercent) }

    /**
     * Power saving is only modelled when its effect has been measured on this device.
     *
     * Manufacturers implement battery saver very differently — on some it is a 10 % improvement,
     * on others closer to 40 % — so an assumed figure would be a guess dressed as a forecast.
     */
    private fun evaluatePowerSaving(
        context: ForecastContext,
        targetMs: Long,
        reservePercent: Double,
        horizonMs: Long,
    ): ScenarioOutcome {
        val multiplier = context.snapshot.powerSaveMultiplier
            ?: return ScenarioOutcome(
                scenario = Scenario.POWER_SAVING,
                confidence = ScenarioConfidence.UNAVAILABLE,
                survivalProbability = null,
                medianPercentAtTarget = null,
                conservativePercentAtTarget = null,
                medianTimeToReserveMs = null,
                substitutedFrom = null,
                note = "BatteryCast has not yet seen this phone run with and without battery " +
                    "saver, so it cannot say what saver would do here. Use it for a while and " +
                    "this comparison will appear.",
            )

        val result = forecastEngine.simulate(
            context,
            ForecastRequest(
                horizonMs = horizonMs,
                reservePercent = reservePercent,
                drainMultiplier = if (context.latest.powerSaveEnabled) 1.0 else multiplier,
            ),
        )

        return ScenarioOutcome(
            scenario = Scenario.POWER_SAVING,
            confidence = ScenarioConfidence.OBSERVED,
            survivalProbability = result.probabilityAbove(targetMs, reservePercent),
            medianPercentAtTarget = result.pointAt(targetMs)?.median,
            conservativePercentAtTarget = result.pointAt(targetMs)?.p10,
            medianTimeToReserveMs = result.medianTimeToThresholdMs(nearestThreshold(reservePercent)),
            substitutedFrom = null,
            note = if (context.latest.powerSaveEnabled) {
                "Battery saver is already on, so this matches your current forecast."
            } else {
                "Measured on this phone: saver has changed drain by a factor of " +
                    String.format("%.2f", multiplier) + "."
            },
        )
    }

    /**
     * Finds the regime to actually simulate.
     *
     * Falls back down an ordered ladder of increasingly general observed behaviours; returns null
     * when even the general ones have no support, which is the "not enough data" answer.
     */
    private fun resolveRegime(context: ForecastContext, requested: UsageRegime): UsageRegime? {
        if (hasSupport(context, requested)) return requested
        return fallbackLadder(requested).firstOrNull { hasSupport(context, it) }
    }

    private fun fallbackLadder(regime: UsageRegime): List<UsageRegime> = when (regime) {
        UsageRegime.GAMING_LIKE -> listOf(UsageRegime.MEDIA_LIKE, UsageRegime.HEAVY_USE, UsageRegime.INTERACTIVE)
        UsageRegime.MEDIA_LIKE -> listOf(UsageRegime.HEAVY_USE, UsageRegime.INTERACTIVE)
        UsageRegime.NAVIGATION_LIKE -> listOf(UsageRegime.HEAVY_USE, UsageRegime.INTERACTIVE)
        UsageRegime.HEAVY_USE -> listOf(UsageRegime.INTERACTIVE)
        UsageRegime.LIGHT_USE -> listOf(UsageRegime.INTERACTIVE, UsageRegime.STANDBY)
        UsageRegime.STANDBY -> listOf(UsageRegime.BACKGROUND_ACTIVE, UsageRegime.LIGHT_USE)
        else -> listOf(UsageRegime.UNCLASSIFIED_DISCHARGE)
    }

    private fun hasSupport(context: ForecastContext, regime: UsageRegime): Boolean {
        val distribution = context.snapshot.drainByRegime[regime] ?: return false
        return distribution.support != SupportLevel.NONE && distribution.effectiveSamples >= MIN_SUPPORT_SAMPLES
    }

    private fun confidenceFor(
        context: ForecastContext,
        regime: UsageRegime,
        substituted: Boolean,
    ): ScenarioConfidence {
        if (substituted) return ScenarioConfidence.SUBSTITUTED
        val distribution = context.snapshot.drainByRegime[regime] ?: return ScenarioConfidence.SUBSTITUTED
        return when (distribution.support) {
            SupportLevel.STRONG -> ScenarioConfidence.OBSERVED
            SupportLevel.MODERATE -> ScenarioConfidence.OBSERVED
            SupportLevel.SPARSE -> ScenarioConfidence.LIMITED
            SupportLevel.NONE -> ScenarioConfidence.UNAVAILABLE
        }
    }

    private fun unavailable(scenario: Scenario) = ScenarioOutcome(
        scenario = scenario,
        confidence = ScenarioConfidence.UNAVAILABLE,
        survivalProbability = null,
        medianPercentAtTarget = null,
        conservativePercentAtTarget = null,
        medianTimeToReserveMs = null,
        substitutedFrom = null,
        note = "Not enough observed behaviour yet to model this scenario on your phone.",
    )

    private fun nearestThreshold(reservePercent: Double): Double =
        com.batterycast.quant.forecasting.sim.MonteCarloSimulator.THRESHOLDS
            .minByOrNull { kotlin.math.abs(it - reservePercent) }
            ?: 10.0

    companion object {
        const val MIN_HORIZON_MS = 30 * 60 * 1000L

        /** Effective observations before a regime is considered modelled at all. */
        const val MIN_SUPPORT_SAMPLES = 0.5

        /** Drain inflation applied when a scenario had to borrow another regime's rate. */
        const val SUBSTITUTION_INFLATION = 1.15
    }
}

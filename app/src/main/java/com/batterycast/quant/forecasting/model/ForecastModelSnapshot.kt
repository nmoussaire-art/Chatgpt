package com.batterycast.quant.forecasting.model

import com.batterycast.quant.forecasting.shrinkage.ShrunkEstimate
import com.batterycast.quant.forecasting.shrinkage.SupportLevel
import com.batterycast.quant.forecasting.uncertainty.ResidualModel
import com.batterycast.quant.telemetry.model.PlugType
import com.batterycast.quant.telemetry.model.SensorCapabilities
import kotlin.math.sqrt

/**
 * A rate and how well it is known.
 *
 * [variance] is the spread of the underlying behaviour; [effectiveSamples] says how confidently
 * the mean itself is pinned down. The simulator uses both, and they mean different things: a
 * phone whose evening drain genuinely varies a lot has high variance even with plenty of data,
 * while a phone with three observations has an uncertain *mean* regardless of its variance.
 */
data class RateDistribution(
    val mean: Double,
    val variance: Double,
    val effectiveSamples: Double,
    val support: SupportLevel,
) {
    val standardDeviation: Double get() = sqrt(variance.coerceAtLeast(0.0))

    /** Standard error of the mean: how uncertain the central estimate is. */
    val meanStandardError: Double
        get() = standardDeviation / sqrt(effectiveSamples.coerceAtLeast(1.0))

    companion object {
        fun from(estimate: ShrunkEstimate): RateDistribution = RateDistribution(
            mean = estimate.mean,
            variance = estimate.variance,
            effectiveSamples = estimate.effectiveSamples,
            support = estimate.supportLevel,
        )
    }
}

/**
 * Transition probabilities between usage regimes, with a persistence-weighted Dirichlet prior.
 *
 * The prior matters: with no history at all, the honest assumption is that whatever the phone is
 * doing now, it will probably keep doing for a while and might change. That is a diagonal-heavy
 * matrix, and it is what the app starts from before it has watched the user's day.
 */
data class RegimeTransitionMatrix(
    private val counts: Map<Pair<UsageRegime, UsageRegime>, Double>,
    private val states: List<UsageRegime>,
    val observedTransitions: Double,
) {
    /** Row of transition probabilities out of [from], summing to one. */
    fun row(from: UsageRegime): Map<UsageRegime, Double> {
        val alphas = states.associateWith { to ->
            val prior = if (to == from) SELF_PRIOR else OTHER_PRIOR
            prior + (counts[from to to] ?: 0.0)
        }
        val total = alphas.values.sum()
        return alphas.mapValues { it.value / total }
    }

    val hasObservedBehaviour: Boolean get() = observedTransitions >= MIN_TRANSITIONS_FOR_PERSONALISATION

    companion object {
        /**
         * Prior weight on staying in the current regime, in pseudo-transitions. Chosen so that
         * with no data the chain stays put for a few steps on average — matching the fact that
         * phone usage arrives in blocks, not in independent five-minute draws.
         */
        const val SELF_PRIOR = 4.0

        /** Prior weight on each alternative regime. Small, but never zero. */
        const val OTHER_PRIOR = 0.25

        const val MIN_TRANSITIONS_FOR_PERSONALISATION = 30.0

        fun of(
            counts: Map<Pair<UsageRegime, UsageRegime>, Double>,
            states: List<UsageRegime>,
        ): RegimeTransitionMatrix = RegimeTransitionMatrix(
            counts = counts,
            states = states,
            observedTransitions = counts.values.sum(),
        )
    }
}

/**
 * Everything the simulator needs, read once from the learned model.
 *
 * Assembling it up front keeps the simulation loop free of database access — 2 000 paths over a
 * twelve-hour horizon is a few hundred thousand steps, and it has to finish while the user is
 * looking at the screen.
 */
data class ForecastModelSnapshot(
    val drainByRegime: Map<UsageRegime, RateDistribution>,
    val chargeRates: Map<ChargeCellKey, RateDistribution>,
    val transitions: RegimeTransitionMatrix,
    /** Multiplier on drain rate by battery-percentage band, learned from this device. */
    val socCurve: Map<SocDecile, Double>,
    val thermalMultipliers: Map<ThermalBucket, Double>,
    val powerSaveMultiplier: Double?,
    val residualModel: ResidualModel,
    val capabilities: SensorCapabilities,
    val maturity: DataMaturity,
    /** Device-wide fallback drain rate, used when the current regime has no cell of its own. */
    val globalDrain: RateDistribution?,
    val globalChargeRate: RateDistribution?,
    val estimatedFullCapacityMilliAh: Double?,
) {
    fun drainFor(regime: UsageRegime): RateDistribution? = drainByRegime[regime] ?: globalDrain

    fun chargeRateFor(plugType: PlugType, socBucket: SocBucket): RateDistribution? =
        chargeRates[ChargeCellKey(plugType, socBucket)]
            ?: chargeRates[ChargeCellKey(PlugType.UNKNOWN, socBucket)]
            ?: globalChargeRate

    /** Multiplier for the battery band, defaulting to 1 where the device has not been observed. */
    fun socMultiplier(percent: Double): Double =
        socCurve[SocDecile.forPercent(percent)] ?: 1.0

    fun thermalMultiplier(bucket: ThermalBucket): Double = thermalMultipliers[bucket] ?: 1.0
}

data class ChargeCellKey(val plugType: PlugType, val socBucket: SocBucket)

/**
 * How much the app actually knows, which gates what it is willing to claim.
 *
 * Each level unlocks features only once the evidence behind them exists. Nothing is shown early
 * with a disclaimer attached — a day-of-week comparison with no second day is not a weak forecast,
 * it is a fabricated one.
 */
enum class DataMaturity(val displayName: String) {
    /** Nothing usable yet: fewer than two readings, or no measurable change. */
    COLLECTING("Collecting live battery behaviour"),

    /** Live instantaneous measurements only. Clearly labelled preliminary wherever shown. */
    PRELIMINARY("Preliminary live estimate"),

    /** Enough real change observed for a time-to-empty forecast and survival probabilities. */
    BASIC("Live forecast"),

    /** Regime-specific rates and charging estimates are supported by real observations. */
    PERSONALISED("Personalised forecast"),

    /** A week or more: day-of-week patterns and accuracy statistics are meaningful. */
    MATURE("Fully personalised forecast"),
    ;

    val supportsSurvivalProbability: Boolean get() = ordinal >= BASIC.ordinal
    val supportsRegimeForecasts: Boolean get() = ordinal >= PERSONALISED.ordinal
    val supportsChargePlanning: Boolean get() = ordinal >= BASIC.ordinal
    val supportsDayOfWeek: Boolean get() = ordinal >= MATURE.ordinal
    val supportsAccuracyReport: Boolean get() = ordinal >= PERSONALISED.ordinal
    val isPreliminary: Boolean get() = this == PRELIMINARY
}

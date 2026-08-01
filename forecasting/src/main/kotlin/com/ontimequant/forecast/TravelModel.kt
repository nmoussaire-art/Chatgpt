package com.ontimequant.forecast

import com.ontimequant.model.DayType
import com.ontimequant.model.PersonalizationLevel
import com.ontimequant.model.TimeBucket
import com.ontimequant.model.TrafficRegime
import java.time.DayOfWeek
import java.time.Instant
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/** One learned data point: how wrong the routing API was on a completed journey. */
data class TravelObservation(
    val at: Instant,
    val routeKey: String,
    val pairKey: String,
    val bucket: TimeBucket,
    val dayType: DayType,
    val dayOfWeek: DayOfWeek,
    val regime: TrafficRegime,
    val predictedSeconds: Double,
    val actualSeconds: Double,
    val weatherSeverity: Double?,
    val eventPressure: Double?,
) {
    /**
     * The model works in log-ratio space, `log(actual / predicted)`.
     *
     * Multiplicative rather than additive: a 5-minute miss on a 10-minute trip and on a
     * 60-minute trip are very different errors, and travel-time errors are known to scale
     * with trip length. Log space also makes the resulting duration strictly positive.
     */
    val logRatio: Double
        get() = if (predictedSeconds > 0 && actualSeconds > 0) ln(actualSeconds / predictedSeconds) else 0.0
}

/** The situation a forecast is being made for. */
data class ForecastContext(
    val routeKey: String,
    val pairKey: String,
    val bucket: TimeBucket,
    val dayType: DayType,
    val dayOfWeek: DayOfWeek,
    val regime: TrafficRegime,
    val weatherSeverity: Double?,
    val eventPressure: Double,
    val routingIsLive: Boolean,
    val routingAgeMinutes: Long,
)

/**
 * Draws a multiplicative travel-time shock `exp(ε)`.
 *
 * Two regimes:
 *  - **Empirical** — once enough personal residuals exist, resample them (a smoothed
 *    bootstrap: pick a residual, add Gaussian jitter sized by Silverman's rule). This
 *    preserves genuine skew and fat tails the user actually experiences.
 *  - **Parametric** — otherwise, a log-normal with the shrunk location and scale.
 *
 * Both regimes then add a compound-Poisson style right tail for disruptions, which is
 * what makes the arrival distribution asymmetric: you can be very late, but you cannot
 * be symmetrically very early.
 */
class ResidualSampler internal constructor(
    private val pool: DoubleArray,
    private val poolWeightsCumulative: DoubleArray?,
    private val jitter: Double,
    private val location: Double,
    private val scale: Double,
    private val useEmpirical: Boolean,
    private val incidentProbability: Double,
    private val incidentMeanSeconds: Double,
) {
    val effectiveScale: Double get() = scale
    val effectiveLocation: Double get() = location
    val isEmpirical: Boolean get() = useEmpirical
    val incidentP: Double get() = incidentProbability
    val incidentMean: Double get() = incidentMeanSeconds

    /** Returns the multiplicative shock applied to the routing baseline. */
    fun sampleMultiplier(rng: Xoshiro256): Double {
        val eps = if (useEmpirical && pool.isNotEmpty()) {
            val idx = sampleIndex(rng)
            // Centre the pool then re-apply the shrunk location so the empirical shape is
            // kept while the *level* still respects hierarchical shrinkage.
            (pool[idx] - poolMean) + location + jitter * rng.nextGaussian()
        } else {
            location + scale * rng.nextGaussian()
        }
        return exp(eps.coerceIn(-1.2, 1.6))
    }

    /** Additive disruption delay in seconds (0 most of the time). */
    fun sampleIncidentSeconds(rng: Xoshiro256): Double =
        if (incidentProbability > 0 && rng.nextBoolean(incidentProbability)) {
            rng.nextExponential(incidentMeanSeconds)
        } else {
            0.0
        }

    private val poolMean: Double = if (pool.isEmpty()) 0.0 else pool.average()

    private fun sampleIndex(rng: Xoshiro256): Int {
        val cum = poolWeightsCumulative ?: return rng.nextInt(pool.size)
        val u = rng.nextDouble() * cum[cum.size - 1]
        var lo = 0
        var hi = cum.size - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (cum[mid] < u) lo = mid + 1 else hi = mid
        }
        return lo
    }
}

/** Everything the engine learned about how wrong routing estimates are for this user. */
class FittedTravelModel internal constructor(
    private val priors: ModelPriors,
    private val groups: Map<String, ResidualGroup>,
    private val globalGroup: ResidualGroup,
    val weatherCoefficient: Double,
    val weatherCoefficientObservations: Int,
    val totalObservations: Int,
) {

    internal data class ResidualGroup(
        val key: String,
        val ewma: EwmaAccumulator,
        val residuals: List<Double>,
        val weights: List<Double>,
    ) {
        val robustSigma: Double get() = Stats.madSigma(Stats.winsorise(residuals))
        val nEff: Double get() = ewma.effectiveSampleSize
    }

    /**
     * The nesting: route×bucket ⊂ route ⊂ origin/destination pair ⊂ everything the user
     * has ever driven. Ordered from most general to most specific.
     */
    private fun chain(context: ForecastContext): List<Triple<String, ResidualGroup?, Double>> = listOf(
        Triple("Your overall history", globalGroup, priors.kappaUserGlobal),
        Triple("This origin/destination", groups[pairKeyOf(context)], priors.kappaPair),
        Triple("This route", groups[routeKeyOf(context)], priors.kappaRoute),
        Triple("This route at this time of day", groups[bucketKeyOf(context)], priors.kappaRouteBucket),
    )

    /**
     * Evidence available at each level of the chain.
     *
     * The levels are **nested**: a trip on this route at this time of day appears in all
     * four groups. Shrinking level by level with each group's full sample size would count
     * one journey as four independent pieces of evidence, and a handful of trips would run
     * away with the estimate.
     *
     * The broadest level is estimated normally from all of the user's history. Every level
     * below it estimates a *deviation* from its parent, and a deviation is only identifiable
     * to the extent that the parent contains journeys the child does not. Treating it as a
     * two-sample contrast, the evidence for the deviation is the harmonic-style quantity
     *
     * ```
     *   n_dev = n_child · (n_parent − n_child) / n_parent
     * ```
     *
     * which is zero exactly when the child *is* the parent — the case where no
     * between-group difference can be observed — and peaks when the split is even.
     */
    private fun deviationEvidence(chain: List<Triple<String, ResidualGroup?, Double>>): List<Double> {
        val n = chain.map { it.second?.nEff ?: 0.0 }
        return n.indices.map { k ->
            if (k == 0) {
                n[0]
            } else {
                val parent = n[k - 1]
                val child = n[k]
                if (parent <= 0.0 || child <= 0.0) 0.0
                else child * max(0.0, parent - child) / parent
            }
        }
    }

    /** Hierarchical bias estimate: system prior → user global → O/D pair → route → route×bucket. */
    fun bias(context: ForecastContext): ShrunkEstimate {
        val chain = chain(context)
        val excess = deviationEvidence(chain)
        val levels = chain.mapIndexedNotNull { k, (label, group, kappa) ->
            if (group == null || group.residuals.isEmpty() || excess[k] <= 0.0) null
            else Shrinkage.Level(label, group.ewma.mean, excess[k], kappa)
        }
        return Shrinkage.hierarchical(priors.logBias, levels)
    }

    /** Hierarchical residual-scale estimate, combined on the variance scale. */
    fun sigma(context: ForecastContext): ShrunkEstimate {
        val chain = chain(context)
        val excess = deviationEvidence(chain)
        var posterior = priors.logSigma
        var credit = 0.0
        var nEff = 0.0
        var name = "System prior"
        chain.forEachIndexed { k, (label, group, kappa) ->
            if (group != null && group.residuals.size >= 3 && excess[k] > 0.0) {
                val n = excess[k]
                val w = n / (n + kappa)
                posterior = Shrinkage.scaleToward(posterior, group.robustSigma, n, kappa)
                credit = w + (1 - w) * credit
                if (w > 0.10) { name = label; nEff = n }
            }
        }
        val adjusted = posterior.coerceIn(priors.minLogSigma, priors.maxLogSigma)
        return ShrunkEstimate(adjusted, nEff, credit.coerceIn(0.0, 1.0), name)
    }

    /**
     * Build the sampler for one forecast context, applying the multiplicative modifiers
     * for traffic regime, weather, event pressure and data staleness.
     */
    fun sampler(context: ForecastContext, baselineSeconds: Double, staticSeconds: Double): SamplerBundle {
        val biasEstimate = bias(context)
        val sigmaEstimate = sigma(context)

        val weatherSeverity = context.weatherSeverity
        val weatherMeanAdj = if (weatherSeverity != null) weatherCoefficient * weatherSeverity else 0.0
        val eventMeanAdj = if (context.eventPressure > priors.eventMeanThreshold) {
            priors.eventMeanLogCoefficient * (context.eventPressure - priors.eventMeanThreshold) /
                (1.0 - priors.eventMeanThreshold)
        } else {
            0.0
        }

        var scale = sigmaEstimate.value * context.regime.volatilityMultiplier
        scale *= if (weatherSeverity != null) {
            1.0 + priors.weatherSigmaGain * weatherSeverity
        } else {
            1.0 + priors.missingWeatherSigmaGain
        }
        scale *= 1.0 + priors.eventSigmaGain * context.eventPressure
        if (!context.routingIsLive) {
            scale *= StalenessPolicy.sigmaMultiplier(context.routingAgeMinutes, priors.cachedRoutingSigmaGain)
        }
        scale = scale.coerceIn(priors.minLogSigma, priors.maxLogSigma)

        val incidentP = (
            priors.incidentProbability * regimeIncidentMultiplier(context.regime) +
                priors.eventIncidentGain * context.eventPressure +
                (weatherSeverity ?: 0.0) * 0.05
            ).coerceIn(0.0, 0.45)

        // Disruption cost scales with the loaded duration, not the free-flow one: the same
        // blocked lane is far more expensive when the network is already at capacity.
        val loaded = max(baselineSeconds, staticSeconds)
        val incidentMean = max(priors.incidentMeanFloorMinutes * 60.0, priors.incidentMeanFraction * loaded)

        val deepest = deepestGroup(context)
        val useEmpirical = deepest != null &&
            deepest.nEff >= priors.minEffectiveSampleForEmpirical &&
            deepest.residuals.size >= 6

        val pool = deepest?.residuals?.let { Stats.winsorise(it, 0.02).toDoubleArray() } ?: DoubleArray(0)
        val cumulative = deepest?.weights?.let { w ->
            DoubleArray(w.size).also { out ->
                var acc = 0.0
                for (i in w.indices) { acc += w[i]; out[i] = acc }
            }
        }
        val jitter = if (pool.size >= 2) {
            // Silverman's rule of thumb for a smoothed bootstrap.
            0.9 * min(Stats.madSigma(pool.toList()), sigmaEstimate.value) * Math.pow(pool.size.toDouble(), -0.2)
        } else 0.0

        val sampler = ResidualSampler(
            pool = pool,
            poolWeightsCumulative = if (cumulative != null && cumulative.size == pool.size) cumulative else null,
            jitter = max(0.01, jitter),
            location = biasEstimate.value + weatherMeanAdj + eventMeanAdj,
            scale = scale,
            useEmpirical = useEmpirical,
            incidentProbability = incidentP,
            incidentMeanSeconds = incidentMean,
        )
        return SamplerBundle(
            sampler = sampler,
            bias = biasEstimate,
            sigma = sigmaEstimate,
            weatherLogAdjustment = weatherMeanAdj,
            eventLogAdjustment = eventMeanAdj,
            effectiveScale = scale,
        )
    }

    fun personalization(context: ForecastContext): PersonalizationLevel {
        val deepest = deepestGroup(context)
        val routeGroup = groups[routeKeyOf(context)] ?: groups[pairKeyOf(context)]
        val n = max(deepest?.nEff ?: 0.0, routeGroup?.nEff ?: 0.0)
        val globalN = globalGroup.nEff
        val hasScale = (routeGroup?.residuals?.size ?: 0) >= 5
        return when {
            n >= 18.0 && hasScale -> PersonalizationLevel.PERSONALIZED
            n >= 8.0 && hasScale -> PersonalizationLevel.PARTIALLY_PERSONALIZED
            n >= 3.0 || globalN >= 6.0 -> PersonalizationLevel.LEARNING
            else -> PersonalizationLevel.PRELIMINARY
        }
    }

    fun observationsFor(context: ForecastContext): Int =
        (groups[routeKeyOf(context)]?.residuals?.size ?: 0)

    fun bucketObservationsFor(context: ForecastContext): Int =
        (groups[bucketKeyOf(context)]?.residuals?.size ?: 0)

    fun groupSummary(key: String): GroupSummary? = groups[key]?.let {
        GroupSummary(key, it.residuals.size, it.nEff, it.ewma.mean, it.robustSigma)
    }

    fun allGroupKeys(): Set<String> = groups.keys

    data class GroupSummary(
        val key: String,
        val count: Int,
        val effectiveSampleSize: Double,
        val meanLogRatio: Double,
        val robustLogSigma: Double,
    )

    private fun deepestGroup(context: ForecastContext): ResidualGroup? =
        groups[bucketKeyOf(context)]?.takeIf { it.residuals.size >= 6 }
            ?: groups[routeKeyOf(context)]?.takeIf { it.residuals.size >= 4 }
            ?: groups[pairKeyOf(context)]
            ?: globalGroup.takeIf { it.residuals.isNotEmpty() }

    private fun regimeIncidentMultiplier(regime: TrafficRegime): Double = when (regime) {
        TrafficRegime.LIGHT -> 0.6
        TrafficRegime.MODERATE -> 1.0
        TrafficRegime.HEAVY -> 1.6
        TrafficRegime.SEVERE -> 2.4
        TrafficRegime.UNKNOWN -> 1.1
    }

    companion object {
        fun pairKeyOf(c: ForecastContext) = "pair:${c.pairKey}"
        fun routeKeyOf(c: ForecastContext) = "route:${c.routeKey}"
        fun bucketKeyOf(c: ForecastContext) = "route:${c.routeKey}|bucket:${c.bucket}|day:${c.dayType}"
    }
}

data class SamplerBundle(
    val sampler: ResidualSampler,
    val bias: ShrunkEstimate,
    val sigma: ShrunkEstimate,
    val weatherLogAdjustment: Double,
    val eventLogAdjustment: Double,
    val effectiveScale: Double,
)

/** Fits [FittedTravelModel] from an observation history. Pure, order-dependent, no I/O. */
object TravelModelFitter {

    fun fit(
        observations: List<TravelObservation>,
        priors: ModelPriors = ModelPriors.DEFAULT,
    ): FittedTravelModel {
        val ordered = observations.sortedBy { it.at }
        val builders = HashMap<String, MutableGroup>()
        val global = MutableGroup("global", priors.halfLifeCount)

        for (obs in ordered) {
            val r = obs.logRatio
            if (!r.isFinite() || abs(r) > 2.5) continue // structurally impossible ratio, drop
            global.add(r)
            builders.getOrPut("pair:${obs.pairKey}") { MutableGroup("pair:${obs.pairKey}", priors.halfLifeCount) }.add(r)
            builders.getOrPut("route:${obs.routeKey}") { MutableGroup("route:${obs.routeKey}", priors.halfLifeCount) }.add(r)
            val bk = "route:${obs.routeKey}|bucket:${obs.bucket}|day:${obs.dayType}"
            builders.getOrPut(bk) { MutableGroup(bk, priors.halfLifeCount) }.add(r)
        }

        val weatherFit = fitWeatherCoefficient(ordered, global, priors)

        return FittedTravelModel(
            priors = priors,
            groups = builders.mapValues { it.value.build() },
            globalGroup = global.build(),
            weatherCoefficient = weatherFit.first,
            weatherCoefficientObservations = weatherFit.second,
            totalObservations = ordered.size,
        )
    }

    /**
     * Ridge regression of the *residual after bias removal* on the weather severity index:
     *
     * ```
     *   log(actual/predicted) − b̂  ≈  γ · severity
     * ```
     *
     * with a normal prior `γ ~ N(γ₀, 1/λ)`. With no weather-varying history the posterior
     * is exactly the prior, so the model neither invents nor denies a weather effect.
     */
    private fun fitWeatherCoefficient(
        observations: List<TravelObservation>,
        global: MutableGroup,
        priors: ModelPriors,
    ): Pair<Double, Int> {
        val usable = observations.filter { it.weatherSeverity != null && it.logRatio.isFinite() }
        if (usable.size < 5) return priors.weatherLogCoefficient to usable.size
        val baseline = global.build().ewma.mean
        val xs = usable.map { it.weatherSeverity!! }
        val ys = usable.map { it.logRatio - baseline }
        val decay = Math.pow(0.5, 1.0 / priors.halfLifeCount)
        val n = usable.size
        val ws = usable.indices.map { Math.pow(decay, (n - 1 - it).toDouble()) }
        // Not enough variation in the regressor → keep the prior.
        if ((xs.max() - xs.min()) < 0.15) return priors.weatherLogCoefficient to usable.size
        val slope = Stats.ridgeSlopeThroughOrigin(
            x = xs, y = ys, weights = ws,
            priorSlope = priors.weatherLogCoefficient,
            priorPrecision = priors.weatherPriorPrecision,
        )
        return slope.coerceIn(-0.10, 0.45) to usable.size
    }

    private class MutableGroup(val key: String, val halfLife: Double) {
        var ewma = EwmaAccumulator(halfLifeCount = halfLife)
        val residuals = ArrayList<Double>()

        fun add(r: Double) {
            ewma = ewma.update(r)
            residuals += r
            if (residuals.size > MAX_POOL) residuals.removeAt(0)
        }

        fun build(): FittedTravelModel.ResidualGroup {
            val decay = Math.pow(0.5, 1.0 / halfLife)
            val n = residuals.size
            val weights = residuals.indices.map { Math.pow(decay, (n - 1 - it).toDouble()) }
            return FittedTravelModel.ResidualGroup(key, ewma, residuals.toList(), weights)
        }
    }

    private const val MAX_POOL = 120
}

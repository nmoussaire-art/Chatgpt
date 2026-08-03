package com.batterycast.quant.forecasting.sim

import com.batterycast.quant.forecasting.model.ForecastModelSnapshot
import com.batterycast.quant.forecasting.model.SocBucket
import com.batterycast.quant.forecasting.model.ThermalBucket
import com.batterycast.quant.forecasting.model.UsageRegime
import com.batterycast.quant.forecasting.stats.RobustStats
import com.batterycast.quant.forecasting.uncertainty.ResidualModel
import com.batterycast.quant.telemetry.model.PlugType
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.random.Random

/** Everything that defines one forecast run. */
data class SimulationRequest(
    val startPercent: Double,
    val startMs: Long,
    val horizonMs: Long,
    val isCharging: Boolean,
    val plugType: PlugType,
    val initialRegime: UsageRegime,
    val thermalBucket: ThermalBucket,
    val powerSaveEnabled: Boolean,
    /** Overrides the learned regime chain; used by scenarios such as "45 minutes of navigation". */
    val regimeOverride: RegimeSchedule? = null,
    /** Multiplier applied to every drain rate; used by scenarios such as "power saving on". */
    val drainMultiplier: Double = 1.0,
    /** Charging can be scheduled to begin partway through, for the charge planner. */
    val chargingStartsAtMs: Long? = null,
    val chargingEndsAtMs: Long? = null,
    val chargingPlugType: PlugType = PlugType.AC,
    val paths: Int = DEFAULT_PATHS,
    val stepMs: Long = DEFAULT_STEP_MS,
    val seed: Long = DEFAULT_SEED,
) {
    companion object {
        /**
         * Path count.
         *
         * 2 000 paths give a Monte Carlo standard error on a probability near 0.5 of about
         * 1.1 percentage points, which is comfortably finer than the resolution the app displays.
         */
        const val DEFAULT_PATHS = 2_400

        /** Five minutes: fine enough to resolve threshold crossings the user would notice. */
        const val DEFAULT_STEP_MS = 5 * 60 * 1000L

        /**
         * Fixed seed.
         *
         * The randomness models future uncertainty, not measurement — the observations are all
         * real. Fixing the seed means the same evidence always yields the same forecast, so the
         * number does not flicker when the user re-opens the screen, and the tests are exact.
         */
        const val DEFAULT_SEED = 0x5EE9B5L
    }
}

/** A forced sequence of regimes, e.g. "navigation for 45 minutes, then back to normal". */
data class RegimeSchedule(val entries: List<Entry>) {
    data class Entry(val regime: UsageRegime, val durationMs: Long)

    /** The regime in force [offsetMs] into the forecast, or null once the schedule runs out. */
    fun regimeAt(offsetMs: Long): UsageRegime? {
        var cursor = 0L
        entries.forEach { entry ->
            cursor += entry.durationMs
            if (offsetMs < cursor) return entry.regime
        }
        return null
    }

    val totalDurationMs: Long get() = entries.sumOf { it.durationMs }
}

/** Percentile fan and threshold statistics at one point in time. */
data class ForecastPoint(
    val atMs: Long,
    val mean: Double,
    val median: Double,
    val p05: Double,
    val p10: Double,
    val p25: Double,
    val p75: Double,
    val p90: Double,
    val p95: Double,
)

/** The full simulation output. */
class SimulationResult(
    val points: List<ForecastPoint>,
    /**
     * Battery percentage of every path at every step, each row sorted ascending.
     *
     * Kept because a probability is a count over paths: "84 % chance of lasting until 10 pm" is
     * literally the fraction of these paths still above the reserve at that step, and it cannot
     * be recovered from percentiles alone.
     */
    private val sortedPercentByStep: Array<DoubleArray>,
    /** First time each path crossed a threshold, in ms since start; null entries never crossed. */
    val thresholdCrossings: Map<Double, List<Long?>>,
    val paths: Int,
    val startMs: Long,
    val horizonMs: Long,
    val stepMs: Long,
) {
    /** Battery percentage in every path at the final step, sorted ascending. */
    val terminalPercents: DoubleArray get() = sortedPercentByStep.last()

    /**
     * P(battery > [reserve] at [atMs]), counted directly over the simulated paths.
     *
     * Returns null when [atMs] lies outside the simulated horizon rather than extrapolating.
     */
    fun probabilityAbove(atMs: Long, reserve: Double): Double? {
        val step = stepIndexFor(atMs) ?: return null
        return RobustStats.proportionAbove(sortedPercentByStep[step], reserve)
    }

    /** The percentile fan at [atMs], or null when it is outside the horizon. */
    fun pointAt(atMs: Long): ForecastPoint? {
        val step = stepIndexFor(atMs) ?: return null
        return points[step]
    }

    private fun stepIndexFor(atMs: Long): Int? {
        if (atMs < startMs) return null
        val step = ((atMs - startMs) + stepMs / 2) / stepMs
        if (step > points.lastIndex) return null
        return step.toInt()
    }

    /** Median time to reach [threshold], or null when most paths never reach it. */
    fun medianTimeToThresholdMs(threshold: Double): Long? {
        val crossings = thresholdCrossings[threshold] ?: return null
        val reached = crossings.filterNotNull().map { it.toDouble() }
        // A median is only meaningful if at least half the paths got there.
        if (reached.size * 2 < crossings.size) return null
        return RobustStats.median(reached)?.toLong()
    }

    /** Probability of ever reaching [threshold] within the horizon. */
    fun probabilityOfReaching(threshold: Double): Double? {
        val crossings = thresholdCrossings[threshold] ?: return null
        if (crossings.isEmpty()) return null
        return crossings.count { it != null }.toDouble() / crossings.size
    }

    fun timeToThresholdQuantileMs(threshold: Double, p: Double): Long? {
        val crossings = thresholdCrossings[threshold] ?: return null
        val reached = crossings.filterNotNull().map { it.toDouble() }
        if (reached.size * 2 < crossings.size) return null
        return RobustStats.quantile(reached, p)?.toLong()
    }
}

/**
 * Monte Carlo battery-path simulation.
 *
 * Rather than projecting a single drain rate forward, the simulator runs thousands of plausible
 * futures and reads the answer off their distribution. That is what lets the app answer the
 * questions it is for — "will it last until ten" is a probability, not a point estimate, and it
 * cannot be obtained by extrapolating a line.
 *
 * Each path carries four distinct sources of randomness, which correspond to four genuinely
 * different things we do not know:
 *
 * 1. **What the phone will be doing.** A Markov chain over usage regimes, estimated from the
 *    transitions this device actually made. This is why an eight-hour forecast is wider than a
 *    one-hour forecast by more than a factor of eight.
 * 2. **How fast each regime drains.** One draw per path per regime, from the shrunk estimate's
 *    standard error. This is uncertainty about the *parameter*, and it stays fixed along a path
 *    because if this phone's heavy use costs 22 %/h rather than 18 %/h, it does so all evening.
 * 3. **Step-to-step variation.** A residual draw at every step, bootstrapped from real past
 *    forecast errors where enough exist. This is process noise, and it partially averages out.
 * 4. **Thermal state.** A per-path draw over the plausible thermal buckets, since a phone that is
 *    warm now may or may not stay warm.
 *
 * Battery percentage is clamped to [0, 100] at every step, and a path that reaches zero stays
 * there — a phone does not un-discharge.
 */
class MonteCarloSimulator {

    fun simulate(request: SimulationRequest, snapshot: ForecastModelSnapshot): SimulationResult {
        val steps = ((request.horizonMs + request.stepMs - 1) / request.stepMs).toInt().coerceAtLeast(1)
        val stepHours = request.stepMs / 3_600_000.0
        val paths = request.paths.coerceAtLeast(MIN_PATHS)

        // percentAtStep[step][path]; laid out step-major so percentiles are a contiguous sort.
        val percentAtStep = Array(steps + 1) { DoubleArray(paths) }
        val thresholds = THRESHOLDS
        val crossings = thresholds.associateWith { arrayOfNulls<Long>(paths) }

        val random = Random(request.seed)

        for (pathIndex in 0 until paths) {
            simulatePath(
                pathIndex = pathIndex,
                steps = steps,
                stepHours = stepHours,
                request = request,
                snapshot = snapshot,
                random = random,
                percentAtStep = percentAtStep,
                crossings = crossings,
            )
        }

        // Sorting in place: percentAtStep is not read again in path order after this.
        percentAtStep.forEach { it.sort() }

        val points = (0..steps).map { step ->
            val values = percentAtStep[step]
            ForecastPoint(
                atMs = request.startMs + step * request.stepMs,
                mean = values.average(),
                median = RobustStats.quantileOfSorted(values, 0.5),
                p05 = RobustStats.quantileOfSorted(values, 0.05),
                p10 = RobustStats.quantileOfSorted(values, 0.10),
                p25 = RobustStats.quantileOfSorted(values, 0.25),
                p75 = RobustStats.quantileOfSorted(values, 0.75),
                p90 = RobustStats.quantileOfSorted(values, 0.90),
                p95 = RobustStats.quantileOfSorted(values, 0.95),
            )
        }

        return SimulationResult(
            points = points,
            sortedPercentByStep = percentAtStep,
            thresholdCrossings = crossings.mapValues { it.value.toList() },
            paths = paths,
            startMs = request.startMs,
            horizonMs = request.horizonMs,
            stepMs = request.stepMs,
        )
    }

    private fun simulatePath(
        pathIndex: Int,
        steps: Int,
        stepHours: Double,
        request: SimulationRequest,
        snapshot: ForecastModelSnapshot,
        random: Random,
        percentAtStep: Array<DoubleArray>,
        crossings: Map<Double, Array<Long?>>,
    ) {
        var percent = request.startPercent.coerceIn(0.0, 100.0)
        percentAtStep[0][pathIndex] = percent

        // --- Per-path parameter draws (source 2): fixed for the whole path. ---
        val regimeRates = HashMap<UsageRegime, Double>(UsageRegime.entries.size)
        UsageRegime.dischargeRegimes.forEach { regime ->
            val distribution = snapshot.drainFor(regime) ?: return@forEach
            val drawn = distribution.mean +
                ResidualModel.gaussian(random) * distribution.meanStandardError * PARAMETER_INFLATION
            regimeRates[regime] = drawn.coerceAtLeast(0.0)
        }

        // --- Thermal draw (source 4). A warm phone may cool; a cool one rarely heats unprompted. ---
        val thermalMultiplier = drawThermalMultiplier(request.thermalBucket, snapshot, random)

        val powerSaveMultiplier = if (request.powerSaveEnabled) {
            snapshot.powerSaveMultiplier ?: 1.0
        } else {
            1.0
        }

        // Persistent path-level bias, capturing the day-to-day differences the state model does
        // not resolve. Log-normal with unit mean so it neither inflates nor deflates the median.
        val biasSigma = pathBiasSigma(snapshot)
        val pathBias = exp(ResidualModel.gaussian(random) * biasSigma - biasSigma * biasSigma / 2.0)

        var regime = request.initialRegime
        var msSinceTransition = 0L
        var charging = request.isCharging
        var plugType = request.plugType

        for (step in 1..steps) {
            val offsetMs = (step - 1) * request.stepMs
            val nowMs = request.startMs + offsetMs

            // --- Scheduled charging windows drive the charge planner. ---
            request.chargingStartsAtMs?.let { startsAt ->
                if (nowMs >= startsAt) {
                    val endsAt = request.chargingEndsAtMs
                    charging = endsAt == null || nowMs < endsAt
                    if (charging) plugType = request.chargingPlugType
                }
            }

            if (charging) {
                percent = stepCharging(percent, plugType, stepHours, snapshot, random)
            } else {
                // --- Regime evolution (source 1). ---
                val scheduled = request.regimeOverride?.regimeAt(offsetMs)
                if (scheduled != null) {
                    regime = scheduled
                    msSinceTransition = 0L
                } else {
                    msSinceTransition += request.stepMs
                    if (msSinceTransition >= TRANSITION_INTERVAL_MS) {
                        regime = drawNextRegime(regime, snapshot, random)
                        msSinceTransition = 0L
                    }
                }

                percent = stepDischarge(
                    percent = percent,
                    regime = regime,
                    regimeRates = regimeRates,
                    snapshot = snapshot,
                    stepHours = stepHours,
                    thermalMultiplier = thermalMultiplier,
                    powerSaveMultiplier = powerSaveMultiplier,
                    pathBias = pathBias * request.drainMultiplier,
                    random = random,
                )
            }

            percentAtStep[step][pathIndex] = percent

            crossings.forEach { (threshold, record) ->
                if (record[pathIndex] == null && percent <= threshold) {
                    record[pathIndex] = step * request.stepMs
                }
            }
        }
    }

    private fun stepDischarge(
        percent: Double,
        regime: UsageRegime,
        regimeRates: Map<UsageRegime, Double>,
        snapshot: ForecastModelSnapshot,
        stepHours: Double,
        thermalMultiplier: Double,
        powerSaveMultiplier: Double,
        pathBias: Double,
        random: Random,
    ): Double {
        if (percent <= 0.0) return 0.0

        val baseRate = regimeRates[regime]
            ?: snapshot.globalDrain?.mean
            ?: return percent

        // The discharge curve is not linear, and its shape is this device's own.
        val curveMultiplier = snapshot.socMultiplier(percent)

        // Process noise (source 3), bootstrapped from real forecast errors where they exist. It
        // is scaled by sqrt(step) because independent per-step shocks accumulate as a random walk.
        val noise = snapshot.residualModel.sample(random) * sqrt(stepHours)

        val rate = (baseRate * curveMultiplier * thermalMultiplier * powerSaveMultiplier * pathBias + noise)
            .coerceAtLeast(0.0)

        return (percent - rate * stepHours).coerceIn(0.0, 100.0)
    }

    private fun stepCharging(
        percent: Double,
        plugType: PlugType,
        stepHours: Double,
        snapshot: ForecastModelSnapshot,
        random: Random,
    ): Double {
        if (percent >= 100.0) return 100.0

        // Looking the rate up by the *current* band is what makes the model piecewise: the same
        // charger fills 20→50 quickly and 90→100 slowly, and that emerges from the data rather
        // than from an assumed taper curve.
        val bucket = SocBucket.forPercent(percent)
        val distribution = snapshot.chargeRateFor(plugType, bucket)
            ?: snapshot.globalChargeRate
            ?: return percent

        val rate = (
            distribution.mean +
                ResidualModel.gaussian(random) * distribution.meanStandardError * PARAMETER_INFLATION
            ).coerceAtLeast(0.0)

        return (percent + rate * stepHours).coerceIn(0.0, 100.0)
    }

    private fun drawNextRegime(
        current: UsageRegime,
        snapshot: ForecastModelSnapshot,
        random: Random,
    ): UsageRegime {
        val row = snapshot.transitions.row(current)
        var cumulative = 0.0
        val draw = random.nextDouble()
        row.forEach { (regime, probability) ->
            cumulative += probability
            if (draw <= cumulative) return regime
        }
        return current
    }

    /**
     * Draws a thermal multiplier for this path.
     *
     * A phone that is hot right now is more likely than not to still be warm shortly, but it may
     * also cool down, and neither outcome should be asserted. So the draw mixes the current
     * bucket with the adjacent cooler one, weighted towards staying put.
     */
    private fun drawThermalMultiplier(
        bucket: ThermalBucket,
        snapshot: ForecastModelSnapshot,
        random: Random,
    ): Double {
        val current = snapshot.thermalMultiplier(bucket)
        val cooler = when (bucket) {
            ThermalBucket.HOT -> snapshot.thermalMultiplier(ThermalBucket.WARM)
            ThermalBucket.WARM -> snapshot.thermalMultiplier(ThermalBucket.COOL)
            else -> current
        }
        return if (random.nextDouble() < THERMAL_PERSISTENCE) current else cooler
    }

    /**
     * Spread of the per-path bias term.
     *
     * With little history the app knows little about how much this phone's days differ from one
     * another, so the bias is wide; as evidence accumulates it narrows towards the residual
     * model's own volatility. This is the mechanism by which sparse data produces visibly wider
     * forecast intervals rather than a confident-looking wrong answer.
     */
    private fun pathBiasSigma(snapshot: ForecastModelSnapshot): Double {
        val global = snapshot.globalDrain
        if (global == null || global.mean <= 0.0) return MAX_BIAS_SIGMA
        val relativeSpread = global.standardDeviation / global.mean
        val evidenceFactor = 1.0 / sqrt(global.effectiveSamples.coerceAtLeast(1.0))
        val sigma = sqrt(relativeSpread * relativeSpread * SPREAD_SHARE + evidenceFactor * evidenceFactor)
        return sigma.coerceIn(MIN_BIAS_SIGMA, MAX_BIAS_SIGMA)
    }

    companion object {
        /** Thresholds whose crossing times are always recorded. */
        val THRESHOLDS = listOf(20.0, 10.0, 5.0, 1.0)

        const val MIN_PATHS = 2_000

        /** How long a regime persists before the chain gets another chance to move. */
        const val TRANSITION_INTERVAL_MS = 15 * 60 * 1000L

        /**
         * Widens the parameter draws slightly beyond the nominal standard error.
         *
         * The estimate is not from independent samples — consecutive intervals in one evening are
         * correlated — so the nominal standard error understates the real uncertainty.
         */
        const val PARAMETER_INFLATION = 1.35

        const val THERMAL_PERSISTENCE = 0.7

        /** How much of the observed spread is attributed to persistent day-level differences. */
        const val SPREAD_SHARE = 0.35

        const val MIN_BIAS_SIGMA = 0.08
        const val MAX_BIAS_SIGMA = 0.55
    }
}

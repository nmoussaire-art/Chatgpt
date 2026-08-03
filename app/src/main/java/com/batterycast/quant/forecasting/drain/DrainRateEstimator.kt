package com.batterycast.quant.forecasting.drain

import com.batterycast.quant.forecasting.clean.BatterySegment
import com.batterycast.quant.forecasting.clean.SegmentKind
import com.batterycast.quant.forecasting.model.DrainMetric
import com.batterycast.quant.forecasting.stats.RobustStats
import com.batterycast.quant.telemetry.model.BatteryObservation
import com.batterycast.quant.telemetry.model.CurrentSignConvention
import com.batterycast.quant.telemetry.model.SensorCapabilities
import com.batterycast.quant.telemetry.validation.CapabilityLearner
import kotlin.math.abs

/** How a drain rate was arrived at. Surfaced in the UI so a rate can always be justified. */
enum class EstimationMethod(val displayName: String) {
    /** Theil–Sen fit to the battery-percentage series. */
    ROBUST_REGRESSION_PERCENT("robust fit to battery percentage"),

    /** Theil–Sen fit to the fuel gauge's charge counter; immune to percentage rounding. */
    ROBUST_REGRESSION_CHARGE("robust fit to charge counter"),

    /** Theil–Sen fit to the energy counter. */
    ROBUST_REGRESSION_ENERGY("robust fit to energy counter"),

    /** Median of instantaneous current readings — available immediately, but noisy. */
    INSTANTANEOUS_CURRENT("live current readings"),

    /** Two endpoints only. Used when nothing better exists, and always low confidence. */
    ENDPOINT_DIFFERENCE("start and end readings"),
}

enum class EstimateConfidence { LOW, MODERATE, GOOD }

/**
 * A drain rate with everything needed to judge how much to believe it.
 *
 * [ratePerHour] is positive while the battery is losing charge, in whichever unit [metric] names.
 */
data class DrainEstimate(
    val metric: DrainMetric,
    val ratePerHour: Double,
    val method: EstimationMethod,
    val confidence: EstimateConfidence,
    val pointCount: Int,
    val spanHours: Double,
    /** Robust scale of the fit residuals, in the metric's units. */
    val residualScale: Double,
) {
    /** Weight this estimate should carry when several horizons are combined. */
    val blendWeight: Double
        get() {
            val confidenceWeight = when (confidence) {
                EstimateConfidence.GOOD -> 1.0
                EstimateConfidence.MODERATE -> 0.55
                EstimateConfidence.LOW -> 0.2
            }
            // A longer, better-populated window says more about the underlying rate.
            val supportWeight = minOf(1.0, spanHours / 1.0) * minOf(1.0, pointCount / 6.0)
            return confidenceWeight * (0.3 + 0.7 * supportWeight)
        }
}

/** The look-back windows the app estimates over. */
enum class DrainHorizon(val hours: Double, val displayName: String) {
    RECENT(0.5, "last 30 minutes"),
    HOUR(1.0, "last hour"),
    THREE_HOURS(3.0, "last 3 hours"),
    HALF_DAY(12.0, "last 12 hours"),
}

data class HorizonEstimates(
    val byHorizon: Map<DrainHorizon, DrainEstimate>,
    /** Recency-weighted combination across horizons, in percentage points per hour. */
    val blendedPercentPerHour: DrainEstimate?,
    val chargeBasedPercentPerHour: DrainEstimate?,
    val energyBasedWattHoursPerHour: DrainEstimate?,
    val instantaneous: DrainEstimate?,
    /** Full-charge capacity implied by the fuel gauge, in mAh. Null when unavailable. */
    val estimatedFullCapacityMilliAh: Double?,
)

/**
 * Estimates how fast the battery is draining, over several time windows and in every unit the
 * device supports.
 *
 * Three principles run through this:
 *
 * - **Never two points.** A pair of rounded percentages an hour apart can imply anything from
 *   0 to 2 %/h. Every rate that drives a forecast comes from a robust fit over many points, and
 *   the two-point fallback is marked [EstimationMethod.ENDPOINT_DIFFERENCE] and confidence LOW.
 * - **Prefer the counter to the percentage.** Where the fuel gauge reports charge, the fit runs
 *   on µAh, which has far finer resolution than a whole percentage point.
 * - **Return null rather than guess.** A window with no measurable change produces no estimate,
 *   which the caller reports as "no measurable change yet".
 */
object DrainRateEstimator {

    /** Pairs closer together than this in time are dominated by percentage rounding. */
    private const val MIN_PAIR_SEPARATION_HOURS = 5.0 / 60.0

    private const val MIN_POINTS_FOR_REGRESSION = 4
    private const val MIN_SPAN_HOURS_FOR_GOOD = 0.75
    private const val MIN_POINTS_FOR_GOOD = 8

    fun estimate(
        segments: List<BatterySegment>,
        capabilities: SensorCapabilities,
        nowMs: Long,
    ): HorizonEstimates {
        val dischargeObservations = segments
            .filter { it.kind == SegmentKind.DISCHARGE }
            .flatMap { it.observations }
            .sortedBy { it.timestampMs }

        val byHorizon = DrainHorizon.entries.mapNotNull { horizon ->
            val window = dischargeObservations.filter {
                it.timestampMs >= nowMs - (horizon.hours * 3_600_000).toLong()
            }
            percentDrainEstimate(window)?.let { horizon to it }
        }.toMap()

        val blended = blend(byHorizon)

        val capacity = estimateFullCapacityMilliAh(dischargeObservations)
        val chargeBased = chargeCounterDrainEstimate(dischargeObservations, capacity)
        val energyBased = energyDrainEstimate(dischargeObservations)
        val instantaneous = instantaneousDrainEstimate(dischargeObservations, capabilities, capacity, nowMs)

        return HorizonEstimates(
            byHorizon = byHorizon,
            blendedPercentPerHour = blended,
            chargeBasedPercentPerHour = chargeBased,
            energyBasedWattHoursPerHour = energyBased,
            instantaneous = instantaneous,
            estimatedFullCapacityMilliAh = capacity,
        )
    }

    /**
     * Robust fit of battery percentage against elapsed time.
     *
     * Elapsed *monotonic* time is the x axis, so a time-zone change or a daylight-saving jump in
     * the middle of the window cannot distort the slope.
     */
    fun percentDrainEstimate(observations: List<BatteryObservation>): DrainEstimate? {
        val usable = observations.filter { it.usableForModelling }
        if (usable.size < 2) return null

        val base = usable.first().elapsedRealtimeMs
        val xs = usable.map { (it.elapsedRealtimeMs - base) / 3_600_000.0 }
        val ys = usable.map { it.batteryPercent }
        val spanHours = xs.last() - xs.first()
        if (spanHours <= 0.0) return null

        if (usable.size >= MIN_POINTS_FOR_REGRESSION) {
            val fit = RobustStats.theilSenFit(xs, ys, MIN_PAIR_SEPARATION_HOURS)
            if (fit != null) {
                // A flat fit over a real span is a genuine measurement of "barely draining", not a
                // missing one, so it is kept.
                return DrainEstimate(
                    metric = DrainMetric.PERCENT_PER_HOUR,
                    ratePerHour = (-fit.slope).coerceAtLeast(0.0),
                    method = EstimationMethod.ROBUST_REGRESSION_PERCENT,
                    confidence = confidenceFor(usable.size, spanHours),
                    pointCount = usable.size,
                    spanHours = spanHours,
                    residualScale = fit.residualScale,
                )
            }
        }

        val drop = usable.first().batteryPercent - usable.last().batteryPercent
        if (drop <= 0.0) return null
        return DrainEstimate(
            metric = DrainMetric.PERCENT_PER_HOUR,
            ratePerHour = drop / spanHours,
            method = EstimationMethod.ENDPOINT_DIFFERENCE,
            confidence = EstimateConfidence.LOW,
            pointCount = usable.size,
            spanHours = spanHours,
            // With two rounded endpoints the rate is uncertain by at least one point over the
            // span; that is stated rather than hidden.
            residualScale = usable.first().percentGranularity / spanHours,
        )
    }

    /**
     * Fits the charge counter and converts to percentage points per hour using the capacity the
     * gauge itself implies. Far finer resolution than the percentage series, where available.
     */
    fun chargeCounterDrainEstimate(
        observations: List<BatteryObservation>,
        fullCapacityMilliAh: Double?,
    ): DrainEstimate? {
        if (fullCapacityMilliAh == null || fullCapacityMilliAh <= 0.0) return null
        val usable = observations.filter { it.usableForModelling && it.chargeCounterMilliAh != null }
        if (usable.size < MIN_POINTS_FOR_REGRESSION) return null

        val base = usable.first().elapsedRealtimeMs
        val xs = usable.map { (it.elapsedRealtimeMs - base) / 3_600_000.0 }
        val ys = usable.map { it.chargeCounterMilliAh!! }
        val spanHours = xs.last() - xs.first()
        if (spanHours <= 0.0) return null

        val fit = RobustStats.theilSenFit(xs, ys, MIN_PAIR_SEPARATION_HOURS) ?: return null
        val milliAmpHoursPerHour = (-fit.slope).coerceAtLeast(0.0)

        return DrainEstimate(
            metric = DrainMetric.PERCENT_PER_HOUR,
            ratePerHour = milliAmpHoursPerHour / fullCapacityMilliAh * 100.0,
            method = EstimationMethod.ROBUST_REGRESSION_CHARGE,
            confidence = confidenceFor(usable.size, spanHours),
            pointCount = usable.size,
            spanHours = spanHours,
            residualScale = fit.residualScale / fullCapacityMilliAh * 100.0,
        )
    }

    /** Fits the energy counter, giving watt-hours per hour — that is, mean power in watts. */
    fun energyDrainEstimate(observations: List<BatteryObservation>): DrainEstimate? {
        val usable = observations.filter { it.usableForModelling && it.energyWattHours != null }
        if (usable.size < MIN_POINTS_FOR_REGRESSION) return null

        val base = usable.first().elapsedRealtimeMs
        val xs = usable.map { (it.elapsedRealtimeMs - base) / 3_600_000.0 }
        val ys = usable.map { it.energyWattHours!! }
        val spanHours = xs.last() - xs.first()
        if (spanHours <= 0.0) return null

        val fit = RobustStats.theilSenFit(xs, ys, MIN_PAIR_SEPARATION_HOURS) ?: return null
        return DrainEstimate(
            metric = DrainMetric.WATT_HOUR_PER_HOUR,
            ratePerHour = (-fit.slope).coerceAtLeast(0.0),
            method = EstimationMethod.ROBUST_REGRESSION_ENERGY,
            confidence = confidenceFor(usable.size, spanHours),
            pointCount = usable.size,
            spanHours = spanHours,
            residualScale = fit.residualScale,
        )
    }

    /**
     * Median of recent instantaneous current readings, converted to percentage points per hour.
     *
     * This is the only estimator that works within seconds of installation, so it is what backs
     * the clearly-labelled preliminary forecast. It is never treated as better than LOW or
     * MODERATE confidence: instantaneous current swings by an order of magnitude between screen
     * refreshes, and a median of a handful of readings inherits that.
     */
    fun instantaneousDrainEstimate(
        observations: List<BatteryObservation>,
        capabilities: SensorCapabilities,
        fullCapacityMilliAh: Double?,
        nowMs: Long,
        windowMs: Long = 30 * 60 * 1000L,
    ): DrainEstimate? {
        if (capabilities.currentSign == CurrentSignConvention.UNDETERMINED) return null
        if (fullCapacityMilliAh == null || fullCapacityMilliAh <= 0.0) return null

        val recent = observations.filter {
            it.usableForModelling && it.timestampMs >= nowMs - windowMs && !it.isCharging
        }
        val drainMilliAmps = recent.mapNotNull { observation ->
            CapabilityLearner.signedDrainMicroA(observation.currentMicroA, capabilities.currentSign)
                ?.let { it / 1000.0 }
        }.filter { it > 0.0 }

        if (drainMilliAmps.isEmpty()) return null
        val medianMilliAmps = RobustStats.median(drainMilliAmps) ?: return null
        val scale = RobustStats.medianAbsoluteDeviation(drainMilliAmps) ?: 0.0

        return DrainEstimate(
            metric = DrainMetric.PERCENT_PER_HOUR,
            ratePerHour = medianMilliAmps / fullCapacityMilliAh * 100.0,
            method = EstimationMethod.INSTANTANEOUS_CURRENT,
            confidence = if (drainMilliAmps.size >= 5) EstimateConfidence.MODERATE else EstimateConfidence.LOW,
            pointCount = drainMilliAmps.size,
            spanHours = 0.0,
            residualScale = scale / fullCapacityMilliAh * 100.0,
        )
    }

    /**
     * Full-charge capacity implied by the fuel gauge: charge counter divided by state of charge.
     *
     * This is a measured property of the actual battery, including whatever capacity it has lost
     * to ageing — which is exactly what a forecast should be based on. Readings below 15 % are
     * skipped because the ratio becomes numerically unstable there, and the median across
     * readings guards against a single bad pair.
     */
    fun estimateFullCapacityMilliAh(observations: List<BatteryObservation>): Double? {
        val implied = observations.mapNotNull { observation ->
            val counter = observation.chargeCounterMilliAh ?: return@mapNotNull null
            if (observation.batteryPercent < 15.0) return@mapNotNull null
            counter / (observation.batteryPercent / 100.0)
        }.filter { it in MIN_PLAUSIBLE_CAPACITY_MAH..MAX_PLAUSIBLE_CAPACITY_MAH }

        if (implied.size < 3) return null
        return RobustStats.median(implied)
    }

    /**
     * Combines the horizons into one rate.
     *
     * Recent behaviour matters more, but a thirty-minute window on its own is far too jumpy to
     * forecast eight hours from, so each horizon is weighted by both recency and how well it is
     * supported. This is the point where "what the phone is doing right now" and "what the phone
     * has been doing" are reconciled.
     */
    fun blend(byHorizon: Map<DrainHorizon, DrainEstimate>): DrainEstimate? {
        if (byHorizon.isEmpty()) return null

        val recencyWeights = mapOf(
            DrainHorizon.RECENT to 1.0,
            DrainHorizon.HOUR to 0.8,
            DrainHorizon.THREE_HOURS to 0.5,
            DrainHorizon.HALF_DAY to 0.25,
        )

        val entries = byHorizon.entries.toList()
        val values = entries.map { it.value.ratePerHour }
        val weights = entries.map { (horizon, estimate) ->
            (recencyWeights[horizon] ?: 0.25) * estimate.blendWeight
        }
        val blendedRate = RobustStats.weightedMean(values, weights) ?: return null

        val best = entries.maxByOrNull { it.value.blendWeight }!!.value
        // Disagreement between horizons is itself uncertainty about the rate, so it is folded
        // into the residual scale rather than averaged away.
        val spread = if (values.size >= 2) {
            RobustStats.medianAbsoluteDeviation(values) ?: 0.0
        } else {
            0.0
        }

        return DrainEstimate(
            metric = DrainMetric.PERCENT_PER_HOUR,
            ratePerHour = blendedRate.coerceAtLeast(0.0),
            method = best.method,
            confidence = entries.maxOf { it.value.confidence },
            pointCount = entries.sumOf { it.value.pointCount },
            spanHours = entries.maxOf { it.value.spanHours },
            residualScale = maxOf(best.residualScale, spread),
        )
    }

    private fun confidenceFor(pointCount: Int, spanHours: Double): EstimateConfidence = when {
        pointCount >= MIN_POINTS_FOR_GOOD && spanHours >= MIN_SPAN_HOURS_FOR_GOOD -> EstimateConfidence.GOOD
        pointCount >= MIN_POINTS_FOR_REGRESSION -> EstimateConfidence.MODERATE
        else -> EstimateConfidence.LOW
    }

    /** Handset batteries live between roughly 1 Ah and 12 Ah; outside that the ratio is wrong. */
    private const val MIN_PLAUSIBLE_CAPACITY_MAH = 800.0
    private const val MAX_PLAUSIBLE_CAPACITY_MAH = 12_000.0

    /** Charging rate from a charge segment, in percentage points per hour. */
    fun chargeRateEstimate(segment: BatterySegment): DrainEstimate? {
        val usable = segment.observations.filter { it.usableForModelling }
        if (usable.size < 2) return null
        val base = usable.first().elapsedRealtimeMs
        val xs = usable.map { (it.elapsedRealtimeMs - base) / 3_600_000.0 }
        val ys = usable.map { it.batteryPercent }
        val spanHours = xs.last() - xs.first()
        if (spanHours <= 0.0) return null

        if (usable.size >= MIN_POINTS_FOR_REGRESSION) {
            val fit = RobustStats.theilSenFit(xs, ys, MIN_PAIR_SEPARATION_HOURS)
            if (fit != null) {
                return DrainEstimate(
                    metric = DrainMetric.PERCENT_PER_HOUR,
                    ratePerHour = fit.slope.coerceAtLeast(0.0),
                    method = EstimationMethod.ROBUST_REGRESSION_PERCENT,
                    confidence = confidenceFor(usable.size, spanHours),
                    pointCount = usable.size,
                    spanHours = spanHours,
                    residualScale = fit.residualScale,
                )
            }
        }

        val gain = usable.last().batteryPercent - usable.first().batteryPercent
        if (abs(gain) < 1e-9) return null
        return DrainEstimate(
            metric = DrainMetric.PERCENT_PER_HOUR,
            ratePerHour = (gain / spanHours).coerceAtLeast(0.0),
            method = EstimationMethod.ENDPOINT_DIFFERENCE,
            confidence = EstimateConfidence.LOW,
            pointCount = usable.size,
            spanHours = spanHours,
            residualScale = usable.first().percentGranularity / spanHours,
        )
    }
}

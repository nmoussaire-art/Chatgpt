package com.batterycast.quant.forecasting.stats

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Robust estimators.
 *
 * Battery telemetry is not clean: percentages are rounded to whole points, the platform defers
 * background work so samples are unevenly spaced, and a single recalibration event can move the
 * reading by ten points in a second. Ordinary least squares and the arithmetic mean both collapse
 * under those conditions, so every rate in this app is estimated with a method that tolerates a
 * substantial fraction of contaminated points.
 */
object RobustStats {

    /** Sample median. Returns null for an empty input rather than a stand-in value. */
    fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[middle]
        } else {
            (sorted[middle - 1] + sorted[middle]) / 2.0
        }
    }

    /**
     * Median absolute deviation, scaled to be a consistent estimator of the standard deviation
     * for normally distributed data (the 1.4826 factor).
     *
     * Unlike the standard deviation it has a 50 % breakdown point: half the observations could be
     * arbitrarily wrong and it would still describe the rest.
     */
    fun medianAbsoluteDeviation(values: List<Double>): Double? {
        val centre = median(values) ?: return null
        val deviations = values.map { abs(it - centre) }
        val mad = median(deviations) ?: return null
        return mad * MAD_TO_SIGMA
    }

    /**
     * Linear-interpolated quantile, matching the definition used by most statistical packages
     * (type 7). [p] is clamped to [0, 1].
     */
    fun quantile(values: List<Double>, p: Double): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        if (sorted.size == 1) return sorted[0]
        val position = p.coerceIn(0.0, 1.0) * (sorted.size - 1)
        val lower = floor(position).toInt()
        val upper = ceil(position).toInt()
        if (lower == upper) return sorted[lower]
        val weight = position - lower
        return sorted[lower] * (1 - weight) + sorted[upper] * weight
    }

    /**
     * Quantiles of an already-sorted list.
     *
     * The simulator computes several percentiles over the same 2 000+ paths at every time step,
     * so sorting once and reusing the order matters.
     */
    fun quantileOfSorted(sorted: DoubleArray, p: Double): Double {
        if (sorted.isEmpty()) return Double.NaN
        if (sorted.size == 1) return sorted[0]
        val position = p.coerceIn(0.0, 1.0) * (sorted.size - 1)
        val lower = floor(position).toInt()
        val upper = ceil(position).toInt()
        if (lower == upper) return sorted[lower]
        val weight = position - lower
        return sorted[lower] * (1 - weight) + sorted[upper] * weight
    }

    fun mean(values: List<Double>): Double? =
        if (values.isEmpty()) null else values.sum() / values.size

    fun standardDeviation(values: List<Double>): Double? {
        if (values.size < 2) return null
        val m = values.sum() / values.size
        val variance = values.sumOf { (it - m) * (it - m) } / (values.size - 1)
        return sqrt(variance)
    }

    /**
     * Theil–Sen slope: the median of the slopes of all point pairs.
     *
     * Chosen over least squares because it is unaffected by up to ~29 % arbitrarily corrupted
     * points, which is roughly what a battery history contains after a recalibration or a pair of
     * deferred samples. [minimumSeparation] suppresses pairs that are too close together in x,
     * where quantised battery percentages would produce enormous spurious slopes.
     *
     * Returns null when fewer than two sufficiently separated pairs exist — the honest answer
     * when the data cannot support a rate at all.
     */
    fun theilSenSlope(
        xs: List<Double>,
        ys: List<Double>,
        minimumSeparation: Double = 0.0,
    ): Double? {
        require(xs.size == ys.size) { "x and y must be the same length" }
        if (xs.size < 2) return null

        val slopes = ArrayList<Double>(xs.size * (xs.size - 1) / 2)
        for (i in xs.indices) {
            for (j in i + 1 until xs.size) {
                val dx = xs[j] - xs[i]
                if (abs(dx) <= minimumSeparation) continue
                slopes += (ys[j] - ys[i]) / dx
            }
        }
        return median(slopes)
    }

    /**
     * Theil–Sen slope plus the intercept that centres the residuals, giving a complete robust
     * line fit.
     */
    fun theilSenFit(
        xs: List<Double>,
        ys: List<Double>,
        minimumSeparation: Double = 0.0,
    ): LineFit? {
        val slope = theilSenSlope(xs, ys, minimumSeparation) ?: return null
        val intercept = median(ys.indices.map { ys[it] - slope * xs[it] }) ?: return null
        val residuals = ys.indices.map { ys[it] - (slope * xs[it] + intercept) }
        return LineFit(
            slope = slope,
            intercept = intercept,
            residualScale = medianAbsoluteDeviation(residuals) ?: 0.0,
            pointCount = xs.size,
            xSpan = (xs.maxOrNull() ?: 0.0) - (xs.minOrNull() ?: 0.0),
        )
    }

    /**
     * Weighted mean where each value carries its own weight.
     *
     * Used to combine drain estimates over several horizons, where a longer, better-supported
     * window should count for more than a five-minute one.
     */
    fun weightedMean(values: List<Double>, weights: List<Double>): Double? {
        require(values.size == weights.size) { "values and weights must be the same length" }
        val totalWeight = weights.sum()
        if (values.isEmpty() || totalWeight <= 0.0) return null
        var accumulator = 0.0
        for (index in values.indices) accumulator += values[index] * weights[index]
        return accumulator / totalWeight
    }

    /** Fraction of [values] strictly greater than [threshold]; the empirical survival function. */
    fun proportionAbove(values: DoubleArray, threshold: Double): Double {
        if (values.isEmpty()) return 0.0
        var count = 0
        for (value in values) if (value > threshold) count++
        return count.toDouble() / values.size
    }

    /** 1.4826 makes the MAD consistent with the standard deviation under normality. */
    const val MAD_TO_SIGMA = 1.4826
}

/** A robust straight-line fit with the diagnostics needed to judge how much to trust it. */
data class LineFit(
    val slope: Double,
    val intercept: Double,
    /** Robust scale of the residuals, in the units of y. */
    val residualScale: Double,
    val pointCount: Int,
    /** Range covered by x; a fit over five minutes deserves less weight than one over an hour. */
    val xSpan: Double,
) {
    fun predict(x: Double): Double = slope * x + intercept
}

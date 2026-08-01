package com.ontimequant.forecast

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Robust statistics used throughout the engine.
 *
 * Travel-time prediction errors are skewed and contain genuine outliers (an accident,
 * a closed bridge). Mean/standard deviation are not safe estimators here, so the engine
 * leans on medians, the median absolute deviation and winsorised moments.
 */
object Stats {

    /** Linear-interpolated quantile of an *already sorted* array. `q` in `[0, 1]`. */
    fun quantileSorted(sorted: DoubleArray, q: Double): Double {
        require(sorted.isNotEmpty()) { "quantile of empty sample" }
        val p = q.coerceIn(0.0, 1.0)
        if (sorted.size == 1) return sorted[0]
        val pos = p * (sorted.size - 1)
        val lo = pos.toInt()
        val hi = min(lo + 1, sorted.size - 1)
        val frac = pos - lo
        return sorted[lo] + frac * (sorted[hi] - sorted[lo])
    }

    fun quantile(values: DoubleArray, q: Double): Double =
        quantileSorted(values.copyOf().also { it.sort() }, q)

    fun median(values: DoubleArray): Double = quantile(values, 0.5)

    fun median(values: List<Double>): Double =
        if (values.isEmpty()) 0.0 else quantile(values.toDoubleArray(), 0.5)

    /**
     * Median absolute deviation, scaled by 1.4826 so that for Gaussian data it is a
     * consistent estimator of the standard deviation.
     */
    fun madSigma(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        val m = median(values)
        val deviations = values.map { abs(it - m) }
        return 1.4826 * median(deviations)
    }

    /** Weighted median — used when observations carry EWMA recency weights. */
    fun weightedMedian(values: List<Double>, weights: List<Double>): Double {
        require(values.size == weights.size)
        if (values.isEmpty()) return 0.0
        val order = values.indices.sortedBy { values[it] }
        val total = weights.sum()
        if (total <= 0.0) return median(values)
        var acc = 0.0
        for (i in order) {
            acc += weights[i]
            if (acc >= total / 2.0) return values[i]
        }
        return values[order.last()]
    }

    /** Clamp the tails of a sample at the given symmetric quantile. */
    fun winsorise(values: List<Double>, tail: Double = 0.05): List<Double> {
        if (values.size < 5) return values
        val arr = values.toDoubleArray().also { it.sort() }
        val lo = quantileSorted(arr, tail)
        val hi = quantileSorted(arr, 1.0 - tail)
        return values.map { it.coerceIn(lo, hi) }
    }

    fun mean(values: List<Double>): Double = if (values.isEmpty()) 0.0 else values.sum() / values.size

    fun rmse(errors: List<Double>): Double =
        if (errors.isEmpty()) 0.0 else sqrt(errors.sumOf { it * it } / errors.size)

    fun mae(errors: List<Double>): Double =
        if (errors.isEmpty()) 0.0 else errors.sumOf { abs(it) } / errors.size

    /**
     * Standard normal CDF (Abramowitz & Stegun 7.1.26 on erf). Used for closed-form
     * sanity checks and for the analytic fallback when no sample is available.
     */
    fun normalCdf(x: Double): Double {
        val t = 1.0 / (1.0 + 0.2316419 * abs(x))
        val d = 0.3989422804014327 * kotlin.math.exp(-x * x / 2.0)
        val p = d * t * (0.319381530 + t * (-0.356563782 + t * (1.781477937 + t * (-1.821255978 + t * 1.330274429))))
        return if (x >= 0) 1.0 - p else p
    }

    /**
     * Pool-Adjacent-Violators isotonic regression enforcing a **non-increasing** sequence.
     *
     * Applied only to the *displayed* departure curve and only when the raw simulated
     * curve actually violates monotonicity by more than the Monte Carlo standard error.
     * It is never applied to the probabilities used by the solver.
     */
    fun isotonicDecreasing(values: DoubleArray): DoubleArray {
        if (values.size <= 1) return values.copyOf()
        val y = values.copyOf()
        val w = DoubleArray(y.size) { 1.0 }
        val level = DoubleArray(y.size)
        val weight = DoubleArray(y.size)
        val count = IntArray(y.size)
        var j = -1
        for (i in y.indices) {
            j++
            level[j] = y[i]; weight[j] = w[i]; count[j] = 1
            while (j > 0 && level[j - 1] < level[j]) {
                val newWeight = weight[j - 1] + weight[j]
                level[j - 1] = (weight[j - 1] * level[j - 1] + weight[j] * level[j]) / newWeight
                weight[j - 1] = newWeight
                count[j - 1] += count[j]
                j--
            }
        }
        val out = DoubleArray(y.size)
        var idx = 0
        for (b in 0..j) {
            repeat(count[b]) { out[idx++] = level[b] }
        }
        return out
    }

    /** Largest violation of a non-increasing ordering, in absolute terms. */
    fun monotonicityViolation(values: DoubleArray): Double {
        var worst = 0.0
        for (i in 1 until values.size) {
            worst = max(worst, values[i] - values[i - 1])
        }
        return worst
    }

    /** Binomial standard error for a probability estimated from `n` Monte Carlo draws. */
    fun monteCarloStdError(p: Double, n: Int): Double =
        if (n <= 0) 1.0 else sqrt(max(1e-9, p * (1 - p)) / n)

    /** Ridge-regularised weighted slope through the origin: minimises Σw(y - βx)² + λβ². */
    fun ridgeSlopeThroughOrigin(
        x: List<Double>,
        y: List<Double>,
        weights: List<Double>,
        priorSlope: Double,
        priorPrecision: Double,
    ): Double {
        require(x.size == y.size && x.size == weights.size)
        var sxx = 0.0
        var sxy = 0.0
        for (i in x.indices) {
            sxx += weights[i] * x[i] * x[i]
            sxy += weights[i] * x[i] * y[i]
        }
        val denom = sxx + priorPrecision
        if (denom <= 0.0) return priorSlope
        return (sxy + priorPrecision * priorSlope) / denom
    }

    fun clamp01(v: Double): Double = when {
        v.isNaN() -> 0.0
        v < 0.0 -> 0.0
        v > 1.0 -> 1.0
        else -> v
    }

    fun brierScore(predicted: List<Double>, outcomes: List<Boolean>): Double {
        require(predicted.size == outcomes.size)
        if (predicted.isEmpty()) return 0.0
        return predicted.indices.sumOf { i ->
            val o = if (outcomes[i]) 1.0 else 0.0
            (predicted[i] - o).pow(2)
        } / predicted.size
    }
}

/**
 * Exponentially weighted accumulator that also tracks a proper **effective sample size**.
 *
 * With weights `w_i` the effective sample size is `n_eff = (Σw)² / Σw²`. This is the
 * quantity used by the Bayesian shrinkage step, so recency-discounted history correctly
 * counts for less than fresh history.
 *
 * @param halfLifeCount number of observations after which an observation's weight halves.
 */
data class EwmaAccumulator(
    val halfLifeCount: Double = 12.0,
    val sumW: Double = 0.0,
    val sumWX: Double = 0.0,
    val sumWX2: Double = 0.0,
    val sumW2: Double = 0.0,
    val rawCount: Int = 0,
) {
    private val decay: Double get() = 0.5.pow(1.0 / halfLifeCount)

    fun update(x: Double, weight: Double = 1.0): EwmaAccumulator {
        val d = decay
        return copy(
            sumW = sumW * d + weight,
            sumWX = sumWX * d + weight * x,
            sumWX2 = sumWX2 * d + weight * x * x,
            sumW2 = sumW2 * d * d + weight * weight,
            rawCount = rawCount + 1,
        )
    }

    val mean: Double get() = if (sumW <= 0.0) 0.0 else sumWX / sumW

    val effectiveSampleSize: Double get() = if (sumW2 <= 0.0) 0.0 else (sumW * sumW) / sumW2

    /** Weighted variance with the usual reliability correction. */
    val variance: Double
        get() {
            if (sumW <= 0.0) return 0.0
            val m = mean
            val v = (sumWX2 / sumW) - m * m
            val nEff = effectiveSampleSize
            if (nEff <= 1.0) return max(0.0, v)
            return max(0.0, v) * (nEff / (nEff - 1.0))
        }

    val sd: Double get() = sqrt(variance)

    val isEmpty: Boolean get() = rawCount == 0
}

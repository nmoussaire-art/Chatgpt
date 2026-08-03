package com.batterycast.quant.forecasting.uncertainty

import com.batterycast.quant.forecasting.stats.RobustStats
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The empirical distribution of this device's forecast errors, in percentage points per hour.
 *
 * Battery forecast errors are not normal. They are skewed — a phone can drain much faster than
 * expected far more easily than it can drain much slower than zero — and they have heavy tails,
 * because a single unexpected video call moves an hour's drain by more than any Gaussian would
 * allow. Assuming normality would produce prediction intervals that look reassuringly narrow and
 * are wrong roughly twice as often as they claim.
 *
 * So the model keeps the errors themselves and resamples them. Where there are not yet enough
 * errors to resample, it falls back to a Student-t with four degrees of freedom scaled by the
 * robust spread — still heavy-tailed, and honest about being a fallback.
 */
data class ResidualModel(
    /** Observed residuals in percentage points per hour; negative means faster than predicted. */
    val residuals: List<Double>,
    /** Median residual: a persistent non-zero value is bias, and is reported as such. */
    val median: Double,
    /** Robust spread, already scaled to be comparable to a standard deviation. */
    val scale: Double,
    /** Exponentially weighted volatility, so a recently erratic phone gets wider intervals. */
    val volatility: Double,
) {
    val sampleCount: Int get() = residuals.size

    val hasEmpiricalSupport: Boolean get() = residuals.size >= MIN_FOR_EMPIRICAL

    /** Empirical quantile of the residual distribution. */
    fun quantile(p: Double): Double? = RobustStats.quantile(residuals, p)

    /**
     * Draws one residual.
     *
     * With enough history this is a bootstrap resample — drawing an actual past error, which
     * reproduces the real skew and tails exactly. Otherwise it is a scaled Student-t(4) draw.
     */
    fun sample(random: Random): Double {
        if (hasEmpiricalSupport) {
            // Bootstrap: resample with replacement from the observed errors.
            return residuals[random.nextInt(residuals.size)]
        }
        val spread = if (volatility > 0.0) volatility else scale
        if (spread <= 0.0) return 0.0
        return median + spread * studentT(random, STUDENT_T_DEGREES_OF_FREEDOM)
    }

    companion object {
        /** Below this a "quantile" of the residuals is describing individual points, not a shape. */
        const val MIN_FOR_EMPIRICAL = 25

        const val STUDENT_T_DEGREES_OF_FREEDOM = 4

        /** Half-life used when weighting older residuals down in the volatility estimate. */
        const val VOLATILITY_HALF_LIFE_MS = 3L * 24 * 60 * 60 * 1000

        val EMPTY = ResidualModel(emptyList(), 0.0, 0.0, 0.0)

        /**
         * Builds the model from stored residuals, newest first.
         *
         * [volatility] is exponentially weighted so that a phone whose behaviour changed
         * yesterday gets wide intervals today, and narrow ones again once it settles.
         */
        fun from(
            residualsNewestFirst: List<TimedResidual>,
            nowMs: Long,
            halfLifeMs: Long = VOLATILITY_HALF_LIFE_MS,
        ): ResidualModel {
            if (residualsNewestFirst.isEmpty()) return EMPTY

            val values = residualsNewestFirst.map { it.value }
            val median = RobustStats.median(values) ?: 0.0
            val scale = RobustStats.medianAbsoluteDeviation(values) ?: 0.0

            var weightedSquares = 0.0
            var totalWeight = 0.0
            residualsNewestFirst.forEach { residual ->
                val ageMs = (nowMs - residual.atMs).coerceAtLeast(0L)
                val weight = 0.5.pow(ageMs.toDouble() / halfLifeMs)
                val deviation = residual.value - median
                weightedSquares += weight * deviation * deviation
                totalWeight += weight
            }
            val volatility = if (totalWeight > 0.0) sqrt(weightedSquares / totalWeight) else scale

            return ResidualModel(
                residuals = values,
                median = median,
                scale = scale,
                volatility = volatility,
            )
        }

        /**
         * Student-t draw via the ratio of a normal to a chi-scaled denominator, using only the
         * uniform stream so a fixed seed reproduces the same paths exactly.
         */
        fun studentT(random: Random, degreesOfFreedom: Int): Double {
            val z = gaussian(random)
            var chiSquare = 0.0
            repeat(degreesOfFreedom) {
                val g = gaussian(random)
                chiSquare += g * g
            }
            if (chiSquare <= 0.0) return z
            return z / sqrt(chiSquare / degreesOfFreedom)
        }

        /** Box–Muller. Kept here so every random draw in the app runs off one seeded stream. */
        fun gaussian(random: Random): Double {
            var u1 = random.nextDouble()
            // ln(0) is undefined; nudge off the boundary rather than rejecting and re-drawing,
            // which would desynchronise the stream from the seed.
            if (u1 <= 1e-12) u1 = 1e-12
            val u2 = random.nextDouble()
            return sqrt(-2.0 * kotlin.math.ln(u1)) * kotlin.math.cos(2.0 * Math.PI * u2)
        }

        private fun Double.pow(exponent: Double): Double = Math.pow(this, exponent)
    }
}

data class TimedResidual(val value: Double, val atMs: Long)

/** Bias diagnostics reported on the accuracy screen. */
data class ResidualDiagnostics(
    val medianError: Double,
    val meanAbsoluteError: Double,
    val bias: Double,
) {
    companion object {
        fun from(residuals: List<Double>): ResidualDiagnostics? {
            if (residuals.isEmpty()) return null
            return ResidualDiagnostics(
                medianError = RobustStats.median(residuals) ?: 0.0,
                meanAbsoluteError = residuals.map { abs(it) }.average(),
                bias = residuals.average(),
            )
        }
    }
}

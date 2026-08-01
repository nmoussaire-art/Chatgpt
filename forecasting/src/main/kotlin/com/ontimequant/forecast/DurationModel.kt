package com.ontimequant.forecast

import com.ontimequant.model.DataProvenance
import java.time.Instant
import kotlin.math.exp
import kotlin.math.ln

/**
 * A single observed duration for one of the non-driving components: preparation,
 * parking or walking. Keyed so it can be pooled hierarchically.
 */
data class DurationObservation(
    val at: Instant,
    /** Most specific key: destination id for parking/walking, origin id for preparation. */
    val key: String,
    /** Broader key used as the shrinkage parent, e.g. "all destinations". */
    val parentKey: String,
    val seconds: Double,
)

/**
 * A strictly positive, right-skewed duration modelled as log-normal.
 *
 * Preparation, parking search and walking are all bounded below and have long right
 * tails (the lift is slow, the car park is full). A log-normal captures that with two
 * interpretable parameters and is trivially sampled.
 *
 * Both parameters are shrunk toward the prior with the same conjugate rule used by the
 * travel model, so one unusual parking experience cannot dominate.
 */
data class FittedDuration(
    val medianSeconds: Double,
    val logSigma: Double,
    val effectiveSampleSize: Double,
    val personalWeight: Double,
    val provenance: DataProvenance,
    val sourceLabel: String,
) {
    val meanSeconds: Double get() = medianSeconds * exp(logSigma * logSigma / 2.0)

    fun sample(rng: Xoshiro256): Double =
        if (medianSeconds <= 0.0) 0.0 else rng.nextLogNormal(medianSeconds, logSigma).coerceAtMost(medianSeconds * 12)

    fun quantile(q: Double): Double {
        if (medianSeconds <= 0.0) return 0.0
        val z = inverseNormal(q.coerceIn(0.001, 0.999))
        return medianSeconds * exp(logSigma * z)
    }

    companion object {
        fun deterministic(seconds: Double, provenance: DataProvenance, label: String) =
            FittedDuration(seconds, 0.0, 0.0, 0.0, provenance, label)

        /** Acklam-style rational approximation to the standard normal quantile function. */
        fun inverseNormal(p: Double): Double {
            val a = doubleArrayOf(-39.69683028665376, 220.9460984245205, -275.9285104469687, 138.3577518672690, -30.66479806614716, 2.506628277459239)
            val b = doubleArrayOf(-54.47609879822406, 161.5858368580409, -155.6989798598866, 66.80131188771972, -13.28068155288572)
            val c = doubleArrayOf(-0.007784894002430293, -0.3223964580411365, -2.400758277161838, -2.549732539343734, 4.374664141464968, 2.938163982698783)
            val d = doubleArrayOf(0.007784695709041462, 0.3224671290700398, 2.445134137142996, 3.754408661907416)
            val pLow = 0.02425
            return when {
                p < pLow -> {
                    val q = kotlin.math.sqrt(-2 * ln(p))
                    (((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5]) /
                        ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1)
                }
                p <= 1 - pLow -> {
                    val q = p - 0.5
                    val r = q * q
                    (((((a[0] * r + a[1]) * r + a[2]) * r + a[3]) * r + a[4]) * r + a[5]) * q /
                        (((((b[0] * r + b[1]) * r + b[2]) * r + b[3]) * r + b[4]) * r + 1)
                }
                else -> {
                    val q = kotlin.math.sqrt(-2 * ln(1 - p))
                    -(((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5]) /
                        ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1)
                }
            }
        }
    }
}

object DurationModelFitter {

    /**
     * @param userDefaultSeconds a user setting, if they set one; overrides the population prior.
     * @param destinationOverrideSeconds a per-destination value the user typed in; wins outright.
     */
    fun fit(
        observations: List<DurationObservation>,
        key: String,
        parentKey: String,
        priorMedianSeconds: Double,
        priorLogSigma: Double,
        priorStrength: Double,
        halfLifeCount: Double,
        userDefaultSeconds: Double? = null,
        destinationOverrideSeconds: Double? = null,
        enabled: Boolean = true,
    ): FittedDuration {
        if (!enabled) {
            return FittedDuration.deterministic(0.0, DataProvenance.USER_DEFINED, "Turned off in settings")
        }
        if (destinationOverrideSeconds != null) {
            return FittedDuration(
                medianSeconds = destinationOverrideSeconds,
                logSigma = priorLogSigma * 0.6,
                effectiveSampleSize = 0.0,
                personalWeight = 1.0,
                provenance = DataProvenance.USER_DEFINED,
                sourceLabel = "You set this for this destination",
            )
        }

        val rootMedian = userDefaultSeconds ?: priorMedianSeconds
        val rootProvenance = if (userDefaultSeconds != null) DataProvenance.USER_DEFINED else DataProvenance.DEFAULT
        val rootLabel = if (userDefaultSeconds != null) "Your default setting" else "Standard assumption"

        val specific = observations.filter { it.key == key }
        val parent = observations.filter { it.parentKey == parentKey }

        if (parent.isEmpty()) {
            return FittedDuration(rootMedian, priorLogSigma, 0.0, 0.0, rootProvenance, rootLabel)
        }

        val parentFit = fitLevel(parent, halfLifeCount)
        val logParent = Shrinkage.toward(ln(rootMedian.coerceAtLeast(1.0)), parentFit.meanLog, parentFit.nEff, priorStrength)
        val sigmaParent = Shrinkage.scaleToward(priorLogSigma, parentFit.sigmaLog, parentFit.nEff, priorStrength)

        if (specific.isEmpty()) {
            val w = parentFit.nEff / (parentFit.nEff + priorStrength)
            return FittedDuration(
                medianSeconds = exp(logParent),
                logSigma = sigmaParent.coerceIn(0.08, 0.9),
                effectiveSampleSize = parentFit.nEff,
                personalWeight = w,
                provenance = if (w > 0.35) DataProvenance.LEARNED else rootProvenance,
                sourceLabel = if (w > 0.35) "Learned from your trips overall" else rootLabel,
            )
        }

        val specificFit = fitLevel(specific, halfLifeCount)
        val logFinal = Shrinkage.toward(logParent, specificFit.meanLog, specificFit.nEff, priorStrength)
        val sigmaFinal = Shrinkage.scaleToward(sigmaParent, specificFit.sigmaLog, specificFit.nEff, priorStrength)
        val w = specificFit.nEff / (specificFit.nEff + priorStrength)
        return FittedDuration(
            medianSeconds = exp(logFinal),
            logSigma = sigmaFinal.coerceIn(0.08, 0.9),
            effectiveSampleSize = specificFit.nEff,
            personalWeight = (w + (1 - w) * (parentFit.nEff / (parentFit.nEff + priorStrength))).coerceIn(0.0, 1.0),
            provenance = if (w > 0.25) DataProvenance.LEARNED else DataProvenance.LEARNED,
            sourceLabel = if (w > 0.25) "Learned from ${specific.size} trips here" else "Learned from your trips overall",
        )
    }

    private data class LevelFit(val meanLog: Double, val sigmaLog: Double, val nEff: Double)

    private fun fitLevel(obs: List<DurationObservation>, halfLife: Double): LevelFit {
        val ordered = obs.sortedBy { it.at }
        var acc = EwmaAccumulator(halfLifeCount = halfLife)
        val logs = ArrayList<Double>(ordered.size)
        for (o in ordered) {
            val s = o.seconds.coerceAtLeast(1.0)
            val l = ln(s)
            acc = acc.update(l)
            logs += l
        }
        // Robust centre: weighted median of the logs, which is the log of the median duration.
        val decay = Math.pow(0.5, 1.0 / halfLife)
        val n = logs.size
        val weights = logs.indices.map { Math.pow(decay, (n - 1 - it).toDouble()) }
        val centre = if (logs.size >= 4) Stats.weightedMedian(logs, weights) else acc.mean
        val sigma = if (logs.size >= 3) Stats.madSigma(Stats.winsorise(logs)) else acc.sd
        return LevelFit(centre, sigma, acc.effectiveSampleSize)
    }
}

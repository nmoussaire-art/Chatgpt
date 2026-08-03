package com.batterycast.quant.forecasting.shrinkage

import com.batterycast.quant.forecasting.ewma.EwmaCell
import com.batterycast.quant.forecasting.ewma.EwmaModel
import kotlin.math.sqrt

/**
 * A shrunk estimate together with an honest account of where it came from.
 */
data class ShrunkEstimate(
    val mean: Double,
    /** Variance of the underlying rate, including disagreement between hierarchy levels. */
    val variance: Double,
    /** Effective observations behind the most specific level that contributed. */
    val effectiveSamples: Double,
    /** How far towards the specific cell the estimate ended up, 0 (pure prior) to 1 (pure cell). */
    val specificity: Double,
    /** Deepest hierarchy level that had any evidence; -1 when nothing did. */
    val deepestLevelUsed: Int,
    val levelsAvailable: Int,
) {
    val standardDeviation: Double get() = sqrt(variance.coerceAtLeast(0.0))

    /** Rough confidence label for the UI, derived from evidence rather than asserted. */
    val supportLevel: SupportLevel
        get() = when {
            effectiveSamples >= 20.0 && specificity >= 0.6 -> SupportLevel.STRONG
            effectiveSamples >= 6.0 -> SupportLevel.MODERATE
            effectiveSamples > 0.0 -> SupportLevel.SPARSE
            else -> SupportLevel.NONE
        }
}

enum class SupportLevel { NONE, SPARSE, MODERATE, STRONG }

/**
 * Hierarchical (empirical-Bayes) shrinkage.
 *
 * The problem this solves is concrete. "Heavy use, power saving off, warm, on mobile data, Tuesday
 * evening" is the state the phone is actually in, and it is also a state the app may have seen
 * three times. Using its mean alone would let three intervals dictate an eight-hour forecast;
 * ignoring it would throw away the most relevant evidence there is.
 *
 * So the estimate is built from the general down: start at the device-wide average, then move
 * towards each successively more specific cell by an amount proportional to how much evidence
 * that cell holds. With `w = n / (n + k)`, a cell with `k` effective observations gets half the
 * say, and a cell with none changes nothing. As real history accumulates the specific state takes
 * over on its own, without any switch being flipped.
 *
 * The variance is combined by the law of total variance, so *disagreement between levels* — the
 * specific state saying something different from the general one — widens the forecast interval
 * rather than being averaged away.
 */
object BayesianShrinkage {

    /**
     * Prior strength `k`, in effective observations.
     *
     * At `k = 5` a cell needs five effective observations before it outweighs its parent. Low
     * enough to personalise within a day of normal use; high enough that one unusual evening
     * cannot take over.
     */
    const val DEFAULT_PRIOR_STRENGTH = 5.0

    /**
     * Combines a hierarchy of cells, ordered from most general to most specific.
     *
     * [cells] may contain nulls for levels never observed; they are skipped without penalty.
     * Returns null only when no level has any evidence at all, which the caller must report as
     * "not enough data yet" rather than papering over.
     */
    fun shrink(
        cells: List<EwmaCell?>,
        atMs: Long,
        priorStrength: Double = DEFAULT_PRIOR_STRENGTH,
        halfLifeMs: Long = EwmaModel.DEFAULT_HALF_LIFE_MS,
    ): ShrunkEstimate? {
        if (cells.isEmpty()) return null

        var estimate: Double? = null
        var variance = 0.0
        var effectiveSamples = 0.0
        var specificity = 0.0
        var deepestLevel = -1

        cells.forEachIndexed { level, cell ->
            if (cell == null) return@forEachIndexed
            val weight = EwmaModel.effectiveWeight(cell, atMs, halfLifeMs)
            if (weight <= 0.0) return@forEachIndexed

            deepestLevel = level

            val current = estimate
            if (current == null) {
                estimate = cell.mean
                variance = cell.variance
                effectiveSamples = weight
                specificity = 1.0
                return@forEachIndexed
            }

            val w = weight / (weight + priorStrength)
            val blended = w * cell.mean + (1 - w) * current

            // Law of total variance across the two-component mixture {parent, this cell}: the
            // within-component variances, plus the spread between the components themselves.
            val betweenLevel = w * (1 - w) * (cell.mean - current) * (cell.mean - current)
            variance = w * cell.variance + (1 - w) * variance + betweenLevel

            estimate = blended
            effectiveSamples = weight
            specificity = w
        }

        val resolved = estimate ?: return null
        return ShrunkEstimate(
            mean = resolved,
            variance = variance.coerceAtLeast(0.0),
            effectiveSamples = effectiveSamples,
            specificity = specificity,
            deepestLevelUsed = deepestLevel,
            levelsAvailable = cells.size,
        )
    }

    /**
     * Blends a live measurement with the learned model.
     *
     * Right after installation the learned model is empty and the live estimate is all there is;
     * once history exists the live reading is one more piece of evidence rather than the whole
     * story. [liveWeight] expresses how much the live estimate is worth in effective observations
     * — a thirty-minute robust fit is worth more than three instantaneous current readings.
     */
    fun blendWithLive(
        model: ShrunkEstimate?,
        liveMean: Double?,
        liveVariance: Double,
        liveWeight: Double,
    ): ShrunkEstimate? {
        if (model == null && liveMean == null) return null
        if (model == null) {
            return ShrunkEstimate(
                mean = liveMean!!,
                variance = liveVariance,
                effectiveSamples = liveWeight,
                specificity = 1.0,
                deepestLevelUsed = 0,
                levelsAvailable = 1,
            )
        }
        if (liveMean == null) return model

        val w = liveWeight / (liveWeight + model.effectiveSamples.coerceAtLeast(0.001))
        val mean = w * liveMean + (1 - w) * model.mean
        val between = w * (1 - w) * (liveMean - model.mean) * (liveMean - model.mean)
        val variance = w * liveVariance + (1 - w) * model.variance + between

        return model.copy(
            mean = mean,
            variance = variance.coerceAtLeast(0.0),
            effectiveSamples = model.effectiveSamples + liveWeight,
            specificity = maxOf(model.specificity, w),
        )
    }
}

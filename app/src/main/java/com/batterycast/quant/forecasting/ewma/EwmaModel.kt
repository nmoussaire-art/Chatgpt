package com.batterycast.quant.forecasting.ewma

import com.batterycast.quant.core.database.entity.ModelCellEntity
import com.batterycast.quant.forecasting.model.DrainMetric
import com.batterycast.quant.forecasting.model.ModelNamespace
import kotlin.math.pow

/**
 * One exponentially weighted cell: a running mean and variance for a metric in a device state.
 */
data class EwmaCell(
    val namespace: ModelNamespace,
    val key: String,
    val metric: DrainMetric,
    val mean: Double,
    val variance: Double,
    /** Effective sample count after time decay. Fractional, and the basis for shrinkage. */
    val weight: Double,
    val rawSamples: Long,
    val lastUpdateMs: Long,
) {
    val standardDeviation: Double get() = kotlin.math.sqrt(variance.coerceAtLeast(0.0))
}

/**
 * Exponentially weighted mean and variance with explicit time decay.
 *
 * Two things a phone's battery behaviour does at once: it drifts (a new app, a new commute, a
 * battery a year older) and it repeats (the same evenings, the same commute). A plain average
 * over all history is blind to the first; a window over the last hour is blind to the second.
 *
 * This model handles both by decaying each cell's accumulated weight with a half-life rather than
 * discarding old data outright. A cell that has not been visited for a fortnight still remembers
 * its mean, but carries little enough weight that a handful of fresh observations can move it.
 *
 * The update is West's weighted incremental algorithm, which gives the same answer as recomputing
 * the weighted mean and variance from scratch over all decayed observations, in constant memory.
 */
object EwmaModel {

    /**
     * Half-life for the weight of past observations.
     *
     * Seven days keeps roughly a fortnight of real influence: recent behaviour dominates, while a
     * pattern from last week is still visible.
     */
    const val DEFAULT_HALF_LIFE_MS = 7L * 24 * 60 * 60 * 1000

    /**
     * Largest weight a single interval may contribute.
     *
     * Without this, one eight-hour overnight interval would count as much as sixteen half-hour
     * daytime ones and quietly become the model.
     */
    const val MAX_OBSERVATION_WEIGHT = 2.0

    fun emptyCell(
        namespace: ModelNamespace,
        key: String,
        metric: DrainMetric,
        atMs: Long,
    ): EwmaCell = EwmaCell(
        namespace = namespace,
        key = key,
        metric = metric,
        mean = 0.0,
        variance = 0.0,
        weight = 0.0,
        rawSamples = 0L,
        lastUpdateMs = atMs,
    )

    /**
     * Folds one observation into a cell.
     *
     * [observationWeight] is normally the interval's duration in hours, capped, so that a rate
     * measured over forty minutes counts for more than one measured over six.
     */
    fun update(
        cell: EwmaCell,
        value: Double,
        observationWeight: Double,
        atMs: Long,
        halfLifeMs: Long = DEFAULT_HALF_LIFE_MS,
    ): EwmaCell {
        val cappedWeight = observationWeight.coerceIn(MIN_OBSERVATION_WEIGHT, MAX_OBSERVATION_WEIGHT)
        val decayed = decayWeight(cell.weight, cell.lastUpdateMs, atMs, halfLifeMs)

        if (decayed <= 0.0) {
            return cell.copy(
                mean = value,
                variance = 0.0,
                weight = cappedWeight,
                rawSamples = cell.rawSamples + 1,
                lastUpdateMs = atMs,
            )
        }

        val totalWeight = decayed + cappedWeight
        val alpha = cappedWeight / totalWeight
        val delta = value - cell.mean
        val newMean = cell.mean + alpha * delta
        // West's update: the (1 - alpha) factor keeps this an unbiased weighted variance rather
        // than one that shrinks towards zero as weight accumulates.
        val newVariance = ((1 - alpha) * (cell.variance + alpha * delta * delta)).coerceAtLeast(0.0)

        return cell.copy(
            mean = newMean,
            variance = newVariance,
            weight = totalWeight,
            rawSamples = cell.rawSamples + 1,
            lastUpdateMs = atMs,
        )
    }

    /** Applies the half-life decay to a stored weight, bringing it forward to [atMs]. */
    fun decayWeight(weight: Double, lastUpdateMs: Long, atMs: Long, halfLifeMs: Long): Double {
        if (weight <= 0.0) return 0.0
        val elapsed = (atMs - lastUpdateMs).coerceAtLeast(0L)
        if (elapsed == 0L) return weight
        val halfLives = elapsed.toDouble() / halfLifeMs
        return weight * 0.5.pow(halfLives)
    }

    /** The cell's weight as of now, without mutating it. Used when reading for a forecast. */
    fun effectiveWeight(cell: EwmaCell, atMs: Long, halfLifeMs: Long = DEFAULT_HALF_LIFE_MS): Double =
        decayWeight(cell.weight, cell.lastUpdateMs, atMs, halfLifeMs)

    /** A very short interval still carries a little information; it should not count as nothing. */
    const val MIN_OBSERVATION_WEIGHT = 0.05
}

fun EwmaCell.toEntity(): ModelCellEntity = ModelCellEntity(
    namespace = namespace,
    cellKey = key,
    metric = metric,
    mean = mean,
    variance = variance,
    weight = weight,
    rawSamples = rawSamples,
    lastUpdateMs = lastUpdateMs,
)

fun ModelCellEntity.toDomain(): EwmaCell = EwmaCell(
    namespace = namespace,
    key = cellKey,
    metric = metric,
    mean = mean,
    variance = variance,
    weight = weight,
    rawSamples = rawSamples,
    lastUpdateMs = lastUpdateMs,
)

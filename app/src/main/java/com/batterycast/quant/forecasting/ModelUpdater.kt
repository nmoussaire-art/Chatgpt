package com.batterycast.quant.forecasting

import com.batterycast.quant.core.database.dao.ModelCellDao
import com.batterycast.quant.core.database.dao.RegimeTransitionDao
import com.batterycast.quant.core.database.entity.RegimeTransitionEntity
import com.batterycast.quant.core.datastore.SettingsStore
import com.batterycast.quant.di.DefaultDispatcher
import com.batterycast.quant.forecasting.clean.CleanedHistory
import com.batterycast.quant.forecasting.clean.ObservationCleaner
import com.batterycast.quant.forecasting.clean.SegmentKind
import com.batterycast.quant.forecasting.ewma.EwmaCell
import com.batterycast.quant.forecasting.ewma.EwmaModel
import com.batterycast.quant.forecasting.ewma.toDomain
import com.batterycast.quant.forecasting.ewma.toEntity
import com.batterycast.quant.forecasting.model.ChargeStateKey
import com.batterycast.quant.forecasting.model.DrainMetric
import com.batterycast.quant.forecasting.model.ModelNamespace
import com.batterycast.quant.forecasting.model.SocBucket
import com.batterycast.quant.forecasting.model.SocDecile
import com.batterycast.quant.forecasting.model.ThermalBucket
import com.batterycast.quant.forecasting.model.UsageRegime
import com.batterycast.quant.forecasting.regime.RegimeClassifier
import com.batterycast.quant.forecasting.regime.RegimeInterval
import com.batterycast.quant.forecasting.regime.RegimeThresholds
import com.batterycast.quant.telemetry.model.ThermalStatus
import com.batterycast.quant.telemetry.repository.ObservationRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Folds newly observed intervals into the learned model.
 *
 * Everything this class writes is derived from measurements taken on this device. It never
 * initialises a cell to a default rate, never seeds the model with typical values, and never
 * creates a cell for a state that has not actually occurred — an unvisited state is represented
 * by its absence, which is what makes shrinkage fall back to the general case honestly.
 */
@Singleton
class ModelUpdater @Inject constructor(
    private val observationRepository: ObservationRepository,
    private val modelCellDao: ModelCellDao,
    private val regimeTransitionDao: RegimeTransitionDao,
    private val settingsStore: SettingsStore,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
) {
    private val updateMutex = Mutex()
    private val classifier = RegimeClassifier()

    /**
     * Learns from every interval newer than the stored watermark.
     *
     * The watermark is what stops the same evening being learned from twenty times; without it
     * the model's effective sample counts would grow with how often the app is opened rather than
     * with how much has actually been observed.
     */
    suspend fun refresh(nowMs: Long = System.currentTimeMillis()): ModelUpdateResult =
        withContext(defaultDispatcher) {
            updateMutex.withLock {
                val watermark = settingsStore.modelWatermarkMs()

                // Re-read a little before the watermark so an interval straddling it still has
                // both endpoints; the watermark comparison below prevents double counting.
                val lookbackStart = (watermark - OVERLAP_MS).coerceAtLeast(0L)
                val history = observationRepository.since(
                    if (watermark == 0L) 0L else lookbackStart,
                )
                if (history.size < 2) return@withLock ModelUpdateResult.empty(watermark)

                val cleaned = ObservationCleaner.clean(history)
                if (cleaned.segments.isEmpty()) return@withLock ModelUpdateResult.empty(watermark)

                // Bands come from the whole visible history, not just the new part, so the
                // notion of "heavy use on this phone" stays stable between refreshes.
                val thresholds = RegimeThresholds.learn(classifier.rawIntervals(cleaned.segments))

                val intervals = cleaned.segments.flatMap { segment ->
                    classifier.classifySegment(segment, thresholds)
                }.filter { it.from.timestampMs > watermark }

                if (intervals.isEmpty()) {
                    return@withLock ModelUpdateResult.empty(watermark)
                }

                val cells = loadCells()
                applyIntervals(intervals, cleaned, cells)
                modelCellDao.upsert(cells.values.map { it.toEntity() })

                updateRegimeTransitions(intervals, nowMs)

                val newWatermark = intervals.maxOf { it.from.timestampMs }
                settingsStore.setModelWatermarkMs(newWatermark)

                ModelUpdateResult(
                    intervalsLearned = intervals.size,
                    cellsTouched = cells.size,
                    watermarkMs = newWatermark,
                    thresholds = thresholds,
                )
            }
        }

    private suspend fun loadCells(): MutableMap<CellId, EwmaCell> =
        modelCellDao.all().associate { entity ->
            CellId(entity.namespace, entity.cellKey, entity.metric) to entity.toDomain()
        }.toMutableMap()

    private fun applyIntervals(
        intervals: List<RegimeInterval>,
        cleaned: CleanedHistory,
        cells: MutableMap<CellId, EwmaCell>,
    ) {
        // A global discharge baseline, needed before per-band multipliers mean anything.
        val dischargeIntervals = intervals.filter { it.regime.isDischarge }
        val baselineRate = dischargeIntervals
            .map { it.ratePerHour }
            .takeIf { it.isNotEmpty() }
            ?.average()

        intervals.forEach { interval ->
            val atMs = interval.midpointMs
            val weight = interval.durationHours

            if (interval.regime.isCharging) {
                applyChargeInterval(interval, cells, atMs, weight)
            } else {
                applyDrainInterval(interval, cells, atMs, weight)
                applyCurveAndModifiers(interval, cells, atMs, weight, baselineRate)
            }
        }

        // Charge-counter and energy metrics are learned per regime, where supported, so a device
        // with a fuel gauge gets rates that do not depend on percentage rounding at all.
        applyElectricalMetrics(intervals, cleaned, cells)
    }

    private fun applyDrainInterval(
        interval: RegimeInterval,
        cells: MutableMap<CellId, EwmaCell>,
        atMs: Long,
        weight: Double,
    ) {
        val rate = interval.ratePerHour
        if (rate < 0.0) return
        interval.stateKey.hierarchy().forEach { key ->
            touch(cells, ModelNamespace.DRAIN, key, DrainMetric.PERCENT_PER_HOUR, rate, weight, atMs)
        }
    }

    private fun applyChargeInterval(
        interval: RegimeInterval,
        cells: MutableMap<CellId, EwmaCell>,
        atMs: Long,
        weight: Double,
    ) {
        val rate = interval.ratePerHour
        if (rate <= 0.0) return
        val thermal = thermalBucketOf(interval)
        val key = ChargeStateKey(
            plugType = interval.from.plugType,
            socBucket = SocBucket.forPercent(interval.meanPercent),
            thermal = thermal,
        )
        key.hierarchy().forEach { cellKey ->
            touch(cells, ModelNamespace.CHARGE, cellKey, DrainMetric.PERCENT_PER_HOUR, rate, weight, atMs)
        }
    }

    /**
     * Learns the shape of this device's discharge curve and its thermal and power-save effects.
     *
     * Each is stored as a *multiplier* against the interval's own baseline rate, so the curve
     * describes how the battery behaves at 15 % versus 85 % independently of how hard the phone
     * happened to be working at the time.
     */
    private fun applyCurveAndModifiers(
        interval: RegimeInterval,
        cells: MutableMap<CellId, EwmaCell>,
        atMs: Long,
        weight: Double,
        baselineRate: Double?,
    ) {
        if (baselineRate == null || baselineRate <= MIN_BASELINE_FOR_RATIO) return
        val ratio = interval.ratePerHour / baselineRate
        if (!ratio.isFinite() || ratio <= 0.0 || ratio > MAX_PLAUSIBLE_RATIO) return

        val decile = SocDecile.forPercent(interval.meanPercent)
        touch(cells, ModelNamespace.SOC_CURVE, decile.name, DrainMetric.PERCENT_PER_HOUR, ratio, weight, atMs)

        val thermal = thermalBucketOf(interval)
        if (thermal != ThermalBucket.UNKNOWN) {
            touch(cells, ModelNamespace.THERMAL, thermal.name, DrainMetric.PERCENT_PER_HOUR, ratio, weight, atMs)
        }

        touch(
            cells,
            ModelNamespace.POWER_SAVE,
            interval.from.powerSaveEnabled.toString(),
            DrainMetric.PERCENT_PER_HOUR,
            ratio,
            weight,
            atMs,
        )
    }

    /**
     * Learns mAh/h and Wh/h rates on devices whose fuel gauge supports them.
     *
     * Runs over whole segments rather than single intervals because counter deltas over a few
     * minutes are dominated by gauge noise.
     */
    private fun applyElectricalMetrics(
        intervals: List<RegimeInterval>,
        cleaned: CleanedHistory,
        cells: MutableMap<CellId, EwmaCell>,
    ) {
        val newest = intervals.maxOfOrNull { it.from.timestampMs } ?: return
        val oldest = intervals.minOfOrNull { it.from.timestampMs } ?: return

        cleaned.segments
            .filter { it.kind == SegmentKind.DISCHARGE }
            .filter { it.endMs >= oldest && it.startMs <= newest }
            .forEach { segment ->
                val observations = segment.observations.filter { it.usableForModelling }
                if (observations.size < 3) return@forEach
                val hours = segment.durationHours
                if (hours < MIN_SEGMENT_HOURS_FOR_ELECTRICAL) return@forEach

                val dominantRegime = intervals
                    .filter { it.from.timestampMs in segment.startMs..segment.endMs }
                    .groupingBy { it.regime }
                    .eachCount()
                    .maxByOrNull { it.value }
                    ?.key
                    ?: UsageRegime.UNCLASSIFIED_DISCHARGE
                val key = "r=${dominantRegime.name}"
                val atMs = (segment.startMs + segment.endMs) / 2
                val weight = hours

                val firstCharge = observations.first().chargeCounterMilliAh
                val lastCharge = observations.last().chargeCounterMilliAh
                if (firstCharge != null && lastCharge != null) {
                    val milliAmpHoursPerHour = (firstCharge - lastCharge) / hours
                    if (milliAmpHoursPerHour > 0.0) {
                        listOf("global", key).forEach { cellKey ->
                            touch(
                                cells, ModelNamespace.DRAIN, cellKey,
                                DrainMetric.MILLIAMP_HOUR_PER_HOUR, milliAmpHoursPerHour, weight, atMs,
                            )
                        }
                    }
                }

                val firstEnergy = observations.first().energyWattHours
                val lastEnergy = observations.last().energyWattHours
                if (firstEnergy != null && lastEnergy != null) {
                    val wattHoursPerHour = (firstEnergy - lastEnergy) / hours
                    if (wattHoursPerHour > 0.0) {
                        listOf("global", key).forEach { cellKey ->
                            touch(
                                cells, ModelNamespace.DRAIN, cellKey,
                                DrainMetric.WATT_HOUR_PER_HOUR, wattHoursPerHour, weight, atMs,
                            )
                        }
                    }
                }
            }
    }

    /**
     * Updates the regime transition chain.
     *
     * The simulator needs to know that "heavy use" rarely lasts eight hours and that "standby"
     * usually persists overnight. Both come from counting the transitions this device actually
     * made, decayed so recent habits count for more.
     */
    private suspend fun updateRegimeTransitions(intervals: List<RegimeInterval>, nowMs: Long) {
        if (intervals.size < 2) return

        val existing = regimeTransitionDao.all()
            .associateBy { it.fromRegime to it.toRegime }
            .toMutableMap()

        for (index in 0 until intervals.size - 1) {
            val from = intervals[index]
            val to = intervals[index + 1]
            // Only count transitions between adjacent intervals; a gap means we did not see the
            // transition and counting it would invent a behaviour change.
            if (to.from.timestampMs != from.to.timestampMs) continue

            val pair = from.regime to to.regime
            val current = existing[pair]
            val decayed = current?.let {
                EwmaModel.decayWeight(it.decayedCount, it.lastUpdateMs, nowMs, TRANSITION_HALF_LIFE_MS)
            } ?: 0.0
            existing[pair] = RegimeTransitionEntity(
                fromRegime = pair.first,
                toRegime = pair.second,
                decayedCount = decayed + 1.0,
                lastUpdateMs = nowMs,
            )
        }

        regimeTransitionDao.upsert(existing.values.toList())
    }

    private fun thermalBucketOf(interval: RegimeInterval): ThermalBucket =
        if (interval.from.thermalStatus == ThermalStatus.UNSUPPORTED) {
            ThermalBucket.forTemperature(interval.from.temperatureCelsius)
        } else {
            ThermalBucket.forStatus(interval.from.thermalStatus)
        }

    private fun touch(
        cells: MutableMap<CellId, EwmaCell>,
        namespace: ModelNamespace,
        key: String,
        metric: DrainMetric,
        value: Double,
        weight: Double,
        atMs: Long,
    ) {
        val id = CellId(namespace, key, metric)
        val existing = cells[id] ?: EwmaModel.emptyCell(namespace, key, metric, atMs)
        cells[id] = EwmaModel.update(existing, value, weight, atMs)
    }

    /** Forgets everything learned, for the "delete my data" path. */
    suspend fun reset() = withContext(defaultDispatcher) {
        modelCellDao.deleteAll()
        regimeTransitionDao.deleteAll()
        settingsStore.clearModelWatermark()
    }

    private data class CellId(val namespace: ModelNamespace, val key: String, val metric: DrainMetric)

    companion object {
        /** Re-read window so an interval spanning the watermark keeps both endpoints. */
        const val OVERLAP_MS = 60 * 60 * 1000L

        /** Habits shift faster than drain rates, so transitions decay more quickly. */
        const val TRANSITION_HALF_LIFE_MS = 10L * 24 * 60 * 60 * 1000

        /** Below this the ratio denominator is too small to be meaningful. */
        const val MIN_BASELINE_FOR_RATIO = 0.2

        /** A ratio beyond this is a segmentation artefact, not a real curve effect. */
        const val MAX_PLAUSIBLE_RATIO = 8.0

        const val MIN_SEGMENT_HOURS_FOR_ELECTRICAL = 0.25
    }
}

data class ModelUpdateResult(
    val intervalsLearned: Int,
    val cellsTouched: Int,
    val watermarkMs: Long,
    val thresholds: RegimeThresholds,
) {
    companion object {
        fun empty(watermarkMs: Long) = ModelUpdateResult(0, 0, watermarkMs, RegimeThresholds.EMPTY)
    }
}

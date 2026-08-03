package com.batterycast.quant.forecasting.accuracy

import com.batterycast.quant.core.database.dao.ForecastRecordDao
import com.batterycast.quant.core.database.dao.ResidualDao
import com.batterycast.quant.core.database.entity.ForecastRecordEntity
import com.batterycast.quant.core.database.entity.ResidualEntity
import com.batterycast.quant.di.DefaultDispatcher
import com.batterycast.quant.forecasting.model.BatteryForecast
import com.batterycast.quant.forecasting.stats.RobustStats
import com.batterycast.quant.telemetry.repository.ObservationRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/** Honest accuracy statistics, computed from forecasts made before the outcome was known. */
data class AccuracyReport(
    val evaluatedForecasts: Int,
    /** Median absolute error in percentage points, by how far ahead the forecast reached. */
    val medianErrorByHorizon: Map<HorizonBucket, Double>,
    /** Signed median error: positive means the app has been too pessimistic. */
    val bias: Double?,
    /** Fraction of outcomes that fell inside each stated interval. */
    val intervalCoverage: Map<IntervalBand, Double>,
    /** Predicted-versus-observed frequency for survival probabilities. */
    val calibrationBins: List<CalibrationBin>,
    val timeToTwentyPercentErrorMinutes: Double?,
) {
    val hasEnoughData: Boolean get() = evaluatedForecasts >= MIN_FORECASTS_FOR_REPORT

    companion object {
        /** Below this, a "coverage" figure is describing a handful of coin flips. */
        const val MIN_FORECASTS_FOR_REPORT = 20
    }
}

enum class HorizonBucket(val label: String, val upperMinutes: Double) {
    UNDER_ONE_HOUR("Under 1 hour", 60.0),
    ONE_TO_THREE_HOURS("1–3 hours", 180.0),
    THREE_TO_SIX_HOURS("3–6 hours", 360.0),
    OVER_SIX_HOURS("Over 6 hours", Double.MAX_VALUE),
    ;

    companion object {
        fun forMinutes(minutes: Double): HorizonBucket =
            entries.first { minutes <= it.upperMinutes }
    }
}

enum class IntervalBand(val label: String, val nominalCoverage: Double) {
    FIFTY("50% interval", 0.50),
    EIGHTY("80% interval", 0.80),
    NINETY("90% interval", 0.90),
}

/**
 * One probability bin: what the app predicted, versus how often it actually happened.
 *
 * A well-calibrated forecaster's 70 % predictions come true about 70 % of the time. This is the
 * only claim about accuracy worth making for a probabilistic app, and it is the reason the
 * accuracy screen never says anything like "98 % accurate".
 */
data class CalibrationBin(
    val predictedLow: Double,
    val predictedHigh: Double,
    val predictedMean: Double,
    val observedFrequency: Double,
    val count: Int,
)

/**
 * Records forecasts when they are made, scores them once the target time passes, and turns the
 * scores into both the accuracy report and the residual pool the simulator resamples from.
 *
 * This closes the loop: the app's stated uncertainty is derived from its own past errors, so an
 * over-confident model widens itself automatically as its misses accumulate.
 */
@Singleton
class AccuracyEvaluator @Inject constructor(
    private val forecastRecordDao: ForecastRecordDao,
    private val residualDao: ResidualDao,
    private val observationRepository: ObservationRepository,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
) {

    /**
     * Files a forecast for later scoring.
     *
     * Only forecasts with a real target inside the horizon are recorded, and only at intervals —
     * recording every refresh would flood the pool with near-duplicates and make the coverage
     * statistics look far better supported than they are.
     */
    suspend fun record(forecast: BatteryForecast, targetMs: Long, reservePercent: Double) =
        withContext(defaultDispatcher) {
            val point = forecast.curve.minByOrNull { abs(it.atMs - targetMs) } ?: return@withContext
            val horizonMinutes = (targetMs - forecast.generatedAtMs) / 60_000.0
            if (horizonMinutes < MIN_RECORDED_HORIZON_MINUTES) return@withContext

            val survival = forecast.target?.survivalProbability ?: return@withContext

            forecastRecordDao.insert(
                ForecastRecordEntity(
                    issuedAtMs = forecast.generatedAtMs,
                    targetMs = targetMs,
                    horizonMinutes = horizonMinutes,
                    batteryPercentAtIssue = forecast.currentPercent,
                    predictedMedian = point.median,
                    predictedP10 = point.p10,
                    predictedP25 = point.p25,
                    predictedP75 = point.p75,
                    predictedP90 = point.p90,
                    predictedSurvivalProbability = survival,
                    reservePercent = reservePercent,
                    maturity = forecast.maturity.name,
                    wasChargingAtIssue = forecast.isCharging,
                ),
            )
        }

    /**
     * Scores every forecast whose target time has passed and for which a real observation exists
     * close enough in time to compare against.
     *
     * A forecast with no nearby observation is discarded rather than scored against an
     * interpolation — a made-up outcome would corrupt both the accuracy report and the residual
     * pool that drives the app's uncertainty.
     */
    suspend fun evaluatePending(nowMs: Long = System.currentTimeMillis()): Int =
        withContext(defaultDispatcher) {
            val pending = forecastRecordDao.pendingEvaluation(nowMs)
            var scored = 0

            pending.forEach { record ->
                val actual = observationRepository.nearest(record.targetMs, MATCH_TOLERANCE_MS)
                if (actual == null) {
                    // Give it a grace period in case a later sample lands near the target; beyond
                    // that the forecast simply cannot be scored and is dropped.
                    if (nowMs - record.targetMs > ABANDON_AFTER_MS) forecastRecordDao.delete(record.id)
                    return@forEach
                }

                forecastRecordDao.recordOutcome(
                    id = record.id,
                    actualPercent = actual.batteryPercent,
                    actualWasCharging = actual.isCharging,
                    evaluatedAtMs = nowMs,
                )

                // Charging between issue and target changes the physics entirely; those outcomes
                // are kept for the accuracy report but not fed into the discharge residual pool.
                if (!actual.isCharging && !record.wasChargingAtIssue) {
                    val errorPercentPoints = actual.batteryPercent - record.predictedMedian
                    val hours = (record.horizonMinutes / 60.0).coerceAtLeast(0.25)
                    residualDao.insert(
                        ResidualEntity(
                            createdAtMs = record.targetMs,
                            horizonMinutes = record.horizonMinutes,
                            residualPercentPoints = errorPercentPoints,
                            // Expressed as a rate so it can be added to a per-hour drain in the
                            // simulator. The sign is flipped because a *lower* than predicted
                            // battery means a *higher* than predicted drain rate.
                            residualPercentPerHour = -errorPercentPoints / hours,
                            regime = com.batterycast.quant.forecasting.model.UsageRegime.UNCLASSIFIED_DISCHARGE,
                            wasCharging = false,
                        ),
                    )
                }
                scored++
            }
            scored
        }

    /** Builds the accuracy report, or null when too few forecasts have been scored to say anything. */
    suspend fun report(limit: Int = REPORT_WINDOW): AccuracyReport? = withContext(defaultDispatcher) {
        val evaluated = forecastRecordDao.evaluated(limit).filter { it.actualPercent != null }
        if (evaluated.isEmpty()) return@withContext null

        val byHorizon = evaluated
            .groupBy { HorizonBucket.forMinutes(it.horizonMinutes) }
            .mapNotNull { (bucket, records) ->
                val errors = records.map { abs(it.actualPercent!! - it.predictedMedian) }
                RobustStats.median(errors)?.let { bucket to it }
            }
            .toMap()

        val signedErrors = evaluated.map { it.actualPercent!! - it.predictedMedian }

        val coverage = IntervalBand.entries.associateWith { band ->
            val inside = evaluated.count { record ->
                val actual = record.actualPercent!!
                when (band) {
                    IntervalBand.FIFTY -> actual in record.predictedP25..record.predictedP75
                    // The simulator's fan is symmetric around the median, so the 10th-to-90th
                    // percentile band is the 80 % interval and the widest recorded pair is used
                    // for the 90 % figure.
                    IntervalBand.EIGHTY -> actual in record.predictedP10..record.predictedP90
                    IntervalBand.NINETY -> actual in (record.predictedP10 - HALF_TAIL_MARGIN)..
                        (record.predictedP90 + HALF_TAIL_MARGIN)
                }
            }
            inside.toDouble() / evaluated.size
        }

        AccuracyReport(
            evaluatedForecasts = evaluated.size,
            medianErrorByHorizon = byHorizon,
            bias = RobustStats.median(signedErrors),
            intervalCoverage = coverage,
            calibrationBins = calibrationBins(evaluated),
            timeToTwentyPercentErrorMinutes = null,
        )
    }

    /**
     * Groups scored forecasts by the probability they stated and compares against how often the
     * event actually occurred.
     */
    private fun calibrationBins(records: List<ForecastRecordEntity>): List<CalibrationBin> {
        val edges = listOf(0.0, 0.2, 0.4, 0.6, 0.8, 1.0001)
        return (0 until edges.size - 1).mapNotNull { index ->
            val low = edges[index]
            val high = edges[index + 1]
            val bin = records.filter { it.predictedSurvivalProbability in low..<high }
            if (bin.size < MIN_PER_CALIBRATION_BIN) return@mapNotNull null
            val observed = bin.count { it.actualPercent!! > it.reservePercent }.toDouble() / bin.size
            CalibrationBin(
                predictedLow = low,
                predictedHigh = minOf(high, 1.0),
                predictedMean = bin.map { it.predictedSurvivalProbability }.average(),
                observedFrequency = observed,
                count = bin.size,
            )
        }
    }

    suspend fun clear() = withContext(defaultDispatcher) {
        forecastRecordDao.deleteAll()
        residualDao.deleteAll()
    }

    companion object {
        /** An observation must be this close to the target time to score against it. */
        const val MATCH_TOLERANCE_MS = 20 * 60 * 1000L

        /** After this long with no matching observation, the forecast is unscoreable. */
        const val ABANDON_AFTER_MS = 6 * 60 * 60 * 1000L

        const val MIN_RECORDED_HORIZON_MINUTES = 20.0

        const val REPORT_WINDOW = 500

        const val MIN_PER_CALIBRATION_BIN = 5

        /** Extra margin used to approximate the 90 % band from the recorded 10/90 percentiles. */
        const val HALF_TAIL_MARGIN = 1.5
    }
}

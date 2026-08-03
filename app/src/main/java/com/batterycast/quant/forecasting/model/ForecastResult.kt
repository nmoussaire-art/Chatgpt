package com.batterycast.quant.forecasting.model

import com.batterycast.quant.forecasting.drain.DrainEstimate
import com.batterycast.quant.forecasting.sim.ForecastPoint
import com.batterycast.quant.telemetry.model.PlugType
import com.batterycast.quant.telemetry.model.SensorCapabilities

/** A complete forecast, ready to be rendered without further computation. */
data class BatteryForecast(
    val generatedAtMs: Long,
    /** Timestamp of the reading this forecast is based on; the honest "as of" figure. */
    val observedAtMs: Long,
    val currentPercent: Double,
    val isCharging: Boolean,
    val plugType: PlugType,
    val currentRegime: UsageRegime,
    val maturity: DataMaturity,
    val horizonMs: Long,

    /** The percentile fan, one point per simulation step. */
    val curve: List<ForecastPoint>,

    /** Time until the battery reaches each threshold, where the paths support an answer. */
    val thresholds: List<ThresholdOutcome>,

    /** Outcome at the user's selected target time, when one is set and inside the horizon. */
    val target: TargetOutcome?,

    /** Survival probability at each future hour, for the probability screen. */
    val hourlySurvival: List<HourlySurvival>,

    val drain: DrainSummary,
    val dataQuality: DataQualityReport,
    val drivers: List<ForecastDriver>,
) {
    val isPreliminary: Boolean get() = maturity.isPreliminary

    /** Median time until the battery is empty, when the horizon reaches that far. */
    val timeToEmptyMs: Long? get() = thresholds.firstOrNull { it.thresholdPercent == 1.0 }?.medianMs
}

/** When the battery is expected to reach a given level. */
data class ThresholdOutcome(
    val thresholdPercent: Double,
    /** Median crossing time in ms from now; null when most paths never get there in the horizon. */
    val medianMs: Long?,
    val p10Ms: Long?,
    val p90Ms: Long?,
    /** Probability of reaching this level at all within the horizon. */
    val probabilityOfReaching: Double,
)

/** Everything the dashboard says about the user's chosen target time. */
data class TargetOutcome(
    val label: String,
    val targetMs: Long,
    val reservePercent: Double,
    /** P(battery > reserve at the target time). */
    val survivalProbability: Double,
    val median: Double,
    val p10: Double,
    val p25: Double,
    val p75: Double,
    val p90: Double,
    /** The 10th percentile, presented as the conservative planning number. */
    val conservative: Double,
)

data class HourlySurvival(
    val atMs: Long,
    val probabilityAboveReserve: Double,
    val medianPercent: Double,
)

/** How fast the battery is going, and how we know. */
data class DrainSummary(
    val currentPercentPerHour: Double?,
    val method: String?,
    val byHorizon: Map<String, Double>,
    val milliAmpsNow: Double?,
    val wattsNow: Double?,
    /** The learned baseline for this state, for comparison with what is happening now. */
    val baselinePercentPerHour: Double?,
    val estimate: DrainEstimate?,
)

/**
 * An honest account of what the forecast rests on.
 *
 * Shown wherever a number could be over-read: a forecast built on nine minutes of data and one
 * built on three weeks look very different, and the app should not let them look the same.
 */
data class DataQualityReport(
    val observationCount: Int,
    val usableObservationCount: Int,
    val observedSpanHours: Double,
    val totalPercentObserved: Double,
    val capabilities: SensorCapabilities,
    val unsupportedFields: List<String>,
    val rejectedCount: Int,
    val newestObservationAgeMs: Long,
    val modelEffectiveSamples: Double,
) {
    /** True when the newest reading is old enough that the state may have changed since. */
    val isStale: Boolean get() = newestObservationAgeMs > STALE_THRESHOLD_MS

    companion object {
        const val STALE_THRESHOLD_MS = 45 * 60 * 1000L
    }
}

/**
 * One reason the forecast looks the way it does.
 *
 * Drivers are computed by comparing what is happening now against the learned baseline for the
 * same state, so each one carries a real number behind it rather than a stock phrase.
 */
data class ForecastDriver(
    val headline: String,
    val detail: String,
    val direction: DriverDirection,
    val magnitude: Double?,
)

enum class DriverDirection { WORSE, BETTER, NEUTRAL, UNCERTAINTY }

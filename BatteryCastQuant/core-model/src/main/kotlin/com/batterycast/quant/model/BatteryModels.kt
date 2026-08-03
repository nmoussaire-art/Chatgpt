package com.batterycast.quant.model

enum class DataQuality { HIGH, ACCEPTABLE, LOW_PRECISION, UNSUPPORTED_FIELD, SUSPECTED_OUTLIER }
enum class NetworkType { WIFI, CELLULAR, OFFLINE, OTHER, UNKNOWN }
enum class UsageRegime { STANDBY, LIGHT, NORMAL, HEAVY, MEDIA_GAMING, NAVIGATION, HOTSPOT, CHARGING, FAST_CHARGING, CHARGING_TAPER, UNKNOWN }
enum class ForecastConfidence { PRELIMINARY, LOW, MODERATE, HIGH }
enum class PlugType { AC, USB, WIRELESS, DOCK, NONE, UNKNOWN }

data class BatteryObservation(
    val id: Long = 0,
    val timestamp: Long,
    val elapsedRealtimeMs: Long,
    val bootCount: Int?,
    val batteryPercent: Double,
    val chargeCounterMicroAh: Long?,
    val currentMicroA: Long?,
    val averageCurrentMicroA: Long?,
    val energyNanoWh: Long?,
    val voltageMv: Int?,
    val temperatureDeciC: Int?,
    val isCharging: Boolean,
    val plugType: PlugType,
    val batteryHealth: Int?,
    val chargingStatus: Int?,
    val chargingPolicy: Int?,
    val powerSaveEnabled: Boolean,
    val thermalStatus: Int?,
    val screenInteractive: Boolean,
    val networkType: NetworkType,
    val bluetoothEnabled: Boolean?,
    val recentScreenTimeMs: Long?,
    val recentForegroundUsageMs: Long?,
    val usageRegime: UsageRegime,
    val overallQuality: DataQuality,
    val source: String
)

data class ForecastPoint(
    val timestamp: Long,
    val p05: Double,
    val p10: Double,
    val p25: Double,
    val median: Double,
    val mean: Double,
    val p75: Double,
    val p90: Double,
    val p95: Double,
    val probabilityAboveReserve: Double? = null
)

data class ThresholdDistribution(
    val threshold: Int,
    val medianTimestamp: Long?,
    val p10Timestamp: Long?,
    val p90Timestamp: Long?,
    val probabilityReachedBeforeTarget: Double
)

data class ForecastResult(
    val generatedAt: Long,
    val targetTime: Long,
    val reservePercent: Int,
    val confidence: ForecastConfidence,
    val qualityMessage: String,
    val survivalProbability: Double?,
    val expectedAtTarget: Double?,
    val medianAtTarget: Double?,
    val conservativeAtTarget: Double?,
    val targetP05: Double?,
    val targetP10: Double?,
    val targetP25: Double?,
    val targetP75: Double?,
    val targetP90: Double?,
    val targetP95: Double?,
    val points: List<ForecastPoint>,
    val thresholds: List<ThresholdDistribution>,
    val baseDrainPercentPerHour: Double?,
    val baseDrainMicroAhPerHour: Double?,
    val baseDrainMilliWhPerHour: Double?,
    val uncertaintyPercentPerHour: Double?,
    val mainDriver: String,
    val sampleCount: Int,
    val preliminary: Boolean
)

data class ScenarioDefinition(
    val id: String,
    val title: String,
    val regime: UsageRegime,
    val durationMinutes: Int,
    val powerSave: Boolean? = null,
    val mix: Map<UsageRegime, Double> = emptyMap()
)

data class ScenarioForecast(
    val scenario: ScenarioDefinition,
    val survivalProbability: Double?,
    val medianAtTarget: Double?,
    val confidence: ForecastConfidence,
    val note: String
)

data class ChargePlanRequest(
    val eventTime: Long,
    val desiredBatteryPercent: Int,
    val desiredConfidence: Double,
    val maxChargeMinutes: Int = 240
)

data class ChargePlan(
    val generatedAt: Long,
    val latestSafeStart: Long?,
    val recommendedDurationMinutes: Int?,
    val expectedBatteryAfterCharging: Double?,
    val conservativeBatteryAfterCharging: Double?,
    val expectedBatteryAtEvent: Double?,
    val conservativeBatteryAtEvent: Double?,
    val confidenceAchieved: Double?,
    val chargerKnown: Boolean,
    val message: String
)

data class ForecastEvaluation(
    val reservePercent: Int,
    val medianAtTarget: Double,
    val p05: Double?,
    val p10: Double?,
    val p25: Double?,
    val p75: Double?,
    val p90: Double?,
    val p95: Double?,
    val survivalProbability: Double?,
    val predictedTimeTo20Median: Long?,
    val actualAtTarget: Double,
    val actualTimeTo20: Long?
)

data class AccuracySummary(
    val evaluatedForecasts: Int,
    val medianAbsoluteErrorPercent: Double?,
    val medianTimeTo20ErrorMinutes: Double?,
    val biasPercent: Double?,
    val coverage50: Double?,
    val coverage80: Double?,
    val coverage90: Double?,
    val calibrationScore: Double?
)

data class CalendarTarget(val eventId: Long, val title: String, val startTime: Long, val endTime: Long)

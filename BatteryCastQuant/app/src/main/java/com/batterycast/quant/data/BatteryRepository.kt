package com.batterycast.quant.data

import com.batterycast.quant.model.BatteryObservation
import com.batterycast.quant.model.ForecastResult
import com.batterycast.quant.telemetry.BatteryTelemetrySource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BatteryRepository @Inject constructor(
    private val dao: BatteryDao,
    private val forecastDao: ForecastDao,
    private val source: BatteryTelemetrySource
) {
    fun latest(): Flow<BatteryObservation?> = dao.latest().map { it?.toDomain() }

    fun history(from: Long): Flow<List<BatteryObservation>> =
        dao.since(from).map { rows -> rows.map(BatteryObservationEntity::toDomain) }

    suspend fun historyOnce(from: Long): List<BatteryObservation> =
        dao.sinceOnce(from).map(BatteryObservationEntity::toDomain)

    suspend fun all(): List<BatteryObservation> = dao.all().map(BatteryObservationEntity::toDomain)

    suspend fun capture(reason: String): BatteryObservation? = source.read(reason)?.also { observation ->
        dao.insert(observation.toEntity())

        forecastDao.due(observation.timestamp).forEach { record ->
            val actual = dao.nearestAfter(record.targetTime, observation.timestamp)?.batteryPercent
            if (actual != null) forecastDao.attachActual(record.id, actual)
        }

        if (!observation.isCharging && observation.batteryPercent <= 20.0) {
            forecastDao.awaitingTimeTo20(observation.timestamp).forEach { record ->
                forecastDao.attachActualTimeTo20(record.id, observation.timestamp)
            }
        }
    }

    suspend fun saveForecast(forecast: ForecastResult) {
        if (forecast.medianAtTarget == null || forecast.targetTime <= forecast.generatedAt + 5 * 60_000L) return
        val predictedTimeTo20 = forecast.thresholds.firstOrNull { it.threshold == 20 }?.medianTimestamp
        forecastDao.insert(
            ForecastRecordEntity(
                generatedAt = forecast.generatedAt,
                targetTime = forecast.targetTime,
                reservePercent = forecast.reservePercent,
                medianAtTarget = forecast.medianAtTarget,
                p05 = forecast.targetP05,
                p10 = forecast.targetP10,
                p25 = forecast.targetP25,
                p75 = forecast.targetP75,
                p90 = forecast.targetP90,
                p95 = forecast.targetP95,
                survivalProbability = forecast.survivalProbability,
                predictedTimeTo20Median = predictedTimeTo20
            )
        )
    }

    suspend fun clear() {
        dao.deleteAll()
        forecastDao.deleteAll()
    }

    suspend fun count(): Int = dao.count()

    suspend fun exportCsv(): String = buildString {
        appendLine(
            "timestamp,batteryPercent,isCharging,plugType,currentMicroA,averageCurrentMicroA," +
                "chargeCounterMicroAh,energyNanoWh,voltageMv,temperatureDeciC,chargingPolicy," +
                "powerSave,thermalStatus,screenInteractive,networkType,usageRegime,quality,source"
        )
        all().forEach { observation ->
            appendLine(
                listOf(
                    observation.timestamp,
                    observation.batteryPercent,
                    observation.isCharging,
                    observation.plugType,
                    observation.currentMicroA ?: "",
                    observation.averageCurrentMicroA ?: "",
                    observation.chargeCounterMicroAh ?: "",
                    observation.energyNanoWh ?: "",
                    observation.voltageMv ?: "",
                    observation.temperatureDeciC ?: "",
                    observation.chargingPolicy ?: "",
                    observation.powerSaveEnabled,
                    observation.thermalStatus ?: "",
                    observation.screenInteractive,
                    observation.networkType,
                    observation.usageRegime,
                    observation.overallQuality,
                    csvEscape(observation.source)
                ).joinToString(",")
            )
        }
    }

    private fun csvEscape(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' }) "\"${value.replace("\"", "\"\"")}\"" else value
}

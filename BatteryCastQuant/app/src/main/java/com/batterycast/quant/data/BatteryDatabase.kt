package com.batterycast.quant.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import com.batterycast.quant.model.BatteryObservation
import com.batterycast.quant.model.DataQuality
import com.batterycast.quant.model.NetworkType
import com.batterycast.quant.model.PlugType
import com.batterycast.quant.model.UsageRegime
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "battery_observations", indices = [Index("timestamp", unique = true)])
data class BatteryObservationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
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
    val plugType: String,
    val batteryHealth: Int?,
    val chargingStatus: Int?,
    val chargingPolicy: Int?,
    val powerSaveEnabled: Boolean,
    val thermalStatus: Int?,
    val screenInteractive: Boolean,
    val networkType: String,
    val bluetoothEnabled: Boolean?,
    val recentScreenTimeMs: Long?,
    val recentForegroundUsageMs: Long?,
    val usageRegime: String,
    val overallQuality: String,
    val source: String
)

@Entity(tableName = "forecast_records", indices = [Index("targetTime"), Index("generatedAt")])
data class ForecastRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val generatedAt: Long,
    val targetTime: Long,
    val reservePercent: Int,
    val medianAtTarget: Double?,
    val p05: Double?,
    val p10: Double?,
    val p25: Double?,
    val p75: Double?,
    val p90: Double?,
    val p95: Double?,
    val survivalProbability: Double?,
    val predictedTimeTo20Median: Long?,
    val actualAtTarget: Double? = null,
    val actualTimeTo20: Long? = null
)

@Dao
interface BatteryDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(observation: BatteryObservationEntity): Long

    @Query("SELECT * FROM battery_observations ORDER BY timestamp DESC LIMIT 1")
    fun latest(): Flow<BatteryObservationEntity?>

    @Query("SELECT * FROM battery_observations ORDER BY timestamp DESC LIMIT 1")
    suspend fun latestOnce(): BatteryObservationEntity?

    @Query("SELECT * FROM battery_observations WHERE timestamp >= :from ORDER BY timestamp")
    fun since(from: Long): Flow<List<BatteryObservationEntity>>

    @Query("SELECT * FROM battery_observations WHERE timestamp >= :from ORDER BY timestamp")
    suspend fun sinceOnce(from: Long): List<BatteryObservationEntity>

    @Query("SELECT * FROM battery_observations ORDER BY timestamp")
    suspend fun all(): List<BatteryObservationEntity>

    @Query(
        "SELECT * FROM battery_observations " +
            "WHERE timestamp >= :target AND timestamp <= :latest ORDER BY timestamp LIMIT 1"
    )
    suspend fun nearestAfter(target: Long, latest: Long): BatteryObservationEntity?

    @Query("DELETE FROM battery_observations")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM battery_observations")
    suspend fun count(): Int
}

@Dao
interface ForecastDao {
    @Insert
    suspend fun insert(record: ForecastRecordEntity): Long

    @Query("SELECT * FROM forecast_records ORDER BY generatedAt DESC LIMIT :limit")
    suspend fun recent(limit: Int = 500): List<ForecastRecordEntity>

    @Query("UPDATE forecast_records SET actualAtTarget = :actual WHERE id = :id")
    suspend fun attachActual(id: Long, actual: Double)

    @Query("UPDATE forecast_records SET actualTimeTo20 = :actualTime WHERE id = :id")
    suspend fun attachActualTimeTo20(id: Long, actualTime: Long)

    @Query(
        "SELECT * FROM forecast_records WHERE actualAtTarget IS NULL " +
            "AND targetTime <= :now ORDER BY targetTime LIMIT 100"
    )
    suspend fun due(now: Long): List<ForecastRecordEntity>

    @Query(
        "SELECT * FROM forecast_records WHERE actualTimeTo20 IS NULL " +
            "AND predictedTimeTo20Median IS NOT NULL " +
            "AND generatedAt <= :observedAt AND targetTime >= :observedAt " +
            "ORDER BY generatedAt DESC LIMIT 100"
    )
    suspend fun awaitingTimeTo20(observedAt: Long): List<ForecastRecordEntity>

    @Query("DELETE FROM forecast_records")
    suspend fun deleteAll()
}

@Database(
    entities = [BatteryObservationEntity::class, ForecastRecordEntity::class],
    version = 1,
    exportSchema = true
)
abstract class BatteryDatabase : RoomDatabase() {
    abstract fun batteryDao(): BatteryDao
    abstract fun forecastDao(): ForecastDao
}

fun BatteryObservationEntity.toDomain() = BatteryObservation(
    id = id,
    timestamp = timestamp,
    elapsedRealtimeMs = elapsedRealtimeMs,
    bootCount = bootCount,
    batteryPercent = batteryPercent,
    chargeCounterMicroAh = chargeCounterMicroAh,
    currentMicroA = currentMicroA,
    averageCurrentMicroA = averageCurrentMicroA,
    energyNanoWh = energyNanoWh,
    voltageMv = voltageMv,
    temperatureDeciC = temperatureDeciC,
    isCharging = isCharging,
    plugType = runCatching { PlugType.valueOf(plugType) }.getOrDefault(PlugType.UNKNOWN),
    batteryHealth = batteryHealth,
    chargingStatus = chargingStatus,
    chargingPolicy = chargingPolicy,
    powerSaveEnabled = powerSaveEnabled,
    thermalStatus = thermalStatus,
    screenInteractive = screenInteractive,
    networkType = runCatching { NetworkType.valueOf(networkType) }.getOrDefault(NetworkType.UNKNOWN),
    bluetoothEnabled = bluetoothEnabled,
    recentScreenTimeMs = recentScreenTimeMs,
    recentForegroundUsageMs = recentForegroundUsageMs,
    usageRegime = runCatching { UsageRegime.valueOf(usageRegime) }.getOrDefault(UsageRegime.UNKNOWN),
    overallQuality = runCatching { DataQuality.valueOf(overallQuality) }.getOrDefault(DataQuality.LOW_PRECISION),
    source = source
)

fun BatteryObservation.toEntity() = BatteryObservationEntity(
    id = id,
    timestamp = timestamp,
    elapsedRealtimeMs = elapsedRealtimeMs,
    bootCount = bootCount,
    batteryPercent = batteryPercent,
    chargeCounterMicroAh = chargeCounterMicroAh,
    currentMicroA = currentMicroA,
    averageCurrentMicroA = averageCurrentMicroA,
    energyNanoWh = energyNanoWh,
    voltageMv = voltageMv,
    temperatureDeciC = temperatureDeciC,
    isCharging = isCharging,
    plugType = plugType.name,
    batteryHealth = batteryHealth,
    chargingStatus = chargingStatus,
    chargingPolicy = chargingPolicy,
    powerSaveEnabled = powerSaveEnabled,
    thermalStatus = thermalStatus,
    screenInteractive = screenInteractive,
    networkType = networkType.name,
    bluetoothEnabled = bluetoothEnabled,
    recentScreenTimeMs = recentScreenTimeMs,
    recentForegroundUsageMs = recentForegroundUsageMs,
    usageRegime = usageRegime.name,
    overallQuality = overallQuality.name,
    source = source
)

package com.batterycast.quant.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.batterycast.quant.telemetry.model.BatteryHealth
import com.batterycast.quant.telemetry.model.BatteryObservation
import com.batterycast.quant.telemetry.model.BluetoothState
import com.batterycast.quant.telemetry.model.ChargingPolicy
import com.batterycast.quant.telemetry.model.ChargingStatus
import com.batterycast.quant.telemetry.model.NetworkType
import com.batterycast.quant.telemetry.model.ObservationQuality
import com.batterycast.quant.telemetry.model.ObservationSource
import com.batterycast.quant.telemetry.model.PlugType
import com.batterycast.quant.telemetry.model.QualityNote
import com.batterycast.quant.telemetry.model.ThermalStatus
import com.batterycast.quant.telemetry.model.UsageCategory

/**
 * A stored battery observation.
 *
 * Nullable columns are genuinely nullable in the database: a device that cannot report current
 * stores NULL, and every query and model treats that as "unmeasured", never as zero.
 */
@Entity(
    tableName = "observations",
    indices = [
        Index(value = ["timestampMs"]),
        Index(value = ["bootSessionId", "timestampMs"]),
    ],
)
data class ObservationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val timestampMs: Long,
    val elapsedRealtimeMs: Long,
    val bootSessionId: Long,

    val batteryPercent: Double,
    val rawLevel: Int,
    val rawScale: Int,

    val chargeCounterMicroAh: Long?,
    val currentMicroA: Long?,
    val averageCurrentMicroA: Long?,
    val energyNanoWh: Long?,
    val voltageMv: Int?,
    val temperatureDeciC: Int?,

    val isCharging: Boolean,
    val chargingStatus: ChargingStatus,
    val plugType: PlugType,
    val batteryHealth: BatteryHealth,
    val chargingPolicy: ChargingPolicy?,

    val powerSaveEnabled: Boolean,
    val thermalStatus: ThermalStatus,
    val screenInteractive: Boolean,
    val networkType: NetworkType,
    val bluetoothState: BluetoothState,

    val recentScreenTimeMs: Long?,
    val recentForegroundUsageMs: Long?,
    val dominantUsageCategory: UsageCategory?,

    val quality: ObservationQuality,
    @ColumnInfo(name = "notes") val notes: Set<QualityNote>,
    val source: ObservationSource,
)

fun ObservationEntity.toDomain(): BatteryObservation = BatteryObservation(
    id = id,
    timestampMs = timestampMs,
    elapsedRealtimeMs = elapsedRealtimeMs,
    bootSessionId = bootSessionId,
    batteryPercent = batteryPercent,
    rawLevel = rawLevel,
    rawScale = rawScale,
    chargeCounterMicroAh = chargeCounterMicroAh,
    currentMicroA = currentMicroA,
    averageCurrentMicroA = averageCurrentMicroA,
    energyNanoWh = energyNanoWh,
    voltageMv = voltageMv,
    temperatureDeciC = temperatureDeciC,
    isCharging = isCharging,
    chargingStatus = chargingStatus,
    plugType = plugType,
    batteryHealth = batteryHealth,
    chargingPolicy = chargingPolicy,
    powerSaveEnabled = powerSaveEnabled,
    thermalStatus = thermalStatus,
    screenInteractive = screenInteractive,
    networkType = networkType,
    bluetoothState = bluetoothState,
    recentScreenTimeMs = recentScreenTimeMs,
    recentForegroundUsageMs = recentForegroundUsageMs,
    dominantUsageCategory = dominantUsageCategory,
    quality = quality,
    notes = notes,
    source = source,
)

fun BatteryObservation.toEntity(): ObservationEntity = ObservationEntity(
    id = id,
    timestampMs = timestampMs,
    elapsedRealtimeMs = elapsedRealtimeMs,
    bootSessionId = bootSessionId,
    batteryPercent = batteryPercent,
    rawLevel = rawLevel,
    rawScale = rawScale,
    chargeCounterMicroAh = chargeCounterMicroAh,
    currentMicroA = currentMicroA,
    averageCurrentMicroA = averageCurrentMicroA,
    energyNanoWh = energyNanoWh,
    voltageMv = voltageMv,
    temperatureDeciC = temperatureDeciC,
    isCharging = isCharging,
    chargingStatus = chargingStatus,
    plugType = plugType,
    batteryHealth = batteryHealth,
    chargingPolicy = chargingPolicy,
    powerSaveEnabled = powerSaveEnabled,
    thermalStatus = thermalStatus,
    screenInteractive = screenInteractive,
    networkType = networkType,
    bluetoothState = bluetoothState,
    recentScreenTimeMs = recentScreenTimeMs,
    recentForegroundUsageMs = recentForegroundUsageMs,
    dominantUsageCategory = dominantUsageCategory,
    quality = quality,
    notes = notes,
    source = source,
)

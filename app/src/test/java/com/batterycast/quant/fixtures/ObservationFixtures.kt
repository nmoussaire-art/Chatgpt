package com.batterycast.quant.fixtures

import com.batterycast.quant.telemetry.model.BatteryHealth
import com.batterycast.quant.telemetry.model.BatteryObservation
import com.batterycast.quant.telemetry.model.BluetoothState
import com.batterycast.quant.telemetry.model.ChargingStatus
import com.batterycast.quant.telemetry.model.NetworkType
import com.batterycast.quant.telemetry.model.ObservationQuality
import com.batterycast.quant.telemetry.model.ObservationSource
import com.batterycast.quant.telemetry.model.PlugType
import com.batterycast.quant.telemetry.model.QualityNote
import com.batterycast.quant.telemetry.model.RawBatteryReading
import com.batterycast.quant.telemetry.model.ThermalStatus
import com.batterycast.quant.telemetry.model.UsageCategory

/**
 * Deterministic builders for tests.
 *
 * This file lives in `src/test` and is referenced by nothing in `src/main`. Both the Gradle
 * `verifyNoSampleData` task and `ProductionPurityTest` fail the build if anything resembling it
 * ever appears in production sources, which is what keeps the shipped app free of invented data.
 */
object ObservationFixtures {

    const val BASE_TIMESTAMP_MS = 1_700_000_000_000L
    const val BASE_ELAPSED_MS = 500_000L
    const val BOOT_SESSION = (BASE_TIMESTAMP_MS - BASE_ELAPSED_MS) / 10_000L

    fun observation(
        offsetMinutes: Double = 0.0,
        percent: Double = 80.0,
        charging: Boolean = false,
        screenOn: Boolean = false,
        chargeCounterMicroAh: Long? = null,
        currentMicroA: Long? = null,
        energyNanoWh: Long? = null,
        voltageMv: Int? = 3900,
        temperatureDeciC: Int? = 300,
        powerSave: Boolean = false,
        thermal: ThermalStatus = ThermalStatus.NONE,
        network: NetworkType = NetworkType.WIFI,
        plug: PlugType = if (charging) PlugType.AC else PlugType.NONE,
        quality: ObservationQuality = ObservationQuality.ACCEPTABLE,
        notes: Set<QualityNote> = emptySet(),
        bootSessionId: Long = BOOT_SESSION,
        recentScreenTimeMs: Long? = null,
        recentForegroundUsageMs: Long? = null,
        usageCategory: UsageCategory? = null,
        clockSkewMs: Long = 0L,
        elapsedOverrideMs: Long? = null,
    ): BatteryObservation {
        val offsetMs = (offsetMinutes * 60_000).toLong()
        return BatteryObservation(
            timestampMs = BASE_TIMESTAMP_MS + offsetMs + clockSkewMs,
            elapsedRealtimeMs = elapsedOverrideMs ?: (BASE_ELAPSED_MS + offsetMs),
            bootSessionId = bootSessionId,
            batteryPercent = percent,
            rawLevel = percent.toInt(),
            rawScale = 100,
            chargeCounterMicroAh = chargeCounterMicroAh,
            currentMicroA = currentMicroA,
            averageCurrentMicroA = null,
            energyNanoWh = energyNanoWh,
            voltageMv = voltageMv,
            temperatureDeciC = temperatureDeciC,
            isCharging = charging,
            chargingStatus = if (charging) ChargingStatus.CHARGING else ChargingStatus.DISCHARGING,
            plugType = plug,
            batteryHealth = BatteryHealth.GOOD,
            chargingPolicy = null,
            powerSaveEnabled = powerSave,
            thermalStatus = thermal,
            screenInteractive = screenOn,
            networkType = network,
            bluetoothState = BluetoothState.OFF,
            recentScreenTimeMs = recentScreenTimeMs,
            recentForegroundUsageMs = recentForegroundUsageMs,
            dominantUsageCategory = usageCategory,
            quality = quality,
            notes = notes,
            source = ObservationSource.PERIODIC_WORK,
        )
    }

    /**
     * A steady discharge at [ratePerHour] percentage points per hour, sampled every
     * [intervalMinutes], with percentages rounded exactly as Android reports them.
     */
    fun steadyDischarge(
        startPercent: Double = 80.0,
        ratePerHour: Double = 8.0,
        intervalMinutes: Double = 15.0,
        count: Int = 12,
        screenOn: Boolean = false,
        withChargeCounter: Boolean = false,
        capacityMilliAh: Double = 4000.0,
    ): List<BatteryObservation> = (0 until count).map { index ->
        val offsetMinutes = index * intervalMinutes
        val exactPercent = startPercent - ratePerHour * (offsetMinutes / 60.0)
        val roundedPercent = kotlin.math.floor(exactPercent).coerceAtLeast(0.0)
        observation(
            offsetMinutes = offsetMinutes,
            percent = roundedPercent,
            screenOn = screenOn,
            // The charge counter tracks the *unrounded* level, which is exactly why it produces a
            // better rate estimate than the percentage series does.
            chargeCounterMicroAh = if (withChargeCounter) {
                (exactPercent / 100.0 * capacityMilliAh * 1000).toLong()
            } else {
                null
            },
        )
    }

    fun steadyCharge(
        startPercent: Double = 20.0,
        ratePerHour: Double = 40.0,
        intervalMinutes: Double = 10.0,
        count: Int = 12,
        startOffsetMinutes: Double = 0.0,
    ): List<BatteryObservation> = (0 until count).map { index ->
        val offsetMinutes = startOffsetMinutes + index * intervalMinutes
        val percent = (startPercent + ratePerHour * ((offsetMinutes - startOffsetMinutes) / 60.0))
            .coerceAtMost(100.0)
        observation(
            offsetMinutes = offsetMinutes,
            percent = kotlin.math.floor(percent),
            charging = true,
        )
    }

    fun rawReading(
        level: Int = 80,
        scale: Int = 100,
        statusRaw: Int = 3,
        pluggedRaw: Int = 0,
        healthRaw: Int = 2,
        voltageMvRaw: Int? = 3900,
        temperatureDeciCRaw: Int? = 300,
        currentNowRaw: Long? = null,
        currentAverageRaw: Long? = null,
        chargeCounterRaw: Long? = null,
        energyCounterRaw: Long? = null,
        thermalStatusRaw: Int? = 0,
        screenInteractive: Boolean = false,
        powerSaveEnabled: Boolean = false,
        offsetMinutes: Double = 0.0,
        clockSkewMs: Long = 0L,
        elapsedOverrideMs: Long? = null,
        usageAccessGranted: Boolean = false,
    ): RawBatteryReading {
        val offsetMs = (offsetMinutes * 60_000).toLong()
        return RawBatteryReading(
            timestampMs = BASE_TIMESTAMP_MS + offsetMs + clockSkewMs,
            elapsedRealtimeMs = elapsedOverrideMs ?: (BASE_ELAPSED_MS + offsetMs),
            level = level,
            scale = scale,
            statusRaw = statusRaw,
            pluggedRaw = pluggedRaw,
            healthRaw = healthRaw,
            voltageMvRaw = voltageMvRaw,
            temperatureDeciCRaw = temperatureDeciCRaw,
            currentNowRaw = currentNowRaw,
            currentAverageRaw = currentAverageRaw,
            chargeCounterRaw = chargeCounterRaw,
            energyCounterRaw = energyCounterRaw,
            thermalStatusRaw = thermalStatusRaw,
            screenInteractive = screenInteractive,
            powerSaveEnabled = powerSaveEnabled,
            usageAccessGranted = usageAccessGranted,
        )
    }
}

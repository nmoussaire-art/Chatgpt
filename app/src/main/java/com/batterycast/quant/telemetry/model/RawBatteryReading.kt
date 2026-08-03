package com.batterycast.quant.telemetry.model

/**
 * Exactly what the platform handed us, before any interpretation.
 *
 * Keeping the raw shape separate lets the entire validation and unit-detection pipeline run as
 * plain JVM code under unit test, with no Android framework involved.
 *
 * A null field means the platform call threw, returned a sentinel, or the property is absent on
 * this API level. It never means "zero".
 */
data class RawBatteryReading(
    val timestampMs: Long,
    val elapsedRealtimeMs: Long,

    /** `BatteryManager.EXTRA_LEVEL`. */
    val level: Int,
    /** `BatteryManager.EXTRA_SCALE`. */
    val scale: Int,
    /** `BatteryManager.EXTRA_STATUS`. */
    val statusRaw: Int,
    /** `BatteryManager.EXTRA_PLUGGED`. */
    val pluggedRaw: Int,
    /** `BatteryManager.EXTRA_HEALTH`. */
    val healthRaw: Int,

    val voltageMvRaw: Int? = null,
    val temperatureDeciCRaw: Int? = null,
    val currentNowRaw: Long? = null,
    val currentAverageRaw: Long? = null,
    val chargeCounterRaw: Long? = null,
    val energyCounterRaw: Long? = null,
    val chargingPolicyRaw: Int? = null,

    val powerSaveEnabled: Boolean = false,
    val thermalStatusRaw: Int? = null,
    val screenInteractive: Boolean = false,
    val networkType: NetworkType = NetworkType.UNKNOWN,
    val bluetoothState: BluetoothState = BluetoothState.UNKNOWN,

    val recentScreenTimeMs: Long? = null,
    val recentForegroundUsageMs: Long? = null,
    val dominantUsageCategory: UsageCategory? = null,

    val usageAccessGranted: Boolean = false,
)

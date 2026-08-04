package com.batterycast.quant.telemetry.model

/**
 * One measurement of the device's battery and the context it was taken in.
 *
 * Every field that Android may or may not expose is nullable. A null means "this device did not
 * give us a usable number" and is never replaced by a substitute value anywhere in the app —
 * the forecasting engine degrades to percentage-based estimation instead.
 */
data class BatteryObservation(
    val id: Long = 0L,
    /** Wall-clock time of the reading. */
    val timestampMs: Long,
    /**
     * Monotonic time since boot. Survives wall-clock jumps (time-zone changes, NTP corrections,
     * daylight-saving transitions) so drain rates can be computed from a trustworthy elapsed time.
     */
    val elapsedRealtimeMs: Long,
    /**
     * Identifies the boot session: `timestampMs - elapsedRealtimeMs`, quantised to remove jitter.
     * A change means the device rebooted, which invalidates charge-counter deltas.
     */
    val bootSessionId: Long,

    /** Battery level as a percentage of scale, 0..100. Always available. */
    val batteryPercent: Double,
    /** Raw level reported by the platform, retained to reason about rounding granularity. */
    val rawLevel: Int,
    /** Raw scale reported by the platform (usually 100, occasionally 255 on older hardware). */
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
    /** `BatteryManager.BATTERY_PROPERTY_CHARGING_POLICY`, API 34+. Null when unavailable. */
    val chargingPolicy: ChargingPolicy?,

    val powerSaveEnabled: Boolean,
    val thermalStatus: ThermalStatus,
    val screenInteractive: Boolean,
    val networkType: NetworkType,
    val bluetoothState: BluetoothState,

    /** Milliseconds the screen was interactive in the window preceding this reading. */
    val recentScreenTimeMs: Long?,
    /** Foreground app time in the preceding window; only present with usage access granted. */
    val recentForegroundUsageMs: Long?,
    /** Coarse category of the dominant foreground app, when usage access is granted. */
    val dominantUsageCategory: UsageCategory?,

    val quality: ObservationQuality,
    val notes: Set<QualityNote> = emptySet(),
    val source: ObservationSource,
) {
    val temperatureCelsius: Double? get() = temperatureDeciC?.let { it / 10.0 }
    val voltageVolts: Double? get() = voltageMv?.let { it / 1000.0 }
    val chargeCounterMilliAh: Double? get() = chargeCounterMicroAh?.let { it / 1000.0 }
    val energyWattHours: Double? get() = energyNanoWh?.let { it / 3_600_000_000_000.0 }

    /** True when this reading is trustworthy enough to drive a drain-rate estimate. */
    val usableForModelling: Boolean
        get() = quality != ObservationQuality.SUSPECTED_OUTLIER

    /** Percentage resolution of this device's level reporting, in percentage points. */
    val percentGranularity: Double
        get() = if (rawScale > 0) 100.0 / rawScale else 1.0
}

enum class ObservationSource {
    /** `ACTION_BATTERY_CHANGED` broadcast: level, plug, health or status changed. */
    BATTERY_BROADCAST,
    /** Periodic `WorkManager` sample. */
    PERIODIC_WORK,
    /** The user opened the app or pulled to refresh. */
    APP_FOREGROUND,
    /** High-cadence sample inside a user-started precision session. */
    PRECISION_SESSION,
    /** Power-save mode or another system state toggled. */
    STATE_CHANGE,
    /** First sample after boot. */
    BOOT_COMPLETED,
}

/**
 * The headline quality flag carried by every observation.
 *
 * Ordered from most to least trustworthy so comparisons read naturally.
 */
enum class ObservationQuality {
    /** Charge counter and/or current available, values internally consistent. */
    HIGH_QUALITY,

    /** Percentage and context are sound; some electrical detail missing. */
    ACCEPTABLE,

    /** Usable, but resolution is coarse — for example percentage-only on a 1-point scale. */
    LOW_PRECISION,

    /** The reading is dominated by fields this device does not support. */
    UNSUPPORTED_FIELD,

    /** Physically implausible or contradicted by neighbouring readings; excluded from modelling. */
    SUSPECTED_OUTLIER,
}

/** Specific, machine-readable reasons behind an observation's quality flag. */
enum class QualityNote {
    CURRENT_UNSUPPORTED,
    CURRENT_OUT_OF_RANGE,
    CURRENT_SIGN_UNCALIBRATED,
    AVERAGE_CURRENT_UNSUPPORTED,
    CHARGE_COUNTER_UNSUPPORTED,
    CHARGE_COUNTER_OUT_OF_RANGE,
    CHARGE_COUNTER_RESET,
    ENERGY_UNSUPPORTED,
    ENERGY_OUT_OF_RANGE,
    VOLTAGE_UNSUPPORTED,
    VOLTAGE_OUT_OF_RANGE,
    TEMPERATURE_UNSUPPORTED,
    TEMPERATURE_OUT_OF_RANGE,
    THERMAL_STATUS_UNSUPPORTED,
    COARSE_PERCENT_SCALE,
    PERCENT_JUMP,
    PERCENT_ROSE_WHILE_DISCHARGING,
    PERCENT_FELL_WHILE_CHARGING,
    REBOOT_DETECTED,
    CLOCK_JUMP_DETECTED,
    SUSPECTED_RECALIBRATION,
    USAGE_ACCESS_UNAVAILABLE,
    DUPLICATE_TIMESTAMP,
}

enum class ChargingStatus { CHARGING, DISCHARGING, FULL, NOT_CHARGING, UNKNOWN }

enum class PlugType { NONE, AC, USB, WIRELESS, DOCK, UNKNOWN }

enum class BatteryHealth { GOOD, OVERHEAT, DEAD, OVER_VOLTAGE, COLD, UNSPECIFIED_FAILURE, UNKNOWN }

enum class ChargingPolicy { DEFAULT, ADAPTIVE_AC, ADAPTIVE_AON, ADAPTIVE_LONGLIFE, UNKNOWN }

/** Mirrors `PowerManager.THERMAL_STATUS_*`; [UNSUPPORTED] when the platform will not report it. */
enum class ThermalStatus { NONE, LIGHT, MODERATE, SEVERE, CRITICAL, EMERGENCY, SHUTDOWN, UNSUPPORTED }

enum class NetworkType { WIFI, CELLULAR, ETHERNET, VPN, BLUETOOTH_TETHER, OFFLINE, UNKNOWN }

enum class BluetoothState { ON, OFF, UNKNOWN }

/**
 * Deliberately coarse. BatteryCast never names an app on screen, and never attributes a precise
 * amount of drain to one — it only says which broad kind of activity coincided with the drain.
 */
enum class UsageCategory { COMMUNICATION, SOCIAL, VIDEO, GAME, MAPS_NAVIGATION, PRODUCTIVITY, OTHER, UNKNOWN }

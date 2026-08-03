package com.batterycast.quant.forecasting.model

import com.batterycast.quant.telemetry.model.NetworkType
import com.batterycast.quant.telemetry.model.PlugType
import com.batterycast.quant.telemetry.model.ThermalStatus

/**
 * Interpretable behaviour regimes.
 *
 * A regime is only assigned when the device state supports it. Where the evidence is thin the
 * classifier deliberately falls back to a broader category — [UNCLASSIFIED_DISCHARGE] exists so
 * the app never has to invent a specific story about what the phone was doing.
 */
enum class UsageRegime(val displayName: String, val isCharging: Boolean = false) {
    STANDBY("Screen off / standby"),
    BACKGROUND_ACTIVE("Screen off, background activity"),
    LIGHT_USE("Light use"),
    INTERACTIVE("Normal interactive use"),
    HEAVY_USE("Heavy use"),
    MEDIA_LIKE("Media-like use"),
    GAMING_LIKE("Gaming-like use"),
    NAVIGATION_LIKE("Navigation-like use"),
    UNCLASSIFIED_DISCHARGE("Unclassified discharge"),

    CHARGING("Charging", isCharging = true),
    FAST_CHARGING("Fast charging", isCharging = true),
    CHARGING_TAPER("Charging taper", isCharging = true),
    ;

    val isDischarge: Boolean get() = !isCharging

    companion object {
        val dischargeRegimes: List<UsageRegime> get() = entries.filter { it.isDischarge }
        val chargingRegimes: List<UsageRegime> get() = entries.filter { it.isCharging }
    }
}

/**
 * Which physical quantity a drain rate is expressed in.
 *
 * Percentage per hour is always available. The other two exist only on devices whose fuel gauge
 * actually reports charge or energy, and the model uses them when it can because they are
 * immune to percentage rounding.
 */
enum class DrainMetric {
    /** Percentage points per hour. Positive means the battery is losing charge. */
    PERCENT_PER_HOUR,

    /** Milliamp-hours per hour, i.e. mean current in mA. Requires a working charge counter. */
    MILLIAMP_HOUR_PER_HOUR,

    /** Watt-hours per hour, i.e. mean power in W. Requires the energy counter. */
    WATT_HOUR_PER_HOUR,
}

/** Which family of learned parameters a model cell belongs to. */
enum class ModelNamespace {
    /** Discharge rate by device state. */
    DRAIN,

    /** Charge rate by charger type and state of charge. */
    CHARGE,

    /**
     * Shape of the discharge curve: the multiplier applied to the baseline drain rate in each
     * 10-point band of battery percentage, learned from this device only.
     */
    SOC_CURVE,

    /** Multiplier on drain rate by thermal status. */
    THERMAL,

    /** Multiplier on drain rate when power saving is enabled. */
    POWER_SAVE,
}

/** Coarse time-of-day bucket. Four buckets keep the per-hour cells populated on real histories. */
enum class TimeBucket(val displayName: String) {
    NIGHT("Night"),
    MORNING("Morning"),
    AFTERNOON("Afternoon"),
    EVENING("Evening"),
    ;

    companion object {
        fun forHour(hourOfDay: Int): TimeBucket = when (hourOfDay) {
            in 0..5 -> NIGHT
            in 6..11 -> MORNING
            in 12..17 -> AFTERNOON
            else -> EVENING
        }
    }
}

enum class DayType { WEEKDAY, WEEKEND }

enum class ThermalBucket {
    COOL, WARM, HOT, UNKNOWN;

    companion object {
        fun forStatus(status: ThermalStatus): ThermalBucket = when (status) {
            ThermalStatus.NONE -> COOL
            ThermalStatus.LIGHT, ThermalStatus.MODERATE -> WARM
            ThermalStatus.SEVERE, ThermalStatus.CRITICAL,
            ThermalStatus.EMERGENCY, ThermalStatus.SHUTDOWN,
            -> HOT
            ThermalStatus.UNSUPPORTED -> UNKNOWN
        }

        /** Temperature-based fallback for devices with no thermal status API. */
        fun forTemperature(celsius: Double?): ThermalBucket = when {
            celsius == null -> UNKNOWN
            celsius < 35.0 -> COOL
            celsius < 41.0 -> WARM
            else -> HOT
        }
    }
}

enum class NetworkBucket {
    WIFI, MOBILE, NONE, UNKNOWN;

    companion object {
        fun forType(type: NetworkType): NetworkBucket = when (type) {
            NetworkType.WIFI, NetworkType.ETHERNET -> WIFI
            NetworkType.CELLULAR, NetworkType.BLUETOOTH_TETHER -> MOBILE
            NetworkType.VPN -> UNKNOWN
            NetworkType.OFFLINE -> NONE
            NetworkType.UNKNOWN -> UNKNOWN
        }
    }
}

/**
 * The full device state a drain rate was measured in.
 *
 * [hierarchy] expands the state into a list of increasingly general keys, from the most specific
 * combination down to the device-wide average. Bayesian shrinkage walks that list from general to
 * specific, so a state that has never been seen still yields a defensible estimate.
 */
data class DrainStateKey(
    val regime: UsageRegime,
    val timeBucket: TimeBucket,
    val dayType: DayType,
    val screenOn: Boolean,
    val powerSave: Boolean,
    val thermal: ThermalBucket,
    val network: NetworkBucket,
) {
    fun hierarchy(): List<String> = listOf(
        GLOBAL_KEY,
        "r=${regime.name}",
        "r=${regime.name}|ps=$powerSave",
        "r=${regime.name}|ps=$powerSave|th=${thermal.name}",
        "r=${regime.name}|ps=$powerSave|th=${thermal.name}|net=${network.name}",
        "r=${regime.name}|ps=$powerSave|th=${thermal.name}|net=${network.name}|d=${dayType.name}|t=${timeBucket.name}",
    )

    /** The most specific key for this state. */
    fun specificKey(): String = hierarchy().last()

    companion object {
        const val GLOBAL_KEY = "global"
    }
}

/** Key identifying a charging cell: charger type crossed with state of charge. */
data class ChargeStateKey(
    val plugType: PlugType,
    val socBucket: SocBucket,
    val thermal: ThermalBucket,
) {
    fun hierarchy(): List<String> = listOf(
        DrainStateKey.GLOBAL_KEY,
        "p=${plugType.name}",
        "p=${plugType.name}|soc=${socBucket.name}",
        "p=${plugType.name}|soc=${socBucket.name}|th=${thermal.name}",
    )

    fun specificKey(): String = hierarchy().last()
}

/**
 * Bands of state of charge.
 *
 * Charging speed changes sharply across these bands — constant-current below roughly 80 %, then
 * an increasingly aggressive taper — so charging is modelled piecewise rather than as one rate.
 */
enum class SocBucket(val lowerPercent: Double, val upperPercent: Double, val displayName: String) {
    VERY_LOW(0.0, 20.0, "0–20%"),
    LOW(20.0, 50.0, "20–50%"),
    MID(50.0, 80.0, "50–80%"),
    HIGH(80.0, 90.0, "80–90%"),
    VERY_HIGH(90.0, 100.0, "90–100%"),
    ;

    companion object {
        fun forPercent(percent: Double): SocBucket = when {
            percent < 20.0 -> VERY_LOW
            percent < 50.0 -> LOW
            percent < 80.0 -> MID
            percent < 90.0 -> HIGH
            else -> VERY_HIGH
        }
    }
}

/** Ten-point bands used to learn the shape of this device's discharge curve. */
enum class SocDecile(val lowerPercent: Double) {
    D0(0.0), D10(10.0), D20(20.0), D30(30.0), D40(40.0),
    D50(50.0), D60(60.0), D70(70.0), D80(80.0), D90(90.0),
    ;

    companion object {
        fun forPercent(percent: Double): SocDecile {
            val index = (percent / 10.0).toInt().coerceIn(0, entries.size - 1)
            return entries[index]
        }
    }
}

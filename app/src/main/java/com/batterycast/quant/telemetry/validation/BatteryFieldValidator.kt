package com.batterycast.quant.telemetry.validation

import com.batterycast.quant.telemetry.model.CurrentUnit
import kotlin.math.abs

/**
 * Physical plausibility bounds for a single-cell handset battery.
 *
 * These are not model parameters and they are not device-specific tuning: they are the limits
 * outside which a reading cannot be describing a phone battery at all, so the only honest thing
 * to do is discard the field.
 */
object BatteryFieldValidator {

    /** Sentinels commonly returned by kernels that do not implement a property. */
    private val INT_SENTINELS = setOf(Int.MIN_VALUE, Int.MAX_VALUE)
    private val LONG_SENTINELS = setOf(Long.MIN_VALUE, Long.MAX_VALUE, Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())

    // A Li-ion cell below 2.5 V is shut down; above 5.0 V it is not a single cell.
    private const val MIN_VOLTAGE_MV = 2_000
    private const val MAX_VOLTAGE_MV = 5_500

    // -30 °C to +80 °C in deci-Celsius. Beyond this the sensor is not describing a battery.
    private const val MIN_TEMPERATURE_DECI_C = -300
    private const val MAX_TEMPERATURE_DECI_C = 800

    // 50 mAh to 30 Ah expressed in µAh.
    private const val MIN_CHARGE_COUNTER_MICRO_AH = 50_000L
    private const val MAX_CHARGE_COUNTER_MICRO_AH = 30_000_000L

    // 1 mA to 15 A expressed in µA. Below 1 mA a powered-on handset is not drawing anything real.
    private const val MIN_CURRENT_MICRO_A = 1_000L
    private const val MAX_CURRENT_MICRO_A = 15_000_000L

    // 0.1 Wh to 200 Wh expressed in nWh.
    private const val MIN_ENERGY_NWH = 100_000_000L
    private const val MAX_ENERGY_NWH = 200_000_000_000L

    /**
     * Normalises a voltage reading.
     *
     * Some devices report microvolts through the millivolt extra, and a few report whole volts.
     * Both are recognisable by magnitude and converted; anything else is rejected outright.
     */
    fun validateVoltageMv(raw: Int?): Int? {
        if (raw == null || raw in INT_SENTINELS || raw == 0) return null
        val normalised = when {
            raw in MIN_VOLTAGE_MV..MAX_VOLTAGE_MV -> raw
            // Microvolts: 3 800 000 µV.
            raw in (MIN_VOLTAGE_MV * 1000)..(MAX_VOLTAGE_MV * 1000) -> raw / 1000
            // Whole volts: 4. Too coarse to be useful, but not wrong.
            raw in 2..5 -> raw * 1000
            else -> return null
        }
        return normalised.takeIf { it in MIN_VOLTAGE_MV..MAX_VOLTAGE_MV }
    }

    /**
     * Validates temperature in deci-Celsius.
     *
     * Deliberately does *not* try to rescue a reading that might be whole Celsius: 32 is a valid
     * 3.2 °C and an equally valid 32 °C, and guessing would fabricate data.
     */
    fun validateTemperatureDeciC(raw: Int?): Int? {
        if (raw == null || raw in INT_SENTINELS) return null
        return raw.takeIf { it in MIN_TEMPERATURE_DECI_C..MAX_TEMPERATURE_DECI_C }
    }

    fun validateChargeCounterMicroAh(raw: Long?): Long? {
        if (raw == null || raw in LONG_SENTINELS || raw <= 0L) return null
        return raw.takeIf { it in MIN_CHARGE_COUNTER_MICRO_AH..MAX_CHARGE_COUNTER_MICRO_AH }
    }

    fun validateEnergyNanoWh(raw: Long?): Long? {
        if (raw == null || raw in LONG_SENTINELS || raw <= 0L) return null
        return raw.takeIf { it in MIN_ENERGY_NWH..MAX_ENERGY_NWH }
    }

    /**
     * Validates a current reading and converts it to microamps.
     *
     * [unit] comes from [CurrentUnitDetector], which learns the device's unit from the magnitude
     * distribution of many readings. While the unit is undetermined the reading is accepted only
     * if it is already plausible as microamps.
     */
    fun validateCurrentMicroA(raw: Long?, unit: CurrentUnit): Long? {
        if (raw == null || raw in LONG_SENTINELS) return null
        // Exact zero is how a great many kernels signal "not implemented". A device genuinely
        // drawing zero current is not a state a running app can observe.
        if (raw == 0L) return null

        val magnitudeMicroA = when (unit) {
            CurrentUnit.MILLIAMPS -> abs(raw) * 1000
            else -> abs(raw)
        }
        if (magnitudeMicroA !in MIN_CURRENT_MICRO_A..MAX_CURRENT_MICRO_A) return null

        val sign = if (raw < 0) -1 else 1
        return sign * magnitudeMicroA
    }

    /** True when the raw magnitude is only explicable as milliamps. */
    fun looksLikeMilliamps(raw: Long): Boolean {
        val magnitude = abs(raw)
        // 1 mA .. 15 A expressed *in milliamps* is 1..15 000, and that range is two orders of
        // magnitude below any plausible microamp reading for a running handset.
        return magnitude in 1L..15_000L
    }

    /** True when the raw magnitude is only explicable as microamps. */
    fun looksLikeMicroamps(raw: Long): Boolean =
        abs(raw) in MIN_CURRENT_MICRO_A..MAX_CURRENT_MICRO_A

    /**
     * Battery percentage from the raw level/scale pair.
     *
     * Returns null when the platform reports a nonsensical pair, which happens transiently on
     * some devices during boot.
     */
    fun validatePercent(level: Int, scale: Int): Double? {
        if (scale <= 0 || level < 0) return null
        if (level > scale) return null
        val percent = level * 100.0 / scale
        return percent.takeIf { it in 0.0..100.0 }
    }
}

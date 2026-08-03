package com.batterycast.quant.telemetry.validation

import com.batterycast.quant.telemetry.model.BatteryHealth
import com.batterycast.quant.telemetry.model.BatteryObservation
import com.batterycast.quant.telemetry.model.ChargingPolicy
import com.batterycast.quant.telemetry.model.ChargingStatus
import com.batterycast.quant.telemetry.model.CurrentSignConvention
import com.batterycast.quant.telemetry.model.FieldSupport
import com.batterycast.quant.telemetry.model.ObservationQuality
import com.batterycast.quant.telemetry.model.ObservationSource
import com.batterycast.quant.telemetry.model.PlugType
import com.batterycast.quant.telemetry.model.QualityNote
import com.batterycast.quant.telemetry.model.RawBatteryReading
import com.batterycast.quant.telemetry.model.SensorCapabilities
import com.batterycast.quant.telemetry.model.ThermalStatus
import kotlin.math.abs

/**
 * Android platform constants, restated here so the whole validation pipeline compiles and runs on
 * a plain JVM under unit test without pulling in the framework.
 *
 * `PlatformConstantsTest` asserts these against the real `android.os.BatteryManager` values under
 * Robolectric, so they cannot drift.
 */
internal object PlatformConstants {
    const val STATUS_UNKNOWN = 1
    const val STATUS_CHARGING = 2
    const val STATUS_DISCHARGING = 3
    const val STATUS_NOT_CHARGING = 4
    const val STATUS_FULL = 5

    const val PLUGGED_AC = 1
    const val PLUGGED_USB = 2
    const val PLUGGED_WIRELESS = 4
    const val PLUGGED_DOCK = 8

    const val HEALTH_UNKNOWN = 1
    const val HEALTH_GOOD = 2
    const val HEALTH_OVERHEAT = 3
    const val HEALTH_DEAD = 4
    const val HEALTH_OVER_VOLTAGE = 5
    const val HEALTH_UNSPECIFIED_FAILURE = 6
    const val HEALTH_COLD = 7

    const val CHARGING_POLICY_DEFAULT = 1
    const val CHARGING_POLICY_ADAPTIVE_AC = 2
    const val CHARGING_POLICY_ADAPTIVE_AON = 3
    const val CHARGING_POLICY_ADAPTIVE_LONGLIFE = 4

    const val THERMAL_STATUS_NONE = 0
    const val THERMAL_STATUS_LIGHT = 1
    const val THERMAL_STATUS_MODERATE = 2
    const val THERMAL_STATUS_SEVERE = 3
    const val THERMAL_STATUS_CRITICAL = 4
    const val THERMAL_STATUS_EMERGENCY = 5
    const val THERMAL_STATUS_SHUTDOWN = 6
}

/**
 * Turns a raw platform reading into a validated [BatteryObservation].
 *
 * This is where "unsupported" becomes an explicit null with a recorded reason instead of a zero
 * that the model would later mistake for a measurement.
 */
object ObservationFactory {

    /** Battery percentage cannot legitimately move faster than this outside a reboot. */
    private const val MAX_PLAUSIBLE_PERCENT_PER_MINUTE = 3.0

    /** A jump at least this large with no plausible drain behind it suggests recalibration. */
    private const val RECALIBRATION_JUMP_PERCENT = 8.0

    /** Tolerance for wall-clock drift against monotonic time before we call it a clock jump. */
    private const val CLOCK_JUMP_TOLERANCE_MS = 5_000L

    fun create(
        raw: RawBatteryReading,
        capabilities: SensorCapabilities,
        previous: BatteryObservation?,
        source: ObservationSource,
    ): BatteryObservation? {
        val percent = BatteryFieldValidator.validatePercent(raw.level, raw.scale) ?: return null
        val notes = mutableSetOf<QualityNote>()

        val voltage = BatteryFieldValidator.validateVoltageMv(raw.voltageMvRaw)
            .also { if (it == null && raw.voltageMvRaw != null) notes += QualityNote.VOLTAGE_OUT_OF_RANGE }
            .also { if (raw.voltageMvRaw == null) notes += QualityNote.VOLTAGE_UNSUPPORTED }

        val temperature = BatteryFieldValidator.validateTemperatureDeciC(raw.temperatureDeciCRaw)
            .also { if (it == null && raw.temperatureDeciCRaw != null) notes += QualityNote.TEMPERATURE_OUT_OF_RANGE }
            .also { if (raw.temperatureDeciCRaw == null) notes += QualityNote.TEMPERATURE_UNSUPPORTED }

        val chargeCounter = BatteryFieldValidator.validateChargeCounterMicroAh(raw.chargeCounterRaw)
        if (raw.chargeCounterRaw == null) {
            notes += QualityNote.CHARGE_COUNTER_UNSUPPORTED
        } else if (chargeCounter == null) {
            notes += QualityNote.CHARGE_COUNTER_OUT_OF_RANGE
        }

        val energy = BatteryFieldValidator.validateEnergyNanoWh(raw.energyCounterRaw)
        if (raw.energyCounterRaw == null) {
            notes += QualityNote.ENERGY_UNSUPPORTED
        } else if (energy == null) {
            notes += QualityNote.ENERGY_OUT_OF_RANGE
        }

        val current = BatteryFieldValidator.validateCurrentMicroA(raw.currentNowRaw, capabilities.currentUnit)
        if (raw.currentNowRaw == null) {
            notes += QualityNote.CURRENT_UNSUPPORTED
        } else if (current == null) {
            notes += QualityNote.CURRENT_OUT_OF_RANGE
        } else if (capabilities.currentSign == CurrentSignConvention.UNDETERMINED) {
            notes += QualityNote.CURRENT_SIGN_UNCALIBRATED
        }

        val averageCurrent = BatteryFieldValidator.validateCurrentMicroA(raw.currentAverageRaw, capabilities.currentUnit)
        if (raw.currentAverageRaw == null || averageCurrent == null) {
            notes += QualityNote.AVERAGE_CURRENT_UNSUPPORTED
        }

        if (raw.thermalStatusRaw == null) notes += QualityNote.THERMAL_STATUS_UNSUPPORTED
        if (!raw.usageAccessGranted) notes += QualityNote.USAGE_ACCESS_UNAVAILABLE

        val granularity = if (raw.scale > 0) 100.0 / raw.scale else 1.0
        if (granularity > 1.0) notes += QualityNote.COARSE_PERCENT_SCALE

        val bootSessionId = bootSessionId(raw.timestampMs, raw.elapsedRealtimeMs)
        val status = mapStatus(raw.statusRaw)
        val plug = mapPlug(raw.pluggedRaw)
        // Trust the plug extra over the status extra: some devices report NOT_CHARGING while
        // plugged into a slow source, and the cable is what actually changes the physics.
        val charging = status == ChargingStatus.CHARGING ||
            (plug != PlugType.NONE && status != ChargingStatus.DISCHARGING)

        if (previous != null) {
            checkContinuity(previous, raw, percent, bootSessionId, charging, notes)
        }

        val quality = assessQuality(notes, capabilities, chargeCounter, current, granularity)

        return BatteryObservation(
            timestampMs = raw.timestampMs,
            elapsedRealtimeMs = raw.elapsedRealtimeMs,
            bootSessionId = bootSessionId,
            batteryPercent = percent,
            rawLevel = raw.level,
            rawScale = raw.scale,
            chargeCounterMicroAh = chargeCounter,
            currentMicroA = current,
            averageCurrentMicroA = averageCurrent,
            energyNanoWh = energy,
            voltageMv = voltage,
            temperatureDeciC = temperature,
            isCharging = charging,
            chargingStatus = status,
            plugType = plug,
            batteryHealth = mapHealth(raw.healthRaw),
            chargingPolicy = raw.chargingPolicyRaw?.let(::mapChargingPolicy),
            powerSaveEnabled = raw.powerSaveEnabled,
            thermalStatus = raw.thermalStatusRaw?.let(::mapThermal) ?: ThermalStatus.UNSUPPORTED,
            screenInteractive = raw.screenInteractive,
            networkType = raw.networkType,
            bluetoothState = raw.bluetoothState,
            recentScreenTimeMs = raw.recentScreenTimeMs,
            recentForegroundUsageMs = raw.recentForegroundUsageMs,
            dominantUsageCategory = raw.dominantUsageCategory,
            quality = quality,
            notes = notes,
            source = source,
        )
    }

    /**
     * Wall-clock minus monotonic time identifies the boot session.
     *
     * Quantised to ten seconds so ordinary NTP nudges do not masquerade as reboots, while an
     * actual reboot — which resets `elapsedRealtime` to zero — always produces a new value.
     */
    fun bootSessionId(timestampMs: Long, elapsedRealtimeMs: Long): Long =
        (timestampMs - elapsedRealtimeMs) / 10_000L

    private fun checkContinuity(
        previous: BatteryObservation,
        raw: RawBatteryReading,
        percent: Double,
        bootSessionId: Long,
        charging: Boolean,
        notes: MutableSet<QualityNote>,
    ) {
        val monotonicDeltaMs = raw.elapsedRealtimeMs - previous.elapsedRealtimeMs
        val wallDeltaMs = raw.timestampMs - previous.timestampMs

        // A reboot is identified by the *monotonic* clock going backwards, which only happens
        // when `elapsedRealtime` restarts at boot. Deriving it from the boot-session id alone
        // would be wrong: a daylight-saving change moves the wall clock and therefore the derived
        // id too, and mistaking that for a reboot would throw away a perfectly good segment.
        val rebooted = monotonicDeltaMs < 0
        if (rebooted) notes += QualityNote.REBOOT_DETECTED

        if (raw.timestampMs == previous.timestampMs) {
            notes += QualityNote.DUPLICATE_TIMESTAMP
        }

        // Inside one boot session the two clocks must advance together. A divergence means the
        // wall clock moved: time-zone change, daylight saving, or a manual correction. The
        // reading itself stays usable, because every rate is computed from monotonic time.
        if (!rebooted &&
            monotonicDeltaMs > 0 &&
            abs(wallDeltaMs - monotonicDeltaMs) > CLOCK_JUMP_TOLERANCE_MS
        ) {
            notes += QualityNote.CLOCK_JUMP_DETECTED
        }

        val percentDelta = percent - previous.batteryPercent
        val elapsedMinutes = if (monotonicDeltaMs > 0) monotonicDeltaMs / 60_000.0 else 0.0

        if (elapsedMinutes > 0 && !rebooted) {
            val ratePerMinute = abs(percentDelta) / elapsedMinutes
            if (ratePerMinute > MAX_PLAUSIBLE_PERCENT_PER_MINUTE) {
                notes += QualityNote.PERCENT_JUMP
                if (abs(percentDelta) >= RECALIBRATION_JUMP_PERCENT) {
                    notes += QualityNote.SUSPECTED_RECALIBRATION
                }
            }
        }

        // Direction contradictions. One percentage point either way is ordinary rounding around a
        // boundary; more than that while unplugged is a genuine inconsistency.
        if (!charging && !previous.isCharging && percentDelta > previous.percentGranularity) {
            notes += QualityNote.PERCENT_ROSE_WHILE_DISCHARGING
        }
        if (charging && previous.isCharging && percentDelta < -previous.percentGranularity) {
            notes += QualityNote.PERCENT_FELL_WHILE_CHARGING
        }

        val previousCounter = previous.chargeCounterMicroAh
        val counter = BatteryFieldValidator.validateChargeCounterMicroAh(raw.chargeCounterRaw)
        if (previousCounter != null && counter != null && !rebooted) {
            // A counter that rises while discharging, or falls while charging, has been reset or
            // rescaled by the fuel gauge. The delta is meaningless; the percentage still is not.
            val counterDelta = counter - previousCounter
            val contradictsDirection = (!charging && counterDelta > 0 && percentDelta < 0) ||
                (charging && counterDelta < 0 && percentDelta > 0)
            if (contradictsDirection) notes += QualityNote.CHARGE_COUNTER_RESET
        }
    }

    private fun assessQuality(
        notes: Set<QualityNote>,
        capabilities: SensorCapabilities,
        chargeCounter: Long?,
        current: Long?,
        granularity: Double,
    ): ObservationQuality {
        val contradictory = QualityNote.PERCENT_JUMP in notes ||
            QualityNote.SUSPECTED_RECALIBRATION in notes ||
            QualityNote.PERCENT_ROSE_WHILE_DISCHARGING in notes ||
            QualityNote.PERCENT_FELL_WHILE_CHARGING in notes ||
            QualityNote.DUPLICATE_TIMESTAMP in notes
        if (contradictory) return ObservationQuality.SUSPECTED_OUTLIER

        val hasElectrical = chargeCounter != null ||
            (current != null && capabilities.currentSign != CurrentSignConvention.UNDETERMINED)
        if (hasElectrical) return ObservationQuality.HIGH_QUALITY

        val everythingElectricalMissing = capabilities.chargeCounter == FieldSupport.UNSUPPORTED &&
            capabilities.currentNow == FieldSupport.UNSUPPORTED &&
            capabilities.energyCounter == FieldSupport.UNSUPPORTED
        if (everythingElectricalMissing && granularity > 1.0) return ObservationQuality.UNSUPPORTED_FIELD
        if (granularity > 1.0) return ObservationQuality.LOW_PRECISION
        if (everythingElectricalMissing) return ObservationQuality.LOW_PRECISION

        return ObservationQuality.ACCEPTABLE
    }

    fun mapStatus(raw: Int): ChargingStatus = when (raw) {
        PlatformConstants.STATUS_CHARGING -> ChargingStatus.CHARGING
        PlatformConstants.STATUS_DISCHARGING -> ChargingStatus.DISCHARGING
        PlatformConstants.STATUS_FULL -> ChargingStatus.FULL
        PlatformConstants.STATUS_NOT_CHARGING -> ChargingStatus.NOT_CHARGING
        else -> ChargingStatus.UNKNOWN
    }

    fun mapPlug(raw: Int): PlugType = when {
        raw == 0 -> PlugType.NONE
        raw and PlatformConstants.PLUGGED_AC != 0 -> PlugType.AC
        raw and PlatformConstants.PLUGGED_USB != 0 -> PlugType.USB
        raw and PlatformConstants.PLUGGED_WIRELESS != 0 -> PlugType.WIRELESS
        raw and PlatformConstants.PLUGGED_DOCK != 0 -> PlugType.DOCK
        else -> PlugType.UNKNOWN
    }

    fun mapHealth(raw: Int): BatteryHealth = when (raw) {
        PlatformConstants.HEALTH_GOOD -> BatteryHealth.GOOD
        PlatformConstants.HEALTH_OVERHEAT -> BatteryHealth.OVERHEAT
        PlatformConstants.HEALTH_DEAD -> BatteryHealth.DEAD
        PlatformConstants.HEALTH_OVER_VOLTAGE -> BatteryHealth.OVER_VOLTAGE
        PlatformConstants.HEALTH_COLD -> BatteryHealth.COLD
        PlatformConstants.HEALTH_UNSPECIFIED_FAILURE -> BatteryHealth.UNSPECIFIED_FAILURE
        else -> BatteryHealth.UNKNOWN
    }

    fun mapChargingPolicy(raw: Int): ChargingPolicy = when (raw) {
        PlatformConstants.CHARGING_POLICY_DEFAULT -> ChargingPolicy.DEFAULT
        PlatformConstants.CHARGING_POLICY_ADAPTIVE_AC -> ChargingPolicy.ADAPTIVE_AC
        PlatformConstants.CHARGING_POLICY_ADAPTIVE_AON -> ChargingPolicy.ADAPTIVE_AON
        PlatformConstants.CHARGING_POLICY_ADAPTIVE_LONGLIFE -> ChargingPolicy.ADAPTIVE_LONGLIFE
        else -> ChargingPolicy.UNKNOWN
    }

    fun mapThermal(raw: Int): ThermalStatus = when (raw) {
        PlatformConstants.THERMAL_STATUS_NONE -> ThermalStatus.NONE
        PlatformConstants.THERMAL_STATUS_LIGHT -> ThermalStatus.LIGHT
        PlatformConstants.THERMAL_STATUS_MODERATE -> ThermalStatus.MODERATE
        PlatformConstants.THERMAL_STATUS_SEVERE -> ThermalStatus.SEVERE
        PlatformConstants.THERMAL_STATUS_CRITICAL -> ThermalStatus.CRITICAL
        PlatformConstants.THERMAL_STATUS_EMERGENCY -> ThermalStatus.EMERGENCY
        PlatformConstants.THERMAL_STATUS_SHUTDOWN -> ThermalStatus.SHUTDOWN
        else -> ThermalStatus.UNSUPPORTED
    }
}

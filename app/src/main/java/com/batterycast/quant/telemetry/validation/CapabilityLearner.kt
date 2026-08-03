package com.batterycast.quant.telemetry.validation

import com.batterycast.quant.telemetry.model.CurrentSignConvention
import com.batterycast.quant.telemetry.model.CurrentUnit
import com.batterycast.quant.telemetry.model.FieldSupport
import com.batterycast.quant.telemetry.model.RawBatteryReading
import com.batterycast.quant.telemetry.model.SensorCapabilities
import kotlin.math.abs
import kotlin.math.min

/**
 * Running tally of what this device has actually produced.
 *
 * Persisted between app launches so the app does not have to relearn the device on every start.
 */
data class CapabilityEvidence(
    val samples: Int = 0,

    val currentNowPresent: Int = 0,
    val currentNowAbsent: Int = 0,
    val currentAveragePresent: Int = 0,
    val currentAverageAbsent: Int = 0,
    val chargeCounterPresent: Int = 0,
    val chargeCounterAbsent: Int = 0,
    val energyPresent: Int = 0,
    val energyAbsent: Int = 0,
    val voltagePresent: Int = 0,
    val voltageAbsent: Int = 0,
    val temperaturePresent: Int = 0,
    val temperatureAbsent: Int = 0,
    val thermalPresent: Int = 0,
    val thermalAbsent: Int = 0,
    val chargingPolicyPresent: Int = 0,
    val chargingPolicyAbsent: Int = 0,

    val currentMicroampLike: Int = 0,
    val currentMilliampLike: Int = 0,

    val currentPositiveWhileCharging: Int = 0,
    val currentNegativeWhileCharging: Int = 0,
    val currentPositiveWhileDischarging: Int = 0,
    val currentNegativeWhileDischarging: Int = 0,

    /** Smallest non-zero battery-percentage step ever seen; starts at the nominal scale step. */
    val smallestPercentStep: Double = 1.0,
    val designCapacityMilliAh: Double? = null,
)

/**
 * Learns a device's battery-sensor behaviour from the readings it produces.
 *
 * Two things here matter more than they might appear:
 *
 * 1. **Sign convention.** Android documents `CURRENT_NOW` as negative while discharging, but a
 *    substantial number of OEM kernels invert it. Trusting the documented sign on an inverted
 *    device would make the app predict a charging battery while it drains. So the convention is
 *    inferred by correlating the observed sign against the charging state, and current readings
 *    are treated as unusable until the correlation is decisive.
 *
 * 2. **Unit.** A handful of kernels report milliamps through the microamp property. That is a
 *    factor-of-1000 error in every mAh-based drain rate, and it is detectable purely by
 *    magnitude, because 0.4 mA is not a plausible draw for a running handset.
 */
object CapabilityLearner {

    /** Minimum observations before any support verdict is issued. */
    private const val MIN_SAMPLES_FOR_VERDICT = 4

    /** Minimum observations before a field is declared unsupported. */
    private const val MIN_SAMPLES_FOR_UNSUPPORTED = 6

    /** Minimum signed observations before the sign convention is trusted. */
    private const val MIN_SIGN_EVIDENCE = 5
    private const val SIGN_AGREEMENT_THRESHOLD = 0.75

    private const val MIN_UNIT_EVIDENCE = 5
    private const val UNIT_AGREEMENT_THRESHOLD = 0.8

    private const val SUPPORT_THRESHOLD = 0.6
    private const val LOW_PRECISION_THRESHOLD = 0.2

    fun accumulate(evidence: CapabilityEvidence, raw: RawBatteryReading): CapabilityEvidence {
        val voltage = BatteryFieldValidator.validateVoltageMv(raw.voltageMvRaw)
        val temperature = BatteryFieldValidator.validateTemperatureDeciC(raw.temperatureDeciCRaw)
        val chargeCounter = BatteryFieldValidator.validateChargeCounterMicroAh(raw.chargeCounterRaw)
        val energy = BatteryFieldValidator.validateEnergyNanoWh(raw.energyCounterRaw)

        // Unit evidence is gathered from the raw magnitude, independent of the current verdict,
        // so the detector cannot lock itself into its first guess.
        val currentRaw = raw.currentNowRaw?.takeIf { it != 0L && it != Long.MIN_VALUE && it != Int.MIN_VALUE.toLong() }
        val microampLike = currentRaw?.let { BatteryFieldValidator.looksLikeMicroamps(it) } == true
        val milliampLike = currentRaw?.let {
            !BatteryFieldValidator.looksLikeMicroamps(it) && BatteryFieldValidator.looksLikeMilliamps(it)
        } == true
        val currentUsable = microampLike || milliampLike

        val averageRaw = raw.currentAverageRaw?.takeIf { it != 0L && it != Long.MIN_VALUE && it != Int.MIN_VALUE.toLong() }
        val averageUsable = averageRaw != null &&
            (BatteryFieldValidator.looksLikeMicroamps(averageRaw) || BatteryFieldValidator.looksLikeMilliamps(averageRaw))

        val charging = raw.statusRaw == ANDROID_STATUS_CHARGING || raw.pluggedRaw != 0
        val positive = currentRaw != null && currentRaw > 0

        val scaleStep = if (raw.scale > 0) 100.0 / raw.scale else 1.0

        return evidence.copy(
            samples = evidence.samples + 1,
            currentNowPresent = evidence.currentNowPresent + if (currentUsable) 1 else 0,
            currentNowAbsent = evidence.currentNowAbsent + if (currentUsable) 0 else 1,
            currentAveragePresent = evidence.currentAveragePresent + if (averageUsable) 1 else 0,
            currentAverageAbsent = evidence.currentAverageAbsent + if (averageUsable) 0 else 1,
            chargeCounterPresent = evidence.chargeCounterPresent + if (chargeCounter != null) 1 else 0,
            chargeCounterAbsent = evidence.chargeCounterAbsent + if (chargeCounter != null) 0 else 1,
            energyPresent = evidence.energyPresent + if (energy != null) 1 else 0,
            energyAbsent = evidence.energyAbsent + if (energy != null) 0 else 1,
            voltagePresent = evidence.voltagePresent + if (voltage != null) 1 else 0,
            voltageAbsent = evidence.voltageAbsent + if (voltage != null) 0 else 1,
            temperaturePresent = evidence.temperaturePresent + if (temperature != null) 1 else 0,
            temperatureAbsent = evidence.temperatureAbsent + if (temperature != null) 0 else 1,
            thermalPresent = evidence.thermalPresent + if (raw.thermalStatusRaw != null) 1 else 0,
            thermalAbsent = evidence.thermalAbsent + if (raw.thermalStatusRaw != null) 0 else 1,
            chargingPolicyPresent = evidence.chargingPolicyPresent + if (raw.chargingPolicyRaw != null) 1 else 0,
            chargingPolicyAbsent = evidence.chargingPolicyAbsent + if (raw.chargingPolicyRaw != null) 0 else 1,
            currentMicroampLike = evidence.currentMicroampLike + if (microampLike) 1 else 0,
            currentMilliampLike = evidence.currentMilliampLike + if (milliampLike) 1 else 0,
            currentPositiveWhileCharging = evidence.currentPositiveWhileCharging +
                if (currentUsable && charging && positive) 1 else 0,
            currentNegativeWhileCharging = evidence.currentNegativeWhileCharging +
                if (currentUsable && charging && !positive) 1 else 0,
            currentPositiveWhileDischarging = evidence.currentPositiveWhileDischarging +
                if (currentUsable && !charging && positive) 1 else 0,
            currentNegativeWhileDischarging = evidence.currentNegativeWhileDischarging +
                if (currentUsable && !charging && !positive) 1 else 0,
            smallestPercentStep = min(evidence.smallestPercentStep, scaleStep),
            designCapacityMilliAh = evidence.designCapacityMilliAh,
        )
    }

    /**
     * Records an observed change in battery percentage so the app learns the real reporting
     * granularity rather than assuming one point.
     */
    fun recordPercentStep(evidence: CapabilityEvidence, delta: Double): CapabilityEvidence {
        val magnitude = abs(delta)
        if (magnitude <= 1e-9) return evidence
        return evidence.copy(smallestPercentStep = min(evidence.smallestPercentStep, magnitude))
    }

    fun toCapabilities(evidence: CapabilityEvidence): SensorCapabilities {
        val unit = detectUnit(evidence)
        return SensorCapabilities(
            currentNow = verdict(evidence.currentNowPresent, evidence.currentNowAbsent, evidence.samples),
            currentAverage = verdict(evidence.currentAveragePresent, evidence.currentAverageAbsent, evidence.samples),
            chargeCounter = verdict(evidence.chargeCounterPresent, evidence.chargeCounterAbsent, evidence.samples),
            energyCounter = verdict(evidence.energyPresent, evidence.energyAbsent, evidence.samples),
            voltage = verdict(evidence.voltagePresent, evidence.voltageAbsent, evidence.samples),
            temperature = verdict(evidence.temperaturePresent, evidence.temperatureAbsent, evidence.samples),
            thermalStatus = verdict(evidence.thermalPresent, evidence.thermalAbsent, evidence.samples),
            chargingPolicy = verdict(evidence.chargingPolicyPresent, evidence.chargingPolicyAbsent, evidence.samples),
            currentSign = detectSign(evidence),
            currentUnit = unit,
            percentGranularity = evidence.smallestPercentStep,
            designCapacityMilliAh = evidence.designCapacityMilliAh,
            samplesConsidered = evidence.samples,
        )
    }

    private fun verdict(present: Int, absent: Int, samples: Int): FieldSupport {
        val total = present + absent
        if (total == 0 || samples < MIN_SAMPLES_FOR_VERDICT) return FieldSupport.UNKNOWN
        val ratio = present.toDouble() / total
        return when {
            ratio >= SUPPORT_THRESHOLD -> FieldSupport.SUPPORTED
            present == 0 && total >= MIN_SAMPLES_FOR_UNSUPPORTED -> FieldSupport.UNSUPPORTED
            ratio >= LOW_PRECISION_THRESHOLD -> FieldSupport.LOW_PRECISION
            total >= MIN_SAMPLES_FOR_UNSUPPORTED -> FieldSupport.UNSUPPORTED
            else -> FieldSupport.UNKNOWN
        }
    }

    /**
     * Decides the discharge sign by correlating the observed sign with the charging state.
     *
     * Both directions contribute evidence: positive-while-charging and negative-while-discharging
     * both support the documented convention.
     */
    fun detectSign(evidence: CapabilityEvidence): CurrentSignConvention {
        val supportsDocumented = evidence.currentPositiveWhileCharging + evidence.currentNegativeWhileDischarging
        val supportsInverted = evidence.currentNegativeWhileCharging + evidence.currentPositiveWhileDischarging
        val total = supportsDocumented + supportsInverted
        if (total < MIN_SIGN_EVIDENCE) return CurrentSignConvention.UNDETERMINED

        val documentedShare = supportsDocumented.toDouble() / total
        return when {
            documentedShare >= SIGN_AGREEMENT_THRESHOLD -> CurrentSignConvention.NEGATIVE_IS_DISCHARGE
            (1 - documentedShare) >= SIGN_AGREEMENT_THRESHOLD -> CurrentSignConvention.POSITIVE_IS_DISCHARGE
            else -> CurrentSignConvention.UNDETERMINED
        }
    }

    fun detectUnit(evidence: CapabilityEvidence): CurrentUnit {
        val total = evidence.currentMicroampLike + evidence.currentMilliampLike
        if (total < MIN_UNIT_EVIDENCE) return CurrentUnit.UNDETERMINED
        val microShare = evidence.currentMicroampLike.toDouble() / total
        return when {
            microShare >= UNIT_AGREEMENT_THRESHOLD -> CurrentUnit.MICROAMPS
            (1 - microShare) >= UNIT_AGREEMENT_THRESHOLD -> CurrentUnit.MILLIAMPS
            else -> CurrentUnit.UNDETERMINED
        }
    }

    /**
     * Converts a validated current reading into a signed drain figure in microamps, where a
     * positive result means the battery is losing charge.
     *
     * Returns null while the sign convention is undetermined: guessing here would invert the
     * forecast on an inverted device.
     */
    fun signedDrainMicroA(
        currentMicroA: Long?,
        convention: CurrentSignConvention,
    ): Long? {
        if (currentMicroA == null) return null
        return when (convention) {
            CurrentSignConvention.UNDETERMINED -> null
            CurrentSignConvention.NEGATIVE_IS_DISCHARGE -> -currentMicroA
            CurrentSignConvention.POSITIVE_IS_DISCHARGE -> currentMicroA
        }
    }

    /** `BatteryManager.BATTERY_STATUS_CHARGING`, duplicated to keep this file framework-free. */
    private const val ANDROID_STATUS_CHARGING = 2
}

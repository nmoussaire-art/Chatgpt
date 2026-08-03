package com.batterycast.quant.telemetry.model

/**
 * What this device's battery subsystem genuinely provides.
 *
 * Android's `BatteryManager` contract is advisory: a property can be declared on the API surface
 * and still return 0, `Int.MIN_VALUE`, or a value in the wrong unit. Everything here is therefore
 * *learned from readings taken on this device*, never inferred from the API level alone.
 */
data class SensorCapabilities(
    val currentNow: FieldSupport = FieldSupport.UNKNOWN,
    val currentAverage: FieldSupport = FieldSupport.UNKNOWN,
    val chargeCounter: FieldSupport = FieldSupport.UNKNOWN,
    val energyCounter: FieldSupport = FieldSupport.UNKNOWN,
    val voltage: FieldSupport = FieldSupport.UNKNOWN,
    val temperature: FieldSupport = FieldSupport.UNKNOWN,
    val thermalStatus: FieldSupport = FieldSupport.UNKNOWN,
    val chargingPolicy: FieldSupport = FieldSupport.UNKNOWN,

    /**
     * Which sign this device uses for discharge current.
     *
     * The platform documents negative-when-discharging, but a meaningful number of OEM kernels
     * invert it. We learn the convention by correlating the reported sign with the charging state
     * and only trust current readings once it is settled.
     */
    val currentSign: CurrentSignConvention = CurrentSignConvention.UNDETERMINED,

    /** Unit actually used by `BATTERY_PROPERTY_CURRENT_NOW` on this device. */
    val currentUnit: CurrentUnit = CurrentUnit.UNDETERMINED,

    /** Smallest battery-percentage step this device reports, in percentage points. */
    val percentGranularity: Double = 1.0,

    /** Design capacity in mAh if it could be read from the platform, else null. */
    val designCapacityMilliAh: Double? = null,

    /** How many readings the capability assessment is based on. */
    val samplesConsidered: Int = 0,
) {
    /** True when electrical readings can carry the model, rather than percentage deltas alone. */
    val hasElectricalMeasurement: Boolean
        get() = chargeCounter == FieldSupport.SUPPORTED ||
            energyCounter == FieldSupport.SUPPORTED ||
            (currentNow == FieldSupport.SUPPORTED && currentSign != CurrentSignConvention.UNDETERMINED)

    /** Human-readable list of the fields this device does not provide. */
    fun unsupportedFields(): List<String> = buildList {
        if (currentNow == FieldSupport.UNSUPPORTED) add("instantaneous current")
        if (currentAverage == FieldSupport.UNSUPPORTED) add("average current")
        if (chargeCounter == FieldSupport.UNSUPPORTED) add("charge counter")
        if (energyCounter == FieldSupport.UNSUPPORTED) add("remaining energy")
        if (voltage == FieldSupport.UNSUPPORTED) add("battery voltage")
        if (temperature == FieldSupport.UNSUPPORTED) add("battery temperature")
        if (thermalStatus == FieldSupport.UNSUPPORTED) add("thermal status")
        if (chargingPolicy == FieldSupport.UNSUPPORTED) add("charging policy")
    }
}

enum class FieldSupport {
    /** Not yet observed enough times to decide. */
    UNKNOWN,

    /** Returns plausible values in the documented unit. */
    SUPPORTED,

    /** Returns values, but coarse, quantised, or only intermittently valid. */
    LOW_PRECISION,

    /** Consistently absent, zero, sentinel, or physically impossible on this device. */
    UNSUPPORTED,
}

enum class CurrentSignConvention {
    UNDETERMINED,

    /** Android's documented convention: negative while discharging. */
    NEGATIVE_IS_DISCHARGE,

    /** Inverted by the OEM kernel: positive while discharging. */
    POSITIVE_IS_DISCHARGE,
}

enum class CurrentUnit {
    UNDETERMINED,

    /** The documented unit. */
    MICROAMPS,

    /**
     * Some kernels report milliamps through the microamp property. Detected by magnitude: a
     * handset that appears to draw 0.3 mA with the screen on is reporting mA, not µA.
     */
    MILLIAMPS,
}

package com.batterycast.quant.telemetry.validation

import com.batterycast.quant.fixtures.ObservationFixtures
import com.batterycast.quant.telemetry.model.CurrentSignConvention
import com.batterycast.quant.telemetry.model.CurrentUnit
import com.batterycast.quant.telemetry.model.FieldSupport
import com.batterycast.quant.telemetry.model.ObservationQuality
import com.batterycast.quant.telemetry.model.ObservationSource
import com.batterycast.quant.telemetry.model.PlugType
import com.batterycast.quant.telemetry.model.QualityNote
import com.batterycast.quant.telemetry.model.SensorCapabilities
import com.batterycast.quant.telemetry.model.ThermalStatus
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ObservationFactoryTest {

    private val fullCapabilities = SensorCapabilities(
        currentNow = FieldSupport.SUPPORTED,
        chargeCounter = FieldSupport.SUPPORTED,
        currentSign = CurrentSignConvention.NEGATIVE_IS_DISCHARGE,
        currentUnit = CurrentUnit.MICROAMPS,
    )

    private val bareCapabilities = SensorCapabilities(
        currentNow = FieldSupport.UNSUPPORTED,
        chargeCounter = FieldSupport.UNSUPPORTED,
        energyCounter = FieldSupport.UNSUPPORTED,
    )

    private fun create(
        raw: com.batterycast.quant.telemetry.model.RawBatteryReading,
        capabilities: SensorCapabilities = fullCapabilities,
        previous: com.batterycast.quant.telemetry.model.BatteryObservation? = null,
    ) = ObservationFactory.create(raw, capabilities, previous, ObservationSource.PERIODIC_WORK)

    @Test
    fun `a healthy reading with electrical data is high quality`() {
        val observation = create(
            ObservationFixtures.rawReading(
                currentNowRaw = -420_000,
                chargeCounterRaw = 3_100_000,
            ),
        )!!

        assertThat(observation.quality).isEqualTo(ObservationQuality.HIGH_QUALITY)
        assertThat(observation.batteryPercent).isEqualTo(80.0)
        assertThat(observation.currentMicroA).isEqualTo(-420_000)
    }

    @Test
    fun `unsupported fields become nulls with recorded reasons, never zeros`() {
        val observation = create(
            ObservationFixtures.rawReading(
                currentNowRaw = null,
                chargeCounterRaw = null,
                energyCounterRaw = null,
                temperatureDeciCRaw = null,
            ),
            capabilities = bareCapabilities,
        )!!

        assertThat(observation.currentMicroA).isNull()
        assertThat(observation.chargeCounterMicroAh).isNull()
        assertThat(observation.energyNanoWh).isNull()
        assertThat(observation.temperatureDeciC).isNull()
        assertThat(observation.notes).containsAtLeast(
            QualityNote.CURRENT_UNSUPPORTED,
            QualityNote.CHARGE_COUNTER_UNSUPPORTED,
            QualityNote.ENERGY_UNSUPPORTED,
            QualityNote.TEMPERATURE_UNSUPPORTED,
        )
        assertThat(observation.quality).isEqualTo(ObservationQuality.LOW_PRECISION)
    }

    @Test
    fun `current is not trusted until the sign convention has been established`() {
        val observation = create(
            ObservationFixtures.rawReading(currentNowRaw = -420_000, chargeCounterRaw = null),
            capabilities = SensorCapabilities(currentSign = CurrentSignConvention.UNDETERMINED),
        )!!

        assertThat(observation.notes).contains(QualityNote.CURRENT_SIGN_UNCALIBRATED)
        assertThat(observation.quality).isNotEqualTo(ObservationQuality.HIGH_QUALITY)
    }

    @Test
    fun `a coarse percentage scale is flagged as low precision`() {
        val observation = create(
            ObservationFixtures.rawReading(level = 20, scale = 25, currentNowRaw = null, chargeCounterRaw = null),
            capabilities = bareCapabilities,
        )!!

        assertThat(observation.notes).contains(QualityNote.COARSE_PERCENT_SCALE)
        assertThat(observation.percentGranularity).isEqualTo(4.0)
        assertThat(observation.quality).isEqualTo(ObservationQuality.UNSUPPORTED_FIELD)
    }

    @Test
    fun `a reboot is detected from the monotonic clock restarting`() {
        val previous = ObservationFixtures.observation(percent = 60.0)
        // After a reboot elapsedRealtime restarts near zero while the wall clock keeps going.
        val afterReboot = ObservationFixtures.rawReading(
            level = 60,
            offsetMinutes = 3.0,
            elapsedOverrideMs = 4_000L,
        )

        val observation = create(afterReboot, previous = previous)!!

        assertThat(observation.notes).contains(QualityNote.REBOOT_DETECTED)
        assertThat(observation.bootSessionId).isNotEqualTo(previous.bootSessionId)
    }

    @Test
    fun `an impossible percentage jump is flagged as an outlier`() {
        val previous = ObservationFixtures.observation(percent = 80.0)
        val jumped = ObservationFixtures.rawReading(level = 40, offsetMinutes = 2.0)

        val observation = create(jumped, previous = previous)!!

        assertThat(observation.notes).contains(QualityNote.PERCENT_JUMP)
        assertThat(observation.notes).contains(QualityNote.SUSPECTED_RECALIBRATION)
        assertThat(observation.quality).isEqualTo(ObservationQuality.SUSPECTED_OUTLIER)
        assertThat(observation.usableForModelling).isFalse()
    }

    @Test
    fun `a rising percentage while unplugged is flagged`() {
        val previous = ObservationFixtures.observation(percent = 60.0)
        val risen = ObservationFixtures.rawReading(level = 63, offsetMinutes = 30.0, pluggedRaw = 0, statusRaw = 3)

        val observation = create(risen, previous = previous)!!

        assertThat(observation.notes).contains(QualityNote.PERCENT_ROSE_WHILE_DISCHARGING)
        assertThat(observation.quality).isEqualTo(ObservationQuality.SUSPECTED_OUTLIER)
    }

    @Test
    fun `a wall-clock jump is detected without disturbing the reading`() {
        val previous = ObservationFixtures.observation(percent = 70.0)
        // Daylight saving: the wall clock moves an hour, monotonic time does not.
        val skewed = ObservationFixtures.rawReading(
            level = 69,
            offsetMinutes = 15.0,
            clockSkewMs = 3_600_000L,
        )

        val observation = create(skewed, previous = previous)!!

        assertThat(observation.notes).contains(QualityNote.CLOCK_JUMP_DETECTED)
        assertThat(observation.batteryPercent).isEqualTo(69.0)
        // The reading itself stays usable: monotonic time is unaffected, so the rate is still right.
        assertThat(observation.usableForModelling).isTrue()
    }

    @Test
    fun `a charge counter contradicting the percentage direction is flagged as a reset`() {
        val previous = ObservationFixtures.observation(percent = 70.0, chargeCounterMicroAh = 2_800_000)
        val contradiction = ObservationFixtures.rawReading(
            level = 68,
            offsetMinutes = 20.0,
            chargeCounterRaw = 3_400_000,
        )

        val observation = create(contradiction, previous = previous)!!

        assertThat(observation.notes).contains(QualityNote.CHARGE_COUNTER_RESET)
    }

    @Test
    fun `a plugged device with a non-charging status is still treated as charging`() {
        val observation = create(
            ObservationFixtures.rawReading(statusRaw = 4, pluggedRaw = 2, currentNowRaw = 300_000),
        )!!

        assertThat(observation.isCharging).isTrue()
        assertThat(observation.plugType).isEqualTo(PlugType.USB)
    }

    @Test
    fun `a nonsensical level and scale yields no observation at all`() {
        assertThat(create(ObservationFixtures.rawReading(level = -1, scale = 100))).isNull()
        assertThat(create(ObservationFixtures.rawReading(level = 50, scale = 0))).isNull()
    }

    @Test
    fun `a missing thermal status becomes unsupported rather than none`() {
        val observation = create(ObservationFixtures.rawReading(thermalStatusRaw = null))!!

        assertThat(observation.thermalStatus).isEqualTo(ThermalStatus.UNSUPPORTED)
        assertThat(observation.notes).contains(QualityNote.THERMAL_STATUS_UNSUPPORTED)
    }

    @Test
    fun `a wall-clock change is not mistaken for a reboot`() {
        val previous = ObservationFixtures.observation(percent = 70.0)
        val skewed = ObservationFixtures.rawReading(
            level = 69,
            offsetMinutes = 15.0,
            clockSkewMs = 3_600_000L,
        )

        val observation = create(skewed, previous = previous)!!

        assertThat(observation.notes).doesNotContain(QualityNote.REBOOT_DETECTED)
        assertThat(observation.notes).contains(QualityNote.CLOCK_JUMP_DETECTED)
    }

    @Test
    fun `boot session identifier tolerates small clock drift`() {
        val a = ObservationFactory.bootSessionId(1_700_000_000_000L, 500_000L)
        val b = ObservationFactory.bootSessionId(1_700_000_002_000L, 502_000L)
        val afterReboot = ObservationFactory.bootSessionId(1_700_000_500_000L, 1_000L)

        assertThat(a).isEqualTo(b)
        assertThat(afterReboot).isNotEqualTo(a)
    }
}

package com.batterycast.quant.telemetry.validation

import com.batterycast.quant.fixtures.ObservationFixtures
import com.batterycast.quant.telemetry.model.CurrentSignConvention
import com.batterycast.quant.telemetry.model.CurrentUnit
import com.batterycast.quant.telemetry.model.FieldSupport
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CapabilityLearnerTest {

    private fun accumulateAll(readings: List<com.batterycast.quant.telemetry.model.RawBatteryReading>) =
        readings.fold(CapabilityEvidence()) { evidence, raw -> CapabilityLearner.accumulate(evidence, raw) }

    @Test
    fun `a fresh install knows nothing and says so`() {
        val capabilities = CapabilityLearner.toCapabilities(CapabilityEvidence())

        assertThat(capabilities.currentNow).isEqualTo(FieldSupport.UNKNOWN)
        assertThat(capabilities.chargeCounter).isEqualTo(FieldSupport.UNKNOWN)
        assertThat(capabilities.currentSign).isEqualTo(CurrentSignConvention.UNDETERMINED)
        assertThat(capabilities.hasElectricalMeasurement).isFalse()
    }

    @Test
    fun `a device that never reports current is marked unsupported`() {
        val readings = (0 until 8).map { index ->
            ObservationFixtures.rawReading(offsetMinutes = index * 15.0, currentNowRaw = null)
        }

        val capabilities = CapabilityLearner.toCapabilities(accumulateAll(readings))

        assertThat(capabilities.currentNow).isEqualTo(FieldSupport.UNSUPPORTED)
        assertThat(capabilities.unsupportedFields()).contains("instantaneous current")
    }

    @Test
    fun `standard sign convention is learned from charging and discharging readings`() {
        val discharging = (0 until 4).map { index ->
            ObservationFixtures.rawReading(
                offsetMinutes = index * 15.0,
                currentNowRaw = -420_000,
                statusRaw = 3,
                pluggedRaw = 0,
            )
        }
        val charging = (0 until 4).map { index ->
            ObservationFixtures.rawReading(
                offsetMinutes = 60.0 + index * 15.0,
                currentNowRaw = 1_500_000,
                statusRaw = 2,
                pluggedRaw = 1,
            )
        }

        val capabilities = CapabilityLearner.toCapabilities(accumulateAll(discharging + charging))

        assertThat(capabilities.currentSign).isEqualTo(CurrentSignConvention.NEGATIVE_IS_DISCHARGE)
        assertThat(capabilities.currentNow).isEqualTo(FieldSupport.SUPPORTED)
    }

    @Test
    fun `inverted OEM sign convention is detected, not assumed away`() {
        // Some kernels report positive while discharging. Trusting the documented sign on such a
        // device would make the app forecast a rising battery while it drains.
        val discharging = (0 until 4).map { index ->
            ObservationFixtures.rawReading(
                offsetMinutes = index * 15.0,
                currentNowRaw = 420_000,
                statusRaw = 3,
                pluggedRaw = 0,
            )
        }
        val charging = (0 until 4).map { index ->
            ObservationFixtures.rawReading(
                offsetMinutes = 60.0 + index * 15.0,
                currentNowRaw = -1_500_000,
                statusRaw = 2,
                pluggedRaw = 1,
            )
        }

        val capabilities = CapabilityLearner.toCapabilities(accumulateAll(discharging + charging))

        assertThat(capabilities.currentSign).isEqualTo(CurrentSignConvention.POSITIVE_IS_DISCHARGE)
    }

    @Test
    fun `contradictory sign evidence leaves the convention undetermined`() {
        val mixed = listOf(
            ObservationFixtures.rawReading(offsetMinutes = 0.0, currentNowRaw = -400_000, statusRaw = 3),
            ObservationFixtures.rawReading(offsetMinutes = 15.0, currentNowRaw = 400_000, statusRaw = 3),
            ObservationFixtures.rawReading(offsetMinutes = 30.0, currentNowRaw = -400_000, statusRaw = 3),
            ObservationFixtures.rawReading(offsetMinutes = 45.0, currentNowRaw = 400_000, statusRaw = 3),
            ObservationFixtures.rawReading(offsetMinutes = 60.0, currentNowRaw = -400_000, statusRaw = 3),
            ObservationFixtures.rawReading(offsetMinutes = 75.0, currentNowRaw = 400_000, statusRaw = 3),
        )

        val capabilities = CapabilityLearner.toCapabilities(accumulateAll(mixed))

        assertThat(capabilities.currentSign).isEqualTo(CurrentSignConvention.UNDETERMINED)
    }

    @Test
    fun `milliamp reporting is detected from magnitude`() {
        val readings = (0 until 8).map { index ->
            ObservationFixtures.rawReading(offsetMinutes = index * 15.0, currentNowRaw = -420)
        }

        val capabilities = CapabilityLearner.toCapabilities(accumulateAll(readings))

        assertThat(capabilities.currentUnit).isEqualTo(CurrentUnit.MILLIAMPS)
    }

    @Test
    fun `microamp reporting is detected from magnitude`() {
        val readings = (0 until 8).map { index ->
            ObservationFixtures.rawReading(offsetMinutes = index * 15.0, currentNowRaw = -420_000)
        }

        val capabilities = CapabilityLearner.toCapabilities(accumulateAll(readings))

        assertThat(capabilities.currentUnit).isEqualTo(CurrentUnit.MICROAMPS)
    }

    @Test
    fun `signed drain returns null while the convention is unknown`() {
        assertThat(
            CapabilityLearner.signedDrainMicroA(-400_000, CurrentSignConvention.UNDETERMINED),
        ).isNull()
    }

    @Test
    fun `signed drain is positive while losing charge under both conventions`() {
        assertThat(
            CapabilityLearner.signedDrainMicroA(-400_000, CurrentSignConvention.NEGATIVE_IS_DISCHARGE),
        ).isEqualTo(400_000)

        assertThat(
            CapabilityLearner.signedDrainMicroA(400_000, CurrentSignConvention.POSITIVE_IS_DISCHARGE),
        ).isEqualTo(400_000)
    }

    @Test
    fun `charge counter support is learned from real readings`() {
        val readings = (0 until 8).map { index ->
            ObservationFixtures.rawReading(
                offsetMinutes = index * 15.0,
                chargeCounterRaw = 3_000_000 - index * 20_000L,
            )
        }

        val capabilities = CapabilityLearner.toCapabilities(accumulateAll(readings))

        assertThat(capabilities.chargeCounter).isEqualTo(FieldSupport.SUPPORTED)
        assertThat(capabilities.hasElectricalMeasurement).isTrue()
    }

    @Test
    fun `observed percentage steps refine the reported granularity`() {
        val evidence = CapabilityLearner.recordPercentStep(CapabilityEvidence(), delta = -0.5)
        assertThat(evidence.smallestPercentStep).isEqualTo(0.5)

        val unchanged = CapabilityLearner.recordPercentStep(evidence, delta = 0.0)
        assertThat(unchanged.smallestPercentStep).isEqualTo(0.5)
    }
}

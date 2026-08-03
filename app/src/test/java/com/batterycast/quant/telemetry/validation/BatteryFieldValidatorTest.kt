package com.batterycast.quant.telemetry.validation

import com.batterycast.quant.telemetry.model.CurrentUnit
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BatteryFieldValidatorTest {

    // --- Voltage -------------------------------------------------------------------------

    @Test
    fun `voltage in millivolts passes through`() {
        assertThat(BatteryFieldValidator.validateVoltageMv(3900)).isEqualTo(3900)
    }

    @Test
    fun `voltage reported in microvolts is converted`() {
        assertThat(BatteryFieldValidator.validateVoltageMv(3_900_000)).isEqualTo(3900)
    }

    @Test
    fun `voltage reported in whole volts is converted`() {
        assertThat(BatteryFieldValidator.validateVoltageMv(4)).isEqualTo(4000)
    }

    @Test
    fun `implausible voltage is rejected rather than clamped`() {
        assertThat(BatteryFieldValidator.validateVoltageMv(50_000)).isNull()
        assertThat(BatteryFieldValidator.validateVoltageMv(0)).isNull()
        assertThat(BatteryFieldValidator.validateVoltageMv(Int.MIN_VALUE)).isNull()
        assertThat(BatteryFieldValidator.validateVoltageMv(null)).isNull()
    }

    // --- Temperature ---------------------------------------------------------------------

    @Test
    fun `temperature within the plausible band is accepted`() {
        assertThat(BatteryFieldValidator.validateTemperatureDeciC(285)).isEqualTo(285)
        assertThat(BatteryFieldValidator.validateTemperatureDeciC(-100)).isEqualTo(-100)
    }

    @Test
    fun `temperature outside physical limits is rejected`() {
        assertThat(BatteryFieldValidator.validateTemperatureDeciC(5000)).isNull()
        assertThat(BatteryFieldValidator.validateTemperatureDeciC(-2000)).isNull()
        assertThat(BatteryFieldValidator.validateTemperatureDeciC(Int.MIN_VALUE)).isNull()
    }

    @Test
    fun `ambiguous whole-celsius temperature is not silently rescaled`() {
        // 32 could be 3.2 C or 32 C. Guessing would fabricate data, so it is taken at face value
        // as deci-Celsius and left for the quality flags to note as unusual.
        assertThat(BatteryFieldValidator.validateTemperatureDeciC(32)).isEqualTo(32)
    }

    // --- Charge counter ------------------------------------------------------------------

    @Test
    fun `charge counter in microamp hours is accepted`() {
        assertThat(BatteryFieldValidator.validateChargeCounterMicroAh(3_200_000)).isEqualTo(3_200_000)
    }

    @Test
    fun `charge counter reported in milliamp hours is rejected not converted`() {
        // 3200 mAh reported through the microamp-hour property is out of range. Converting would
        // be a guess; rejecting it makes the app fall back to percentage-based estimation.
        assertThat(BatteryFieldValidator.validateChargeCounterMicroAh(3_200)).isNull()
    }

    @Test
    fun `charge counter sentinels are rejected`() {
        assertThat(BatteryFieldValidator.validateChargeCounterMicroAh(Long.MIN_VALUE)).isNull()
        assertThat(BatteryFieldValidator.validateChargeCounterMicroAh(Int.MIN_VALUE.toLong())).isNull()
        assertThat(BatteryFieldValidator.validateChargeCounterMicroAh(0L)).isNull()
        assertThat(BatteryFieldValidator.validateChargeCounterMicroAh(null)).isNull()
    }

    // --- Energy --------------------------------------------------------------------------

    @Test
    fun `energy counter accepts a realistic handset value`() {
        // 4000 mAh at 3.85 V is about 15.4 Wh, i.e. 1.54e10 nWh.
        assertThat(BatteryFieldValidator.validateEnergyNanoWh(15_400_000_000)).isEqualTo(15_400_000_000)
    }

    @Test
    fun `energy counter rejects sentinels and impossible magnitudes`() {
        assertThat(BatteryFieldValidator.validateEnergyNanoWh(Long.MIN_VALUE)).isNull()
        assertThat(BatteryFieldValidator.validateEnergyNanoWh(12)).isNull()
        assertThat(BatteryFieldValidator.validateEnergyNanoWh(null)).isNull()
    }

    // --- Current -------------------------------------------------------------------------

    @Test
    fun `current in microamps is preserved with its sign`() {
        assertThat(BatteryFieldValidator.validateCurrentMicroA(-450_000, CurrentUnit.MICROAMPS))
            .isEqualTo(-450_000)
        assertThat(BatteryFieldValidator.validateCurrentMicroA(1_200_000, CurrentUnit.MICROAMPS))
            .isEqualTo(1_200_000)
    }

    @Test
    fun `current reported in milliamps is scaled once the unit is known`() {
        assertThat(BatteryFieldValidator.validateCurrentMicroA(-450, CurrentUnit.MILLIAMPS))
            .isEqualTo(-450_000)
    }

    @Test
    fun `exact zero current is treated as unsupported not as an idle device`() {
        assertThat(BatteryFieldValidator.validateCurrentMicroA(0, CurrentUnit.MICROAMPS)).isNull()
    }

    @Test
    fun `current beyond fifteen amps is rejected`() {
        assertThat(BatteryFieldValidator.validateCurrentMicroA(50_000_000, CurrentUnit.MICROAMPS)).isNull()
    }

    @Test
    fun `unit heuristics separate milliamp from microamp magnitudes`() {
        assertThat(BatteryFieldValidator.looksLikeMilliamps(-450)).isTrue()
        assertThat(BatteryFieldValidator.looksLikeMicroamps(-450)).isFalse()

        assertThat(BatteryFieldValidator.looksLikeMicroamps(-450_000)).isTrue()
        assertThat(BatteryFieldValidator.looksLikeMilliamps(-450_000)).isFalse()
    }

    // --- Percentage ----------------------------------------------------------------------

    @Test
    fun `percent is computed from level and scale`() {
        assertThat(BatteryFieldValidator.validatePercent(50, 100)).isEqualTo(50.0)
        assertThat(BatteryFieldValidator.validatePercent(128, 255)).isWithin(0.01).of(50.196)
    }

    @Test
    fun `nonsensical level or scale yields null`() {
        assertThat(BatteryFieldValidator.validatePercent(50, 0)).isNull()
        assertThat(BatteryFieldValidator.validatePercent(-1, 100)).isNull()
        assertThat(BatteryFieldValidator.validatePercent(150, 100)).isNull()
    }
}

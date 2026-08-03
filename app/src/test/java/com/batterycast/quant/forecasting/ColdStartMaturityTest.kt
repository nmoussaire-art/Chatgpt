package com.batterycast.quant.forecasting

import com.batterycast.quant.fixtures.ObservationFixtures
import com.batterycast.quant.forecasting.clean.ObservationCleaner
import com.batterycast.quant.forecasting.drain.DrainRateEstimator
import com.batterycast.quant.forecasting.ewma.EwmaCell
import com.batterycast.quant.forecasting.model.DataMaturity
import com.batterycast.quant.forecasting.model.DrainMetric
import com.batterycast.quant.forecasting.model.ModelNamespace
import com.batterycast.quant.telemetry.model.CurrentSignConvention
import com.batterycast.quant.telemetry.model.CurrentUnit
import com.batterycast.quant.telemetry.model.FieldSupport
import com.batterycast.quant.telemetry.model.SensorCapabilities
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Cold start: what the app is entitled to claim, and when.
 *
 * The rule under test is that every capability is gated on the evidence that supports it. Nothing
 * is unlocked by elapsed time alone, because a phone that sat untouched for six hours has plenty
 * of history and almost no information.
 */
class ColdStartMaturityTest {

    private val engine = ForecastContextFixtures.engine()
    private val nowMs = ObservationFixtures.BASE_TIMESTAMP_MS + 24 * 3_600_000L

    private val bareDevice = SensorCapabilities(
        currentNow = FieldSupport.UNSUPPORTED,
        chargeCounter = FieldSupport.UNSUPPORTED,
        energyCounter = FieldSupport.UNSUPPORTED,
    )

    private val instrumentedDevice = SensorCapabilities(
        currentNow = FieldSupport.SUPPORTED,
        chargeCounter = FieldSupport.SUPPORTED,
        currentSign = CurrentSignConvention.NEGATIVE_IS_DISCHARGE,
        currentUnit = CurrentUnit.MICROAMPS,
    )

    private fun maturityFor(
        history: List<com.batterycast.quant.telemetry.model.BatteryObservation>,
        capabilities: SensorCapabilities = bareDevice,
        cells: Map<Triple<ModelNamespace, String, DrainMetric>, EwmaCell> = emptyMap(),
        atMs: Long = nowMs,
    ): DataMaturity {
        val cleaned = ObservationCleaner.clean(history)
        val estimates = DrainRateEstimator.estimate(cleaned.segments, capabilities, atMs)
        return engine.assessMaturity(cleaned, estimates, cells, atMs)
    }

    private fun cell(key: String, mean: Double, weight: Double, atMs: Long = nowMs) = EwmaCell(
        namespace = ModelNamespace.DRAIN,
        key = key,
        metric = DrainMetric.PERCENT_PER_HOUR,
        mean = mean,
        variance = 1.0,
        weight = weight,
        rawSamples = weight.toLong(),
        lastUpdateMs = atMs,
    )

    @Test
    fun `immediately after installation there is nothing to forecast from`() {
        val maturity = maturityFor(listOf(ObservationFixtures.observation()))

        assertThat(maturity).isEqualTo(DataMaturity.COLLECTING)
        assertThat(maturity.supportsSurvivalProbability).isFalse()
        assertThat(maturity.supportsChargePlanning).isFalse()
        assertThat(maturity.supportsRegimeForecasts).isFalse()
        assertThat(maturity.supportsAccuracyReport).isFalse()
    }

    @Test
    fun `a device with a live current sensor reaches a preliminary estimate almost at once`() {
        val history = List(4) { index ->
            ObservationFixtures.observation(
                offsetMinutes = index * 2.0,
                percent = 80.0,
                currentMicroA = -450_000,
                chargeCounterMicroAh = 3_200_000,
            )
        }

        val maturity = maturityFor(history, capabilities = instrumentedDevice, atMs = history.last().timestampMs)

        assertThat(maturity).isAnyOf(DataMaturity.PRELIMINARY, DataMaturity.BASIC)
        assertThat(maturity.isPreliminary || maturity == DataMaturity.BASIC).isTrue()
    }

    @Test
    fun `hours of history with no battery movement do not unlock a forecast`() {
        // Plugged in overnight at a constant level: lots of readings, no information about drain.
        val history = List(24) { index ->
            ObservationFixtures.observation(offsetMinutes = index * 15.0, percent = 100.0)
        }

        val maturity = maturityFor(history, atMs = history.last().timestampMs)

        assertThat(maturity).isAnyOf(DataMaturity.COLLECTING, DataMaturity.PRELIMINARY)
        assertThat(maturity.supportsSurvivalProbability).isFalse()
    }

    @Test
    fun `an hour of real discharge unlocks the basic forecast`() {
        val history = ObservationFixtures.steadyDischarge(
            startPercent = 80.0,
            ratePerHour = 9.0,
            intervalMinutes = 10.0,
            count = 13,
        )

        val maturity = maturityFor(history, atMs = history.last().timestampMs)

        assertThat(maturity).isEqualTo(DataMaturity.BASIC)
        assertThat(maturity.supportsSurvivalProbability).isTrue()
        assertThat(maturity.supportsChargePlanning).isTrue()
        // But not the things that need a personal history.
        assertThat(maturity.supportsRegimeForecasts).isFalse()
        assertThat(maturity.supportsDayOfWeek).isFalse()
    }

    @Test
    fun `regime forecasts stay locked until several regimes have actually been observed`() {
        val history = ObservationFixtures.steadyDischarge(
            startPercent = 95.0,
            ratePerHour = 3.0,
            intervalMinutes = 20.0,
            count = 80,
        )
        val oneRegimeOnly = mapOf(
            Triple(ModelNamespace.DRAIN, "global", DrainMetric.PERCENT_PER_HOUR) to cell("global", 3.0, 40.0),
            Triple(ModelNamespace.DRAIN, "r=STANDBY", DrainMetric.PERCENT_PER_HOUR) to cell("r=STANDBY", 3.0, 40.0),
        )

        val maturity = maturityFor(
            history,
            cells = oneRegimeOnly,
            atMs = history.last().timestampMs,
        )

        assertThat(maturity).isEqualTo(DataMaturity.BASIC)
        assertThat(maturity.supportsRegimeForecasts).isFalse()
    }

    @Test
    fun `a day of varied history unlocks personalised forecasts`() {
        val history = ObservationFixtures.steadyDischarge(
            startPercent = 100.0,
            ratePerHour = 3.0,
            intervalMinutes = 20.0,
            count = 80,
        )
        val severalRegimes = mapOf(
            Triple(ModelNamespace.DRAIN, "global", DrainMetric.PERCENT_PER_HOUR) to cell("global", 5.0, 40.0),
            Triple(ModelNamespace.DRAIN, "r=STANDBY", DrainMetric.PERCENT_PER_HOUR) to cell("r=STANDBY", 1.0, 20.0),
            Triple(ModelNamespace.DRAIN, "r=LIGHT_USE", DrainMetric.PERCENT_PER_HOUR) to cell("r=LIGHT_USE", 6.0, 15.0),
            Triple(ModelNamespace.DRAIN, "r=INTERACTIVE", DrainMetric.PERCENT_PER_HOUR) to cell("r=INTERACTIVE", 12.0, 15.0),
        )

        val maturity = maturityFor(
            history,
            cells = severalRegimes,
            atMs = history.last().timestampMs,
        )

        assertThat(maturity).isEqualTo(DataMaturity.PERSONALISED)
        assertThat(maturity.supportsRegimeForecasts).isTrue()
        assertThat(maturity.supportsAccuracyReport).isTrue()
        // Day-of-week personalisation still needs a week.
        assertThat(maturity.supportsDayOfWeek).isFalse()
    }

    @Test
    fun `maturity levels unlock capabilities in a strictly increasing order`() {
        val levels = DataMaturity.entries

        assertThat(levels.map { it.supportsSurvivalProbability }).isInOrder()
        assertThat(levels.map { it.supportsRegimeForecasts }).isInOrder()
        assertThat(levels.map { it.supportsDayOfWeek }).isInOrder()
        assertThat(levels.map { it.supportsAccuracyReport }).isInOrder()
    }

    @Test
    fun `every maturity level has wording the app can show`() {
        DataMaturity.entries.forEach { maturity ->
            assertThat(maturity.displayName).isNotEmpty()
        }
        assertThat(DataMaturity.PRELIMINARY.displayName).contains("Preliminary")
        assertThat(DataMaturity.COLLECTING.displayName).contains("Collecting")
    }
}

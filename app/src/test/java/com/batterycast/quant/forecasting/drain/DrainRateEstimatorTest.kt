package com.batterycast.quant.forecasting.drain

import com.batterycast.quant.fixtures.ObservationFixtures
import com.batterycast.quant.forecasting.clean.ObservationCleaner
import com.batterycast.quant.telemetry.model.CurrentSignConvention
import com.batterycast.quant.telemetry.model.CurrentUnit
import com.batterycast.quant.telemetry.model.FieldSupport
import com.batterycast.quant.telemetry.model.SensorCapabilities
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DrainRateEstimatorTest {

    private val percentOnlyDevice = SensorCapabilities(
        currentNow = FieldSupport.UNSUPPORTED,
        chargeCounter = FieldSupport.UNSUPPORTED,
        energyCounter = FieldSupport.UNSUPPORTED,
    )

    private val fullDevice = SensorCapabilities(
        currentNow = FieldSupport.SUPPORTED,
        chargeCounter = FieldSupport.SUPPORTED,
        currentSign = CurrentSignConvention.NEGATIVE_IS_DISCHARGE,
        currentUnit = CurrentUnit.MICROAMPS,
    )

    @Test
    fun `a steady discharge recovers close to the true rate despite percentage rounding`() {
        val history = ObservationFixtures.steadyDischarge(
            startPercent = 90.0,
            ratePerHour = 8.0,
            intervalMinutes = 15.0,
            count = 16,
        )

        val estimate = DrainRateEstimator.percentDrainEstimate(history)!!

        assertThat(estimate.ratePerHour).isWithin(1.2).of(8.0)
        assertThat(estimate.method).isEqualTo(EstimationMethod.ROBUST_REGRESSION_PERCENT)
        assertThat(estimate.confidence).isEqualTo(EstimateConfidence.GOOD)
    }

    @Test
    fun `two readings produce a low-confidence endpoint estimate, never a confident one`() {
        val history = listOf(
            ObservationFixtures.observation(offsetMinutes = 0.0, percent = 80.0),
            ObservationFixtures.observation(offsetMinutes = 60.0, percent = 74.0),
        )

        val estimate = DrainRateEstimator.percentDrainEstimate(history)!!

        assertThat(estimate.method).isEqualTo(EstimationMethod.ENDPOINT_DIFFERENCE)
        assertThat(estimate.confidence).isEqualTo(EstimateConfidence.LOW)
        assertThat(estimate.ratePerHour).isWithin(0.01).of(6.0)
        // The uncertainty is stated, not hidden: one rounding step over the span.
        assertThat(estimate.residualScale).isGreaterThan(0.0)
    }

    @Test
    fun `no measurable change yields no estimate rather than zero`() {
        val history = listOf(
            ObservationFixtures.observation(offsetMinutes = 0.0, percent = 80.0),
            ObservationFixtures.observation(offsetMinutes = 20.0, percent = 80.0),
        )

        assertThat(DrainRateEstimator.percentDrainEstimate(history)).isNull()
    }

    @Test
    fun `an empty or single-point history yields no estimate`() {
        assertThat(DrainRateEstimator.percentDrainEstimate(emptyList())).isNull()
        assertThat(
            DrainRateEstimator.percentDrainEstimate(listOf(ObservationFixtures.observation())),
        ).isNull()
    }

    @Test
    fun `full capacity is derived from the fuel gauge, not assumed`() {
        val history = ObservationFixtures.steadyDischarge(
            startPercent = 90.0,
            count = 10,
            withChargeCounter = true,
            capacityMilliAh = 4200.0,
        )

        val capacity = DrainRateEstimator.estimateFullCapacityMilliAh(history)!!

        assertThat(capacity).isWithin(120.0).of(4200.0)
    }

    @Test
    fun `capacity is not estimated when the device has no charge counter`() {
        val history = ObservationFixtures.steadyDischarge(count = 10, withChargeCounter = false)

        assertThat(DrainRateEstimator.estimateFullCapacityMilliAh(history)).isNull()
    }

    @Test
    fun `the charge counter gives a sharper rate than rounded percentages`() {
        val history = ObservationFixtures.steadyDischarge(
            startPercent = 88.0,
            ratePerHour = 6.0,
            intervalMinutes = 10.0,
            count = 18,
            withChargeCounter = true,
            capacityMilliAh = 4000.0,
        )
        val capacity = DrainRateEstimator.estimateFullCapacityMilliAh(history)!!

        val fromCounter = DrainRateEstimator.chargeCounterDrainEstimate(history, capacity)!!

        assertThat(fromCounter.method).isEqualTo(EstimationMethod.ROBUST_REGRESSION_CHARGE)
        assertThat(fromCounter.ratePerHour).isWithin(0.4).of(6.0)
    }

    @Test
    fun `instantaneous current is unusable until the sign convention is known`() {
        val history = ObservationFixtures.steadyDischarge(count = 6).map {
            it.copy(currentMicroA = -400_000)
        }

        val estimate = DrainRateEstimator.instantaneousDrainEstimate(
            observations = history,
            capabilities = SensorCapabilities(currentSign = CurrentSignConvention.UNDETERMINED),
            fullCapacityMilliAh = 4000.0,
            nowMs = history.last().timestampMs,
        )

        assertThat(estimate).isNull()
    }

    @Test
    fun `instantaneous current gives a preliminary rate once the sign is known`() {
        val history = ObservationFixtures.steadyDischarge(count = 6, intervalMinutes = 2.0).map {
            it.copy(currentMicroA = -400_000)
        }

        val estimate = DrainRateEstimator.instantaneousDrainEstimate(
            observations = history,
            capabilities = fullDevice,
            fullCapacityMilliAh = 4000.0,
            nowMs = history.last().timestampMs,
        )!!

        // 400 mA from a 4000 mAh pack is 10 %/h.
        assertThat(estimate.ratePerHour).isWithin(0.2).of(10.0)
        assertThat(estimate.method).isEqualTo(EstimationMethod.INSTANTANEOUS_CURRENT)
        assertThat(estimate.confidence).isAnyOf(EstimateConfidence.LOW, EstimateConfidence.MODERATE)
    }

    @Test
    fun `a device with no sensors still yields a percentage-based estimate and does not crash`() {
        val history = ObservationFixtures.steadyDischarge(count = 12)
        val segments = ObservationCleaner.clean(history).segments

        val estimates = DrainRateEstimator.estimate(
            segments = segments,
            capabilities = percentOnlyDevice,
            nowMs = history.last().timestampMs,
        )

        assertThat(estimates.blendedPercentPerHour).isNotNull()
        assertThat(estimates.chargeBasedPercentPerHour).isNull()
        assertThat(estimates.instantaneous).isNull()
        assertThat(estimates.energyBasedWattHoursPerHour).isNull()
    }

    @Test
    fun `horizon blending weights better-supported windows more heavily`() {
        val recent = DrainEstimate(
            metric = com.batterycast.quant.forecasting.model.DrainMetric.PERCENT_PER_HOUR,
            ratePerHour = 20.0,
            method = EstimationMethod.ENDPOINT_DIFFERENCE,
            confidence = EstimateConfidence.LOW,
            pointCount = 2,
            spanHours = 0.4,
            residualScale = 2.0,
        )
        val longer = DrainEstimate(
            metric = com.batterycast.quant.forecasting.model.DrainMetric.PERCENT_PER_HOUR,
            ratePerHour = 6.0,
            method = EstimationMethod.ROBUST_REGRESSION_PERCENT,
            confidence = EstimateConfidence.GOOD,
            pointCount = 20,
            spanHours = 3.0,
            residualScale = 0.5,
        )

        val blended = DrainRateEstimator.blend(
            mapOf(DrainHorizon.RECENT to recent, DrainHorizon.THREE_HOURS to longer),
        )!!

        // Recency pulls it up from 6, but the well-supported estimate keeps it well below 20.
        assertThat(blended.ratePerHour).isGreaterThan(6.0)
        assertThat(blended.ratePerHour).isLessThan(14.0)
    }

    @Test
    fun `disagreement between horizons widens the blended residual scale`() {
        val agreeing = mapOf(
            DrainHorizon.RECENT to estimate(8.0),
            DrainHorizon.HOUR to estimate(8.1),
            DrainHorizon.THREE_HOURS to estimate(7.9),
        )
        val disagreeing = mapOf(
            DrainHorizon.RECENT to estimate(18.0),
            DrainHorizon.HOUR to estimate(8.0),
            DrainHorizon.THREE_HOURS to estimate(3.0),
        )

        val calm = DrainRateEstimator.blend(agreeing)!!
        val volatile = DrainRateEstimator.blend(disagreeing)!!

        assertThat(volatile.residualScale).isGreaterThan(calm.residualScale)
    }

    @Test
    fun `blending nothing yields nothing`() {
        assertThat(DrainRateEstimator.blend(emptyMap())).isNull()
    }

    @Test
    fun `charge rate estimation recovers a charging slope`() {
        val charging = ObservationFixtures.steadyCharge(
            startPercent = 20.0,
            ratePerHour = 45.0,
            intervalMinutes = 5.0,
            count = 12,
        )
        val segment = ObservationCleaner.clean(charging).chargeSegments.single()

        val estimate = DrainRateEstimator.chargeRateEstimate(segment)!!

        assertThat(estimate.ratePerHour).isWithin(6.0).of(45.0)
    }

    private fun estimate(rate: Double) = DrainEstimate(
        metric = com.batterycast.quant.forecasting.model.DrainMetric.PERCENT_PER_HOUR,
        ratePerHour = rate,
        method = EstimationMethod.ROBUST_REGRESSION_PERCENT,
        confidence = EstimateConfidence.GOOD,
        pointCount = 12,
        spanHours = 2.0,
        residualScale = 0.4,
    )
}

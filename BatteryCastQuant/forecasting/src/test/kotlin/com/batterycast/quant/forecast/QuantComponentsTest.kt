package com.batterycast.quant.forecast

import com.batterycast.quant.model.BatteryObservation
import com.batterycast.quant.model.DataQuality
import com.batterycast.quant.model.ForecastEvaluation
import com.batterycast.quant.model.NetworkType
import com.batterycast.quant.model.PlugType
import com.batterycast.quant.model.UsageRegime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class QuantComponentsTest {
    private fun observation(
        timestamp: Long,
        percent: Double,
        charging: Boolean = false,
        plug: PlugType = if (charging) PlugType.AC else PlugType.NONE,
        bootCount: Int = 1,
        regime: UsageRegime = if (charging) UsageRegime.CHARGING else UsageRegime.NORMAL
    ) = BatteryObservation(
        timestamp = timestamp,
        elapsedRealtimeMs = timestamp,
        bootCount = bootCount,
        batteryPercent = percent,
        chargeCounterMicroAh = 2_000_000,
        currentMicroA = if (charging) 1_200_000 else -300_000,
        averageCurrentMicroA = if (charging) 1_100_000 else -280_000,
        energyNanoWh = 8_000_000_000,
        voltageMv = 3_900,
        temperatureDeciC = 310,
        isCharging = charging,
        plugType = plug,
        batteryHealth = 2,
        chargingStatus = null,
        chargingPolicy = null,
        powerSaveEnabled = false,
        thermalStatus = 0,
        screenInteractive = !charging,
        networkType = NetworkType.WIFI,
        bluetoothEnabled = false,
        recentScreenTimeMs = 10_000,
        recentForegroundUsageMs = 10_000,
        usageRegime = regime,
        overallQuality = DataQuality.HIGH,
        source = "test"
    )

    @Test
    fun chargingAndDischargingSegmentsAreSeparated() {
        val start = 1_000_000_000L
        val observations = listOf(
            observation(start, 70.0),
            observation(start + 15 * 60_000L, 69.0),
            observation(start + 30 * 60_000L, 69.5, charging = true),
            observation(start + 45 * 60_000L, 72.0, charging = true),
            observation(start + 60 * 60_000L, 71.5),
            observation(start + 75 * 60_000L, 71.0)
        )
        val segments = BatterySegmenter().detect(observations)
        assertEquals(
            listOf(BatterySegmentType.DISCHARGING, BatterySegmentType.CHARGING, BatterySegmentType.DISCHARGING),
            segments.map { it.type }
        )
        assertEquals(listOf(2, 2, 2), segments.map { it.observations.size })
    }

    @Test
    fun invalidOptionalSensorIsNulledWithoutDiscardingBatteryReading() {
        val reading = observation(1_000_000_000L, 65.0).copy(currentMicroA = 50_000_000L)
        val cleaned = ObservationCleaner().clean(listOf(reading))
        assertEquals(1, cleaned.valid.size)
        assertEquals(null, cleaned.valid.single().currentMicroA)
        assertEquals(DataQuality.LOW_PRECISION, cleaned.valid.single().overallQuality)
        assertTrue(cleaned.rejected.isEmpty())
    }

    @Test
    fun rebootCreatesANewSegment() {
        val start = 1_000_000_000L
        val segments = BatterySegmenter().detect(
            listOf(
                observation(start, 70.0, bootCount = 1),
                observation(start + 15 * 60_000L, 69.0, bootCount = 1),
                observation(start + 30 * 60_000L, 68.5, bootCount = 2)
            )
        )
        assertEquals(2, segments.size)
    }

    @Test
    fun ewmaAndPercentilesAreDeterministic() {
        assertEquals(2.0, ewma(listOf(1.0, 3.0), .5))
        assertEquals(7.5, listOf(0.0, 10.0, 20.0, 30.0).quantile(.25))
        assertEquals(15.0, listOf(0.0, 10.0, 20.0, 30.0).median())
    }

    @Test
    fun percentageRoundingDoesNotCollapseDrainToZero() {
        val start = 1_000_000_000L
        val observations = (0..12).map { index ->
            observation(start + index * 15 * 60_000L, 80.0 - (index / 4))
        }
        val estimate = DrainEstimator().estimate(observations, observations.last().timestamp)
        assertNotNull(estimate)
        assertTrue(estimate.percentPerHour in .4..2.0)
    }

    @Test
    fun sparseRecentExtremeIsShrunkTowardDeviceHistory() {
        val start = 1_000_000_000L
        val broad = (0 until 30).map { index ->
            observation(start + index * 15 * 60_000L, 90.0 - index * .25)
        }
        val recentStart = broad.last().timestamp + 15 * 60_000L
        val recent = (0 until 5).map { index ->
            observation(recentStart + index * 15 * 60_000L, broad.last().batteryPercent - index * 2.0)
        }
        val observations = broad + recent
        val estimate = DrainEstimator().estimate(observations, observations.last().timestamp)
        assertNotNull(estimate)
        assertTrue(estimate.percentPerHour > 1.0)
        assertTrue(estimate.percentPerHour < 8.0)
    }

    @Test
    fun chargingTaperIsLearnedFromObservedBins() {
        val start = 1_000_000_000L
        val observations = buildList {
            var percent = 20.0
            repeat(30) { index ->
                add(observation(start + index * 10 * 60_000L, percent, charging = true))
                percent += if (percent < 80.0) 3.0 else .6
            }
        }
        val model = ChargingModel().fit(observations, PlugType.AC)
        val lowRate = ChargingModel().rateAt(model, 35.0, 310).first
        val highRate = ChargingModel().rateAt(model, 90.0, 310).first
        assertNotNull(lowRate)
        assertNotNull(highRate)
        assertTrue(lowRate > highRate)
    }

    @Test
    fun accuracyCoverageAndCalibrationUseActualOutcomes() {
        val evaluations = (0 until 10).map { index ->
            val survived = index < 8
            ForecastEvaluation(
                reservePercent = 10,
                medianAtTarget = if (survived) 30.0 else 8.0,
                p05 = if (survived) 20.0 else 3.0,
                p10 = if (survived) 22.0 else 4.0,
                p25 = if (survived) 25.0 else 5.0,
                p75 = if (survived) 35.0 else 11.0,
                p90 = if (survived) 38.0 else 13.0,
                p95 = if (survived) 40.0 else 15.0,
                survivalProbability = .8,
                predictedTimeTo20Median = 2_000_000L,
                actualAtTarget = if (survived) 29.0 else 7.0,
                actualTimeTo20 = 2_300_000L
            )
        }
        val summary = AccuracyCalculator().calculate(evaluations)
        assertEquals(10, summary.evaluatedForecasts)
        assertEquals(1.0, summary.medianAbsoluteErrorPercent)
        assertEquals(5.0, summary.medianTimeTo20ErrorMinutes)
        assertEquals(1.0, summary.coverage50)
        assertEquals(1.0, summary.coverage80)
        assertEquals(1.0, summary.coverage90)
        assertTrue(summary.calibrationScore!! < 1e-9)
    }
}

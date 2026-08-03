package com.batterycast.quant.forecast

import com.batterycast.quant.model.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ForecastEngineTest {
    private fun observation(
        timestamp: Long,
        percent: Double,
        charging: Boolean = false,
        regime: UsageRegime = UsageRegime.NORMAL,
        plug: PlugType = if (charging) PlugType.AC else PlugType.NONE,
        bootCount: Int = 1,
        elapsed: Long = timestamp,
        current: Long? = if (charging) 1_400_000 else -350_000,
        counter: Long? = 2_000_000,
        energy: Long? = null,
        temperature: Int? = 310,
        powerSave: Boolean = false
    ) = BatteryObservation(
        timestamp = timestamp,
        elapsedRealtimeMs = elapsed,
        bootCount = bootCount,
        batteryPercent = percent,
        chargeCounterMicroAh = counter,
        currentMicroA = current,
        averageCurrentMicroA = current,
        energyNanoWh = energy,
        voltageMv = 3_900,
        temperatureDeciC = temperature,
        isCharging = charging,
        plugType = plug,
        batteryHealth = 2,
        chargingStatus = null,
        chargingPolicy = null,
        powerSaveEnabled = powerSave,
        thermalStatus = 0,
        screenInteractive = !charging,
        networkType = NetworkType.WIFI,
        bluetoothEnabled = false,
        recentScreenTimeMs = 30_000,
        recentForegroundUsageMs = 30_000,
        usageRegime = if (charging) UsageRegime.CHARGING else regime,
        overallQuality = DataQuality.HIGH,
        source = "test"
    )

    private fun dischargeHistory(
        start: Long = 1_000_000_000L,
        count: Int = 32,
        intervalMinutes: Int = 15,
        startPercent: Double = 90.0,
        dropPerPoint: Double = 0.6,
        regime: UsageRegime = UsageRegime.NORMAL
    ) = (0 until count).map { index ->
        observation(
            timestamp = start + index * intervalMinutes * 60_000L,
            percent = startPercent - index * dropPerPoint,
            regime = regime,
            counter = (2_600_000L - index * 18_000L).coerceAtLeast(100_000L),
            energy = (11_000_000_000L - index * 70_000_000L).coerceAtLeast(100_000_000L)
        )
    }

    @Test
    fun probabilitiesAndPercentilesStayValid() {
        val history = dischargeHistory()
        val now = history.last().timestamp
        val result = BatteryForecastEngine(2_000, 42).forecast(history, now + 8 * 60 * 60_000L, 10, now = now)
        assertTrue(result.survivalProbability!! in 0.0..1.0)
        assertTrue(result.points.all {
            it.p05 <= it.p10 && it.p10 <= it.p25 && it.p25 <= it.median &&
                it.median <= it.p75 && it.p75 <= it.p90 && it.p90 <= it.p95
        })
        assertTrue(result.points.all { it.p05 in 0.0..100.0 && it.p95 in 0.0..100.0 })
    }

    @Test
    fun heavierScenarioNeverImprovesBatteryLife() {
        val history = dischargeHistory()
        val now = history.last().timestamp
        val target = now + 4 * 60 * 60_000L
        val engine = BatteryForecastEngine(2_000, 9)
        val normal = engine.forecast(history, target, 10, ScenarioDefinition("n", "Normal", UsageRegime.NORMAL, 240), now)
        val navigation = engine.forecast(history, target, 10, ScenarioDefinition("nav", "Navigation", UsageRegime.NAVIGATION, 240), now)
        assertTrue(navigation.medianAtTarget!! <= normal.medianAtTarget!!)
    }

    @Test
    fun scenarioDurationIsRespected() {
        val start = 1_000_000_000L
        val normal = dischargeHistory(start = start, count = 20, regime = UsageRegime.NORMAL, dropPerPoint = .3)
        val heavy = dischargeHistory(start = normal.last().timestamp + 15 * 60_000L, count = 12, startPercent = normal.last().batteryPercent, regime = UsageRegime.HEAVY, dropPerPoint = 1.0)
        val history = normal + heavy
        val now = history.last().timestamp
        val engine = BatteryForecastEngine(2_000, 17)
        val short = engine.forecast(history, now + 4 * 60 * 60_000L, 10, ScenarioDefinition("h30", "Heavy", UsageRegime.HEAVY, 30), now)
        val long = engine.forecast(history, now + 4 * 60 * 60_000L, 10, ScenarioDefinition("h180", "Heavy", UsageRegime.HEAVY, 180), now)
        assertTrue(long.medianAtTarget!! <= short.medianAtTarget!!)
    }

    @Test
    fun coldStartIsHonest() {
        val timestamp = 1_000_000_000L
        val result = BatteryForecastEngine(2_000, 1).forecast(
            listOf(observation(timestamp, 50.0)),
            timestamp + 2 * 60 * 60_000L,
            10,
            now = timestamp
        )
        assertTrue(result.preliminary)
        assertTrue(
            result.qualityMessage.contains("Preliminary", ignoreCase = true) ||
                result.qualityMessage.contains("collecting", ignoreCase = true)
        )
    }

    @Test
    fun missingInstantaneousSensorsRemainHonest() {
        val timestamp = 1_000_000_000L
        val noSensors = observation(timestamp, 60.0, current = null, counter = null, energy = null)
        val result = BatteryForecastEngine(2_000, 1).forecast(
            listOf(noSensors), timestamp + 60 * 60_000L, 10, now = timestamp
        )
        assertNull(result.survivalProbability)
    }

    @Test
    fun chargingProjectionRaisesBatteryAndUsesTaperBins() {
        val start = 1_000_000_000L
        val history = buildList {
            var percent = 20.0
            repeat(24) { index ->
                add(observation(start + index * 10 * 60_000L, percent, charging = true))
                percent += if (percent < 80) 2.0 else .7
            }
        }
        val now = history.last().timestamp
        val result = BatteryForecastEngine(2_000, 4).forecast(history, now + 60 * 60_000L, 20, now = now)
        val medianAtTarget = result.medianAtTarget!!
        assertTrue(medianAtTarget >= history.last().batteryPercent)
        assertTrue(medianAtTarget <= 100.0)
    }

    @Test
    fun widerUncertaintyProducesWiderIntervals() {
        val smooth = dischargeHistory(dropPerPoint = .5)
        val noisy = smooth.mapIndexed { index, item ->
            item.copy(batteryPercent = item.batteryPercent + if (index % 3 == 0) .8 else if (index % 3 == 1) -.8 else 0.0)
        }
        val smoothNow = smooth.last().timestamp
        val noisyNow = noisy.last().timestamp
        val engine = BatteryForecastEngine(2_000, 27)
        val smoothForecast = engine.forecast(smooth, smoothNow + 4 * 60 * 60_000L, 10, now = smoothNow)
        val noisyForecast = engine.forecast(noisy, noisyNow + 4 * 60 * 60_000L, 10, now = noisyNow)
        val smoothWidth = smoothForecast.targetP90!! - smoothForecast.targetP10!!
        val noisyWidth = noisyForecast.targetP90!! - noisyForecast.targetP10!!
        assertTrue(noisyWidth >= smoothWidth)
    }

    @Test
    fun outlierAndDuplicateCleaningWorks() {
        val t = 1_000_000_000L
        val normal = observation(t, 70.0)
        val duplicateLowQuality = normal.copy(overallQuality = DataQuality.LOW_PRECISION)
        val impossible = observation(t + 60_000L, 150.0)
        val result = ObservationCleaner().clean(listOf(duplicateLowQuality, normal, impossible))
        assertEquals(1, result.valid.size)
        assertEquals(DataQuality.HIGH, result.valid.single().overallQuality)
        assertEquals(1, result.rejected.size)
    }

    @Test
    fun rebootBoundaryIsNotUsedAsDrainSegment() {
        val t = 1_000_000_000L
        val observations = listOf(
            observation(t, 70.0, bootCount = 1, elapsed = 900_000L),
            observation(t + 15 * 60_000L, 69.0, bootCount = 1, elapsed = 1_800_000L),
            observation(t + 30 * 60_000L, 50.0, bootCount = 2, elapsed = 20_000L),
            observation(t + 45 * 60_000L, 49.5, bootCount = 2, elapsed = 920_000L),
            observation(t + 60 * 60_000L, 49.0, bootCount = 2, elapsed = 1_820_000L)
        )
        val estimate = DrainEstimator().estimate(ObservationCleaner().clean(observations).valid, observations.last().timestamp)
        assertNotNull(estimate)
        assertTrue(estimate.percentPerHour < 20.0)
    }

    @Test
    fun sensorBasedDrainRatesAreCalculatedWhenSupported() {
        val history = dischargeHistory()
        val estimate = DrainEstimator().estimate(history, history.last().timestamp)
        assertNotNull(estimate?.microAhPerHour)
        assertNotNull(estimate?.milliWhPerHour)
        assertTrue(estimate!!.microAhPerHour!! > 0.0)
        assertTrue(estimate.milliWhPerHour!! > 0.0)
    }

    @Test
    fun sparseDataHasLowerConfidenceThanRichHistory() {
        val rich = dischargeHistory(count = 100)
        val sparse = rich.takeLast(8)
        val target = rich.last().timestamp + 3 * 60 * 60_000L
        val engine = BatteryForecastEngine(2_000, 12)
        val sparseResult = engine.forecast(sparse, target, 10, now = sparse.last().timestamp)
        val richResult = engine.forecast(rich, target, 10, now = rich.last().timestamp)
        assertTrue(sparseResult.confidence.ordinal <= richResult.confidence.ordinal)
    }

    @Test
    fun thresholdTimesAreOrderedWhenReached() {
        val history = dischargeHistory(startPercent = 35.0, dropPerPoint = .7)
        val now = history.last().timestamp
        val result = BatteryForecastEngine(2_000, 31).forecast(history, now + 12 * 60 * 60_000L, 0, now = now)
        val t20 = result.thresholds.first { it.threshold == 20 }.medianTimestamp
        val t10 = result.thresholds.first { it.threshold == 10 }.medianTimestamp
        val t5 = result.thresholds.first { it.threshold == 5 }.medianTimestamp
        if (t20 != null && t10 != null) assertTrue(t20 <= t10)
        if (t10 != null && t5 != null) assertTrue(t10 <= t5)
    }

    @Test
    fun chargePlannerReturnsNoPlanWithoutObservedCharging() {
        val history = dischargeHistory()
        val now = history.last().timestamp
        val plan = ChargePlanner(2_000, 4).plan(
            history,
            ChargePlanRequest(now + 4 * 60 * 60_000L, 60, .9),
            now
        )
        assertNull(plan.latestSafeStart)
        assertTrue(plan.message.contains("charging curve", ignoreCase = true))
    }

    @Test
    fun chargePlannerFindsSafeStartWithObservedCharging() {
        val start = 1_000_000_000L
        val discharge = dischargeHistory(start = start, count = 20, startPercent = 80.0, dropPerPoint = .4)
        val chargingStart = discharge.last().timestamp + 10 * 60_000L
        val charging = (0..24).map { index ->
            observation(
                chargingStart + index * 5 * 60_000L,
                35.0 + index * if (index < 16) 1.5 else .6,
                charging = true,
                plug = PlugType.AC
            )
        }
        val live = observation(charging.last().timestamp + 10 * 60_000L, 38.0, charging = false)
        val history = discharge + charging + live
        val now = live.timestamp
        val plan = ChargePlanner(2_000, 8).plan(
            history,
            ChargePlanRequest(now + 5 * 60 * 60_000L, 55, .8),
            now
        )
        assertNotNull(plan.latestSafeStart)
        assertNotNull(plan.recommendedDurationMinutes)
        assertTrue(plan.confidenceAchieved!! in 0.0..1.0)
    }
}

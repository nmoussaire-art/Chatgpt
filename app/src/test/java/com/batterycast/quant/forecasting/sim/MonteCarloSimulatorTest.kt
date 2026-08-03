package com.batterycast.quant.forecasting.sim

import com.batterycast.quant.fixtures.SnapshotFixtures
import com.batterycast.quant.forecasting.model.SocDecile
import com.batterycast.quant.forecasting.model.ThermalBucket
import com.batterycast.quant.forecasting.model.UsageRegime
import com.batterycast.quant.forecasting.uncertainty.ResidualModel
import com.batterycast.quant.forecasting.uncertainty.TimedResidual
import com.batterycast.quant.telemetry.model.PlugType
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Properties the simulator must satisfy for the numbers on screen to mean anything.
 *
 * Every test here fixes the seed, so a failure is a real change in behaviour rather than an
 * unlucky draw.
 */
class MonteCarloSimulatorTest {

    private val simulator = MonteCarloSimulator()
    private val startMs = 1_700_000_000_000L
    private val hour = 3_600_000L

    private fun request(
        startPercent: Double = 60.0,
        horizonHours: Long = 8,
        charging: Boolean = false,
        regime: UsageRegime = UsageRegime.INTERACTIVE,
        paths: Int = 2_400,
        seed: Long = SimulationRequest.DEFAULT_SEED,
        drainMultiplier: Double = 1.0,
        regimeOverride: RegimeSchedule? = null,
        chargingStartsAtMs: Long? = null,
        chargingEndsAtMs: Long? = null,
        powerSave: Boolean = false,
        thermal: ThermalBucket = ThermalBucket.COOL,
    ) = SimulationRequest(
        startPercent = startPercent,
        startMs = startMs,
        horizonMs = horizonHours * hour,
        isCharging = charging,
        plugType = if (charging) PlugType.AC else PlugType.NONE,
        initialRegime = regime,
        thermalBucket = thermal,
        powerSaveEnabled = powerSave,
        regimeOverride = regimeOverride,
        drainMultiplier = drainMultiplier,
        chargingStartsAtMs = chargingStartsAtMs,
        chargingEndsAtMs = chargingEndsAtMs,
        paths = paths,
        seed = seed,
    )

    // --- Invariants ----------------------------------------------------------------------

    @Test
    fun `battery percentage never leaves the zero to one hundred range`() {
        val result = simulator.simulate(
            request(startPercent = 4.0, horizonHours = 12),
            SnapshotFixtures.snapshot(globalDrainPerHour = 20.0),
        )

        result.points.forEach { point ->
            assertThat(point.p05).isAtLeast(0.0)
            assertThat(point.p95).isAtMost(100.0)
            assertThat(point.median).isIn(com.google.common.collect.Range.closed(0.0, 100.0))
        }
        result.terminalPercents.forEach { value ->
            assertThat(value).isAtLeast(0.0)
            assertThat(value).isAtMost(100.0)
        }
    }

    @Test
    fun `charging cannot push the battery past one hundred percent`() {
        val result = simulator.simulate(
            request(startPercent = 96.0, charging = true, horizonHours = 6),
            SnapshotFixtures.snapshot(chargeRatePerHour = 60.0),
        )

        assertThat(result.terminalPercents.max()).isAtMost(100.0)
    }

    @Test
    fun `percentiles are correctly ordered at every step`() {
        val result = simulator.simulate(request(), SnapshotFixtures.snapshot())

        result.points.forEach { point ->
            assertThat(point.p05).isAtMost(point.p10)
            assertThat(point.p10).isAtMost(point.p25)
            assertThat(point.p25).isAtMost(point.median)
            assertThat(point.median).isAtMost(point.p75)
            assertThat(point.p75).isAtMost(point.p90)
            assertThat(point.p90).isAtMost(point.p95)
        }
    }

    @Test
    fun `probabilities always lie between zero and one`() {
        val result = simulator.simulate(request(), SnapshotFixtures.snapshot())

        listOf(0.0, 5.0, 10.0, 20.0, 50.0, 99.0).forEach { reserve ->
            result.points.indices.forEach { step ->
                val atMs = startMs + step * SimulationRequest.DEFAULT_STEP_MS
                val probability = result.probabilityAbove(atMs, reserve)
                if (probability != null) {
                    assertThat(probability).isAtLeast(0.0)
                    assertThat(probability).isAtMost(1.0)
                }
            }
        }

        MonteCarloSimulator.THRESHOLDS.forEach { threshold ->
            val probability = result.probabilityOfReaching(threshold)!!
            assertThat(probability).isAtLeast(0.0)
            assertThat(probability).isAtMost(1.0)
        }
    }

    @Test
    fun `a fixed seed produces an identical forecast`() {
        val snapshot = SnapshotFixtures.snapshot()
        val first = simulator.simulate(request(), snapshot)
        val second = simulator.simulate(request(), snapshot)

        assertThat(second.points.map { it.median }).isEqualTo(first.points.map { it.median })
        assertThat(second.probabilityAbove(startMs + 4 * hour, 20.0))
            .isEqualTo(first.probabilityAbove(startMs + 4 * hour, 20.0))
    }

    @Test
    fun `the simulator enforces a floor on path count`() {
        val result = simulator.simulate(request(paths = 10), SnapshotFixtures.snapshot())

        assertThat(result.paths).isAtLeast(MonteCarloSimulator.MIN_PATHS)
    }

    // --- Monotonicity: the model must not be self-contradictory ----------------------------

    @Test
    fun `a faster drain never predicts a longer-lasting battery`() {
        val gentle = simulator.simulate(
            request(),
            SnapshotFixtures.snapshot(globalDrainPerHour = 5.0),
        )
        val harsh = simulator.simulate(
            request(),
            SnapshotFixtures.snapshot(globalDrainPerHour = 15.0),
        )

        val atFourHours = startMs + 4 * hour
        assertThat(harsh.pointAt(atFourHours)!!.median)
            .isLessThan(gentle.pointAt(atFourHours)!!.median)
        assertThat(harsh.probabilityAbove(atFourHours, 20.0)!!)
            .isLessThan(gentle.probabilityAbove(atFourHours, 20.0)!!)
    }

    @Test
    fun `a heavier usage scenario never predicts more battery than a lighter one`() {
        val snapshot = SnapshotFixtures.snapshot()
        val light = simulator.simulate(
            request(
                regimeOverride = RegimeSchedule(
                    listOf(RegimeSchedule.Entry(UsageRegime.LIGHT_USE, 3 * hour)),
                ),
            ),
            snapshot,
        )
        val heavy = simulator.simulate(
            request(
                regimeOverride = RegimeSchedule(
                    listOf(RegimeSchedule.Entry(UsageRegime.GAMING_LIKE, 3 * hour)),
                ),
            ),
            snapshot,
        )

        val atThreeHours = startMs + 3 * hour
        assertThat(heavy.pointAt(atThreeHours)!!.median)
            .isLessThan(light.pointAt(atThreeHours)!!.median)
    }

    @Test
    fun `charging raises the projected battery above the discharging case`() {
        val snapshot = SnapshotFixtures.snapshot()
        val discharging = simulator.simulate(request(startPercent = 40.0), snapshot)
        val charging = simulator.simulate(request(startPercent = 40.0, charging = true), snapshot)

        val atTwoHours = startMs + 2 * hour
        assertThat(charging.pointAt(atTwoHours)!!.median).isGreaterThan(40.0)
        assertThat(charging.pointAt(atTwoHours)!!.median)
            .isGreaterThan(discharging.pointAt(atTwoHours)!!.median)
    }

    @Test
    fun `a scheduled charging window lifts the battery from the moment it starts`() {
        val snapshot = SnapshotFixtures.snapshot()
        val plan = simulator.simulate(
            request(
                startPercent = 30.0,
                horizonHours = 6,
                chargingStartsAtMs = startMs + 2 * hour,
                chargingEndsAtMs = startMs + 4 * hour,
            ),
            snapshot,
        )

        val beforeCharging = plan.pointAt(startMs + 2 * hour)!!.median
        val afterCharging = plan.pointAt(startMs + 4 * hour)!!.median

        assertThat(beforeCharging).isLessThan(30.0)
        assertThat(afterCharging).isGreaterThan(beforeCharging)
    }

    @Test
    fun `power saving with a measured multiplier extends the forecast`() {
        val withoutSaver = simulator.simulate(
            request(powerSave = false),
            SnapshotFixtures.snapshot(powerSaveMultiplier = 0.6),
        )
        val withSaver = simulator.simulate(
            request(powerSave = true),
            SnapshotFixtures.snapshot(powerSaveMultiplier = 0.6),
        )

        val atSixHours = startMs + 6 * hour
        assertThat(withSaver.pointAt(atSixHours)!!.median)
            .isGreaterThan(withoutSaver.pointAt(atSixHours)!!.median)
    }

    @Test
    fun `a hotter thermal state drains at least as fast as a cool one`() {
        val snapshot = SnapshotFixtures.snapshot(
            thermalMultipliers = mapOf(
                ThermalBucket.COOL to 1.0,
                ThermalBucket.WARM to 1.25,
                ThermalBucket.HOT to 1.6,
            ),
        )
        val cool = simulator.simulate(request(thermal = ThermalBucket.COOL), snapshot)
        val hot = simulator.simulate(request(thermal = ThermalBucket.HOT), snapshot)

        val atFourHours = startMs + 4 * hour
        assertThat(hot.pointAt(atFourHours)!!.median)
            .isLessThan(cool.pointAt(atFourHours)!!.median)
    }

    // --- Uncertainty ----------------------------------------------------------------------

    @Test
    fun `wider drain uncertainty produces wider forecast intervals`() {
        val certain = simulator.simulate(
            request(),
            SnapshotFixtures.snapshot(variance = 0.25, samples = 200.0),
        )
        val uncertain = simulator.simulate(
            request(),
            SnapshotFixtures.snapshot(variance = 16.0, samples = 200.0),
        )

        val atFourHours = startMs + 4 * hour
        val certainWidth = certain.pointAt(atFourHours)!!.let { it.p90 - it.p10 }
        val uncertainWidth = uncertain.pointAt(atFourHours)!!.let { it.p90 - it.p10 }

        assertThat(uncertainWidth).isGreaterThan(certainWidth)
    }

    @Test
    fun `sparse evidence produces wider intervals than plentiful evidence`() {
        val plentiful = simulator.simulate(
            request(),
            SnapshotFixtures.snapshot(variance = 4.0, samples = 200.0),
        )
        val sparse = simulator.simulate(
            request(),
            SnapshotFixtures.snapshot(variance = 4.0, samples = 1.5),
        )

        val atFourHours = startMs + 4 * hour
        val plentifulWidth = plentiful.pointAt(atFourHours)!!.let { it.p90 - it.p10 }
        val sparseWidth = sparse.pointAt(atFourHours)!!.let { it.p90 - it.p10 }

        assertThat(sparseWidth).isGreaterThan(plentifulWidth)
    }

    @Test
    fun `intervals widen as the horizon lengthens`() {
        val result = simulator.simulate(request(horizonHours = 12), SnapshotFixtures.snapshot())

        val oneHour = result.pointAt(startMs + hour)!!.let { it.p90 - it.p10 }
        val fourHours = result.pointAt(startMs + 4 * hour)!!.let { it.p90 - it.p10 }

        assertThat(fourHours).isGreaterThan(oneHour)
    }

    @Test
    fun `empirical residuals are resampled when enough exist`() {
        val residuals = ResidualModel.from(
            (0 until 60).map { TimedResidual(if (it % 2 == 0) 3.0 else -3.0, startMs - it * 60_000L) },
            startMs,
        )
        assertThat(residuals.hasEmpiricalSupport).isTrue()

        val withNoise = simulator.simulate(
            request(),
            SnapshotFixtures.snapshot(residualModel = residuals),
        )
        val withoutNoise = simulator.simulate(request(), SnapshotFixtures.snapshot())

        val atFourHours = startMs + 4 * hour
        assertThat(withNoise.pointAt(atFourHours)!!.let { it.p90 - it.p10 })
            .isGreaterThan(withoutNoise.pointAt(atFourHours)!!.let { it.p90 - it.p10 })
    }

    // --- Threshold crossings --------------------------------------------------------------

    @Test
    fun `threshold crossing times are ordered from higher to lower levels`() {
        val result = simulator.simulate(
            request(startPercent = 70.0, horizonHours = 16),
            SnapshotFixtures.snapshot(globalDrainPerHour = 8.0),
        )

        val toTwenty = result.medianTimeToThresholdMs(20.0)!!
        val toTen = result.medianTimeToThresholdMs(10.0)!!
        val toFive = result.medianTimeToThresholdMs(5.0)!!

        assertThat(toTwenty).isLessThan(toTen)
        assertThat(toTen).isLessThan(toFive)
    }

    @Test
    fun `a threshold most paths never reach has no median time`() {
        val result = simulator.simulate(
            request(startPercent = 95.0, horizonHours = 2),
            SnapshotFixtures.snapshot(globalDrainPerHour = 4.0),
        )

        assertThat(result.medianTimeToThresholdMs(5.0)).isNull()
        assertThat(result.probabilityOfReaching(5.0)).isLessThan(0.5)
    }

    @Test
    fun `time-to-threshold quantiles bracket the median`() {
        val result = simulator.simulate(
            request(startPercent = 60.0, horizonHours = 16),
            SnapshotFixtures.snapshot(globalDrainPerHour = 9.0),
        )

        val p10 = result.timeToThresholdQuantileMs(20.0, 0.10)!!
        val median = result.medianTimeToThresholdMs(20.0)!!
        val p90 = result.timeToThresholdQuantileMs(20.0, 0.90)!!

        assertThat(p10).isAtMost(median)
        assertThat(median).isAtMost(p90)
    }

    // --- Discharge curve ------------------------------------------------------------------

    @Test
    fun `a learned discharge curve changes the projection at the affected levels`() {
        val flat = simulator.simulate(
            request(startPercent = 25.0, horizonHours = 4),
            SnapshotFixtures.snapshot(),
        )
        // This device is observed to drain faster below 30 %.
        val steepAtLowCharge = simulator.simulate(
            request(startPercent = 25.0, horizonHours = 4),
            SnapshotFixtures.snapshot(
                socCurve = mapOf(SocDecile.D20 to 1.8, SocDecile.D10 to 2.0, SocDecile.D0 to 2.0),
            ),
        )

        val atTwoHours = startMs + 2 * hour
        assertThat(steepAtLowCharge.pointAt(atTwoHours)!!.median)
            .isLessThan(flat.pointAt(atTwoHours)!!.median)
    }

    // --- Out-of-range queries -------------------------------------------------------------

    @Test
    fun `queries outside the horizon return null rather than extrapolating`() {
        val result = simulator.simulate(request(horizonHours = 4), SnapshotFixtures.snapshot())

        assertThat(result.probabilityAbove(startMs + 20 * hour, 10.0)).isNull()
        assertThat(result.pointAt(startMs - hour)).isNull()
    }

    @Test
    fun `a regime schedule takes effect only for its stated duration`() {
        val snapshot = SnapshotFixtures.snapshot()
        val briefBurst = simulator.simulate(
            request(
                horizonHours = 8,
                regimeOverride = RegimeSchedule(
                    listOf(RegimeSchedule.Entry(UsageRegime.GAMING_LIKE, 30 * 60_000L)),
                ),
            ),
            snapshot,
        )
        val sustained = simulator.simulate(
            request(
                horizonHours = 8,
                regimeOverride = RegimeSchedule(
                    listOf(RegimeSchedule.Entry(UsageRegime.GAMING_LIKE, 4 * hour)),
                ),
            ),
            snapshot,
        )

        val atFourHours = startMs + 4 * hour
        assertThat(sustained.pointAt(atFourHours)!!.median)
            .isLessThan(briefBurst.pointAt(atFourHours)!!.median)
    }

    @Test
    fun `a regime schedule reports the regime in force at each offset`() {
        val schedule = RegimeSchedule(
            listOf(
                RegimeSchedule.Entry(UsageRegime.NAVIGATION_LIKE, 45 * 60_000L),
                RegimeSchedule.Entry(UsageRegime.STANDBY, 60 * 60_000L),
            ),
        )

        assertThat(schedule.regimeAt(0)).isEqualTo(UsageRegime.NAVIGATION_LIKE)
        assertThat(schedule.regimeAt(44 * 60_000L)).isEqualTo(UsageRegime.NAVIGATION_LIKE)
        assertThat(schedule.regimeAt(46 * 60_000L)).isEqualTo(UsageRegime.STANDBY)
        assertThat(schedule.regimeAt(3 * 3_600_000L)).isNull()
    }

    @Test
    fun `an empty model produces a flat projection rather than crashing`() {
        val emptySnapshot = SnapshotFixtures.snapshot(
            globalDrainPerHour = 0.0,
            samples = 0.5,
            chargeRatePerHour = null,
        )

        val result = simulator.simulate(request(), emptySnapshot)

        assertThat(result.points).isNotEmpty()
        result.points.forEach { assertThat(it.median).isAtLeast(0.0) }
    }
}

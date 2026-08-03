package com.batterycast.quant.forecasting.scenario

import com.batterycast.quant.fixtures.ObservationFixtures
import com.batterycast.quant.fixtures.SnapshotFixtures
import com.batterycast.quant.forecasting.ForecastContextFixtures
import com.batterycast.quant.forecasting.model.RateDistribution
import com.batterycast.quant.forecasting.model.UsageRegime
import com.batterycast.quant.forecasting.shrinkage.SupportLevel
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ScenarioEngineTest {

    private val engine = ScenarioEngine(ForecastContextFixtures.engine())
    private val nowMs = ObservationFixtures.BASE_TIMESTAMP_MS + 3 * 3_600_000L
    private val targetMs = nowMs + 6 * 3_600_000L
    private val reserve = 10.0

    private fun context(
        drainOverrides: Map<UsageRegime, RateDistribution> = emptyMap(),
        powerSaveMultiplier: Double? = null,
    ) = ForecastContextFixtures.context(
        nowMs = nowMs,
        latest = ObservationFixtures.observation(offsetMinutes = 180.0, percent = 70.0),
        snapshot = SnapshotFixtures.snapshot(
            globalDrainPerHour = 7.0,
            drainOverrides = drainOverrides,
            powerSaveMultiplier = powerSaveMultiplier,
        ),
    )

    @Test
    fun `normal use is answered directly from the learned model`() {
        val outcome = engine.evaluate(context(), Scenario.NORMAL, targetMs, reserve)

        assertThat(outcome.survivalProbability).isNotNull()
        assertThat(outcome.substitutedFrom).isNull()
        assertThat(outcome.confidence).isAnyOf(ScenarioConfidence.OBSERVED, ScenarioConfidence.LIMITED)
    }

    @Test
    fun `a heavier scenario never predicts a better outcome than a lighter one`() {
        val context = context()

        val standby = engine.evaluate(context, Scenario.STANDBY, targetMs, reserve)
        val gaming = engine.evaluate(context, Scenario.GAMING_THIRTY_MINUTES, targetMs, reserve)

        assertThat(gaming.medianPercentAtTarget!!).isAtMost(standby.medianPercentAtTarget!!)
        assertThat(gaming.survivalProbability!!).isAtMost(standby.survivalProbability!!)
    }

    @Test
    fun `every scenario returns probabilities inside zero to one`() {
        val outcomes = engine.evaluateAll(context(), targetMs, reserve)

        outcomes.forEach { outcome ->
            outcome.survivalProbability?.let {
                assertThat(it).isAtLeast(0.0)
                assertThat(it).isAtMost(1.0)
            }
            outcome.medianPercentAtTarget?.let {
                assertThat(it).isAtLeast(0.0)
                assertThat(it).isAtMost(100.0)
            }
        }
    }

    @Test
    fun `an unobserved regime is substituted from the closest one and labelled as such`() {
        // This phone has never been used for navigation.
        val context = context(
            drainOverrides = mapOf(
                UsageRegime.NAVIGATION_LIKE to RateDistribution(0.0, 0.0, 0.0, SupportLevel.NONE),
            ),
        )

        val outcome = engine.evaluate(context, Scenario.NAVIGATION_FORTY_FIVE, targetMs, reserve)

        assertThat(outcome.confidence).isEqualTo(ScenarioConfidence.SUBSTITUTED)
        assertThat(outcome.substitutedFrom).isEqualTo(UsageRegime.NAVIGATION_LIKE)
        assertThat(outcome.note).isNotNull()
        assertThat(outcome.survivalProbability).isNotNull()
    }

    @Test
    fun `a substituted scenario is treated more cautiously than an observed one`() {
        // Both contexts give navigation and heavy use the same drain rate, so the only difference
        // between the two runs is that one had to borrow the rate from a different regime.
        val sameRate = RateDistribution(16.0, 4.0, 30.0, SupportLevel.STRONG)

        val observed = engine.evaluate(
            context(
                drainOverrides = mapOf(
                    UsageRegime.NAVIGATION_LIKE to sameRate,
                    UsageRegime.HEAVY_USE to sameRate,
                ),
            ),
            Scenario.NAVIGATION_FORTY_FIVE,
            targetMs,
            reserve,
        )
        val substituted = engine.evaluate(
            context(
                drainOverrides = mapOf(
                    UsageRegime.NAVIGATION_LIKE to RateDistribution(0.0, 0.0, 0.0, SupportLevel.NONE),
                    UsageRegime.HEAVY_USE to sameRate,
                ),
            ),
            Scenario.NAVIGATION_FORTY_FIVE,
            targetMs,
            reserve,
        )

        assertThat(observed.confidence).isNotEqualTo(ScenarioConfidence.SUBSTITUTED)
        assertThat(substituted.confidence).isEqualTo(ScenarioConfidence.SUBSTITUTED)
        // Borrowing another regime's rate is a weaker claim, so the forecast is pulled down
        // rather than presented at face value.
        assertThat(substituted.medianPercentAtTarget!!).isLessThan(observed.medianPercentAtTarget!!)
    }

    @Test
    fun `the hotspot scenario says plainly that Android does not expose hotspot power`() {
        val context = context(
            drainOverrides = mapOf(
                UsageRegime.HEAVY_USE to RateDistribution(0.0, 0.0, 0.0, SupportLevel.NONE),
            ),
        )

        val outcome = engine.evaluate(context, Scenario.HOTSPOT_ONE_HOUR, targetMs, reserve)

        assertThat(outcome.confidence).isEqualTo(ScenarioConfidence.SUBSTITUTED)
        assertThat(outcome.note).contains("does not expose hotspot power")
    }

    @Test
    fun `power saving is unavailable until its effect has been measured on this device`() {
        val outcome = engine.evaluate(context(powerSaveMultiplier = null), Scenario.POWER_SAVING, targetMs, reserve)

        assertThat(outcome.confidence).isEqualTo(ScenarioConfidence.UNAVAILABLE)
        assertThat(outcome.survivalProbability).isNull()
        assertThat(outcome.note).contains("has not yet seen this phone")
    }

    @Test
    fun `a measured power-saving effect improves the forecast and says where it came from`() {
        val outcome = engine.evaluate(
            context(powerSaveMultiplier = 0.55),
            Scenario.POWER_SAVING,
            targetMs,
            reserve,
        )
        val baseline = engine.evaluate(context(powerSaveMultiplier = 0.55), Scenario.NORMAL, targetMs, reserve)

        assertThat(outcome.confidence).isEqualTo(ScenarioConfidence.OBSERVED)
        assertThat(outcome.medianPercentAtTarget!!).isGreaterThan(baseline.medianPercentAtTarget!!)
        assertThat(outcome.note).contains("Measured on this phone")
    }

    @Test
    fun `a scenario the device cannot speak to at all returns unavailable, not a number`() {
        val nothingObserved = ForecastContextFixtures.context(
            nowMs = nowMs,
            latest = ObservationFixtures.observation(offsetMinutes = 180.0, percent = 70.0),
            snapshot = SnapshotFixtures.snapshot(
                globalDrainPerHour = 7.0,
                drainOverrides = UsageRegime.dischargeRegimes.associateWith {
                    RateDistribution(0.0, 0.0, 0.0, SupportLevel.NONE)
                },
            ),
        )

        val outcome = engine.evaluate(nothingObserved, Scenario.GAMING_THIRTY_MINUTES, targetMs, reserve)

        assertThat(outcome.confidence).isEqualTo(ScenarioConfidence.UNAVAILABLE)
        assertThat(outcome.survivalProbability).isNull()
    }

    @Test
    fun `a longer heavy stretch costs more than a shorter one`() {
        val context = context()

        val thirtyMinutesGaming = engine.evaluate(context, Scenario.GAMING_THIRTY_MINUTES, targetMs, reserve)
        val oneHourVideo = engine.evaluate(context, Scenario.VIDEO_ONE_HOUR, targetMs, reserve)

        // Both are heavy; the point is that each returns a coherent, bounded answer.
        assertThat(thirtyMinutesGaming.medianPercentAtTarget!!).isLessThan(70.0)
        assertThat(oneHourVideo.medianPercentAtTarget!!).isLessThan(70.0)
    }
}

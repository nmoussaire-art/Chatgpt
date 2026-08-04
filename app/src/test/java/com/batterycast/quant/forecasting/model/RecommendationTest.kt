package com.batterycast.quant.forecasting.model

import com.batterycast.quant.core.ui.theme.RiskTone
import com.batterycast.quant.fixtures.ObservationFixtures
import com.batterycast.quant.fixtures.SnapshotFixtures
import com.batterycast.quant.forecasting.ForecastContextFixtures
import com.batterycast.quant.forecasting.ForecastRequest
import com.google.common.truth.Truth.assertThat
import com.batterycast.quant.telemetry.model.PlugType
import org.junit.Test

/**
 * The advice layer.
 *
 * v2's central change is that the app answers "what should I do" before "here are the model
 * outputs". These tests pin the behaviour that makes that safe: the advice always follows the
 * simulated probability, and it refuses to advise at all while the model is still learning.
 */
class RecommendationTest {

    private val engine = ForecastContextFixtures.engine()
    private val nowMs = ObservationFixtures.BASE_TIMESTAMP_MS + 3 * 3_600_000L
    private val hour = 3_600_000L

    private fun forecast(
        drainPerHour: Double,
        startPercent: Double,
        charging: Boolean = false,
        maturity: DataMaturity = DataMaturity.PERSONALISED,
        hoursAhead: Long = 4,
    ): BatteryForecast {
        val context = ForecastContextFixtures.context(
            nowMs = nowMs,
            latest = ObservationFixtures.observation(
                offsetMinutes = 180.0,
                percent = startPercent,
                charging = charging,
            ),
            snapshot = SnapshotFixtures.snapshot(globalDrainPerHour = drainPerHour),
            maturity = maturity,
        )
        return engine.buildForecast(
            context,
            ForecastRequest(
                horizonMs = hoursAhead * hour,
                reservePercent = 10.0,
                targetMs = nowMs + hoursAhead * hour,
                targetLabel = "your event",
            ),
            nowMs,
        )
    }

    @Test
    fun `a comfortable forecast recommends nothing at all`() {
        val recommendation = forecast(drainPerHour = 2.0, startPercent = 95.0).recommendation

        assertThat(recommendation.tone).isEqualTo(RiskTone.SECURE)
        assertThat(recommendation.action).isEqualTo(RecommendedAction.NONE)
        assertThat(recommendation.headline).contains("fine")
    }

    @Test
    fun `a forecast that will not reach the target says to charge`() {
        val recommendation = forecast(drainPerHour = 30.0, startPercent = 25.0).recommendation

        assertThat(recommendation.tone).isEqualTo(RiskTone.CRITICAL)
        assertThat(recommendation.action).isEqualTo(RecommendedAction.CHARGE_NOW)
        assertThat(recommendation.headline).contains("Charge")
    }

    @Test
    fun `the model refuses to advise while it is still learning`() {
        val recommendation = forecast(
            drainPerHour = 30.0,
            startPercent = 25.0,
            maturity = DataMaturity.PRELIMINARY,
        ).recommendation

        assertThat(recommendation.action).isEqualTo(RecommendedAction.STILL_LEARNING)
        assertThat(recommendation.headline).contains("Early")
        // Crucially, it does not tell the user to charge on evidence it does not have.
        assertThat(recommendation.action).isNotEqualTo(RecommendedAction.CHARGE_NOW)
    }

    @Test
    fun `a charging phone that is already safe is told it can unplug`() {
        val recommendation = forecast(
            drainPerHour = 3.0,
            startPercent = 88.0,
            charging = true,
        ).recommendation

        assertThat(recommendation.action).isEqualTo(RecommendedAction.CAN_UNPLUG)
        assertThat(recommendation.headline).contains("unplug")
    }

    @Test
    fun `advice never contradicts the probability beside it`() {
        listOf(2.0, 8.0, 16.0, 30.0).forEach { rate ->
            val result = forecast(drainPerHour = rate, startPercent = 50.0)
            val probability = result.target?.survivalProbability ?: return@forEach

            assertThat(result.recommendation.tone).isEqualTo(RiskTone.forProbability(probability))
        }
    }

    @Test
    fun `worse odds never produce softer advice`() {
        val comfortable = forecast(drainPerHour = 2.0, startPercent = 90.0)
        val marginal = forecast(drainPerHour = 12.0, startPercent = 50.0)
        val doomed = forecast(drainPerHour = 40.0, startPercent = 25.0)

        val severity = listOf(comfortable, marginal, doomed).map { it.recommendation.tone.ordinal }

        assertThat(severity).isInOrder()
    }

    // --- The zero-floor wording, which v1 rendered as a bare "0%" ---

    @Test
    fun `an outcome that can reach empty says so rather than printing zero`() {
        val outcome = TargetOutcome(
            label = "event",
            targetMs = nowMs,
            reservePercent = 10.0,
            survivalProbability = 0.3,
            median = 8.0,
            p10 = 0.0,
            p25 = 3.0,
            p75 = 18.0,
            p90 = 26.0,
            conservative = 0.0,
        )

        val text = Recommendations.expectedRange(outcome)!!

        assertThat(text).contains("empty")
        assertThat(text).doesNotContain("0%")
    }

    @Test
    fun `an ordinary range reads as a range`() {
        val outcome = TargetOutcome(
            label = "event",
            targetMs = nowMs,
            reservePercent = 10.0,
            survivalProbability = 0.8,
            median = 22.0,
            p10 = 12.0,
            p25 = 17.0,
            p75 = 28.0,
            p90 = 35.0,
            conservative = 12.0,
        )

        assertThat(Recommendations.expectedRange(outcome)).isEqualTo("Usually between 12% and 35%")
    }

    @Test
    fun `no target means no range`() {
        assertThat(Recommendations.expectedRange(null)).isNull()
    }

    // --- Risk tone thresholds, which drive both the colour and the wording ---

    @Test
    fun `risk tones partition the probability range without gaps`() {
        assertThat(RiskTone.forProbability(1.0)).isEqualTo(RiskTone.SECURE)
        assertThat(RiskTone.forProbability(0.85)).isEqualTo(RiskTone.SECURE)
        assertThat(RiskTone.forProbability(0.84)).isEqualTo(RiskTone.TIGHT)
        assertThat(RiskTone.forProbability(0.60)).isEqualTo(RiskTone.TIGHT)
        assertThat(RiskTone.forProbability(0.59)).isEqualTo(RiskTone.AT_RISK)
        assertThat(RiskTone.forProbability(0.35)).isEqualTo(RiskTone.AT_RISK)
        assertThat(RiskTone.forProbability(0.34)).isEqualTo(RiskTone.CRITICAL)
        assertThat(RiskTone.forProbability(0.0)).isEqualTo(RiskTone.CRITICAL)
    }

    @Test
    fun `tone severity increases monotonically as the probability falls`() {
        val ordinals = listOf(0.95, 0.75, 0.5, 0.2).map { RiskTone.forProbability(it).ordinal }

        assertThat(ordinals).isInOrder()
    }

    // --- Plug naming, which v1 rendered as "Ac" and "Usb" ---

    @Test
    fun `plug types render as proper acronyms`() {
        assertThat(PlugType.AC.displayName).isEqualTo("AC")
        assertThat(PlugType.USB.displayName).isEqualTo("USB")
        assertThat(PlugType.WIRELESS.displayName).isEqualTo("Wireless")
        PlugType.entries.forEach { assertThat(it.displayName).isNotEmpty() }
    }
}

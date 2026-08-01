package com.ontimequant

import com.google.common.truth.Truth.assertThat
import com.ontimequant.forecast.ExternalSignals
import com.ontimequant.forecast.ForecastEngine
import com.ontimequant.forecast.ForecastSettings
import com.ontimequant.forecast.HistoryMapper
import com.ontimequant.forecast.JourneyForecast
import com.ontimequant.forecast.demo.DemoScenario
import com.ontimequant.model.DataProvenance
import com.ontimequant.notifications.NotificationDecision
import com.ontimequant.notifications.NotificationKind
import com.ontimequant.notifications.NotificationMemory
import com.ontimequant.notifications.NotificationPolicy
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * The notification policy decides when to interrupt someone. These tests are the contract
 * for "restrained": they assert as hard on the cases that must stay *silent* as on the
 * ones that must fire.
 */
class NotificationPolicyTest {

    private val zone = DemoScenario.ZONE
    private val day: LocalDate = LocalDate.of(2025, 11, 4)
    private val now: Instant = day.atTime(6, 45).atZone(zone).toInstant()

    private fun forecast(confidence: Double = 0.90): JourneyForecast {
        val matrix = DemoScenario.routeMatrix(
            windowStart = day.atTime(6, 30).atZone(zone).toInstant(),
            windowEnd = day.atTime(8, 15).atZone(zone).toInstant(),
            stepMinutes = 5, zone = zone, fetchedAt = now,
        )
        return ForecastEngine().forecast(
            appointment = DemoScenario.appointment(day, zone, confidence),
            routeMatrix = matrix,
            signals = ExternalSignals(
                weather = DemoScenario.weather(now),
                weatherProvenance = DataProvenance.DEMO,
            ),
            history = HistoryMapper.toLearningHistory(DemoScenario.completedTrips(day, zone)),
            settings = ForecastSettings(confidenceTarget = confidence, simulationDraws = 2000),
            now = now,
        )
    }

    private val blankMemory = NotificationMemory(null, null, null, null, null)

    @Test
    fun `a fresh forecast an hour out sends one quiet summary`() {
        val f = forecast()
        val decision = NotificationPolicy.decide(
            forecast = f,
            memory = blankMemory,
            now = f.recommendedDeparture!!.minus(Duration.ofMinutes(40)),
            remindersEnabled = true,
            changeAlertsEnabled = true,
        )
        assertThat(decision).isInstanceOf(NotificationDecision.Send::class.java)
        assertThat((decision as NotificationDecision.Send).kind).isEqualTo(NotificationKind.ADVANCE_SUMMARY)
    }

    @Test
    fun `an imminent departure sends the departure alert`() {
        val f = forecast()
        val decision = NotificationPolicy.decide(
            forecast = f,
            memory = blankMemory,
            now = f.recommendedDeparture!!.minus(Duration.ofMinutes(6)),
            remindersEnabled = true,
            changeAlertsEnabled = true,
        )
        assertThat((decision as NotificationDecision.Send).kind)
            .isEqualTo(NotificationKind.DEPARTURE_APPROACHING)
        assertThat(decision.title).contains("6 minutes")
    }

    @Test
    fun `the departure alert is not repeated`() {
        val f = forecast()
        val decision = NotificationPolicy.decide(
            forecast = f,
            memory = blankMemory.copy(
                lastSent = now,
                lastKind = NotificationKind.DEPARTURE_APPROACHING,
                lastRecommendedDeparture = f.recommendedDeparture,
                lastOnTimeProbability = f.onTimeProbability,
            ),
            now = f.recommendedDeparture!!.minus(Duration.ofMinutes(4)),
            remindersEnabled = true,
            changeAlertsEnabled = true,
        )
        assertThat(decision).isEqualTo(NotificationDecision.Silent)
    }

    @Test
    fun `an unchanged forecast inside the cooldown stays silent`() {
        val f = forecast()
        val decision = NotificationPolicy.decide(
            forecast = f,
            memory = NotificationMemory(
                lastSent = now.minus(Duration.ofMinutes(3)),
                lastRecommendedDeparture = f.recommendedDeparture,
                lastOnTimeProbability = f.onTimeProbability,
                lastRiskLevel = f.trafficRisk,
                lastKind = NotificationKind.ADVANCE_SUMMARY,
            ),
            now = now,
            remindersEnabled = true,
            changeAlertsEnabled = true,
        )
        assertThat(decision).isEqualTo(NotificationDecision.Silent)
    }

    @Test
    fun `a departure moving three minutes earlier is worth an alert`() {
        val f = forecast()
        val decision = NotificationPolicy.decide(
            forecast = f,
            memory = NotificationMemory(
                lastSent = now.minus(Duration.ofHours(1)),
                lastRecommendedDeparture = f.recommendedDeparture!!.plus(Duration.ofMinutes(4)),
                lastOnTimeProbability = f.onTimeProbability,
                lastRiskLevel = f.trafficRisk,
                lastKind = NotificationKind.ADVANCE_SUMMARY,
            ),
            now = now,
            remindersEnabled = true,
            changeAlertsEnabled = true,
        )
        assertThat((decision as NotificationDecision.Send).kind).isEqualTo(NotificationKind.RISK_INCREASED)
        assertThat(decision.title).contains("increased")
    }

    @Test
    fun `a departure moving two minutes is not worth an alert`() {
        val f = forecast()
        val decision = NotificationPolicy.decide(
            forecast = f,
            memory = NotificationMemory(
                lastSent = now.minus(Duration.ofHours(1)),
                lastRecommendedDeparture = f.recommendedDeparture!!.plus(Duration.ofMinutes(2)),
                lastOnTimeProbability = f.onTimeProbability,
                lastRiskLevel = f.trafficRisk,
                lastKind = NotificationKind.ADVANCE_SUMMARY,
            ),
            now = now,
            remindersEnabled = true,
            changeAlertsEnabled = true,
        )
        assertThat(decision).isEqualTo(NotificationDecision.Silent)
    }

    @Test
    fun `gaining time is reported as good news`() {
        val f = forecast()
        val decision = NotificationPolicy.decide(
            forecast = f,
            memory = NotificationMemory(
                lastSent = now.minus(Duration.ofHours(1)),
                lastRecommendedDeparture = f.recommendedDeparture!!.minus(Duration.ofMinutes(6)),
                lastOnTimeProbability = f.onTimeProbability,
                lastRiskLevel = f.trafficRisk,
                lastKind = NotificationKind.ADVANCE_SUMMARY,
            ),
            now = now,
            remindersEnabled = true,
            changeAlertsEnabled = true,
        )
        assertThat((decision as NotificationDecision.Send).kind)
            .isEqualTo(NotificationKind.MORE_TIME_AVAILABLE)
        assertThat(decision.title).contains("later")
    }

    @Test
    fun `crossing below the confidence target is always reported`() {
        val f = forecast(0.95)
        // Force the situation: the stored probability was above target, now it is not.
        // The risk level is held constant so only the target crossing can fire.
        val degraded = f.copy(
            recommendedDeparture = null,
            recommended = null,
            bestEffort = f.bestEffort.copy(onTimeProbability = 0.62),
            feasible = false,
        )
        val decision = NotificationPolicy.decide(
            forecast = degraded,
            memory = NotificationMemory(
                lastSent = now.minus(Duration.ofHours(2)),
                lastRecommendedDeparture = null,
                lastOnTimeProbability = 0.97,
                lastRiskLevel = degraded.trafficRisk,
                lastKind = NotificationKind.ADVANCE_SUMMARY,
            ),
            now = now,
            remindersEnabled = true,
            changeAlertsEnabled = true,
        )
        assertThat((decision as NotificationDecision.Send).kind).isEqualTo(NotificationKind.BELOW_TARGET)
    }

    @Test
    fun `turning change alerts off silences everything except the departure reminder`() {
        val f = forecast()
        val recommended = f.recommendedDeparture!!
        val silent = NotificationPolicy.decide(
            forecast = f,
            memory = NotificationMemory(
                lastSent = now.minus(Duration.ofHours(2)),
                lastRecommendedDeparture = recommended.plus(Duration.ofMinutes(20)),
                lastOnTimeProbability = f.onTimeProbability,
                lastRiskLevel = f.trafficRisk,
                lastKind = NotificationKind.ADVANCE_SUMMARY,
            ),
            now = now,
            remindersEnabled = true,
            changeAlertsEnabled = false,
        )
        assertThat(silent).isEqualTo(NotificationDecision.Silent)

        val reminder = NotificationPolicy.decide(
            forecast = f,
            memory = blankMemory,
            now = recommended.minus(Duration.ofMinutes(3)),
            remindersEnabled = true,
            changeAlertsEnabled = false,
        )
        assertThat((reminder as NotificationDecision.Send).kind)
            .isEqualTo(NotificationKind.DEPARTURE_APPROACHING)
    }

    @Test
    fun `turning reminders off silences the departure alert`() {
        val f = forecast()
        val decision = NotificationPolicy.decide(
            forecast = f,
            memory = blankMemory.copy(lastSent = now.minus(Duration.ofHours(3))),
            now = f.recommendedDeparture!!.minus(Duration.ofMinutes(3)),
            remindersEnabled = false,
            changeAlertsEnabled = true,
        )
        assertThat(decision).isEqualTo(NotificationDecision.Silent)
    }

    @Test
    fun `a missing destination produces a specific, actionable message`() {
        val decision = NotificationPolicy.missingDestination("Board meeting", "Tomorrow at 09:00")
        assertThat(decision.kind).isEqualTo(NotificationKind.MISSING_DESTINATION)
        assertThat(decision.body).contains("no location")
        assertThat(decision.title).contains("Board meeting")
    }
}

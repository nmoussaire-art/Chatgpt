package com.ontimequant.forecast

import com.google.common.truth.Truth.assertThat
import com.ontimequant.forecast.demo.DemoScenario
import com.ontimequant.model.DataProvenance
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.roundToInt

/**
 * Verifies the demo journey produces a credible, steeply-falling departure curve.
 *
 * The brief's target shape is roughly 95% / 87% / 72% / 49% at 07:15 / 07:20 / 07:25 /
 * 07:30 for an 08:00 arrival. Those exact numbers cannot be asserted, because they are
 * *outputs* of the engine, not stored values. What is asserted is the shape:
 * monotone decline, a very high start, a sub-60% end, and a steepening middle.
 */
class DemoCalibrationTest {

    private val day: LocalDate = LocalDate.of(2025, 11, 4) // a Tuesday

    private fun buildForecast(confidence: Double = 0.90) = run {
        val zone = DemoScenario.ZONE
        val appointment = DemoScenario.appointment(day, zone, confidence)
        val now = day.atTime(6, 40).atZone(zone).toInstant()
        val matrix = DemoScenario.routeMatrix(
            windowStart = day.atTime(6, 40).atZone(zone).toInstant(),
            windowEnd = day.atTime(8, 20).atZone(zone).toInstant(),
            stepMinutes = 5,
            zone = zone,
            fetchedAt = now,
        )
        val trips = DemoScenario.completedTrips(day, zone)
        val history = HistoryMapper.toLearningHistory(trips)
        ForecastEngine().forecast(
            appointment = appointment,
            routeMatrix = matrix,
            signals = ExternalSignals(
                weather = DemoScenario.weather(now),
                weatherProvenance = DataProvenance.DEMO,
            ),
            history = history,
            settings = ForecastSettings(confidenceTarget = confidence, simulationDraws = 6000),
            now = now,
        )
    }

    private fun probabilityAt(hour: Int, minute: Int): Double {
        val forecast = buildForecast()
        val target = day.atTime(LocalTime.of(hour, minute)).atZone(DemoScenario.ZONE).toInstant()
        val inputsCurve = forecast.curve
        val idx = inputsCurve.nearestIndex(target)
        return inputsCurve.probabilities[idx]
    }

    @Test
    fun `demo curve declines steeply through the morning peak`() {
        val forecast = buildForecast()
        val zone = DemoScenario.ZONE
        val points = listOf(7 to 15, 7 to 20, 7 to 25, 7 to 30).map { (h, m) ->
            val t = day.atTime(h, m).atZone(zone).toInstant()
            val idx = forecast.curve.nearestIndex(t)
            (h to m) to forecast.curve.probabilities[idx]
        }
        // Printed so the calibration is auditable when the profile is tuned.
        points.forEach { (time, p) ->
            println("Leave at ${time.first}:${"%02d".format(time.second)} -> ${(p * 100).roundToInt()}%")
        }
        val values = points.map { it.second }

        // Strictly declining.
        for (i in 1 until values.size) {
            assertThat(values[i]).isLessThan(values[i - 1])
        }
        // High confidence early, clearly compromised late.
        assertThat(values.first()).isAtLeast(0.90)
        assertThat(values.last()).isAtMost(0.62)
        // The cost of waiting accelerates: each 5-minute step must cost more than the last.
        val drops = values.zipWithNext { a, b -> a - b }
        assertThat(drops[1]).isGreaterThan(drops[0])
        assertThat(drops[2]).isGreaterThan(drops[1])
    }

    @Test
    fun `demo recommends a departure inside the morning window`() {
        val forecast = buildForecast()
        val recommended = requireNotNull(forecast.recommendedDeparture)
        val local = recommended.atZone(DemoScenario.ZONE).toLocalTime()
        println("Recommended departure: $local  (${(forecast.onTimeProbability * 100).roundToInt()}%)")
        println("Expected arrival: ${forecast.recommended!!.expectedArrival.atZone(DemoScenario.ZONE).toLocalTime()}")
        println("80% range: ${forecast.recommended.percentiles[10]!!.atZone(DemoScenario.ZONE).toLocalTime()} – " +
            "${forecast.recommended.percentiles[90]!!.atZone(DemoScenario.ZONE).toLocalTime()}")
        println("P(>5 late) = ${(forecast.recommended.probabilityMoreThan5LateSeconds * 100).roundToInt()}%")
        println("P(>10 late) = ${(forecast.recommended.probabilityMoreThan10LateSeconds * 100).roundToInt()}%")
        println("Personalisation: ${forecast.personalization} — ${forecast.personalizationDetail}")
        assertThat(local).isGreaterThan(LocalTime.of(6, 45))
        assertThat(local).isLessThan(LocalTime.of(7, 40))
        assertThat(forecast.onTimeProbability).isAtLeast(0.90)
    }

    @Test
    fun `higher confidence target recommends an earlier departure`() {
        val relaxed = buildForecast(0.70).recommendedDeparture!!
        val safe = buildForecast(0.90).recommendedDeparture!!
        val verySafe = buildForecast(0.95).recommendedDeparture!!
        assertThat(safe).isLessThan(relaxed)
        assertThat(verySafe).isLessThan(safe)
    }

    @Test
    fun `demo history reaches partial personalization`() {
        val forecast = buildForecast()
        assertThat(forecast.personalization.name).isAnyOf("PARTIALLY_PERSONALIZED", "PERSONALIZED")
    }
}

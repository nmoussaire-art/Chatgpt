package com.ontimequant.ui.preview

import com.ontimequant.data.prefs.UserSettings
import com.ontimequant.forecast.CalibrationAnalyser
import com.ontimequant.forecast.AccuracyReport
import com.ontimequant.forecast.Backtester
import com.ontimequant.forecast.ExternalSignals
import com.ontimequant.forecast.ForecastEngine
import com.ontimequant.forecast.ForecastSettings
import com.ontimequant.forecast.HistoryMapper
import com.ontimequant.forecast.Insight
import com.ontimequant.forecast.InsightEngine
import com.ontimequant.forecast.JourneyForecast
import com.ontimequant.forecast.demo.DemoScenario
import com.ontimequant.model.CompletedTrip
import com.ontimequant.model.DataProvenance
import com.ontimequant.ui.home.HomeUiState
import java.time.Instant
import java.time.LocalDate

/**
 * Data for `@Preview` composables and for the Compose UI test.
 *
 * Note that this runs the **real engine** over the **real demo scenario**. The previews
 * are therefore not mock-ups: the departure time and probabilities you see in Android
 * Studio are the same numbers the app computes at runtime. If the model changes, the
 * previews change.
 */
object PreviewData {

    private val day: LocalDate = LocalDate.of(2025, 11, 4)
    private val zone = DemoScenario.ZONE
    val now: Instant = day.atTime(6, 40).atZone(zone).toInstant()

    val trips: List<CompletedTrip> by lazy { DemoScenario.completedTrips(day, zone, count = 24) }

    val forecast: JourneyForecast by lazy {
        val matrix = DemoScenario.routeMatrix(
            windowStart = day.atTime(6, 30).atZone(zone).toInstant(),
            windowEnd = day.atTime(8, 15).atZone(zone).toInstant(),
            stepMinutes = 5,
            zone = zone,
            fetchedAt = now,
        )
        ForecastEngine().forecast(
            appointment = DemoScenario.appointment(day, zone, 0.90),
            routeMatrix = matrix,
            signals = ExternalSignals(
                weather = DemoScenario.weather(now),
                weatherProvenance = DataProvenance.DEMO,
            ),
            history = HistoryMapper.toLearningHistory(trips),
            settings = ForecastSettings(confidenceTarget = 0.90, simulationDraws = 3000),
            now = now,
        )
    }

    val accuracy: AccuracyReport by lazy {
        CalibrationAnalyser.analyse(Backtester.run(trips), DataProvenance.DEMO)
    }

    val insights: List<Insight> by lazy {
        InsightEngine.generate(
            trips = trips,
            accuracy = accuracy,
            preparation = HistoryMapper.preparationFor(trips, DemoScenario.HOME.id),
        )
    }

    fun homeState(): HomeUiState = HomeUiState(
        loading = false,
        appointments = listOf(DemoScenario.appointment(day, zone, 0.90)),
        selected = DemoScenario.appointment(day, zone, 0.90),
        forecast = forecast,
        notices = forecast.notices,
        settings = UserSettings(demoMode = true, use24HourClock = true),
        demoMode = true,
        now = now,
    )
}

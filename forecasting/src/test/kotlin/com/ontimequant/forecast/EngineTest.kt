package com.ontimequant.forecast

import com.google.common.truth.Truth.assertThat
import com.ontimequant.forecast.demo.DemoScenario
import com.ontimequant.model.DataProvenance
import com.ontimequant.model.EventPressure
import com.ontimequant.model.GeoPoint
import com.ontimequant.model.NearbyEvent
import com.ontimequant.model.PersonalizationLevel
import com.ontimequant.model.ProviderNotice
import com.ontimequant.model.RouteMatrix
import com.ontimequant.model.WeatherSnapshot
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class ForecastEngineTest {

    private val zone = DemoScenario.ZONE
    private val day: LocalDate = LocalDate.of(2025, 11, 4)
    private val now: Instant = day.atTime(6, 30).atZone(zone).toInstant()

    private fun matrix(provenance: DataProvenance = DataProvenance.DEMO, fetchedAt: Instant = now): RouteMatrix =
        DemoScenario.routeMatrix(
            windowStart = day.atTime(6, 30).atZone(zone).toInstant(),
            windowEnd = day.atTime(8, 10).atZone(zone).toInstant(),
            stepMinutes = 5, zone = zone, fetchedAt = fetchedAt,
        ).copy(provenance = provenance)

    private fun run(
        history: LearningHistory = LearningHistory(),
        signals: ExternalSignals = ExternalSignals(),
        routeMatrix: RouteMatrix = matrix(),
        confidence: Double = 0.90,
    ) = ForecastEngine().forecast(
        appointment = DemoScenario.appointment(day, zone, confidence),
        routeMatrix = routeMatrix,
        signals = signals,
        history = history,
        settings = ForecastSettings(confidenceTarget = confidence, simulationDraws = 3000),
        now = now,
    )

    @Test
    fun `a first-day user gets a preliminary but usable forecast`() {
        val forecast = run()
        assertThat(forecast.personalization).isEqualTo(PersonalizationLevel.PRELIMINARY)
        assertThat(forecast.personalizationDetail).contains("Preliminary")
        assertThat(forecast.recommendedDeparture).isNotNull()
        assertThat(forecast.onTimeProbability).isAtLeast(0.90)
    }

    @Test
    fun `missing weather and event data does not crash and is reported honestly`() {
        val forecast = run(signals = ExternalSignals(weather = null, eventPressure = EventPressure.NONE))
        assertThat(forecast.notices.map { it.source }).containsAtLeast("Weather", "Events")
        assertThat(forecast.notices.first { it.source == "Weather" }.message).contains("No weather adjustment")
        val weatherComponent = forecast.components.first { it.id == "weather" }
        assertThat(weatherComponent.provenance).isEqualTo(DataProvenance.UNAVAILABLE)
        assertThat(weatherComponent.valueSeconds).isEqualTo(0.0)
    }

    @Test
    fun `cached routing data is labelled and widens the forecast`() {
        val live = run(routeMatrix = matrix(DataProvenance.LIVE))
        val cached = run(routeMatrix = matrix(DataProvenance.CACHED, fetchedAt = now.minusSeconds(5400)))
        assertThat(cached.routeProvenance).isEqualTo(DataProvenance.CACHED)
        assertThat(cached.bestEffort.intervalWidthSeconds).isGreaterThan(live.bestEffort.intervalWidthSeconds)
        assertThat(cached.components.first { it.id == "traffic" }.sourceLabel).contains("cached")
    }

    @Test
    fun `every displayed component is attributed to a source`() {
        val forecast = run(
            signals = ExternalSignals(
                weather = DemoScenario.weather(now),
                weatherProvenance = DataProvenance.DEMO,
            ),
        )
        assertThat(forecast.components).isNotEmpty()
        forecast.components.forEach {
            assertThat(it.sourceLabel).isNotEmpty()
            assertThat(it.explanation).isNotEmpty()
        }
        val ids = forecast.components.map { it.id }
        assertThat(ids).containsAtLeast(
            "free_flow", "traffic", "bias", "uncertainty", "weather", "events",
            "preparation", "parking", "walking", "entry_buffer",
        )
    }

    @Test
    fun `personal history changes the recommendation relative to a cold start`() {
        val trips = DemoScenario.completedTrips(day, zone)
        val cold = run()
        val warm = run(history = HistoryMapper.toLearningHistory(trips))
        assertThat(warm.personalization).isNotEqualTo(cold.personalization)
        assertThat(warm.recommendedDeparture).isNotEqualTo(cold.recommendedDeparture)
    }

    @Test
    fun `an unreachable deadline yields an honest infeasible state`() {
        val impossible = DemoScenario.appointment(day, zone, 0.95)
            .copy(startTime = now.plusSeconds(300))
        val forecast = ForecastEngine().forecast(
            appointment = impossible,
            routeMatrix = matrix(),
            signals = ExternalSignals(),
            history = LearningHistory(),
            settings = ForecastSettings(confidenceTarget = 0.95, simulationDraws = 2000),
            now = now,
        )
        assertThat(forecast.feasible).isFalse()
        assertThat(forecast.recommendedDeparture).isNull()
        assertThat(forecast.summary).contains("below your")
        assertThat(forecast.bestEffort.onTimeProbability).isAtMost(0.95)
    }

    @Test
    fun `the entry buffer moves the deadline and the recommendation earlier`() {
        val base = DemoScenario.appointment(day, zone, 0.90, entryBufferMinutes = 0)
        val buffered = base.copy(entryBufferMinutes = 15)
        assertThat(buffered.requiredArrival).isEqualTo(base.requiredArrival.minusSeconds(900))
        val engine = ForecastEngine()
        val settings = ForecastSettings(confidenceTarget = 0.90, simulationDraws = 2500)
        val a = engine.forecast(base, matrix(), ExternalSignals(), LearningHistory(), settings, now)
        val b = engine.forecast(buffered, matrix(), ExternalSignals(), LearningHistory(), settings, now)
        assertThat(b.recommendedDeparture!!).isLessThan(a.recommendedDeparture!!)
    }

    @Test
    fun `simulation draws meet the documented minimum`() {
        val forecast = run()
        assertThat(forecast.simulationDraws).isAtLeast(2000)
        assertThat(forecast.bestEffort.draws).isAtLeast(2000)
    }

    @Test
    fun `the same inputs always produce the same forecast`() {
        val a = run()
        val b = run()
        assertThat(a.recommendedDeparture).isEqualTo(b.recommendedDeparture)
        assertThat(a.onTimeProbability).isEqualTo(b.onTimeProbability)
        assertThat(a.curve.probabilities).isEqualTo(b.curve.probabilities)
    }

    @Test
    fun `notices from providers are surfaced to the user`() {
        val notice = ProviderNotice("Routing", DataProvenance.CACHED, "Live traffic unavailable.")
        val forecast = run(routeMatrix = matrix(DataProvenance.CACHED).copy(notice = notice))
        assertThat(forecast.notices).contains(notice)
    }
}

class TimeZoneTest {

    @Test
    fun `required arrival is derived in the appointments own zone`() {
        val london = ZoneId.of("Europe/London")
        val appointment = DemoScenario.appointment(LocalDate.of(2025, 6, 10), london, 0.9, entryBufferMinutes = 10)
        val start = ZonedDateTime.of(2025, 6, 10, 8, 0, 0, 0, london).toInstant()
        assertThat(appointment.startTime).isEqualTo(start)
        assertThat(appointment.requiredArrival).isEqualTo(start.minusSeconds(600))
        assertThat(appointment.localStart().hour).isEqualTo(8)
    }

    @Test
    fun `an appointment across a spring-forward transition keeps the right wall clock`() {
        // Europe/London springs forward at 01:00 UTC on 30 March 2025.
        val london = ZoneId.of("Europe/London")
        val appointment = DemoScenario.appointment(LocalDate.of(2025, 3, 30), london, 0.9)
        assertThat(appointment.localStart().hour).isEqualTo(8)
        // 08:00 BST is 07:00 UTC, one hour earlier in absolute terms than on the day before.
        val dayBefore = DemoScenario.appointment(LocalDate.of(2025, 3, 29), london, 0.9)
        assertThat(appointment.startTime.epochSecond - dayBefore.startTime.epochSecond)
            .isEqualTo(23 * 3600L)
    }

    @Test
    fun `an appointment across an autumn transition keeps the right wall clock`() {
        val london = ZoneId.of("Europe/London")
        val before = DemoScenario.appointment(LocalDate.of(2025, 10, 25), london, 0.9)
        val after = DemoScenario.appointment(LocalDate.of(2025, 10, 26), london, 0.9)
        assertThat(after.localStart().hour).isEqualTo(8)
        assertThat(after.startTime.epochSecond - before.startTime.epochSecond).isEqualTo(25 * 3600L)
    }

    @Test
    fun `a journey crossing time zones is solved on the absolute timeline`() {
        val dubai = ZoneId.of("Asia/Dubai")
        val appointment = DemoScenario.appointment(LocalDate.of(2025, 11, 4), dubai, 0.9)
            .copy(zone = ZoneId.of("Asia/Muscat"))
        // Muscat and Dubai share UTC+4, so the wall clock is unchanged.
        assertThat(appointment.localStart().hour).isEqualTo(8)
    }
}

class EventPressureTest {

    private val destination = GeoPoint(24.4874, 54.3609)
    private val travelStart = Instant.parse("2025-11-04T03:00:00Z")
    private val travelEnd = travelStart.plusSeconds(1800)

    private fun event(
        lat: Double, lon: Double, startOffsetMinutes: Long, capacity: Int? = 20_000,
    ) = NearbyEvent(
        id = "e", name = "Concert", venueName = "Arena",
        venuePoint = GeoPoint(lat, lon),
        startTime = travelStart.plusSeconds(startOffsetMinutes * 60),
        endTime = travelStart.plusSeconds((startOffsetMinutes + 150) * 60),
        venueCapacity = capacity,
    )

    private fun compute(events: List<NearbyEvent>) = EventPressureModel.compute(
        events, DemoScenario.POLYLINE, destination, travelStart, travelEnd, DataProvenance.LIVE,
    )

    @Test
    fun `no events means no pressure`() {
        assertThat(compute(emptyList()).score).isEqualTo(0.0)
    }

    @Test
    fun `a distant event contributes nothing`() {
        val far = compute(listOf(event(25.6, 55.9, 20)))
        assertThat(far.score).isLessThan(0.02)
    }

    @Test
    fun `a large event next to the route just before it starts scores highly`() {
        val near = compute(listOf(event(24.4915, 54.3806, 20)))
        assertThat(near.score).isGreaterThan(0.3)
        assertThat(near.contributors).isNotEmpty()
    }

    @Test
    fun `pressure decays as the event moves away from the route`() {
        val close = compute(listOf(event(24.4915, 54.3810, 20))).score
        val medium = compute(listOf(event(24.5100, 54.3810, 20))).score
        val far = compute(listOf(event(24.5600, 54.3810, 20))).score
        assertThat(close).isGreaterThan(medium)
        assertThat(medium).isGreaterThan(far)
    }

    @Test
    fun `an event long past its end contributes nothing`() {
        assertThat(compute(listOf(event(24.4915, 54.3806, -600))).score).isLessThan(0.02)
    }

    @Test
    fun `overlapping events combine but saturate at one`() {
        val many = (0 until 12).map { event(24.4915 + it * 0.0001, 54.3806, 15) }
        val score = compute(many).score
        assertThat(score).isAtMost(1.0)
        assertThat(score).isGreaterThan(compute(listOf(event(24.4915, 54.3806, 15))).score)
    }

    @Test
    fun `a bigger venue produces more pressure than a small one`() {
        val big = compute(listOf(event(24.4915, 54.3806, 20, capacity = 50_000))).score
        val small = compute(listOf(event(24.4915, 54.3806, 20, capacity = 400))).score
        assertThat(big).isGreaterThan(small)
    }

    @Test
    fun `missing capacity still yields a bounded score`() {
        val score = compute(listOf(event(24.4915, 54.3806, 20, capacity = null))).score
        assertThat(score).isAtLeast(0.0)
        assertThat(score).isAtMost(1.0)
    }

    @Test
    fun `event pressure widens uncertainty before it shifts the mean`() {
        val model = TravelModelFitter.fit(Fixture.observations(30, 0.0, 0.2))
        val mild = model.sampler(Fixture.context(event = 0.3), 1200.0, 900.0)
        val heavy = model.sampler(Fixture.context(event = 0.95), 1200.0, 900.0)
        assertThat(mild.eventLogAdjustment).isEqualTo(0.0) // below the threshold: risk only
        assertThat(mild.effectiveScale).isGreaterThan(
            model.sampler(Fixture.context(event = 0.0), 1200.0, 900.0).effectiveScale,
        )
        assertThat(heavy.eventLogAdjustment).isGreaterThan(0.0)
    }
}

class WeatherSnapshotTest {

    @Test
    fun `severity is bounded and rises with adversity`() {
        val clear = WeatherSnapshot(Instant.EPOCH, 0.0, 0.0, 12_000.0, 5.0, 24.0)
        val storm = WeatherSnapshot(Instant.EPOCH, 0.95, 9.0, 500.0, 70.0, 3.0, severeAlert = true)
        assertThat(clear.severityIndex()).isAtLeast(0.0)
        assertThat(storm.severityIndex()).isAtMost(1.0)
        assertThat(storm.severityIndex()).isGreaterThan(clear.severityIndex())
    }

    @Test
    fun `a partially populated snapshot still produces a severity`() {
        val partial = WeatherSnapshot(Instant.EPOCH, 0.8, null, null, null, null)
        assertThat(partial.severityIndex()).isGreaterThan(0.0)
        assertThat(partial.severityIndex()).isAtMost(1.0)
    }

    @Test
    fun `an entirely empty snapshot yields zero severity rather than a guess`() {
        val empty = WeatherSnapshot(Instant.EPOCH, null, null, null, null, null)
        assertThat(empty.severityIndex()).isEqualTo(0.0)
    }
}

package com.ontimequant.forecast

import com.google.common.truth.Truth.assertThat
import com.ontimequant.model.DataProvenance
import com.ontimequant.model.DayType
import com.ontimequant.model.TimeBucket
import com.ontimequant.model.TrafficRegime
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant

/** Shared fixtures for the simulation and solver tests. */
internal object Fixture {

    val NOW: Instant = Instant.parse("2025-11-04T02:30:00Z") // 06:30 in Asia/Dubai
    val DEADLINE: Instant = Instant.parse("2025-11-04T04:00:00Z") // 08:00 local

    fun baseline(minutes: Double = 20.0, growthPerHour: Double = 0.0): TravelBaseline {
        val points = (0..24).map { i ->
            val t = NOW.plusSeconds(i * 300L)
            val elapsedHours = i * 300.0 / 3600.0
            val d = (minutes + growthPerHour * elapsedHours) * 60.0
            TravelBaseline.Point(t.epochSecond, d, minutes * 60.0 * 0.72, d / (minutes * 60.0 * 0.72))
        }
        return TravelBaseline.of(points)
    }

    fun context(
        weather: Double? = 0.1,
        event: Double = 0.0,
        regime: TrafficRegime = TrafficRegime.MODERATE,
        live: Boolean = true,
    ) = ForecastContext(
        routeKey = "r1", pairKey = "p1",
        bucket = TimeBucket.MORNING_PEAK, dayType = DayType.WEEKDAY, dayOfWeek = DayOfWeek.TUESDAY,
        regime = regime, weatherSeverity = weather, eventPressure = event,
        routingIsLive = live, routingAgeMinutes = 0,
    )

    fun bundle(
        history: List<TravelObservation> = emptyList(),
        context: ForecastContext = context(),
        baselineSeconds: Double = 1200.0,
    ): SamplerBundle =
        TravelModelFitter.fit(history).sampler(context, baselineSeconds, baselineSeconds * 0.72)

    fun inputs(
        baseline: TravelBaseline = baseline(),
        bundle: SamplerBundle = bundle(),
        prepMinutes: Double = 5.0,
        parkMinutes: Double = 4.0,
        walkMinutes: Double = 3.0,
        entryBufferMinutes: Int = 0,
        draws: Int = 4000,
        seed: Long = 4242L,
    ) = SimulationInputs(
        baseline = baseline,
        samplerBundle = bundle,
        preparation = FittedDuration(prepMinutes * 60, 0.4, 0.0, 0.0, DataProvenance.DEFAULT, "prior"),
        parking = FittedDuration(parkMinutes * 60, 0.45, 0.0, 0.0, DataProvenance.DEFAULT, "prior"),
        walking = FittedDuration(walkMinutes * 60, 0.3, 0.0, 0.0, DataProvenance.DEFAULT, "prior"),
        entryBufferSeconds = entryBufferMinutes * 60.0,
        requiredArrival = DEADLINE.minusSeconds(entryBufferMinutes * 60L),
        draws = draws,
        seed = seed,
    )

    fun observations(
        n: Int,
        logRatio: Double,
        spread: Double = 0.0,
        routeKey: String = "r1",
        weather: Double? = null,
        seed: Long = 5L,
    ): List<TravelObservation> {
        val rng = Xoshiro256(seed)
        return (0 until n).map { i ->
            val predicted = 1200.0
            val shock = logRatio + spread * rng.nextGaussian()
            TravelObservation(
                at = NOW.minusSeconds((n - i) * 86_400L),
                routeKey = routeKey, pairKey = "p1",
                bucket = TimeBucket.MORNING_PEAK, dayType = DayType.WEEKDAY, dayOfWeek = DayOfWeek.TUESDAY,
                regime = TrafficRegime.MODERATE,
                predictedSeconds = predicted,
                actualSeconds = predicted * kotlin.math.exp(shock),
                weatherSeverity = weather,
                eventPressure = null,
            )
        }
    }
}

class MonteCarloTest {

    @Test
    fun `probabilities are valid and percentiles correctly ordered`() {
        val f = MonteCarlo.simulate(Fixture.NOW, Fixture.inputs())
        assertThat(f.onTimeProbability).isAtLeast(0.0)
        assertThat(f.onTimeProbability).isAtMost(1.0)
        assertThat(f.probabilityMoreThan5LateSeconds).isAtLeast(f.probabilityMoreThan10LateSeconds)
        assertThat(f.probabilityMoreThan5LateSeconds).isAtMost(1.0 - f.onTimeProbability + 1e-9)

        val keys = f.percentiles.keys.sorted()
        for (i in 1 until keys.size) {
            assertThat(f.percentiles[keys[i]]!!).isAtLeast(f.percentiles[keys[i - 1]]!!)
        }
        assertThat(f.intervalWidthSeconds).isAtLeast(0.0)
    }

    @Test
    fun `fixed seeds produce deterministic results`() {
        val a = MonteCarlo.simulate(Fixture.NOW, Fixture.inputs(seed = 777L))
        val b = MonteCarlo.simulate(Fixture.NOW, Fixture.inputs(seed = 777L))
        assertThat(a.onTimeProbability).isEqualTo(b.onTimeProbability)
        assertThat(a.medianArrival).isEqualTo(b.medianArrival)
        assertThat(a.expectedArrival).isEqualTo(b.expectedArrival)
    }

    @Test
    fun `increasing uncertainty widens the forecast interval`() {
        val tight = Fixture.inputs(bundle = Fixture.bundle(history = Fixture.observations(40, 0.0, 0.05)))
        val loose = Fixture.inputs(bundle = Fixture.bundle(history = Fixture.observations(40, 0.0, 0.45)))
        val a = MonteCarlo.simulate(Fixture.NOW, tight)
        val b = MonteCarlo.simulate(Fixture.NOW, loose)
        assertThat(b.intervalWidthSeconds).isGreaterThan(a.intervalWidthSeconds)
    }

    @Test
    fun `later departures never arrive earlier on a stable route`() {
        val inputs = Fixture.inputs()
        val early = MonteCarlo.simulate(Fixture.NOW, inputs)
        val late = MonteCarlo.simulate(Fixture.NOW.plusSeconds(900), inputs)
        assertThat(late.medianArrival).isGreaterThan(early.medianArrival)
        assertThat(late.onTimeProbability).isAtMost(early.onTimeProbability)
    }

    @Test
    fun `the routing baseline is evaluated after preparation not at departure`() {
        // A steeply rising traffic profile: a long preparation delay must land the driver
        // in worse traffic, so the journey takes materially longer.
        val rising = Fixture.baseline(minutes = 15.0, growthPerHour = 40.0)
        val quick = MonteCarlo.simulate(Fixture.NOW, Fixture.inputs(baseline = rising, prepMinutes = 1.0))
        val slow = MonteCarlo.simulate(Fixture.NOW, Fixture.inputs(baseline = rising, prepMinutes = 20.0))
        val quickDrive = quick.componentMeans.baselineTravelSeconds
        val slowDrive = slow.componentMeans.baselineTravelSeconds
        assertThat(slowDrive).isGreaterThan(quickDrive + 300)
    }

    @Test
    fun `component means reconstruct the mean total journey`() {
        val f = MonteCarlo.simulate(Fixture.NOW, Fixture.inputs())
        val c = f.componentMeans
        val reconstructed = c.preparationSeconds + c.correctedTravelSeconds + c.incidentSeconds +
            c.parkingSeconds + c.walkingSeconds
        assertThat(reconstructed).isWithin(1.0).of(f.meanTotalSeconds)
    }

    @Test
    fun `an entry buffer moves the deadline earlier and lowers the probability`() {
        // Departing 45 minutes before the deadline puts the probability in the interior,
        // where the effect of moving the deadline is actually observable.
        val departure = Fixture.DEADLINE.minusSeconds(45 * 60)
        val none = MonteCarlo.simulate(departure, Fixture.inputs(entryBufferMinutes = 0))
        val ten = MonteCarlo.simulate(departure, Fixture.inputs(entryBufferMinutes = 10))
        assertThat(ten.requiredArrival).isLessThan(none.requiredArrival)
        assertThat(ten.onTimeProbability).isLessThan(none.onTimeProbability)
    }

    @Test
    fun `expected lateness is only counted over late draws`() {
        // A hopeless departure: everything is late, so conditional lateness must be large.
        val f = MonteCarlo.simulate(Fixture.DEADLINE.minusSeconds(120), Fixture.inputs())
        assertThat(f.onTimeProbability).isLessThan(0.05)
        assertThat(f.expectedLatenessIfLateSeconds).isGreaterThan(600.0)
    }

    @Test
    fun `an impossible journey never reports a positive probability`() {
        val f = MonteCarlo.simulate(Fixture.DEADLINE.plusSeconds(3600), Fixture.inputs())
        assertThat(f.onTimeProbability).isEqualTo(0.0)
        assertThat(f.probabilityMoreThan10LateSeconds).isEqualTo(1.0)
    }
}

class TravelBaselineTest {

    @Test
    fun `interpolation is linear between queried points`() {
        val b = TravelBaseline.of(
            listOf(
                TravelBaseline.Point(0, 600.0, 500.0, 1.2),
                TravelBaseline.Point(300, 900.0, 500.0, 1.8),
            ),
        )
        assertThat(b.durationAt(Instant.ofEpochSecond(150))).isWithin(1e-6).of(750.0)
        assertThat(b.durationAt(Instant.ofEpochSecond(0))).isEqualTo(600.0)
        assertThat(b.durationAt(Instant.ofEpochSecond(300))).isEqualTo(900.0)
    }

    @Test
    fun `outside the queried range the nearest point is held flat and flagged`() {
        val b = TravelBaseline.of(
            listOf(
                TravelBaseline.Point(0, 600.0, 500.0, 1.2),
                TravelBaseline.Point(300, 900.0, 500.0, 1.8),
            ),
        )
        assertThat(b.durationAt(Instant.ofEpochSecond(-500))).isEqualTo(600.0)
        assertThat(b.durationAt(Instant.ofEpochSecond(9_000))).isEqualTo(900.0)
        assertThat(b.outsideQueriedRange(Instant.ofEpochSecond(9_000))).isTrue()
        assertThat(b.outsideQueriedRange(Instant.ofEpochSecond(150))).isFalse()
    }
}

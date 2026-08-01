package com.ontimequant.forecast.demo

import com.ontimequant.forecast.Xoshiro256
import com.ontimequant.model.Appointment
import com.ontimequant.model.CompletedTrip
import com.ontimequant.model.DataProvenance
import com.ontimequant.model.DayType
import com.ontimequant.model.GeoPoint
import com.ontimequant.model.LocationKind
import com.ontimequant.model.NearbyEvent
import com.ontimequant.model.ProviderNotice
import com.ontimequant.model.RouteEstimate
import com.ontimequant.model.RouteMatrix
import com.ontimequant.model.SavedJourney
import com.ontimequant.model.SavedLocation
import com.ontimequant.model.TimeBucket
import com.ontimequant.model.TrafficRegime
import com.ontimequant.model.TripSource
import com.ontimequant.model.WeatherCategory
import com.ontimequant.model.WeatherSnapshot
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.exp
import kotlin.math.roundToLong

/**
 * A complete, self-contained journey used when no API keys are configured.
 *
 * Everything here is *input* data — a traffic-aware duration profile, a weather snapshot,
 * a set of completed trips. No probability, departure recommendation or interval is
 * stored anywhere in this file; those are all produced by the forecasting engine from
 * these inputs, exactly as they would be from live providers.
 *
 * The duration profile and the trip history are calibrated (see
 * `DemoCalibrationTest`) so the resulting departure curve shows a realistic and steep
 * cost of waiting through the Abu Dhabi morning peak.
 */
object DemoScenario {

    val ZONE: ZoneId = ZoneId.of("Asia/Dubai")

    val HOME = SavedLocation(
        id = "demo-home",
        label = "Home",
        address = "Sun Tower, Shams Abu Dhabi, Al Reem Island",
        point = GeoPoint(24.4989, 54.4082),
        kind = LocationKind.HOME,
    )

    val OFFICE = SavedLocation(
        id = "demo-office",
        label = "Office",
        address = "Al Danah, Khalifa bin Zayed the First Street, Abu Dhabi",
        point = GeoPoint(24.4874, 54.3609),
        kind = LocationKind.WORK,
    )

    val AIRPORT = SavedLocation(
        id = "demo-airport",
        label = "Zayed International Airport",
        address = "Abu Dhabi International Airport, Terminal A",
        point = GeoPoint(24.4330, 54.6511),
        kind = LocationKind.AIRPORT,
    )

    val JOURNEY = SavedJourney(
        id = "demo-journey-commute",
        name = "Home to work",
        originId = HOME.id,
        destinationId = OFFICE.id,
        defaultConfidence = 0.90,
        defaultEntryBufferMinutes = 0,
    )

    val AIRPORT_JOURNEY = SavedJourney(
        id = "demo-journey-airport",
        name = "Home to airport",
        originId = HOME.id,
        destinationId = AIRPORT.id,
        defaultConfidence = 0.95,
        defaultEntryBufferMinutes = 15,
    )

    /** Approximate driving line Al Reem Island → central Abu Dhabi, for event proximity. */
    val POLYLINE = listOf(
        GeoPoint(24.4989, 54.4082),
        GeoPoint(24.4961, 54.3993),
        GeoPoint(24.4930, 54.3901),
        GeoPoint(24.4915, 54.3806),
        GeoPoint(24.4896, 54.3714),
        GeoPoint(24.4874, 54.3609),
    )

    // -----------------------------------------------------------------------
    // Traffic-aware duration profile
    // -----------------------------------------------------------------------

    /**
     * Traffic-aware driving minutes as a function of the minute of day the driver joins
     * the road. This is the demo stand-in for the routing API's `duration` field.
     *
     * Shape: free-flow before 06:00, a steep build through the 07:00–08:00 causeway peak,
     * a plateau, then decay. Values are minutes.
     */
    private val PROFILE: List<Pair<Int, Double>> = listOf(
        0 to 10.4,
        5 * 60 to 10.4,
        6 * 60 to 11.1,
        6 * 60 + 30 to 11.8,
        7 * 60 to 11.9,
        7 * 60 + 10 to 12.5,
        7 * 60 + 20 to 13.3,
        7 * 60 + 30 to 14.3,
        7 * 60 + 40 to 15.4,
        7 * 60 + 50 to 16.3,
        8 * 60 to 16.9,
        8 * 60 + 15 to 17.1,
        8 * 60 + 45 to 16.9,
        9 * 60 + 30 to 14.0,
        11 * 60 to 11.5,
        15 * 60 to 12.2,
        17 * 60 to 16.8,
        18 * 60 + 30 to 15.4,
        20 * 60 to 12.0,
        24 * 60 to 10.4,
    )

    private const val FREE_FLOW_MINUTES = 10.4
    private const val DISTANCE_METRES = 9_400.0

    fun trafficAwareMinutes(minuteOfDay: Int): Double {
        val m = ((minuteOfDay % 1440) + 1440) % 1440
        if (m <= PROFILE.first().first) return PROFILE.first().second
        for (i in 0 until PROFILE.size - 1) {
            val (t0, v0) = PROFILE[i]
            val (t1, v1) = PROFILE[i + 1]
            if (m in t0..t1) {
                val f = if (t1 == t0) 0.0 else (m - t0).toDouble() / (t1 - t0)
                return v0 + f * (v1 - v0)
            }
        }
        return PROFILE.last().second
    }

    private fun regimeFor(ratio: Double): TrafficRegime = when {
        ratio < 1.10 -> TrafficRegime.LIGHT
        ratio < 1.35 -> TrafficRegime.MODERATE
        ratio < 1.75 -> TrafficRegime.HEAVY
        else -> TrafficRegime.SEVERE
    }

    /** Builds the demo equivalent of a Routes API response over a departure window. */
    fun routeMatrix(
        windowStart: Instant,
        windowEnd: Instant,
        stepMinutes: Int,
        zone: ZoneId = ZONE,
        fetchedAt: Instant = windowStart,
    ): RouteMatrix {
        val estimates = ArrayList<RouteEstimate>()
        var t = windowStart
        while (!t.isAfter(windowEnd)) {
            estimates += estimateAt(t, zone)
            t = t.plusSeconds(stepMinutes * 60L)
        }
        if (estimates.isEmpty() || estimates.last().departureTime != windowEnd) {
            estimates += estimateAt(windowEnd, zone)
        }
        // One credible alternative: longer in distance, less exposed to the causeway.
        val alternatives = estimates.filterIndexed { i, _ -> i % 4 == 0 }.map {
            it.copy(
                durationSeconds = it.durationSeconds * 1.06 + 90,
                staticDurationSeconds = it.staticDurationSeconds * 1.22,
                distanceMetres = it.distanceMetres * 1.19,
                routeLabel = "Via Al Khaleej Al Arabi Street",
            )
        }
        return RouteMatrix(
            estimates = estimates,
            alternatives = alternatives,
            provenance = DataProvenance.DEMO,
            fetchedAt = fetchedAt,
            notice = ProviderNotice(
                source = "Routing",
                provenance = DataProvenance.DEMO,
                message = "Demo route data. No routing API key is configured, so this journey uses " +
                    "the built-in Abu Dhabi traffic profile instead of live conditions.",
            ),
        )
    }

    fun estimateAt(departure: Instant, zone: ZoneId = ZONE): RouteEstimate {
        val local = departure.atZone(zone).toLocalTime()
        val minuteOfDay = local.hour * 60 + local.minute
        val minutes = trafficAwareMinutes(minuteOfDay)
        val ratio = minutes / FREE_FLOW_MINUTES
        return RouteEstimate(
            departureTime = departure,
            durationSeconds = minutes * 60.0,
            staticDurationSeconds = FREE_FLOW_MINUTES * 60.0,
            distanceMetres = DISTANCE_METRES,
            routeLabel = "Via Reem Causeway and Al Salam Street",
            trafficRegime = regimeFor(ratio),
            polyline = POLYLINE,
        )
    }

    // -----------------------------------------------------------------------
    // Weather & events
    // -----------------------------------------------------------------------

    /**
     * A mild summer morning in Abu Dhabi: hot, hazy, no rain. Severity is low but not
     * zero, which is honest — haze does slow the causeway slightly.
     */
    fun weather(at: Instant): WeatherSnapshot = WeatherSnapshot(
        time = at,
        precipitationProbability = 0.05,
        precipitationMm = 0.0,
        visibilityMetres = 4200.0,
        windSpeedKph = 21.0,
        temperatureC = 34.5,
        severeAlert = false,
        provenance = DataProvenance.DEMO,
    )

    /** One mid-sized event near the destination, enough to register as mild risk. */
    fun events(referenceDay: LocalDate, zone: ZoneId = ZONE): List<NearbyEvent> = listOf(
        NearbyEvent(
            id = "demo-event-1",
            name = "Abu Dhabi Business Forum — opening session",
            venueName = "ADNEC Centre Abu Dhabi",
            venuePoint = GeoPoint(24.4180, 54.4370),
            startTime = referenceDay.atTime(9, 0).atZone(zone).toInstant(),
            endTime = referenceDay.atTime(17, 0).atZone(zone).toInstant(),
            venueCapacity = 12_000,
        ),
        NearbyEvent(
            id = "demo-event-2",
            name = "Corniche Road half marathon",
            venueName = "Corniche Beach",
            venuePoint = GeoPoint(24.4750, 54.3300),
            startTime = referenceDay.atTime(6, 30).atZone(zone).toInstant(),
            endTime = referenceDay.atTime(9, 30).atZone(zone).toInstant(),
            venueCapacity = 4_000,
        ),
    )

    // -----------------------------------------------------------------------
    // Appointment
    // -----------------------------------------------------------------------

    /**
     * The demo appointment: an 08:00 meeting, arriving exactly at the start time so the
     * headline probability answers "will I be there by 08:00?" directly.
     */
    fun appointment(
        day: LocalDate,
        zone: ZoneId = ZONE,
        confidence: Double = 0.90,
        entryBufferMinutes: Int = 0,
    ): Appointment = Appointment(
        id = "demo-appointment",
        title = "Client review — Q3 portfolio",
        startTime = day.atTime(8, 0).atZone(zone).toInstant(),
        zone = zone,
        origin = HOME,
        destination = OFFICE,
        entryBufferMinutes = entryBufferMinutes,
        confidenceTarget = confidence,
        journeyId = JOURNEY.id,
    )

    fun secondaryAppointment(day: LocalDate, zone: ZoneId = ZONE): Appointment = Appointment(
        id = "demo-appointment-2",
        title = "Flight EY 073 to London",
        startTime = day.plusDays(2).atTime(14, 25).atZone(zone).toInstant(),
        zone = zone,
        origin = HOME,
        destination = AIRPORT,
        entryBufferMinutes = 90,
        confidenceTarget = 0.95,
        journeyId = AIRPORT_JOURNEY.id,
    )

    // -----------------------------------------------------------------------
    // Completed trip history
    // -----------------------------------------------------------------------

    /**
     * Generates a plausible history of completed commutes ending the day before
     * [referenceDay].
     *
     * The generator is seeded, so the demo is byte-identical on every device and the
     * numbers shown on the accuracy screen are reproducible. Each trip is produced by
     * *simulating a real outcome* — a routing estimate from the profile, then a realised
     * duration drawn from a skewed multiplicative shock — never by writing down an answer.
     *
     * Calibration targets (see `DemoCalibrationTest`):
     *  - median log-ratio slightly positive: the profile under-predicts the real drive
     *  - robust log-scale ≈ 0.30, the volatility a causeway commute genuinely carries
     */
    fun completedTrips(
        referenceDay: LocalDate,
        zone: ZoneId = ZONE,
        count: Int = 24,
        seed: Long = 918_273_645L,
    ): List<CompletedTrip> {
        val rng = Xoshiro256(seed)
        val trips = ArrayList<CompletedTrip>()
        var day = referenceDay.minusDays(1)
        var made = 0

        while (made < count) {
            if (day.dayOfWeek == DayOfWeek.SATURDAY || day.dayOfWeek == DayOfWeek.SUNDAY) {
                day = day.minusDays(1)
                continue
            }

            // Intended departure drifts around 07:16 — the user is a creature of habit.
            val intendedMinute = 7 * 60 + 16 + (rng.nextGaussian() * 5).roundToLong().toInt()
            val intended = day.atTime(LocalTime.of(intendedMinute / 60, intendedMinute % 60))
                .atZone(zone).toInstant()

            // Preparation: log-normal around 6 minutes.
            val prep = rng.nextLogNormal(6 * 60.0, 0.70)
            val roadStart = intended.plusSeconds(prep.roundToLong())
            val roadLocal = roadStart.atZone(zone).toLocalTime()
            val predictedMinutes = trafficAwareMinutes(roadLocal.hour * 60 + roadLocal.minute)
            val predictedSeconds = predictedMinutes * 60.0

            // Weather: mostly clear, occasionally hazy or a rare shower.
            val weatherSeverity = when {
                rng.nextDouble() < 0.12 -> 0.45 + 0.3 * rng.nextDouble()
                rng.nextDouble() < 0.35 -> 0.18 + 0.15 * rng.nextDouble()
                else -> 0.04 + 0.08 * rng.nextDouble()
            }
            val eventPressure = if (rng.nextDouble() < 0.2) 0.15 + 0.35 * rng.nextDouble() else 0.0

            // Realised outcome: bias + volatility + a genuine right tail.
            val logShock = DEMO_LOG_BIAS +
                DEMO_LOG_SIGMA * rng.nextGaussian() +
                0.10 * weatherSeverity +
                0.06 * eventPressure
            var actualSeconds = predictedSeconds * exp(logShock)
            if (rng.nextDouble() < 0.10) actualSeconds += rng.nextExponential(6.5 * 60.0)

            val parking = rng.nextLogNormal(3.4 * 60.0, 0.42)
            val walking = rng.nextLogNormal(2.7 * 60.0, 0.28)

            val arrival = roadStart.plusSeconds((actualSeconds + parking + walking).roundToLong())
            val required = day.atTime(8, 0).atZone(zone).toInstant()
            val ratio = predictedMinutes / FREE_FLOW_MINUTES

            trips += CompletedTrip(
                id = "demo-trip-${day}",
                journeyId = JOURNEY.id,
                originId = HOME.id,
                destinationId = OFFICE.id,
                appointmentTitle = if (made % 5 == 0) "Team stand-up" else "Morning commute",
                zone = zone,
                requiredArrival = required,
                recommendedDeparture = intended.minusSeconds(120),
                actualDeparture = intended,
                actualArrival = arrival,
                predictedTravelSeconds = predictedSeconds,
                actualTravelSeconds = actualSeconds,
                predictedArrival = roadStart.plusSeconds((predictedSeconds + 3.4 * 60 + 2.7 * 60).roundToLong()),
                onTimeProbabilityAtDeparture = null,
                preparationSeconds = prep,
                parkingSeconds = parking,
                walkingSeconds = walking,
                weatherSeverity = weatherSeverity,
                weatherCategory = when {
                    weatherSeverity >= 0.55 -> WeatherCategory.ADVERSE
                    weatherSeverity >= 0.25 -> WeatherCategory.MIXED
                    else -> WeatherCategory.CLEAR
                },
                eventPressure = eventPressure,
                trafficRegime = regimeFor(ratio),
                timeBucket = TimeBucket.of(roadLocal),
                dayType = DayType.of(day.dayOfWeek),
                dayOfWeek = day.dayOfWeek,
                modelVersion = "otq-forecast-1.0.0",
                source = TripSource.DEMO,
            )
            made++
            day = day.minusDays(1)
        }
        return trips.sortedBy { it.actualDeparture }
    }

    /**
     * Calibrated demo constants.
     *
     * `DEMO_LOG_BIAS` shifts the realised outcome relative to the demo traffic profile and
     * `DEMO_LOG_SIGMA` sets its scale. Together with the profile's peak gradient and the
     * disruption tail these determine the shape of the departure curve. They are the only
     * tuned numbers in the demo, and they are **inputs** to the model, never outputs: the
     * engine reads the resulting trips exactly as it would read real ones, and every
     * probability on screen is simulated from them.
     *
     * A note on the width. Reproducing the brief's target curve — roughly 95% at 07:15
     * falling to about 49% at 07:30 — is only possible with a total door-to-door standard
     * deviation near ten minutes: fifteen minutes of lost budget has to move the normal
     * score by about 1.65, which pins σ ≈ 15/1.65. That is why the demo's 80% arrival
     * range is around twenty minutes wide rather than the fourteen quoted elsewhere in
     * the brief. The two cannot both hold, and the curve is the stated calibration target,
     * so the interval is reported honestly at whatever width the model implies rather than
     * being narrowed for appearance.
     */
    const val DEMO_LOG_BIAS = -0.06
    const val DEMO_LOG_SIGMA = 0.48

    /** How stale the demo pretends its routing data is. Always fresh: it is generated on demand. */
    fun demoAge(now: Instant, fetchedAt: Instant): Long = Duration.between(fetchedAt, now).toMinutes()
}

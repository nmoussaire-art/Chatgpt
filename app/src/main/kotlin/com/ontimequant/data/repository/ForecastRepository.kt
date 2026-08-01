package com.ontimequant.data.repository

import com.ontimequant.data.db.CandidateDepartureForecastEntity
import com.ontimequant.data.db.EventPressureSnapshotEntity
import com.ontimequant.data.db.ForecastDao
import com.ontimequant.data.db.PredictionSnapshotEntity
import com.ontimequant.data.db.RouteForecastEntity
import com.ontimequant.data.db.WeatherSnapshotEntity
import com.ontimequant.data.prefs.SettingsRepository
import com.ontimequant.forecast.DepartureSolver
import com.ontimequant.forecast.EventPressureModel
import com.ontimequant.forecast.ExternalSignals
import com.ontimequant.forecast.ForecastEngine
import com.ontimequant.forecast.ForecastSettings
import com.ontimequant.forecast.JourneyForecast
import com.ontimequant.model.Appointment
import com.ontimequant.model.DataProvenance
import com.ontimequant.model.EventPressure
import com.ontimequant.model.ProviderNotice
import com.ontimequant.model.ProviderResult
import com.ontimequant.model.RouteMatrix
import com.ontimequant.model.WeatherSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** What the UI receives: either a forecast, or an honest explanation of why there isn't one. */
sealed interface ForecastOutcome {
    data class Ready(val forecast: JourneyForecast) : ForecastOutcome
    data class Unavailable(val notices: List<ProviderNotice>, val message: String) : ForecastOutcome
}

/**
 * Produces forecasts. This is the only place that talks to providers *and* the engine.
 *
 * The order of operations matters:
 *  1. Work out the departure window from the deadline (routing is queried across it).
 *  2. Fetch routing — required. Everything else is best-effort and runs concurrently.
 *  3. Fetch weather and events in parallel; either may fail without consequence.
 *  4. Load learned history.
 *  5. Run the engine off the main thread.
 *  6. Persist the result so the home screen has something to show instantly next time.
 */
@Singleton
class ForecastRepository @Inject constructor(
    private val providers: ProviderRegistry,
    private val settings: SettingsRepository,
    private val trips: TripRepository,
    private val forecastDao: ForecastDao,
    private val engine: ForecastEngine,
) {

    suspend fun forecast(
        appointment: Appointment,
        now: Instant = Instant.now(),
        persist: Boolean = true,
    ): ForecastOutcome = withContext(Dispatchers.Default) {
        val userSettings = settings.current()

        // A rough first guess at the journey length, only used to size the query window.
        // It is intentionally generous: querying a slightly wider window is cheap, and a
        // window that is too narrow would truncate the departure curve.
        val roughTotalSeconds = 45 * 60.0
        val (windowStart, windowEnd) = DepartureSolver.window(
            requiredArrival = appointment.requiredArrival,
            typicalTotalSeconds = roughTotalSeconds,
            earliestAllowed = now,
        )

        if (!windowEnd.isAfter(windowStart.minusSeconds(1))) {
            return@withContext ForecastOutcome.Unavailable(
                emptyList(),
                "That appointment has already started.",
            )
        }

        val routesProvider = providers.routes()
        val routeResult = routesProvider.routeMatrix(
            origin = appointment.origin.point,
            destination = appointment.destination.point,
            windowStart = windowStart,
            // Routing is queried a little past the last useful departure so interpolation
            // near the end of the curve is not extrapolation.
            windowEnd = windowEnd.plusSeconds(15 * 60),
            stepMinutes = ROUTING_STEP_MINUTES,
        )

        val matrix: RouteMatrix = when (routeResult) {
            is ProviderResult.Success -> routeResult.value
            is ProviderResult.Failure -> return@withContext ForecastOutcome.Unavailable(
                listOf(routeResult.notice),
                "No route estimate is available for this journey, so no forecast can be produced. " +
                    "Check your connection, or switch on demo mode in Settings.",
            )
        }
        if (matrix.isEmpty) {
            return@withContext ForecastOutcome.Unavailable(
                listOfNotNull(matrix.notice),
                "The routing provider returned no usable estimates for this journey.",
            )
        }

        // The moment the driver is most likely to be on the road — used to time the
        // weather lookup and the event window.
        val midJourney = appointment.requiredArrival.minusSeconds(
            (matrix.estimates.map { it.durationSeconds }.average() / 2).toLong(),
        )

        val signals = coroutineScope {
            val weatherJob = async { fetchWeather(appointment, midJourney) }
            val eventJob = async { fetchEvents(appointment, matrix, windowStart) }
            val (weather, weatherNotice) = weatherJob.await()
            val (pressure, eventNotice) = eventJob.await()
            ExternalSignals(
                weather = weather,
                weatherProvenance = weather?.provenance ?: DataProvenance.UNAVAILABLE,
                eventPressure = pressure,
                notices = listOfNotNull(weatherNotice, eventNotice),
            )
        }

        val history = trips.learningHistory()

        val forecast = engine.forecast(
            appointment = appointment,
            routeMatrix = matrix,
            signals = signals,
            history = history,
            settings = ForecastSettings(
                confidenceTarget = appointment.confidenceTarget,
                simulationDraws = userSettings.simulationDraws,
                preparationLearningEnabled = userSettings.preparationLearningEnabled,
                defaultParkingSeconds = userSettings.defaultParkingSeconds,
                defaultWalkingSeconds = userSettings.defaultWalkingSeconds,
                defaultPreparationSeconds = userSettings.defaultPreparationSeconds,
                seed = seedFor(appointment),
            ),
            now = now,
        )

        if (persist) persist(appointment, forecast, signals)
        ForecastOutcome.Ready(forecast)
    }

    /**
     * A per-appointment but *stable* seed. Two recalculations of the same appointment with
     * the same inputs give byte-identical output, so the user never sees the recommendation
     * jitter by a minute for no reason. Different appointments get independent streams.
     */
    private fun seedFor(appointment: Appointment): Long =
        appointment.id.hashCode().toLong() * 31 + appointment.requiredArrival.epochSecond

    private suspend fun fetchWeather(
        appointment: Appointment,
        at: Instant,
    ): Pair<WeatherSnapshot?, ProviderNotice?> =
        when (val result = providers.weather().forecast(appointment.destination.point, at)) {
            is ProviderResult.Success -> result.value to null
            is ProviderResult.Failure -> null to result.notice
        }

    private suspend fun fetchEvents(
        appointment: Appointment,
        matrix: RouteMatrix,
        windowStart: Instant,
    ): Pair<EventPressure, ProviderNotice?> {
        val polyline = matrix.estimates.firstOrNull { it.polyline.isNotEmpty() }?.polyline.orEmpty()
        val result = providers.events().eventsNear(
            point = appointment.destination.point,
            radiusMetres = EVENT_RADIUS_METRES,
            from = windowStart.minusSeconds(3 * 3600),
            to = appointment.requiredArrival.plusSeconds(2 * 3600),
        )
        return when (result) {
            is ProviderResult.Success -> {
                val pressure = EventPressureModel.compute(
                    events = result.value,
                    routePolyline = polyline,
                    destination = appointment.destination.point,
                    travelWindowStart = windowStart,
                    travelWindowEnd = appointment.requiredArrival,
                    provenance = result.provenance,
                )
                pressure to null
            }
            is ProviderResult.Failure -> EventPressure.NONE to result.notice
        }
    }

    private suspend fun persist(
        appointment: Appointment,
        forecast: JourneyForecast,
        signals: ExternalSignals,
    ) {
        val id = UUID.randomUUID().toString()
        val headline = forecast.recommended ?: forecast.bestEffort
        forecastDao.saveComplete(
            forecast = RouteForecastEntity(
                id = id,
                appointmentId = appointment.id,
                generatedAtEpoch = forecast.generatedAt.epochSecond,
                recommendedDepartureEpoch = forecast.recommendedDeparture?.epochSecond,
                onTimeProbability = headline.onTimeProbability,
                expectedArrivalEpoch = headline.expectedArrival.epochSecond,
                medianArrivalEpoch = headline.medianArrival.epochSecond,
                p10ArrivalEpoch = headline.percentiles[10]?.epochSecond ?: 0,
                p90ArrivalEpoch = headline.percentiles[90]?.epochSecond ?: 0,
                probabilityMoreThan5Late = headline.probabilityMoreThan5LateSeconds,
                probabilityMoreThan10Late = headline.probabilityMoreThan10LateSeconds,
                confidenceTarget = forecast.confidenceTarget,
                feasible = forecast.feasible,
                riskLevel = forecast.trafficRisk.name,
                personalizationLevel = forecast.personalization.name,
                personalizationDetail = forecast.personalizationDetail,
                routeProvenance = forecast.routeProvenance.name,
                weatherProvenance = forecast.weatherProvenance.name,
                eventProvenance = forecast.eventProvenance.name,
                safetyMarginSeconds = forecast.recommendedSafetyMarginSeconds,
                simulationDraws = forecast.simulationDraws,
                seed = forecast.seed,
                modelVersion = forecast.modelVersion,
                summary = forecast.summary,
            ),
            curve = forecast.curve.points.map { point ->
                CandidateDepartureForecastEntity(
                    forecastId = id,
                    departureEpoch = point.departure.epochSecond,
                    onTimeProbability = point.onTimeProbability,
                    probabilityMoreThan10Late = point.probabilityMoreThan10LateSeconds,
                    expectedArrivalEpoch = point.expectedArrival.epochSecond,
                    p10ArrivalEpoch = point.percentiles[10]?.epochSecond ?: 0,
                    p90ArrivalEpoch = point.percentiles[90]?.epochSecond ?: 0,
                )
            },
            components = forecast.components.map {
                PredictionSnapshotEntity(
                    forecastId = id,
                    componentId = it.id,
                    label = it.label,
                    valueSeconds = it.valueSeconds,
                    deltaSeconds = it.deltaSeconds,
                    uncertaintySeconds = it.uncertaintySeconds,
                    provenance = it.provenance.name,
                    sourceLabel = it.sourceLabel,
                    explanation = it.explanation,
                )
            },
            weather = signals.weather?.let {
                WeatherSnapshotEntity(
                    forecastId = id,
                    atEpoch = it.time.epochSecond,
                    precipitationProbability = it.precipitationProbability,
                    precipitationMm = it.precipitationMm,
                    visibilityMetres = it.visibilityMetres,
                    windSpeedKph = it.windSpeedKph,
                    temperatureC = it.temperatureC,
                    severeAlert = it.severeAlert,
                    severityIndex = it.severityIndex(),
                    category = it.category().name,
                    provenance = it.provenance.name,
                )
            },
            events = signals.eventPressure.takeIf { it.provenance != DataProvenance.UNAVAILABLE }?.let {
                EventPressureSnapshotEntity(
                    forecastId = id,
                    score = it.score,
                    topEventName = it.contributors.firstOrNull()?.eventName,
                    topVenueName = it.contributors.firstOrNull()?.venueName,
                    metresFromRoute = it.contributors.firstOrNull()?.metresFromRoute,
                    provenance = it.provenance.name,
                )
            },
        )
    }

    /** The last stored forecast for an appointment, with its true age. */
    suspend fun cachedForecast(appointmentId: String): CachedForecast? {
        val row = forecastDao.latestFor(appointmentId) ?: return null
        return CachedForecast(
            row,
            Duration.between(Instant.ofEpochSecond(row.generatedAtEpoch), Instant.now()),
        )
    }

    data class CachedForecast(val row: RouteForecastEntity, val age: Duration)

    private companion object {
        /**
         * The routing grid. Five minutes matches the solver's coarse pass, keeps a
         * two-hour window inside 25 requests, and is fine enough that one-minute
         * interpolation between points is well behaved.
         */
        const val ROUTING_STEP_MINUTES = 5
        const val EVENT_RADIUS_METRES = 12_000.0
    }
}

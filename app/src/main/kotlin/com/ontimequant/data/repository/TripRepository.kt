package com.ontimequant.data.repository

import com.ontimequant.data.db.CalibrationBucketEntity
import com.ontimequant.data.db.CalibrationDao
import com.ontimequant.data.db.CalibrationResultEntity
import com.ontimequant.data.db.LearningDao
import com.ontimequant.data.db.ModelResidualEntity
import com.ontimequant.data.db.ParkingObservationEntity
import com.ontimequant.data.db.PreparationObservationEntity
import com.ontimequant.data.db.RouteBiasStateEntity
import com.ontimequant.data.db.TripDao
import com.ontimequant.data.db.TripObservationDao
import com.ontimequant.data.db.TripObservationEntity
import com.ontimequant.data.db.WalkingObservationEntity
import com.ontimequant.data.db.toDomain
import com.ontimequant.data.db.toEntity
import com.ontimequant.data.prefs.SettingsRepository
import com.ontimequant.forecast.AccuracyReport
import com.ontimequant.forecast.Backtester
import com.ontimequant.forecast.CalibrationAnalyser
import com.ontimequant.forecast.CalibrationBucket
import com.ontimequant.forecast.FittedDuration
import com.ontimequant.forecast.HistoryMapper
import com.ontimequant.forecast.Insight
import com.ontimequant.forecast.InsightEngine
import com.ontimequant.forecast.LearningHistory
import com.ontimequant.forecast.MODEL_VERSION
import com.ontimequant.forecast.ModelPriors
import com.ontimequant.forecast.TravelModelFitter
import com.ontimequant.model.Appointment
import com.ontimequant.model.CompletedTrip
import com.ontimequant.model.DataProvenance
import com.ontimequant.model.DayType
import com.ontimequant.model.TimeBucket
import com.ontimequant.model.TrafficRegime
import com.ontimequant.model.TripSource
import com.ontimequant.model.WeatherCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Everything to do with completed journeys: recording them, learning from them, and
 * scoring the model against them.
 *
 * The learning pipeline is deliberately one-directional:
 *
 * ```
 *   trip recorded → residual + duration observations written
 *                 → EWMA bias state recomputed (a cache, always rebuildable)
 *                 → walk-forward backtest re-run → calibration stored
 * ```
 *
 * Nothing reads the model to decide what to record, so a bad model can never poison its
 * own training data.
 */
@Singleton
class TripRepository @Inject constructor(
    private val trips: TripDao,
    private val observations: TripObservationDao,
    private val learning: LearningDao,
    private val calibration: CalibrationDao,
    private val settings: SettingsRepository,
) {

    val allTrips: Flow<List<CompletedTrip>> =
        trips.observeAll().map { rows -> rows.map { it.toDomain() } }

    val tripCount: Flow<Int> = trips.observeCount()

    val activeObservation: Flow<TripObservationEntity?> = observations.observeActive()

    val latestCalibration: Flow<CalibrationResultEntity?> = calibration.observeLatest()

    suspend fun trip(id: String): CompletedTrip? = trips.byId(id)?.toDomain()

    /** One-shot read of the in-flight journey, for workers that cannot collect a flow. */
    suspend fun activeObservationOnce(): TripObservationEntity? = observations.active()

    suspend fun completedTrips(): List<CompletedTrip> =
        trips.allChronological().map { it.toDomain() }

    suspend fun tripsForJourney(journeyId: String): List<CompletedTrip> =
        trips.forJourney(journeyId).map { it.toDomain() }

    /** The observation streams the forecasting engine consumes. */
    suspend fun learningHistory(): LearningHistory = withContext(Dispatchers.Default) {
        HistoryMapper.toLearningHistory(completedTrips())
    }

    // -----------------------------------------------------------------------
    // Journey lifecycle
    // -----------------------------------------------------------------------

    /**
     * Called when the user accepts a recommendation (taps Navigate or Leave now).
     * The gap between this instant and the first detected movement is the preparation delay.
     */
    suspend fun beginJourney(
        appointment: Appointment,
        predictedTravelSeconds: Double,
        weatherSeverity: Double?,
        eventPressure: Double?,
        trafficRegime: TrafficRegime,
        at: Instant = Instant.now(),
    ): String {
        observations.clear()
        val id = UUID.randomUUID().toString()
        observations.upsert(
            TripObservationEntity(
                id = id,
                appointmentId = appointment.id,
                journeyId = appointment.journeyId,
                originId = appointment.origin.id,
                destinationId = appointment.destination.id,
                decisionEpoch = at.epochSecond,
                movementEpoch = null,
                arrivalEpoch = null,
                predictedTravelSeconds = predictedTravelSeconds,
                forecastId = null,
                weatherSeverity = weatherSeverity,
                eventPressure = eventPressure,
                trafficRegime = trafficRegime.name,
                createdAt = at.epochSecond,
            ),
        )
        return id
    }

    /** Movement detected (or declared). This is the real departure. */
    suspend fun recordMovement(at: Instant = Instant.now()) {
        observations.active()?.let { observations.upsert(it.copy(movementEpoch = at.epochSecond)) }
    }

    suspend fun cancelJourney() = observations.clear()

    /**
     * Arrival. Completes the observation into a stored trip and updates the model.
     *
     * @param parkingSeconds and [walkingSeconds] are what the user reports (or the model's
     *   own assumption when they do not); the road time is arrival minus departure minus both.
     */
    suspend fun completeJourney(
        appointment: Appointment,
        arrival: Instant = Instant.now(),
        parkingSeconds: Double? = null,
        walkingSeconds: Double? = null,
        source: TripSource = TripSource.DETECTED,
    ): CompletedTrip? {
        val observation = observations.active() ?: return null
        val departure = Instant.ofEpochSecond(
            observation.movementEpoch ?: observation.decisionEpoch ?: observation.createdAt,
        )
        val decision = observation.decisionEpoch?.let(Instant::ofEpochSecond)
        val preparation = if (decision != null && observation.movementEpoch != null) {
            Duration.between(decision, departure).seconds.toDouble().coerceAtLeast(0.0)
        } else null

        // Duplicate guard: two detection paths reporting the same journey.
        trips.findDuplicate(observation.originId, observation.destinationId, departure.epochSecond)?.let {
            observations.clear()
            return it.toDomain()
        }

        val totalSeconds = Duration.between(departure, arrival).seconds.toDouble()
        if (totalSeconds <= 0) {
            observations.clear()
            return null
        }
        val park = parkingSeconds ?: 0.0
        val walk = walkingSeconds ?: 0.0
        val roadSeconds = (totalSeconds - park - walk).coerceAtLeast(60.0)

        val zoned = departure.atZone(appointment.zone)
        val trip = CompletedTrip(
            id = UUID.randomUUID().toString(),
            journeyId = observation.journeyId,
            originId = observation.originId,
            destinationId = observation.destinationId,
            appointmentTitle = appointment.title,
            zone = appointment.zone,
            requiredArrival = appointment.requiredArrival,
            recommendedDeparture = decision,
            actualDeparture = departure,
            actualArrival = arrival,
            predictedTravelSeconds = observation.predictedTravelSeconds,
            actualTravelSeconds = roadSeconds,
            predictedArrival = null,
            onTimeProbabilityAtDeparture = null,
            preparationSeconds = preparation,
            parkingSeconds = parkingSeconds,
            walkingSeconds = walkingSeconds,
            weatherSeverity = observation.weatherSeverity,
            weatherCategory = observation.weatherSeverity?.let {
                when {
                    it >= 0.55 -> WeatherCategory.ADVERSE
                    it >= 0.25 -> WeatherCategory.MIXED
                    else -> WeatherCategory.CLEAR
                }
            } ?: WeatherCategory.UNKNOWN,
            eventPressure = observation.eventPressure,
            trafficRegime = runCatching { TrafficRegime.valueOf(observation.trafficRegime) }
                .getOrDefault(TrafficRegime.UNKNOWN),
            timeBucket = TimeBucket.of(zoned.toLocalTime()),
            dayType = DayType.of(zoned.dayOfWeek),
            dayOfWeek = zoned.dayOfWeek,
            modelVersion = MODEL_VERSION,
            source = source,
        )

        saveTrip(trip)
        observations.clear()
        return trip
    }

    /** Persists a trip and folds it into every learned model. */
    suspend fun saveTrip(trip: CompletedTrip) {
        trips.upsert(trip.toEntity())
        if (!trip.excludedFromLearning) writeObservations(trip)
        refreshDerivedState()
    }

    private suspend fun writeObservations(trip: CompletedTrip) {
        if (trip.predictedTravelSeconds > 0 && trip.actualTravelSeconds > 0) {
            val routeKey = trip.journeyId ?: "${trip.originId}->${trip.destinationId}"
            learning.insertResidual(
                ModelResidualEntity(
                    groupKey = "route:$routeKey|bucket:${trip.timeBucket}|day:${trip.dayType}",
                    tripId = trip.id,
                    atEpoch = trip.actualDeparture.epochSecond,
                    logRatio = kotlin.math.ln(trip.actualTravelSeconds / trip.predictedTravelSeconds),
                    predictedSeconds = trip.predictedTravelSeconds,
                    actualSeconds = trip.actualTravelSeconds,
                    weatherSeverity = trip.weatherSeverity,
                    eventPressure = trip.eventPressure,
                ),
            )
        }
        trip.preparationSeconds?.takeIf { it > 0 }?.let {
            learning.insertPreparation(
                PreparationObservationEntity(
                    tripId = trip.id, originId = trip.originId,
                    atEpoch = trip.actualDeparture.epochSecond, seconds = it,
                ),
            )
        }
        trip.parkingSeconds?.takeIf { it > 0 }?.let {
            learning.insertParking(
                ParkingObservationEntity(
                    tripId = trip.id, destinationId = trip.destinationId,
                    atEpoch = trip.actualArrival.epochSecond, seconds = it,
                ),
            )
        }
        trip.walkingSeconds?.takeIf { it > 0 }?.let {
            learning.insertWalking(
                WalkingObservationEntity(
                    tripId = trip.id, destinationId = trip.destinationId,
                    atEpoch = trip.actualArrival.epochSecond, seconds = it,
                ),
            )
        }
    }

    // -----------------------------------------------------------------------
    // Trip history editing
    // -----------------------------------------------------------------------

    suspend fun updateTrip(trip: CompletedTrip) = saveTrip(trip)

    suspend fun deleteTrip(id: String) {
        trips.delete(id)
        refreshDerivedState()
    }

    suspend fun setExcludedFromLearning(id: String, excluded: Boolean) {
        val trip = trip(id) ?: return
        saveTrip(trip.copy(excludedFromLearning = excluded))
    }

    // -----------------------------------------------------------------------
    // Derived model state, backtesting and insights
    // -----------------------------------------------------------------------

    /**
     * Recomputes the EWMA bias cache and the walk-forward calibration.
     *
     * Both are pure functions of the stored trips, so this can be called at any time and
     * is idempotent. It is the only writer of `route_bias_state` and `calibration_*`.
     */
    suspend fun refreshDerivedState() = withContext(Dispatchers.Default) {
        val history = completedTrips()
        val fitted = TravelModelFitter.fit(HistoryMapper.toLearningHistory(history).travel)
        val now = Instant.now().epochSecond
        fitted.allGroupKeys().forEach { key ->
            fitted.groupSummary(key)?.let { summary ->
                learning.upsertBias(
                    RouteBiasStateEntity(
                        groupKey = summary.key,
                        meanLogRatio = summary.meanLogRatio,
                        robustLogSigma = summary.robustLogSigma,
                        effectiveSampleSize = summary.effectiveSampleSize,
                        observationCount = summary.count,
                        updatedAtEpoch = now,
                    ),
                )
            }
        }

        val report = accuracyReport(history)
        val resultId = "latest"
        calibration.replace(
            result = CalibrationResultEntity(
                id = resultId,
                computedAtEpoch = now,
                sampleSize = report.sampleSize,
                meanAbsoluteErrorSeconds = report.meanAbsoluteErrorSeconds,
                medianAbsoluteErrorSeconds = report.medianAbsoluteErrorSeconds,
                rmseSeconds = report.rmseSeconds,
                meanBiasSeconds = report.meanBiasSeconds,
                medianBiasSeconds = report.medianBiasSeconds,
                coverage50 = report.coverage50,
                coverage80 = report.coverage80,
                coverage90 = report.coverage90,
                brierScore = report.brierScore,
                medianIntervalWidthSeconds = report.medianIntervalWidthSeconds,
                recentMedianAbsoluteErrorSeconds = report.recentMedianAbsoluteErrorSeconds,
                longRunMedianAbsoluteErrorSeconds = report.longRunMedianAbsoluteErrorSeconds,
                provenance = report.provenance.name,
            ),
            buckets = report.calibration.map {
                CalibrationBucketEntity(
                    resultId = resultId,
                    lowerProbability = it.lowerProbability,
                    upperProbability = it.upperProbability,
                    count = it.count,
                    meanPredicted = it.meanPredicted,
                    observedFrequency = it.observedFrequency,
                )
            },
        )
    }

    suspend fun accuracyReport(history: List<CompletedTrip>? = null): AccuracyReport =
        withContext(Dispatchers.Default) {
            val trips = history ?: completedTrips()
            val provenance = if (trips.any { it.source == TripSource.DEMO }) {
                DataProvenance.DEMO
            } else {
                DataProvenance.LEARNED
            }
            CalibrationAnalyser.analyse(Backtester.run(trips), provenance)
        }

    suspend fun calibrationBuckets(): List<CalibrationBucket> =
        calibration.buckets("latest").map {
            CalibrationBucket(
                lowerProbability = it.lowerProbability,
                upperProbability = it.upperProbability,
                count = it.count,
                meanPredicted = it.meanPredicted,
                observedFrequency = it.observedFrequency,
            )
        }

    suspend fun insights(): List<Insight> = withContext(Dispatchers.Default) {
        val history = completedTrips()
        val userSettings = settings.current()
        val preparation = history.firstOrNull()?.originId?.let { originId ->
            HistoryMapper.preparationFor(
                trips = history,
                originId = originId,
                userDefaultSeconds = userSettings.defaultPreparationSeconds,
                enabled = userSettings.preparationLearningEnabled,
            )
        }
        InsightEngine.generate(history, accuracyReport(history), preparation)
    }

    /** The learned preparation model, exposed for the settings and insight screens. */
    suspend fun preparationModel(originId: String): FittedDuration = withContext(Dispatchers.Default) {
        val userSettings = settings.current()
        HistoryMapper.preparationFor(
            trips = completedTrips(),
            originId = originId,
            priors = ModelPriors.DEFAULT,
            userDefaultSeconds = userSettings.defaultPreparationSeconds,
            enabled = userSettings.preparationLearningEnabled,
        )
    }
}

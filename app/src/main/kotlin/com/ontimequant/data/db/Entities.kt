package com.ontimequant.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room schema.
 *
 * Design rules that follow from the privacy stance:
 *  - No continuous location trace is ever persisted. A trip stores four instants
 *    (recommended departure, actual departure, actual arrival, deadline) and durations.
 *    It does not store where the user was in between.
 *  - Coordinates are stored only for places the user deliberately saved.
 *  - Every forecast keeps enough *context* to be replayed — the routing estimate, the
 *    weather severity, the event pressure, the model version — which is what makes
 *    walk-forward backtesting possible without keeping raw provider payloads.
 */

@Entity(tableName = "saved_locations")
data class SavedLocationEntity(
    @PrimaryKey val id: String,
    val label: String,
    val address: String,
    val latitude: Double,
    val longitude: Double,
    val kind: String,
    val providerPlaceId: String?,
    val defaultParkingMinutes: Double?,
    val defaultWalkingMinutes: Double?,
    val createdAt: Long,
)

@Entity(
    tableName = "saved_journeys",
    indices = [Index("originId"), Index("destinationId")],
)
data class SavedJourneyEntity(
    @PrimaryKey val id: String,
    val name: String,
    val originId: String,
    val destinationId: String,
    val defaultConfidence: Double,
    val defaultEntryBufferMinutes: Int,
    val createdAt: Long,
)

@Entity(
    tableName = "appointments",
    indices = [Index("startTimeEpoch"), Index("calendarEventId")],
)
data class AppointmentEntity(
    @PrimaryKey val id: String,
    val title: String,
    /** Absolute instant. The wall-clock time is always derived via [zoneId]. */
    val startTimeEpoch: Long,
    /** Stored explicitly so a trip booked in another zone stays correct across travel. */
    val zoneId: String,
    val originId: String,
    val destinationId: String,
    val entryBufferMinutes: Int,
    val confidenceTarget: Double,
    val journeyId: String?,
    val calendarEventId: Long?,
    val calendarId: Long?,
    val remindersEnabled: Boolean,
    val dismissed: Boolean,
    val createdAt: Long,
)

/** The headline forecast for an appointment, kept so the UI can show a cached answer. */
@Entity(tableName = "route_forecasts", indices = [Index("appointmentId")])
data class RouteForecastEntity(
    @PrimaryKey val id: String,
    val appointmentId: String,
    val generatedAtEpoch: Long,
    val recommendedDepartureEpoch: Long?,
    val onTimeProbability: Double,
    val expectedArrivalEpoch: Long,
    val medianArrivalEpoch: Long,
    val p10ArrivalEpoch: Long,
    val p90ArrivalEpoch: Long,
    val probabilityMoreThan5Late: Double,
    val probabilityMoreThan10Late: Double,
    val confidenceTarget: Double,
    val feasible: Boolean,
    val riskLevel: String,
    val personalizationLevel: String,
    val personalizationDetail: String,
    val routeProvenance: String,
    val weatherProvenance: String,
    val eventProvenance: String,
    val safetyMarginSeconds: Double,
    val simulationDraws: Int,
    val seed: Long,
    val modelVersion: String,
    val summary: String,
)

/** One point of the departure curve. Enough to redraw the chart without re-simulating. */
@Entity(
    tableName = "candidate_departure_forecasts",
    indices = [Index("forecastId")],
)
data class CandidateDepartureForecastEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val forecastId: String,
    val departureEpoch: Long,
    val onTimeProbability: Double,
    val probabilityMoreThan10Late: Double,
    val expectedArrivalEpoch: Long,
    val p10ArrivalEpoch: Long,
    val p90ArrivalEpoch: Long,
)

/** The component breakdown behind a forecast, for the "why" screen and for audit. */
@Entity(tableName = "prediction_snapshots", indices = [Index("forecastId")])
data class PredictionSnapshotEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val forecastId: String,
    val componentId: String,
    val label: String,
    val valueSeconds: Double,
    val deltaSeconds: Double,
    val uncertaintySeconds: Double,
    val provenance: String,
    val sourceLabel: String,
    val explanation: String,
)

@Entity(
    tableName = "completed_trips",
    indices = [Index("journeyId"), Index("actualDepartureEpoch"), Index("destinationId")],
)
data class CompletedTripEntity(
    @PrimaryKey val id: String,
    val journeyId: String?,
    val originId: String,
    val destinationId: String,
    val appointmentTitle: String,
    val zoneId: String,
    val requiredArrivalEpoch: Long,
    val recommendedDepartureEpoch: Long?,
    val actualDepartureEpoch: Long,
    val actualArrivalEpoch: Long,
    val predictedTravelSeconds: Double,
    val actualTravelSeconds: Double,
    val predictedArrivalEpoch: Long?,
    val onTimeProbabilityAtDeparture: Double?,
    val preparationSeconds: Double?,
    val parkingSeconds: Double?,
    val walkingSeconds: Double?,
    val weatherSeverity: Double?,
    val weatherCategory: String,
    val eventPressure: Double?,
    val trafficRegime: String,
    val timeBucket: String,
    val dayType: String,
    val dayOfWeek: String,
    val excludedFromLearning: Boolean,
    val unusualCircumstances: String?,
    val modelVersion: String,
    val source: String,
)

/**
 * A journey in flight: departure has been detected or declared, arrival has not.
 * Exactly one row is expected at a time.
 */
@Entity(tableName = "trip_observations")
data class TripObservationEntity(
    @PrimaryKey val id: String,
    val appointmentId: String,
    val journeyId: String?,
    val originId: String,
    val destinationId: String,
    /** When the user tapped Navigate / accepted the recommendation. */
    val decisionEpoch: Long?,
    /** When movement was actually detected. Departure minus decision is preparation delay. */
    val movementEpoch: Long?,
    val arrivalEpoch: Long?,
    val predictedTravelSeconds: Double,
    val forecastId: String?,
    val weatherSeverity: Double?,
    val eventPressure: Double?,
    val trafficRegime: String,
    val createdAt: Long,
)

@Entity(tableName = "weather_snapshots", indices = [Index("forecastId")])
data class WeatherSnapshotEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val forecastId: String,
    val atEpoch: Long,
    val precipitationProbability: Double?,
    val precipitationMm: Double?,
    val visibilityMetres: Double?,
    val windSpeedKph: Double?,
    val temperatureC: Double?,
    val severeAlert: Boolean,
    val severityIndex: Double,
    val category: String,
    val provenance: String,
)

@Entity(tableName = "event_pressure_snapshots", indices = [Index("forecastId")])
data class EventPressureSnapshotEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val forecastId: String,
    val score: Double,
    val topEventName: String?,
    val topVenueName: String?,
    val metresFromRoute: Double?,
    val provenance: String,
)

/** Raw learned residual, kept so the empirical bootstrap and MAD can be recomputed. */
@Entity(tableName = "model_residuals", indices = [Index("groupKey"), Index("atEpoch")])
data class ModelResidualEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val groupKey: String,
    val tripId: String,
    val atEpoch: Long,
    val logRatio: Double,
    val predictedSeconds: Double,
    val actualSeconds: Double,
    val weatherSeverity: Double?,
    val eventPressure: Double?,
)

/**
 * Derived EWMA state per group. This is a cache of a fold over [ModelResidualEntity];
 * it can always be rebuilt, which is why there is no migration risk in changing it.
 */
@Entity(tableName = "route_bias_state")
data class RouteBiasStateEntity(
    @PrimaryKey val groupKey: String,
    val meanLogRatio: Double,
    val robustLogSigma: Double,
    val effectiveSampleSize: Double,
    val observationCount: Int,
    val updatedAtEpoch: Long,
)

@Entity(tableName = "preparation_observations", indices = [Index("originId")])
data class PreparationObservationEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val tripId: String,
    val originId: String,
    val atEpoch: Long,
    val seconds: Double,
)

@Entity(tableName = "parking_observations", indices = [Index("destinationId")])
data class ParkingObservationEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val tripId: String,
    val destinationId: String,
    val atEpoch: Long,
    val seconds: Double,
)

@Entity(tableName = "walking_observations", indices = [Index("destinationId")])
data class WalkingObservationEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val tripId: String,
    val destinationId: String,
    val atEpoch: Long,
    val seconds: Double,
)

/** Result of the most recent walk-forward backtest. */
@Entity(tableName = "calibration_results")
data class CalibrationResultEntity(
    @PrimaryKey val id: String,
    val computedAtEpoch: Long,
    val sampleSize: Int,
    val meanAbsoluteErrorSeconds: Double,
    val medianAbsoluteErrorSeconds: Double,
    val rmseSeconds: Double,
    val meanBiasSeconds: Double,
    val medianBiasSeconds: Double,
    val coverage50: Double,
    val coverage80: Double,
    val coverage90: Double,
    val brierScore: Double,
    val medianIntervalWidthSeconds: Double,
    val recentMedianAbsoluteErrorSeconds: Double?,
    val longRunMedianAbsoluteErrorSeconds: Double?,
    val provenance: String,
)

@Entity(tableName = "calibration_buckets", indices = [Index("resultId")])
data class CalibrationBucketEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val resultId: String,
    val lowerProbability: Double,
    val upperProbability: Double,
    val count: Int,
    val meanPredicted: Double,
    val observedFrequency: Double,
)

/** Deduplication and cool-down state for notifications. */
@Entity(tableName = "notification_state")
data class NotificationStateEntity(
    @PrimaryKey val key: String,
    val lastSentEpoch: Long,
    val lastRecommendedDepartureEpoch: Long?,
    val lastOnTimeProbability: Double?,
    val lastRiskLevel: String?,
    val lastKind: String,
)

/** Cached routing responses, so the app can degrade honestly instead of failing. */
@Entity(tableName = "route_cache", indices = [Index("cacheKey")])
data class RouteCacheEntity(
    @PrimaryKey val cacheKey: String,
    val fetchedAtEpoch: Long,
    val payloadJson: String,
    val provenance: String,
)

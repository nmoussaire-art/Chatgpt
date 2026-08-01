package com.ontimequant.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface LocationDao {
    @Query("SELECT * FROM saved_locations ORDER BY createdAt")
    fun observeAll(): Flow<List<SavedLocationEntity>>

    @Query("SELECT * FROM saved_locations")
    suspend fun all(): List<SavedLocationEntity>

    @Query("SELECT * FROM saved_locations WHERE id = :id")
    suspend fun byId(id: String): SavedLocationEntity?

    @Query("SELECT * FROM saved_locations WHERE kind = :kind LIMIT 1")
    suspend fun byKind(kind: String): SavedLocationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(location: SavedLocationEntity)

    @Query("DELETE FROM saved_locations WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface JourneyDao {
    @Query("SELECT * FROM saved_journeys ORDER BY createdAt")
    fun observeAll(): Flow<List<SavedJourneyEntity>>

    @Query("SELECT * FROM saved_journeys ORDER BY createdAt")
    suspend fun allJourneys(): List<SavedJourneyEntity>

    @Query("SELECT * FROM saved_journeys WHERE id = :id")
    suspend fun byId(id: String): SavedJourneyEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(journey: SavedJourneyEntity)

    @Query("DELETE FROM saved_journeys WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface AppointmentDao {
    @Query("SELECT * FROM appointments WHERE dismissed = 0 ORDER BY startTimeEpoch")
    fun observeUpcoming(): Flow<List<AppointmentEntity>>

    @Query("SELECT * FROM appointments WHERE dismissed = 0 AND startTimeEpoch >= :fromEpoch ORDER BY startTimeEpoch LIMIT 1")
    suspend fun nextAfter(fromEpoch: Long): AppointmentEntity?

    @Query("SELECT * FROM appointments WHERE id = :id")
    suspend fun byId(id: String): AppointmentEntity?

    @Query("SELECT * FROM appointments WHERE calendarEventId = :eventId LIMIT 1")
    suspend fun byCalendarEvent(eventId: Long): AppointmentEntity?

    @Query("SELECT * FROM appointments WHERE dismissed = 0")
    suspend fun allActive(): List<AppointmentEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(appointment: AppointmentEntity)

    @Update
    suspend fun update(appointment: AppointmentEntity)

    @Query("DELETE FROM appointments WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface ForecastDao {
    @Query("SELECT * FROM route_forecasts WHERE appointmentId = :appointmentId ORDER BY generatedAtEpoch DESC LIMIT 1")
    suspend fun latestFor(appointmentId: String): RouteForecastEntity?

    @Query("SELECT * FROM route_forecasts WHERE appointmentId = :appointmentId ORDER BY generatedAtEpoch DESC LIMIT 1")
    fun observeLatestFor(appointmentId: String): Flow<RouteForecastEntity?>

    @Query("SELECT * FROM candidate_departure_forecasts WHERE forecastId = :forecastId ORDER BY departureEpoch")
    suspend fun curveFor(forecastId: String): List<CandidateDepartureForecastEntity>

    @Query("SELECT * FROM prediction_snapshots WHERE forecastId = :forecastId")
    suspend fun componentsFor(forecastId: String): List<PredictionSnapshotEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertForecast(forecast: RouteForecastEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCurve(points: List<CandidateDepartureForecastEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertComponents(components: List<PredictionSnapshotEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWeather(snapshot: WeatherSnapshotEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEventPressure(snapshot: EventPressureSnapshotEntity)

    @Transaction
    suspend fun saveComplete(
        forecast: RouteForecastEntity,
        curve: List<CandidateDepartureForecastEntity>,
        components: List<PredictionSnapshotEntity>,
        weather: WeatherSnapshotEntity?,
        events: EventPressureSnapshotEntity?,
    ) {
        purgeFor(forecast.appointmentId)
        insertForecast(forecast)
        insertCurve(curve)
        insertComponents(components)
        weather?.let { insertWeather(it) }
        events?.let { insertEventPressure(it) }
    }

    @Query(
        """
        DELETE FROM candidate_departure_forecasts WHERE forecastId IN
          (SELECT id FROM route_forecasts WHERE appointmentId = :appointmentId)
        """,
    )
    suspend fun purgeCurves(appointmentId: String)

    @Query(
        """
        DELETE FROM prediction_snapshots WHERE forecastId IN
          (SELECT id FROM route_forecasts WHERE appointmentId = :appointmentId)
        """,
    )
    suspend fun purgeComponents(appointmentId: String)

    @Query("DELETE FROM route_forecasts WHERE appointmentId = :appointmentId")
    suspend fun purgeForecasts(appointmentId: String)

    @Transaction
    suspend fun purgeFor(appointmentId: String) {
        purgeCurves(appointmentId)
        purgeComponents(appointmentId)
        purgeForecasts(appointmentId)
    }
}

@Dao
interface TripDao {
    @Query("SELECT * FROM completed_trips ORDER BY actualDepartureEpoch DESC")
    fun observeAll(): Flow<List<CompletedTripEntity>>

    @Query("SELECT * FROM completed_trips ORDER BY actualDepartureEpoch")
    suspend fun allChronological(): List<CompletedTripEntity>

    @Query("SELECT * FROM completed_trips WHERE journeyId = :journeyId ORDER BY actualDepartureEpoch")
    suspend fun forJourney(journeyId: String): List<CompletedTripEntity>

    @Query("SELECT * FROM completed_trips WHERE id = :id")
    suspend fun byId(id: String): CompletedTripEntity?

    @Query("SELECT COUNT(*) FROM completed_trips")
    fun observeCount(): Flow<Int>

    /**
     * Duplicate guard: the same journey recorded twice within a few minutes is almost
     * certainly one trip detected by two mechanisms (geofence and manual confirmation).
     */
    @Query(
        """
        SELECT * FROM completed_trips
        WHERE originId = :originId AND destinationId = :destinationId
          AND ABS(actualDepartureEpoch - :departureEpoch) < :toleranceSeconds
        LIMIT 1
        """,
    )
    suspend fun findDuplicate(
        originId: String,
        destinationId: String,
        departureEpoch: Long,
        toleranceSeconds: Long = 900,
    ): CompletedTripEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(trip: CompletedTripEntity)

    @Query("DELETE FROM completed_trips WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface TripObservationDao {
    @Query("SELECT * FROM trip_observations WHERE arrivalEpoch IS NULL ORDER BY createdAt DESC LIMIT 1")
    fun observeActive(): Flow<TripObservationEntity?>

    @Query("SELECT * FROM trip_observations WHERE arrivalEpoch IS NULL ORDER BY createdAt DESC LIMIT 1")
    suspend fun active(): TripObservationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(observation: TripObservationEntity)

    @Query("DELETE FROM trip_observations WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM trip_observations")
    suspend fun clear()
}

@Dao
interface LearningDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertResidual(residual: ModelResidualEntity)

    @Query("SELECT * FROM model_residuals ORDER BY atEpoch")
    suspend fun allResiduals(): List<ModelResidualEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBias(state: RouteBiasStateEntity)

    @Query("SELECT * FROM route_bias_state")
    fun observeBias(): Flow<List<RouteBiasStateEntity>>

    @Query("SELECT * FROM route_bias_state WHERE groupKey = :key")
    suspend fun bias(key: String): RouteBiasStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPreparation(observation: PreparationObservationEntity)

    @Query("SELECT * FROM preparation_observations ORDER BY atEpoch")
    suspend fun allPreparation(): List<PreparationObservationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertParking(observation: ParkingObservationEntity)

    @Query("SELECT * FROM parking_observations ORDER BY atEpoch")
    suspend fun allParking(): List<ParkingObservationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWalking(observation: WalkingObservationEntity)

    @Query("SELECT * FROM walking_observations ORDER BY atEpoch")
    suspend fun allWalking(): List<WalkingObservationEntity>
}

@Dao
interface CalibrationDao {
    @Query("SELECT * FROM calibration_results ORDER BY computedAtEpoch DESC LIMIT 1")
    fun observeLatest(): Flow<CalibrationResultEntity?>

    @Query("SELECT * FROM calibration_results ORDER BY computedAtEpoch DESC LIMIT 1")
    suspend fun latest(): CalibrationResultEntity?

    @Query("SELECT * FROM calibration_buckets WHERE resultId = :resultId ORDER BY lowerProbability")
    suspend fun buckets(resultId: String): List<CalibrationBucketEntity>

    @Query("SELECT * FROM calibration_buckets WHERE resultId = :resultId ORDER BY lowerProbability")
    fun observeBuckets(resultId: String): Flow<List<CalibrationBucketEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertResult(result: CalibrationResultEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBuckets(buckets: List<CalibrationBucketEntity>)

    @Query("DELETE FROM calibration_results")
    suspend fun clearResults()

    @Query("DELETE FROM calibration_buckets")
    suspend fun clearBuckets()

    @Transaction
    suspend fun replace(result: CalibrationResultEntity, buckets: List<CalibrationBucketEntity>) {
        clearBuckets()
        clearResults()
        insertResult(result)
        insertBuckets(buckets)
    }
}

@Dao
interface NotificationDao {
    @Query("SELECT * FROM notification_state WHERE key = :key")
    suspend fun state(key: String): NotificationStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: NotificationStateEntity)

    @Query("DELETE FROM notification_state WHERE key = :key")
    suspend fun clear(key: String)
}

@Dao
interface RouteCacheDao {
    @Query("SELECT * FROM route_cache WHERE cacheKey = :key")
    suspend fun get(key: String): RouteCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entry: RouteCacheEntity)

    @Query("DELETE FROM route_cache WHERE fetchedAtEpoch < :beforeEpoch")
    suspend fun prune(beforeEpoch: Long)

    @Query("DELETE FROM route_cache")
    suspend fun clear()
}

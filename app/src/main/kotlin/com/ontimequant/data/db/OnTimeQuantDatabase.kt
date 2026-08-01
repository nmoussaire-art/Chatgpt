package com.ontimequant.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Local, on-device database. Nothing here is synchronised to a server; there is no
 * server. See `docs/PRIVACY.md`.
 *
 * **Migration strategy.** Version 1 is the first shipped schema. Until the first public
 * release the development strategy is destructive migration on a debug build only —
 * every table here is either user-entered (re-creatable), a cache (recomputable), or
 * learned state derived from `completed_trips` and `model_residuals`. From version 2
 * onward, additive `Migration` objects go in [MIGRATIONS] and the exported schema JSON
 * under `app/schemas` is the reference. `fallbackToDestructiveMigration` is deliberately
 * *not* enabled for release builds, so a user's trip history can never be silently wiped.
 */
@Database(
    entities = [
        SavedLocationEntity::class,
        SavedJourneyEntity::class,
        AppointmentEntity::class,
        RouteForecastEntity::class,
        CandidateDepartureForecastEntity::class,
        PredictionSnapshotEntity::class,
        CompletedTripEntity::class,
        TripObservationEntity::class,
        WeatherSnapshotEntity::class,
        EventPressureSnapshotEntity::class,
        ModelResidualEntity::class,
        RouteBiasStateEntity::class,
        PreparationObservationEntity::class,
        ParkingObservationEntity::class,
        WalkingObservationEntity::class,
        CalibrationResultEntity::class,
        CalibrationBucketEntity::class,
        NotificationStateEntity::class,
        RouteCacheEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class OnTimeQuantDatabase : RoomDatabase() {
    abstract fun locationDao(): LocationDao
    abstract fun journeyDao(): JourneyDao
    abstract fun appointmentDao(): AppointmentDao
    abstract fun forecastDao(): ForecastDao
    abstract fun tripDao(): TripDao
    abstract fun tripObservationDao(): TripObservationDao
    abstract fun learningDao(): LearningDao
    abstract fun calibrationDao(): CalibrationDao
    abstract fun notificationDao(): NotificationDao
    abstract fun routeCacheDao(): RouteCacheDao

    companion object {
        const val NAME = "ontime-quant.db"

        /** Empty at version 1; every future schema change adds an entry here. */
        val MIGRATIONS = emptyArray<androidx.room.migration.Migration>()
    }
}

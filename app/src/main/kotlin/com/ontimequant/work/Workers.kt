package com.ontimequant.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ontimequant.data.prefs.SettingsRepository
import com.ontimequant.data.repository.ForecastOutcome
import com.ontimequant.data.repository.ForecastRepository
import com.ontimequant.data.repository.JourneyRepository
import com.ontimequant.data.repository.TripRepository
import com.ontimequant.notifications.NotificationScheduler
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Periodic recalculation.
 *
 * Runs at most every 15 minutes (the platform floor), only when a network is available,
 * and only touches appointments whose deadline is within the next few hours. Anything
 * further out cannot have a materially changed traffic forecast yet, so recalculating it
 * would burn API quota and battery for nothing.
 */
@HiltWorker
class ForecastRefreshWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val journeys: JourneyRepository,
    private val forecasts: ForecastRepository,
    private val notifications: NotificationScheduler,
    private val settings: SettingsRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val userSettings = settings.current()
        if (!userSettings.onboardingComplete) return Result.success()

        val now = Instant.now()
        val horizon = now.plus(Duration.ofHours(RELEVANT_HORIZON_HOURS))

        val relevant = journeys.allActiveAppointments().filter { appointment ->
            appointment.remindersEnabled &&
                appointment.requiredArrival.isAfter(now) &&
                appointment.requiredArrival.isBefore(horizon)
        }
        if (relevant.isEmpty()) return Result.success()

        var anyFailed = false
        for (appointment in relevant) {
            when (val outcome = forecasts.forecast(appointment, now)) {
                is ForecastOutcome.Ready -> notifications.evaluate(outcome.forecast, now)
                is ForecastOutcome.Unavailable -> anyFailed = true
            }
        }
        // A failed refresh is retried, but never at the cost of the ones that succeeded.
        return if (anyFailed) Result.retry() else Result.success()
    }

    companion object {
        const val NAME = "forecast-refresh"
        const val RELEVANT_HORIZON_HOURS = 4L
    }
}

/** Records an arrival detected by geofence, off the broadcast receiver's thread. */
@HiltWorker
class ArrivalDetectedWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val journeys: JourneyRepository,
    private val trips: TripRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val observation = trips.activeObservationOnce() ?: return Result.success()
        val appointment = journeys.appointment(observation.appointmentId) ?: return Result.success()
        trips.completeJourney(appointment = appointment, arrival = Instant.now())
        return Result.success()
    }

    companion object {
        const val NAME = "arrival-detected"
        const val KEY_OBSERVATION_ID = "observationId"
    }
}

/** Recomputes the walk-forward backtest and calibration after learning changes. */
@HiltWorker
class CalibrationWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val trips: TripRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        trips.refreshDerivedState()
        return Result.success()
    }

    companion object {
        const val NAME = "calibration-refresh"
    }
}

object WorkScheduler {

    fun schedulePeriodicRefresh(context: Context) {
        val request = PeriodicWorkRequestBuilder<ForecastRefreshWorker>(15, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            ForecastRefreshWorker.NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun cancelPeriodicRefresh(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(ForecastRefreshWorker.NAME)
    }

    fun enqueueArrivalDetected(context: Context, observationId: String) {
        val request = OneTimeWorkRequestBuilder<ArrivalDetectedWorker>()
            .setInputData(Data.Builder().putString(ArrivalDetectedWorker.KEY_OBSERVATION_ID, observationId).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            ArrivalDetectedWorker.NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    fun enqueueCalibration(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            CalibrationWorker.NAME,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<CalibrationWorker>().build(),
        )
    }
}

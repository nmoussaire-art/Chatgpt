package com.batterycast.quant.telemetry.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.batterycast.quant.forecasting.ModelUpdater
import com.batterycast.quant.notifications.AlertCoordinator
import com.batterycast.quant.telemetry.model.ObservationSource
import com.batterycast.quant.telemetry.repository.ObservationRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * The routine background sample.
 *
 * Deliberately modest: one battery reading, one incremental model update, and a check of whether
 * any alert threshold has been crossed. No wake locks, no foreground service, no polling loop.
 * A battery-forecasting app that measurably drains the battery has defeated itself, so this is
 * the only recurring background work the app schedules apart from daily maintenance.
 *
 * WorkManager may run this later than requested — Doze, app standby buckets and battery saver all
 * defer background work. That is expected and handled: observations carry their own timestamps,
 * the model works from irregular spacing, and the app tells the user how fresh its data actually
 * is rather than assuming the schedule was honoured.
 */
@HiltWorker
class ObservationWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val observationRepository: ObservationRepository,
    private val modelUpdater: ModelUpdater,
    private val alertCoordinator: AlertCoordinator,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val observation = observationRepository.recordSample(ObservationSource.PERIODIC_WORK)
            // Nothing to store is not a failure: the platform simply had no battery state to give.
            // Retrying immediately would burn power for nothing.
            ?: return Result.success()

        return runCatching {
            modelUpdater.refresh()
            alertCoordinator.evaluate(observation)
            Result.success()
        }.getOrElse { error ->
            if (error is kotlinx.coroutines.CancellationException) throw error
            // The reading is already safely stored; the model can catch up on the next run.
            Result.success()
        }
    }

    companion object {
        const val UNIQUE_NAME = "batterycast_periodic_observation"
    }
}

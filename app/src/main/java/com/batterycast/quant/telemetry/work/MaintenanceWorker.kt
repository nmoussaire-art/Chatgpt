package com.batterycast.quant.telemetry.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.batterycast.quant.forecasting.accuracy.AccuracyEvaluator
import com.batterycast.quant.telemetry.repository.ObservationRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Daily housekeeping: score the forecasts whose target time has passed, and drop history beyond
 * the retention window.
 *
 * Scoring past forecasts is what makes the accuracy screen truthful — every figure there comes
 * from a prediction this app made before the outcome was known.
 */
@HiltWorker
class MaintenanceWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val observationRepository: ObservationRepository,
    private val accuracyEvaluator: AccuracyEvaluator,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = runCatching {
        accuracyEvaluator.evaluatePending()
        observationRepository.prune(System.currentTimeMillis())
        Result.success()
    }.getOrElse { error ->
        if (error is kotlinx.coroutines.CancellationException) throw error
        Result.retry()
    }

    companion object {
        const val UNIQUE_NAME = "batterycast_daily_maintenance"
    }
}

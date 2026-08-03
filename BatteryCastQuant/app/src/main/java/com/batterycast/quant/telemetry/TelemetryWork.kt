package com.batterycast.quant.telemetry

import android.content.Context
import android.content.Intent
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.batterycast.quant.data.BatteryRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@HiltWorker
class BatteryObservationWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: BatteryRepository,
    private val alerts: ForecastAlertEvaluator
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = runCatching {
        repository.capture(inputData.getString("reason") ?: "periodic")
        alerts.evaluate()
        Result.success()
    }.getOrElse { Result.retry() }
}

@Singleton
class TelemetryScheduler @Inject constructor(@ApplicationContext private val context: Context) {
    fun ensurePeriodicSampling() {
        val request = PeriodicWorkRequestBuilder<BatteryObservationWorker>(15, TimeUnit.MINUTES, 5, TimeUnit.MINUTES)
            .setInputData(workDataOf("reason" to "periodic"))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    fun captureNow(reason: String) {
        val request = OneTimeWorkRequestBuilder<BatteryObservationWorker>()
            .setInputData(workDataOf("reason" to reason))
            .build()
        WorkManager.getInstance(context).enqueue(request)
    }

    companion object { const val PERIODIC_NAME = "batterycast_periodic" }
}

class BatteryEventReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val request = OneTimeWorkRequestBuilder<BatteryObservationWorker>()
            .setInputData(workDataOf("reason" to (intent.action ?: "battery_event")))
            .build()
        WorkManager.getInstance(context).enqueue(request)
    }
}

class BootReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val periodic = PeriodicWorkRequestBuilder<BatteryObservationWorker>(15, TimeUnit.MINUTES, 5, TimeUnit.MINUTES)
            .setInputData(workDataOf("reason" to "post_boot"))
            .build()
        val workManager = WorkManager.getInstance(context)
        workManager.enqueueUniquePeriodicWork(
            TelemetryScheduler.PERIODIC_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            periodic
        )
        workManager.enqueue(
            OneTimeWorkRequestBuilder<BatteryObservationWorker>()
                .setInputData(workDataOf("reason" to (intent.action ?: "system_change")))
                .build()
        )
    }
}

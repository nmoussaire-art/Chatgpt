package com.batterycast.quant.telemetry.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.batterycast.quant.notifications.BatteryCastNotifier
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Fires once, at the moment the charge planner said to plug in.
 *
 * This is the one notification the user asks for explicitly, so it bypasses the threshold and
 * cooldown logic that governs the automatic alerts — a reminder you requested is not an
 * interruption.
 */
@HiltWorker
class ChargeReminderWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val notifier: BatteryCastNotifier,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val eventLabel = inputData.getString(KEY_EVENT_LABEL).orEmpty()
        val targetPercent = inputData.getInt(KEY_TARGET_PERCENT, 0)
        val durationMinutes = inputData.getInt(KEY_DURATION_MINUTES, 0)

        notifier.postChargeAdvice(
            title = "Time to plug in",
            body = buildString {
                append("Start charging now to reach $targetPercent%")
                if (eventLabel.isNotBlank()) append(" by $eventLabel")
                append(".")
                if (durationMinutes > 0) append(" About $durationMinutes minutes of charging.")
            },
        )
        return Result.success()
    }

    companion object {
        const val UNIQUE_NAME = "batterycast_charge_reminder"
        const val KEY_EVENT_LABEL = "event_label"
        const val KEY_TARGET_PERCENT = "target_percent"
        const val KEY_DURATION_MINUTES = "duration_minutes"
    }
}

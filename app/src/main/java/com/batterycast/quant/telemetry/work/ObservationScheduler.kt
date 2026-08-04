package com.batterycast.quant.telemetry.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schedules the app's background observation work.
 *
 * The strategy is hybrid on purpose. A fifteen-minute period — WorkManager's shortest — gives a
 * baseline history at negligible cost, and the moments that actually carry information (a plug
 * event, a power-save toggle, opening the app) trigger an immediate one-off sample instead of
 * waiting for the next slot.
 *
 * Note what is *not* here: no exact alarms, no persistent foreground service, no wake locks, and
 * no constraint requiring the battery to be above any level — a forecast is most valuable when
 * the battery is low, so the app keeps observing then too.
 */
@Singleton
class ObservationScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val workManager: WorkManager get() = WorkManager.getInstance(context)

    /**
     * Ensures the periodic work exists.
     *
     * Uses KEEP so re-running this on every app start does not reset the period and delay the
     * next sample indefinitely.
     */
    fun ensureScheduled() {
        val periodic = PeriodicWorkRequestBuilder<ObservationWorker>(
            PERIOD_MINUTES, TimeUnit.MINUTES,
            FLEX_MINUTES, TimeUnit.MINUTES,
        )
            .setConstraints(Constraints.NONE)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()

        workManager.enqueueUniquePeriodicWork(
            ObservationWorker.UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            periodic,
        )

        val maintenance = PeriodicWorkRequestBuilder<MaintenanceWorker>(1, TimeUnit.DAYS)
            .setConstraints(
                Constraints.Builder()
                    // Housekeeping is the one job that can politely wait for a good moment.
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.HOURS)
            .build()

        workManager.enqueueUniquePeriodicWork(
            MaintenanceWorker.UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            maintenance,
        )
    }

    /**
     * Requests an immediate sample after an event that changed the device's power state.
     *
     * Expedited so the reading lands close to the event it describes — a charging-start sample
     * taken twenty minutes late tells us nothing about when charging started. If the app has no
     * expedited quota left the request runs as ordinary work rather than failing.
     */
    fun requestImmediateSample() {
        val request = OneTimeWorkRequestBuilder<ObservationWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()

        workManager.enqueueUniqueWork(
            IMMEDIATE_SAMPLING_WORK,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request,
        )
    }

    /**
     * Schedules the charge reminder the user asked for on the planner screen.
     *
     * WorkManager rather than an exact alarm: a reminder that arrives within a few minutes of the
     * recommended time is useful, and an exact-alarm permission would be a heavy ask for it.
     * Replacing the previous request means there is only ever one pending reminder.
     */
    fun scheduleChargeReminder(atMs: Long, eventLabel: String, targetPercent: Int, durationMinutes: Int) {
        val delayMs = (atMs - System.currentTimeMillis()).coerceAtLeast(0L)
        val request = OneTimeWorkRequestBuilder<ChargeReminderWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setInputData(
                workDataOf(
                    ChargeReminderWorker.KEY_EVENT_LABEL to eventLabel,
                    ChargeReminderWorker.KEY_TARGET_PERCENT to targetPercent,
                    ChargeReminderWorker.KEY_DURATION_MINUTES to durationMinutes,
                ),
            )
            .build()

        workManager.enqueueUniqueWork(
            ChargeReminderWorker.UNIQUE_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    fun cancelChargeReminder() {
        workManager.cancelUniqueWork(ChargeReminderWorker.UNIQUE_NAME)
    }

    fun cancelAll() {
        workManager.cancelUniqueWork(ObservationWorker.UNIQUE_NAME)
        workManager.cancelUniqueWork(MaintenanceWorker.UNIQUE_NAME)
    }

    companion object {
        /** WorkManager's minimum periodic interval. Asking for less would not make it happen. */
        const val PERIOD_MINUTES = 15L

        /** Lets the platform batch our run with other wakeups it was already going to make. */
        const val FLEX_MINUTES = 5L

        const val IMMEDIATE_SAMPLING_WORK = "batterycast_immediate_sample"
    }
}

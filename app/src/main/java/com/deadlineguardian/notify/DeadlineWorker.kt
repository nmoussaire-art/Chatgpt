package com.deadlineguardian.notify

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.deadlineguardian.data.AppDatabase
import com.deadlineguardian.data.Urgency
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/**
 * Wakes once a day and notifies about anything that has entered its warning window.
 *
 * Each deadline is notified at most once per day (tracked via `notifiedAt`) — the whole
 * point of the app is to be trusted, and a reminder that repeats every few hours gets
 * muted within a week.
 */
class DeadlineWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val dao = AppDatabase.get(applicationContext).dao()
        val today = LocalDate.now()

        // Widest lead we ever use, so one query covers every deadline kind.
        val horizon = today.plusDays(120)
        val due = dao.dueBy(horizon).map { it.toDomain() }

        val startOfDay = today.atStartOfDay()
            .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

        val toNotify = due.filter { entry ->
            val urgency = entry.urgency(today)
            val inWindow = urgency == Urgency.ACT_NOW || urgency == Urgency.OVERDUE
            // Don't keep shouting about something that expired months ago.
            val stillRelevant = entry.daysLeft(today) > -30
            val notYetToday = (entry.deadline.notifiedAt ?: 0L) < startOfDay
            inWindow && stillRelevant && notYetToday
        }

        toNotify.forEach { Notifier.notifyDeadline(applicationContext, it) }

        if (toNotify.isNotEmpty()) {
            dao.markNotified(toNotify.map { it.deadline.id }, System.currentTimeMillis())
        }
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "deadline-daily-check"

        fun schedule(context: Context, hour: Int = 9) {
            val request = PeriodicWorkRequestBuilder<DeadlineWorker>(1, TimeUnit.DAYS)
                .setInitialDelay(initialDelayMinutes(hour), TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().build())
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                // REPLACE so a changed notification hour takes effect immediately.
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }

        /** Minutes until the next occurrence of [hour] o'clock. */
        private fun initialDelayMinutes(hour: Int): Long {
            val now = LocalDateTime.now()
            var next = now.toLocalDate().atTime(LocalTime.of(hour.coerceIn(0, 23), 0))
            if (!next.isAfter(now)) next = next.plusDays(1)
            return Duration.between(now, next).toMinutes().coerceAtLeast(1)
        }
    }
}

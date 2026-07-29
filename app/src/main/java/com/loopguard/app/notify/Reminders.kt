package com.loopguard.app.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.loopguard.app.R
import com.loopguard.app.data.LoopRepository
import com.loopguard.app.data.Side
import com.loopguard.app.data.SettingsStore
import com.loopguard.app.domain.PriorityBand
import com.loopguard.app.domain.PriorityEngine
import com.loopguard.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit

object Notifications {
    const val CHANNEL_REMINDERS = "loop_reminders"
    const val CHANNEL_DIGEST = "loop_digest"

    const val DIGEST_NOTIFICATION_ID = 1001
    private const val LOOP_NOTIFICATION_BASE = 2000

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_DIGEST,
                context.getString(R.string.notification_channel_digest),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.notification_channel_digest_desc)
            }
        )

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_REMINDERS,
                context.getString(R.string.notification_channel_reminders),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.notification_channel_reminders_desc)
            }
        )
    }

    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED

    fun loopNotificationId(loopId: Long): Int = LOOP_NOTIFICATION_BASE + (loopId % 500).toInt()
}

/**
 * Daily worker that decides what deserves a notification.
 *
 * The rule is deliberately strict: at most one digest plus at most two
 * individual high-pressure loops per day. A follow-through app that nags
 * every day about everything gets its notifications turned off in a week.
 */
class DigestWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val context = applicationContext
        val settings = SettingsStore(context)
        val config = settings.current()

        if (!config.remindersEnabled) return@withContext Result.success()
        if (!Notifications.canPost(context)) return@withContext Result.success()

        val today = LocalDate.now()
        if (settings.lastDigestDay() == today.toEpochDay()) return@withContext Result.success()

        val repository = LoopRepository.get(context)
        val open = repository.openLoops().filterNot { it.isSnoozed(today) }
        if (open.isEmpty()) {
            settings.setLastDigestDay(today.toEpochDay())
            return@withContext Result.success()
        }

        val scored = open
            .map { it to PriorityEngine.evaluate(it, today) }
            .sortedByDescending { it.second.score }

        val overdue = scored.count { (loop, _) ->
            (PriorityEngine.daysUntilDue(loop, today) ?: 1) < 0
        }
        val dueToday = scored.count { (loop, _) ->
            PriorityEngine.daysUntilDue(loop, today) == 0
        }
        val goingQuiet = scored.count { (loop, _) ->
            loop.sideEnum == Side.THEM &&
                PriorityEngine.daysSilent(loop, today) >= config.followUpCadenceDays
        }

        postDigest(context, scored.size, overdue, dueToday, goingQuiet, scored.firstOrNull()?.first?.title)

        scored.filter { it.second.band == PriorityBand.CRITICAL }
            .take(2)
            .forEach { (loop, priority) -> postLoop(context, loop.id, loop.title, priority.headline) }

        settings.setLastDigestDay(today.toEpochDay())
        Result.success()
    }

    private fun postDigest(
        context: Context,
        total: Int,
        overdue: Int,
        dueToday: Int,
        quiet: Int,
        topTitle: String?,
    ) {
        val headline = when {
            overdue > 0 -> "$overdue overdue, $total open"
            dueToday > 0 -> "$dueToday due today, $total open"
            else -> "$total open loops"
        }

        val lines = buildList {
            if (overdue > 0) add("• $overdue past their date")
            if (dueToday > 0) add("• $dueToday due today")
            if (quiet > 0) add("• $quiet waiting on someone who has gone quiet")
            topTitle?.let { add("• Top of the queue: $it") }
        }

        val notification = NotificationCompat.Builder(context, Notifications.CHANNEL_DIGEST)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("LoopGuard")
            .setContentText(headline)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .setBigContentTitle(headline)
                    .bigText(lines.joinToString("\n"))
            )
            .setContentIntent(openAppIntent(context, null))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()

        NotificationManagerCompat.from(context)
            .notifySafely(Notifications.DIGEST_NOTIFICATION_ID, notification)
    }

    private fun postLoop(context: Context, loopId: Long, title: String, headline: String) {
        val snooze = PendingIntent.getBroadcast(
            context,
            (loopId * 10 + 1).toInt(),
            Intent(context, ReminderActionReceiver::class.java).apply {
                action = ReminderActionReceiver.ACTION_SNOOZE
                putExtra(ReminderActionReceiver.EXTRA_LOOP_ID, loopId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val complete = PendingIntent.getBroadcast(
            context,
            (loopId * 10 + 2).toInt(),
            Intent(context, ReminderActionReceiver::class.java).apply {
                action = ReminderActionReceiver.ACTION_COMPLETE
                putExtra(ReminderActionReceiver.EXTRA_LOOP_ID, loopId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, Notifications.CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(headline)
            .setStyle(NotificationCompat.BigTextStyle().bigText(headline))
            .setContentIntent(openAppIntent(context, loopId))
            .addAction(0, "Snooze 1 day", snooze)
            .addAction(0, "Mark done", complete)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()

        NotificationManagerCompat.from(context)
            .notifySafely(Notifications.loopNotificationId(loopId), notification)
    }

    private fun openAppIntent(context: Context, loopId: Long?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            loopId?.let { putExtra(MainActivity.EXTRA_OPEN_LOOP_ID, it) }
        }
        return PendingIntent.getActivity(
            context,
            loopId?.toInt() ?: 0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

private fun NotificationManagerCompat.notifySafely(id: Int, notification: android.app.Notification) {
    try {
        notify(id, notification)
    } catch (_: SecurityException) {
        // Permission revoked between the check and the post; nothing to do.
    }
}

object ReminderScheduler {

    private const val WORK_NAME = "loopguard-daily-digest"

    fun schedule(context: Context) {
        val settings = SettingsStore(context).current()
        val manager = WorkManager.getInstance(context)

        if (!settings.remindersEnabled) {
            manager.cancelUniqueWork(WORK_NAME)
            return
        }

        val request = PeriodicWorkRequestBuilder<DigestWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(delayUntilNext(settings.digestHour).toMinutes(), TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .addTag(WORK_NAME)
            .build()

        manager.enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    private fun delayUntilNext(hour: Int): Duration {
        val now = LocalDateTime.now()
        var target = now.toLocalDate().atTime(LocalTime.of(hour.coerceIn(0, 23), 0))
        if (!target.isAfter(now)) target = target.plusDays(1)
        return Duration.between(now, target)
    }
}

/** Handles the "Snooze 1 day" / "Mark done" notification buttons. */
class ReminderActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val loopId = intent.getLongExtra(EXTRA_LOOP_ID, -1L)
        if (loopId <= 0) return

        val action = intent.action ?: return
        val pending = goAsync()
        val appContext = context.applicationContext

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repository = LoopRepository.get(appContext)
                when (action) {
                    ACTION_SNOOZE -> repository.snooze(loopId, LocalDate.now().plusDays(1))
                    ACTION_COMPLETE -> repository.complete(loopId, "Closed from a notification")
                }
                NotificationManagerCompat.from(appContext)
                    .cancel(Notifications.loopNotificationId(loopId))
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_SNOOZE = "com.loopguard.app.SNOOZE"
        const val ACTION_COMPLETE = "com.loopguard.app.COMPLETE"
        const val EXTRA_LOOP_ID = "loop_id"
    }
}

/** Re-arms the daily worker after a reboot or an app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ReminderScheduler.schedule(context.applicationContext)
    }
}

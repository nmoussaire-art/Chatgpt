package com.deadlineguardian.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.deadlineguardian.MainActivity
import com.deadlineguardian.R
import com.deadlineguardian.data.DeadlineKind
import com.deadlineguardian.data.DeadlineWithItem
import com.deadlineguardian.data.Urgency
import com.deadlineguardian.ui.formatMoney

object Notifier {

    /**
     * Two channels on purpose: a closing return window is money walking out of the door
     * and deserves to interrupt, while a warranty ending in a month does not. Sharing
     * one channel would train the user to silence both.
     */
    const val CHANNEL_URGENT = "deadlines_urgent"
    const val CHANNEL_UPCOMING = "deadlines_upcoming"

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_URGENT,
                "Closing soon",
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "Return windows and expiries you can still act on" }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_UPCOMING,
                "Upcoming",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "Warranties and renewals coming up" }
        )
    }

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ActivityCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

    fun notifyDeadline(context: Context, entry: DeadlineWithItem) {
        if (!canNotify(context)) return
        ensureChannels(context)

        val urgent = entry.urgency() == Urgency.ACT_NOW || entry.urgency() == Urgency.OVERDUE
        val money = entry.deadline.moneyAtRiskMinor
            ?.let { formatMoney(it, entry.item.currency) }

        val title = when (entry.deadline.kind) {
            DeadlineKind.RETURN_WINDOW ->
                if (entry.daysLeft() < 0) "Return window closed" else "Return window closes ${entry.countdownText()}"
            DeadlineKind.WARRANTY_END -> "Warranty ends ${entry.countdownText()}"
            DeadlineKind.EXPIRY -> "Expires ${entry.countdownText()}"
            DeadlineKind.RENEWAL -> "Renews ${entry.countdownText()}"
            DeadlineKind.CUSTOM -> "Deadline ${entry.countdownText()}"
        }

        val body = buildString {
            append(entry.item.title)
            if (money != null && entry.deadline.kind == DeadlineKind.RETURN_WINDOW) {
                append(" · $money recoverable")
            }
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_DEADLINE_ID, entry.deadline.id)
        }
        val pending = PendingIntent.getActivity(
            context,
            entry.deadline.id.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(
            context,
            if (urgent) CHANNEL_URGENT else CHANNEL_UPCOMING
        )
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(if (urgent) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(entry.deadline.id.toInt(), notification)
        }
    }
}

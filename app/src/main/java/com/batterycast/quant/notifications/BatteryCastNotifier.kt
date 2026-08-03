package com.batterycast.quant.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import com.batterycast.quant.MainActivity
import com.batterycast.quant.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds and posts the app's notifications.
 *
 * Two channels, both low-importance by design: an alert that vibrates the phone to tell you your
 * battery might run out is its own small irony. Everything is silent and unobtrusive, and the
 * user can turn either channel off in system settings without breaking the app.
 */
@Singleton
class BatteryCastNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val manager: NotificationManager? = context.getSystemService()

    fun ensureChannels() {
        val notificationManager = manager ?: return

        val alerts = NotificationChannel(
            CHANNEL_ALERTS,
            context.getString(R.string.notification_channel_alerts_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.notification_channel_alerts_description)
            enableVibration(false)
            setShowBadge(false)
        }

        val precision = NotificationChannel(
            CHANNEL_PRECISION,
            context.getString(R.string.notification_channel_precision_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.notification_channel_precision_description)
            enableVibration(false)
            setShowBadge(false)
        }

        notificationManager.createNotificationChannel(alerts)
        notificationManager.createNotificationChannel(precision)
    }

    /** True when the app may post; on API 33+ this needs the runtime permission. */
    fun canPostNotifications(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun postSurvivalAlert(title: String, body: String) = post(ID_SURVIVAL, title, body)

    fun postDrainAlert(title: String, body: String) = post(ID_DRAIN, title, body)

    fun postChargeAdvice(title: String, body: String) = post(ID_CHARGE_ADVICE, title, body)

    /** The ongoing notification shown for the whole life of a precision session. */
    fun buildPrecisionSessionNotification(remainingMinutes: Int): Notification =
        NotificationCompat.Builder(context, CHANNEL_PRECISION)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Precision measurement running")
            .setContentText(
                "Sampling battery current more often for about $remainingMinutes more minutes. " +
                    "This uses slightly more power than normal.",
            )
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openAppIntent())
            .build()

    private fun post(id: Int, title: String, body: String) {
        if (!canPostNotifications()) return
        val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openAppIntent())
            .build()

        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val CHANNEL_ALERTS = "batterycast_alerts"
        const val CHANNEL_PRECISION = "batterycast_precision"

        const val ID_SURVIVAL = 1001
        const val ID_DRAIN = 1002
        const val ID_CHARGE_ADVICE = 1003
        const val ID_PRECISION_SESSION = 1004
    }
}

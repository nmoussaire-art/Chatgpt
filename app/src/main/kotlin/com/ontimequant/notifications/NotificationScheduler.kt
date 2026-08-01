package com.ontimequant.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.ontimequant.MainActivity
import com.ontimequant.R
import com.ontimequant.data.db.NotificationDao
import com.ontimequant.data.db.NotificationStateEntity
import com.ontimequant.data.prefs.SettingsRepository
import com.ontimequant.forecast.JourneyForecast
import com.ontimequant.model.RiskLevel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sends notifications, and — just as importantly — declines to.
 *
 * Every send goes through [NotificationPolicy] and writes back what was sent, so the
 * cool-down and deduplication survive process death.
 */
@Singleton
class NotificationScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: NotificationDao,
    private val settings: SettingsRepository,
) {

    fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_DEPARTURE,
                context.getString(R.string.channel_departure_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = context.getString(R.string.channel_departure_description) },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_FORECAST,
                context.getString(R.string.channel_forecast_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = context.getString(R.string.channel_forecast_description) },
        )
    }

    fun canPost(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }

    /**
     * Evaluates the policy for one appointment and posts at most one notification.
     * Returns the decision so callers (and tests) can assert on it.
     */
    suspend fun evaluate(forecast: JourneyForecast, now: Instant = Instant.now()): NotificationDecision {
        val userSettings = settings.current()
        if (!userSettings.notificationsEnabled) return NotificationDecision.Silent
        if (!forecast.appointment.remindersEnabled) return NotificationDecision.Silent

        val key = keyFor(forecast.appointment.id)
        val stored = dao.state(key)
        val memory = NotificationMemory(
            lastSent = stored?.lastSentEpoch?.let(Instant::ofEpochSecond),
            lastRecommendedDeparture = stored?.lastRecommendedDepartureEpoch?.let(Instant::ofEpochSecond),
            lastOnTimeProbability = stored?.lastOnTimeProbability,
            lastRiskLevel = stored?.lastRiskLevel?.let {
                runCatching { RiskLevel.valueOf(it) }.getOrNull()
            },
            lastKind = stored?.lastKind?.let { runCatching { NotificationKind.valueOf(it) }.getOrNull() },
        )

        val decision = NotificationPolicy.decide(
            forecast = forecast,
            memory = memory,
            now = now,
            remindersEnabled = userSettings.departureRemindersEnabled,
            changeAlertsEnabled = userSettings.forecastChangeAlertsEnabled,
        )

        if (decision is NotificationDecision.Send) {
            post(forecast.appointment.id, decision)
            dao.upsert(
                NotificationStateEntity(
                    key = key,
                    lastSentEpoch = now.epochSecond,
                    lastRecommendedDepartureEpoch = forecast.recommendedDeparture?.epochSecond,
                    lastOnTimeProbability = forecast.onTimeProbability,
                    lastRiskLevel = forecast.trafficRisk.name,
                    lastKind = decision.kind.name,
                ),
            )
        }
        return decision
    }

    /**
     * Lint cannot see through [canPost], which performs the runtime permission check on
     * the line below, so the check is suppressed here rather than duplicated.
     */
    @SuppressLint("MissingPermission")
    fun post(appointmentId: String, decision: NotificationDecision.Send) {
        if (!canPost()) return
        val channel = if (decision.kind == NotificationKind.DEPARTURE_APPROACHING) {
            CHANNEL_DEPARTURE
        } else {
            CHANNEL_FORECAST
        }
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_APPOINTMENT_ID, appointmentId)
        }
        val pending = android.app.PendingIntent.getActivity(
            context, appointmentId.hashCode(), intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(decision.title)
            .setContentText(decision.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(decision.body))
            .setPriority(
                if (channel == CHANNEL_DEPARTURE) NotificationCompat.PRIORITY_HIGH
                else NotificationCompat.PRIORITY_DEFAULT,
            )
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(appointmentId.hashCode(), notification)
        }
    }

    suspend fun forget(appointmentId: String) = dao.clear(keyFor(appointmentId))

    private fun keyFor(appointmentId: String) = "appointment:$appointmentId"

    companion object {
        const val CHANNEL_DEPARTURE = "departure"
        const val CHANNEL_FORECAST = "forecast"
    }
}

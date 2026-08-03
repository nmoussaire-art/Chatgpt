package com.batterycast.quant.telemetry

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.batterycast.quant.data.BatteryRepository
import com.batterycast.quant.forecast.BatteryForecastEngine
import com.batterycast.quant.forecast.DrainEstimator
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ForecastAlertEvaluator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: BatteryRepository,
    private val engine: BatteryForecastEngine
) {
    suspend fun evaluate() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        val preferences = context.getSharedPreferences("batterycast_settings", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val history = repository.historyOnce(now - 14L * 24 * 60 * 60_000L)
        if (history.size < 2) return

        evaluateChargeReminder(preferences, now)
        evaluatePersistentDrain(preferences, history, now)

        val target = preferences.getLong("target_time", 0L)
        val reserve = preferences.getInt("reserve", 10)
        if (target <= now) return
        val forecast = engine.forecast(history, target, reserve, now = history.last().timestamp)
        val probability = forecast.survivalProbability ?: return
        val previous = java.lang.Double.longBitsToDouble(
            preferences.getLong("last_probability_bits", java.lang.Double.doubleToLongBits(1.0))
        )
        val lastAlert = preferences.getLong("last_survival_alert", 0L)
        if (previous >= .70 && probability < .70 && now - lastAlert > 2 * 60 * 60_000L) {
            notify(
                id = 6201,
                title = "Battery survival probability fell below 70%",
                text = "BatteryCast now estimates ${(probability * 100).toInt()}% chance of staying above " +
                    "$reserve% until ${formatTime(target)}."
            )
            preferences.edit().putLong("last_survival_alert", now).apply()
        }
        preferences.edit()
            .putLong("last_probability_bits", java.lang.Double.doubleToLongBits(probability))
            .apply()
    }

    private fun evaluateChargeReminder(
        preferences: android.content.SharedPreferences,
        now: Long
    ) {
        val start = preferences.getLong("charge_plan_start", 0L)
        val duration = preferences.getInt("charge_plan_duration", 0)
        val target = preferences.getInt("charge_plan_target", 0)
        val event = preferences.getLong("charge_plan_event", 0L)
        val alertedStart = preferences.getLong("charge_plan_alerted_start", 0L)
        if (start <= now || start - now > 20 * 60_000L || start == alertedStart || event <= now) return
        notify(
            id = 6202,
            title = "Start charging soon",
            text = "Start by ${formatTime(start)} and charge for about $duration minutes to target $target% by ${formatTime(event)}."
        )
        preferences.edit().putLong("charge_plan_alerted_start", start).apply()
    }

    private fun evaluatePersistentDrain(
        preferences: android.content.SharedPreferences,
        history: List<com.batterycast.quant.model.BatteryObservation>,
        now: Long
    ) {
        val estimate = DrainEstimator().estimate(history, history.last().timestamp) ?: return
        val recent = estimate.horizonRates["60m"]
        val longer = estimate.horizonRates["3h"]
        val unusual = recent != null && longer != null && recent > longer * 1.35 && recent > longer + 1.0
        val editor = preferences.edit()
        if (!unusual) {
            editor.remove("unusual_drain_since").apply()
            return
        }

        val since = preferences.getLong("unusual_drain_since", 0L)
        if (since == 0L) {
            editor.putLong("unusual_drain_since", now).apply()
            return
        }
        val lastAlert = preferences.getLong("last_unusual_drain_alert", 0L)
        if (now - since >= 30 * 60_000L && now - lastAlert >= 6 * 60 * 60_000L) {
            notify(
                id = 6203,
                title = "Battery is draining faster than usual",
                text = "The faster drain has persisted for at least 30 minutes compared with the recent three-hour pattern."
            )
            preferences.edit().putLong("last_unusual_drain_alert", now).apply()
        }
    }

    private fun notify(id: Int, title: String, text: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Battery forecasts", NotificationManager.IMPORTANCE_DEFAULT)
        )
        manager.notify(
            id,
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_lock_idle_low_battery)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .build()
        )
    }

    private fun formatTime(timestamp: Long): String =
        DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(timestamp))

    companion object {
        const val CHANNEL = "forecast_alerts"
    }
}

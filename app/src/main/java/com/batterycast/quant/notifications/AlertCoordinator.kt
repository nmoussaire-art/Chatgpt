package com.batterycast.quant.notifications

import com.batterycast.quant.core.database.dao.NotificationStateDao
import com.batterycast.quant.core.database.entity.NotificationStateEntity
import com.batterycast.quant.core.datastore.SettingsStore
import com.batterycast.quant.forecasting.ForecastEngine
import com.batterycast.quant.forecasting.ForecastRequest
import com.batterycast.quant.forecasting.model.DataMaturity
import com.batterycast.quant.telemetry.model.BatteryObservation
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Decides when an alert is worth interrupting someone for.
 *
 * The bar is deliberately high. A battery app that notifies on every fluctuation gets silenced
 * within a day, at which point it protects nobody. Three rules keep it quiet:
 *
 * - **Threshold crossings, not levels.** An alert fires when the survival probability *crosses*
 *   a threshold, not while it sits below one.
 * - **Hysteresis.** Having fired on the way down, the probability must recover meaningfully
 *   before the same alert can fire again, so a value hovering at the boundary cannot oscillate.
 * - **Cooldown.** Each trigger has a minimum interval regardless of what the numbers do.
 *
 * Nothing fires at all until the model is mature enough for the probability to mean something.
 */
@Singleton
class AlertCoordinator @Inject constructor(
    private val forecastEngine: ForecastEngine,
    private val notifier: BatteryCastNotifier,
    private val notificationStateDao: NotificationStateDao,
    private val settingsStore: SettingsStore,
) {

    suspend fun evaluate(observation: BatteryObservation, nowMs: Long = System.currentTimeMillis()) {
        val settings = settingsStore.current()
        if (!settings.notificationsEnabled) return
        if (!notifier.canPostNotifications()) return

        val targetMs = settings.selectedTargetMs ?: defaultTargetMs(nowMs)
        if (targetMs <= nowMs) return

        val context = forecastEngine.buildContext(nowMs) ?: return
        if (context.maturity < DataMaturity.BASIC) return

        val forecast = forecastEngine.buildForecast(
            context,
            ForecastRequest(
                horizonMs = (targetMs - nowMs).coerceAtMost(ForecastRequest.DEFAULT_HORIZON_MS),
                reservePercent = settings.reservePercent,
                targetMs = targetMs,
                targetLabel = settings.selectedTargetLabel ?: "your target",
            ),
            nowMs,
        )

        val target = forecast.target ?: return
        val targetLabel = settings.selectedTargetLabel ?: formatTime(targetMs)

        checkSurvivalDrop(target.survivalProbability, targetLabel, settings.reservePercent, nowMs)
        checkSurvivalRecovery(target.survivalProbability, targetLabel, settings.reservePercent, nowMs)
        if (settings.unusualDrainAlerts) checkUnusualDrain(context, nowMs)
    }

    private suspend fun checkSurvivalDrop(
        probability: Double,
        targetLabel: String,
        reservePercent: Double,
        nowMs: Long,
    ) {
        val previous = notificationStateDao.get(KEY_SURVIVAL)
        val crossedDown = probability < SURVIVAL_ALERT_THRESHOLD &&
            (previous == null || previous.lastValue >= SURVIVAL_ALERT_THRESHOLD)
        if (!crossedDown) return
        if (!cooldownElapsed(previous, nowMs, SURVIVAL_COOLDOWN_MS)) return

        notifier.postSurvivalAlert(
            title = "Battery may not last until $targetLabel",
            body = "The chance of staying above ${reservePercent.roundToInt()}% until $targetLabel " +
                "has fallen to ${(probability * 100).roundToInt()}%.",
        )
        notificationStateDao.upsert(NotificationStateEntity(KEY_SURVIVAL, nowMs, probability))
    }

    /**
     * The good-news alert: you can unplug now and still be fine.
     *
     * Fires only when charging, so it arrives when it is actionable.
     */
    private suspend fun checkSurvivalRecovery(
        probability: Double,
        targetLabel: String,
        reservePercent: Double,
        nowMs: Long,
    ) {
        val previous = notificationStateDao.get(KEY_SURVIVAL) ?: return
        val recovered = probability >= SURVIVAL_RECOVERY_THRESHOLD &&
            previous.lastValue < SURVIVAL_ALERT_THRESHOLD
        if (!recovered) return
        if (!cooldownElapsed(previous, nowMs, SURVIVAL_COOLDOWN_MS)) return

        notifier.postSurvivalAlert(
            title = "You can unplug now",
            body = "There is a ${(probability * 100).roundToInt()}% chance of staying above " +
                "${reservePercent.roundToInt()}% until $targetLabel.",
        )
        notificationStateDao.upsert(NotificationStateEntity(KEY_SURVIVAL, nowMs, probability))
    }

    /**
     * Flags drain that is well outside this phone's normal range for the state it is in — and
     * only when it has persisted, so a two-minute video call cannot trigger it.
     */
    private suspend fun checkUnusualDrain(
        context: com.batterycast.quant.forecasting.ForecastContext,
        nowMs: Long,
    ) {
        val recent = context.estimates.blendedPercentPerHour ?: return
        if (recent.spanHours < MIN_UNUSUAL_DRAIN_SPAN_HOURS) return

        val baseline = context.snapshot.drainByRegime[context.currentRegime] ?: return
        if (baseline.mean <= MIN_BASELINE_FOR_ALERT) return
        // Two robust standard deviations above the learned mean, and at least half again as fast.
        val boundary = baseline.mean + UNUSUAL_SIGMA * baseline.standardDeviation
        if (recent.ratePerHour < maxOf(boundary, baseline.mean * UNUSUAL_MULTIPLE)) return

        val previous = notificationStateDao.get(KEY_UNUSUAL_DRAIN)
        if (!cooldownElapsed(previous, nowMs, UNUSUAL_DRAIN_COOLDOWN_MS)) return
        if (previous != null && abs(previous.lastValue - recent.ratePerHour) < MIN_CHANGE_TO_REPEAT) return

        notifier.postDrainAlert(
            title = "Draining faster than usual",
            body = "About ${format(recent.ratePerHour)}%/h over the last hour, against " +
                "${format(baseline.mean)}%/h typical for this phone in this state.",
        )
        notificationStateDao.upsert(NotificationStateEntity(KEY_UNUSUAL_DRAIN, nowMs, recent.ratePerHour))
    }

    private fun cooldownElapsed(state: NotificationStateEntity?, nowMs: Long, cooldownMs: Long): Boolean =
        state == null || nowMs - state.lastFiredAtMs >= cooldownMs

    /** Bedtime today, or tomorrow if it has already passed. */
    private suspend fun defaultTargetMs(nowMs: Long): Long {
        val settings = settingsStore.current()
        val zone = ZoneId.systemDefault()
        val now = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMs), zone)
        val bedtime = now.with(LocalTime.of(settings.bedtimeHour, settings.bedtimeMinute))
        val resolved = if (bedtime.isAfter(now)) bedtime else bedtime.plusDays(1)
        return resolved.toInstant().toEpochMilli()
    }

    private fun formatTime(atMs: Long): String {
        val zoned = ZonedDateTime.ofInstant(Instant.ofEpochMilli(atMs), ZoneId.systemDefault())
        return String.format("%02d:%02d", zoned.hour, zoned.minute)
    }

    private fun format(value: Double): String = String.format("%.1f", value)

    companion object {
        const val KEY_SURVIVAL = "survival_probability"
        const val KEY_UNUSUAL_DRAIN = "unusual_drain"

        /** Below this, the target is genuinely at risk. */
        const val SURVIVAL_ALERT_THRESHOLD = 0.70

        /** Hysteresis gap: the probability must climb this far back before the all-clear. */
        const val SURVIVAL_RECOVERY_THRESHOLD = 0.90

        const val SURVIVAL_COOLDOWN_MS = 3 * 60 * 60 * 1000L
        const val UNUSUAL_DRAIN_COOLDOWN_MS = 6 * 60 * 60 * 1000L

        const val MIN_UNUSUAL_DRAIN_SPAN_HOURS = 0.75
        const val MIN_BASELINE_FOR_ALERT = 0.5
        const val UNUSUAL_SIGMA = 2.0
        const val UNUSUAL_MULTIPLE = 1.5
        const val MIN_CHANGE_TO_REPEAT = 2.0
    }
}

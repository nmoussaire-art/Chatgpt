package com.ontimequant.notifications

import com.ontimequant.forecast.JourneyForecast
import com.ontimequant.model.RiskLevel
import java.time.Duration
import java.time.Instant
import kotlin.math.abs
import kotlin.math.roundToInt

/** What a notification, if any, should say. */
sealed interface NotificationDecision {
    data object Silent : NotificationDecision
    data class Send(
        val kind: NotificationKind,
        val title: String,
        val body: String,
    ) : NotificationDecision
}

enum class NotificationKind {
    DEPARTURE_APPROACHING,
    RISK_INCREASED,
    MORE_TIME_AVAILABLE,
    BELOW_TARGET,
    ADVANCE_SUMMARY,
    MISSING_DESTINATION,
}

/** The previously-notified state, loaded from `notification_state`. */
data class NotificationMemory(
    val lastSent: Instant?,
    val lastRecommendedDeparture: Instant?,
    val lastOnTimeProbability: Double?,
    val lastRiskLevel: RiskLevel?,
    val lastKind: NotificationKind?,
)

/**
 * Decides whether a forecast change is worth interrupting someone for.
 *
 * The bar is deliberately high. A punctuality app that pings every five minutes is a
 * punctuality app people turn off, and a silenced app forecasts nothing useful.
 *
 * A notification is sent only when **one** of these is true:
 *  - the recommended departure moved by at least [MATERIAL_SHIFT_MINUTES];
 *  - the on-time probability crossed the user's confidence target;
 *  - the risk classification changed by a whole level;
 *  - departure is imminent and no departure alert has been sent for this journey.
 *
 * And then only if the cool-down has elapsed and the message differs from the last one.
 */
object NotificationPolicy {

    const val MATERIAL_SHIFT_MINUTES = 3L
    val COOLDOWN: Duration = Duration.ofMinutes(20)
    val IMMINENT_WINDOW: Duration = Duration.ofMinutes(10)
    val ADVANCE_WINDOW: Duration = Duration.ofMinutes(60)

    fun decide(
        forecast: JourneyForecast,
        memory: NotificationMemory,
        now: Instant,
        remindersEnabled: Boolean,
        changeAlertsEnabled: Boolean,
    ): NotificationDecision {
        val recommended = forecast.recommendedDeparture
        val target = forecast.confidenceTarget
        val probability = forecast.onTimeProbability
        val cooledDown = memory.lastSent == null ||
            Duration.between(memory.lastSent, now) >= COOLDOWN

        // 1. Imminent departure always wins, and is exempt from the cool-down only if the
        //    departure reminder itself has not already been sent.
        if (remindersEnabled && recommended != null) {
            val until = Duration.between(now, recommended)
            if (!until.isNegative && until <= IMMINENT_WINDOW &&
                memory.lastKind != NotificationKind.DEPARTURE_APPROACHING
            ) {
                val minutes = until.toMinutes()
                return NotificationDecision.Send(
                    kind = NotificationKind.DEPARTURE_APPROACHING,
                    title = if (minutes <= 1) "Time to leave" else "Leave in $minutes minutes",
                    body = "${percent(probability)} chance of reaching ${forecast.appointment.destination.label} " +
                        "in time for ${forecast.appointment.title}.",
                )
            }
        }

        if (!changeAlertsEnabled) return NotificationDecision.Silent
        if (!cooledDown) return NotificationDecision.Silent

        // 2. Fell below the confidence target having previously been at or above it.
        val wasAbove = memory.lastOnTimeProbability?.let { it >= target } ?: true
        if (wasAbove && probability < target && memory.lastKind != NotificationKind.BELOW_TARGET) {
            val leaveIn = recommended?.let { Duration.between(now, it).toMinutes() }
            return NotificationDecision.Send(
                kind = NotificationKind.BELOW_TARGET,
                title = "You are below your ${percent(target)} target",
                body = if (leaveIn != null && leaveIn >= 0) {
                    "Traffic risk has increased. Leave within the next $leaveIn minutes to get back to " +
                        "${percent(target)}."
                } else {
                    "On current conditions the chance of arriving on time is ${percent(probability)}."
                },
            )
        }

        // 3. The recommendation moved materially.
        if (recommended != null && memory.lastRecommendedDeparture != null) {
            val shift = Duration.between(memory.lastRecommendedDeparture, recommended).toMinutes()
            if (abs(shift) >= MATERIAL_SHIFT_MINUTES) {
                return if (shift < 0) {
                    NotificationDecision.Send(
                        kind = NotificationKind.RISK_INCREASED,
                        title = "Traffic risk has increased",
                        body = "Leave ${abs(shift)} minutes earlier than planned — by ${clock(recommended)} — " +
                            "to hold your ${percent(target)} on-time probability.",
                    )
                } else {
                    NotificationDecision.Send(
                        kind = NotificationKind.MORE_TIME_AVAILABLE,
                        title = "You can leave later",
                        body = "Conditions have eased. Leaving at ${clock(recommended)} still keeps you at " +
                            "${percent(target)}.",
                    )
                }
            }
        }

        // 4. Risk classification changed by a whole level.
        if (memory.lastRiskLevel != null && memory.lastRiskLevel != forecast.trafficRisk &&
            forecast.trafficRisk.ordinal > memory.lastRiskLevel.ordinal
        ) {
            return NotificationDecision.Send(
                kind = NotificationKind.RISK_INCREASED,
                title = "Risk is now ${forecast.trafficRisk.name.lowercase()}",
                body = recommended?.let {
                    "Recommended departure is ${clock(it)} for ${percent(probability)} on time."
                } ?: "No departure in the next hour reaches your target.",
            )
        }

        // 5. A single quiet heads-up an hour out, when nothing has been said yet.
        if (recommended != null && memory.lastSent == null) {
            val until = Duration.between(now, recommended)
            if (!until.isNegative && until <= ADVANCE_WINDOW) {
                return NotificationDecision.Send(
                    kind = NotificationKind.ADVANCE_SUMMARY,
                    title = "${forecast.appointment.title} at ${clock(forecast.appointment.startTime, forecast)}",
                    body = "Recommended departure is ${clock(recommended)} for ${percent(probability)} " +
                        "chance of arriving on time.",
                )
            }
        }

        return NotificationDecision.Silent
    }

    /** A separate, once-per-event nudge for calendar entries with no usable location. */
    fun missingDestination(eventTitle: String, whenLabel: String): NotificationDecision.Send =
        NotificationDecision.Send(
            kind = NotificationKind.MISSING_DESTINATION,
            title = "No destination for $eventTitle",
            body = "$whenLabel has no location, so OnTime Quant cannot forecast a departure. " +
                "Add one in your calendar or set a destination in the app.",
        )

    private fun percent(value: Double) = "${(value * 100).roundToInt()}%"

    private fun clock(instant: Instant, forecast: JourneyForecast? = null): String {
        val zone = forecast?.appointment?.zone ?: java.time.ZoneId.systemDefault()
        val local = instant.atZone(zone).toLocalTime()
        return "%02d:%02d".format(local.hour, local.minute)
    }
}

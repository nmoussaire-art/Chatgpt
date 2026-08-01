package com.ontimequant.ui.format

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.platform.LocalContext
import com.ontimequant.model.DataProvenance
import com.ontimequant.model.PersonalizationLevel
import com.ontimequant.model.RiskLevel
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Time formatting.
 *
 * All instants are absolute; the wall clock is only ever produced here, from the
 * appointment's own zone plus the device's locale and 12/24-hour preference. Nothing in
 * the app formats a time by hand, so a user who flips their device to 12-hour format sees
 * it everywhere at once.
 */
class TimeFormatter(
    val zone: ZoneId,
    private val use24Hour: Boolean,
    private val locale: Locale,
) {
    private val timeFormat: DateTimeFormatter =
        DateTimeFormatter.ofPattern(if (use24Hour) "HH:mm" else "h:mm a", locale)

    private val dayFormat: DateTimeFormatter =
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)

    private val weekdayFormat: DateTimeFormatter =
        DateTimeFormatter.ofPattern("EEEE", locale)

    fun clock(instant: Instant): String = timeFormat.format(instant.atZone(zone))

    fun clock(instant: Instant, inZone: ZoneId): String = timeFormat.format(instant.atZone(inZone))

    fun date(instant: Instant): String = dayFormat.format(instant.atZone(zone))

    fun weekday(instant: Instant): String = weekdayFormat.format(instant.atZone(zone))

    /** "Today", "Tomorrow", or the weekday and date. */
    fun dayLabel(instant: Instant, now: Instant = Instant.now()): String {
        val target = instant.atZone(zone).toLocalDate()
        val today = now.atZone(zone).toLocalDate()
        return when (target) {
            today -> "Today"
            today.plusDays(1) -> "Tomorrow"
            today.minusDays(1) -> "Yesterday"
            else -> if (target.isBefore(today.plusDays(7)) && target.isAfter(today)) {
                weekday(instant)
            } else {
                dayFormat.format(target)
            }
        }
    }

    fun dayAndClock(instant: Instant, now: Instant = Instant.now()): String =
        "${dayLabel(instant, now)}, ${clock(instant)}"

    /** "7:38 AM – 7:59 AM" */
    fun range(from: Instant, to: Instant): String = "${clock(from)} – ${clock(to)}"

    companion object {
        fun forZone(context: Context, zone: ZoneId, override: Boolean?): TimeFormatter {
            val use24 = override ?: DateFormat.is24HourFormat(context)
            return TimeFormatter(zone, use24, Locale.getDefault())
        }
    }
}

val LocalTimeFormatter = compositionLocalOf {
    TimeFormatter(ZoneId.systemDefault(), true, Locale.getDefault())
}

@Composable
fun rememberTimeFormatter(zone: ZoneId, use24Hour: Boolean?): TimeFormatter {
    val context = LocalContext.current
    return TimeFormatter.forZone(context, zone, use24Hour)
}

// ---------------------------------------------------------------------------
// Durations and numbers
// ---------------------------------------------------------------------------

/** "12 min", "1 hr 5 min", "under a minute". */
fun formatDuration(seconds: Double, alwaysSigned: Boolean = false): String {
    val total = abs(seconds).roundToInt()
    val sign = when {
        !alwaysSigned -> ""
        seconds > 0 -> "+"
        seconds < 0 -> "−"
        else -> ""
    }
    if (total < 60) return if (total < 30 && !alwaysSigned) "under a minute" else "${sign}${(total / 60.0).roundToInt().coerceAtLeast(0)} min"
    val minutes = (total + 30) / 60
    return if (minutes < 60) {
        "$sign$minutes min"
    } else {
        val hours = minutes / 60
        val rest = minutes % 60
        if (rest == 0) "$sign$hours hr" else "$sign$hours hr $rest min"
    }
}

fun formatMinutes(seconds: Double): String {
    val minutes = (abs(seconds) / 60).roundToInt()
    return "$minutes min"
}

/** Countdown for the home screen: "in 42 min", "in 2 hr 10 min", "now". */
fun formatCountdown(from: Instant, to: Instant): String {
    val duration = Duration.between(from, to)
    if (duration.isNegative) {
        val late = formatDuration(abs(duration.seconds.toDouble()))
        return "$late ago"
    }
    if (duration.seconds < 60) return "now"
    return "in ${formatDuration(duration.seconds.toDouble())}"
}

fun formatPercent(value: Double, decimals: Int = 0): String =
    if (decimals == 0) "${(value * 100).roundToInt()}%"
    else String.format(Locale.getDefault(), "%.${decimals}f%%", value * 100)

fun formatDistance(metres: Double, metric: Boolean): String = if (metric) {
    if (metres < 950) "${(metres / 10).roundToInt() * 10} m"
    else String.format(Locale.getDefault(), "%.1f km", metres / 1000)
} else {
    val miles = metres / 1609.344
    if (miles < 0.2) "${(metres * 3.28084 / 10).roundToInt() * 10} ft"
    else String.format(Locale.getDefault(), "%.1f mi", miles)
}

// ---------------------------------------------------------------------------
// Labels
// ---------------------------------------------------------------------------

fun RiskLevel.label(): String = when (this) {
    RiskLevel.LOW -> "Low"
    RiskLevel.MODERATE -> "Moderate"
    RiskLevel.ELEVATED -> "Elevated"
    RiskLevel.HIGH -> "High"
}

/**
 * A shape name for each risk level, so risk is never conveyed by colour alone.
 * Screen readers get this string too.
 */
fun RiskLevel.glyph(): String = when (this) {
    RiskLevel.LOW -> "●"
    RiskLevel.MODERATE -> "◆"
    RiskLevel.ELEVATED -> "▲"
    RiskLevel.HIGH -> "■"
}

fun RiskLevel.description(): String = when (this) {
    RiskLevel.LOW -> "Conditions are stable and the journey is predictable."
    RiskLevel.MODERATE -> "Normal variability. The recommendation already allows for it."
    RiskLevel.ELEVATED -> "This journey is less predictable than usual today."
    RiskLevel.HIGH -> "Conditions are volatile. Leaving earlier buys a lot of certainty."
}

fun DataProvenance.label(): String = when (this) {
    DataProvenance.LIVE -> "Live"
    DataProvenance.CACHED -> "Cached"
    DataProvenance.DEMO -> "Demo"
    DataProvenance.LEARNED -> "Learned"
    DataProvenance.DEFAULT -> "Default"
    DataProvenance.USER_DEFINED -> "Your setting"
    DataProvenance.UNAVAILABLE -> "Unavailable"
}

fun DataProvenance.explanation(): String = when (this) {
    DataProvenance.LIVE -> "Fetched from a live provider for this forecast."
    DataProvenance.CACHED -> "Reused from an earlier fetch because the live call failed."
    DataProvenance.DEMO -> "Generated offline by the built-in demo scenario. Not live data."
    DataProvenance.LEARNED -> "Estimated from your own completed journeys."
    DataProvenance.DEFAULT -> "A documented standard assumption, not measured for you."
    DataProvenance.USER_DEFINED -> "A value you entered."
    DataProvenance.UNAVAILABLE -> "Could not be obtained, so it was excluded."
}

fun PersonalizationLevel.shortDescription(): String = when (this) {
    PersonalizationLevel.PRELIMINARY -> "Routing data and conservative assumptions"
    PersonalizationLevel.LEARNING -> "Starting to use your own journeys"
    PersonalizationLevel.PARTIALLY_PERSONALIZED -> "Partly tuned to your journeys"
    PersonalizationLevel.PERSONALIZED -> "Tuned to your journeys on this route"
}

fun todayIn(zone: ZoneId): LocalDate = LocalDate.now(zone)

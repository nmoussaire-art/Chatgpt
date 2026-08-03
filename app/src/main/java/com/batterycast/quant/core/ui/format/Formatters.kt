package com.batterycast.quant.core.ui.format

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Presentation rules for every number the app shows.
 *
 * One rule governs all of them: never display precision the measurement cannot support. Android
 * reports battery level in whole percentage points, so battery percentages are shown as integers —
 * "17%", not "17.3%". Rates and temperatures carry one decimal because they are derived from
 * regressions over many points and that decimal is real. Probabilities are whole percentages,
 * because a Monte Carlo run of a few thousand paths cannot justify a tenth of a percent.
 */
object Formatters {

    private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
    private val dateTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofLocalizedDateTime(
        FormatStyle.MEDIUM,
        FormatStyle.SHORT,
    )
    private val dayFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE")

    /** Battery percentage, at the resolution the platform actually reports. */
    fun percent(value: Double?): String = value?.let { "${it.roundToInt()}%" } ?: "—"

    /** Probability given in 0..1, rendered as a whole percentage. */
    fun probability(value: Double?): String = value?.let { "${(it * 100).roundToInt()}%" } ?: "—"

    /** Drain or charge rate. One decimal, because the regression supports it. */
    fun ratePerHour(value: Double?): String =
        value?.let { String.format("%.1f%%/h", it) } ?: "—"

    fun milliAmps(value: Double?): String = value?.let { "${it.roundToInt()} mA" } ?: "—"

    fun watts(value: Double?): String = value?.let { String.format("%.2f W", it) } ?: "—"

    fun temperature(celsius: Double?): String =
        celsius?.let { String.format("%.1f °C", it) } ?: "—"

    fun voltage(volts: Double?): String = volts?.let { String.format("%.2f V", it) } ?: "—"

    fun milliAmpHours(value: Double?): String = value?.let { "${it.roundToInt()} mAh" } ?: "—"

    /** Clock time in the user's locale and time zone. */
    fun clockTime(atMs: Long?, zoneId: ZoneId = ZoneId.systemDefault()): String {
        if (atMs == null) return "—"
        return timeFormatter.format(ZonedDateTime.ofInstant(Instant.ofEpochMilli(atMs), zoneId))
    }

    fun dateAndTime(atMs: Long?, zoneId: ZoneId = ZoneId.systemDefault()): String {
        if (atMs == null) return "—"
        return dateTimeFormatter.format(ZonedDateTime.ofInstant(Instant.ofEpochMilli(atMs), zoneId))
    }

    /**
     * Clock time, qualified with the day when it is not today.
     *
     * "11:08 PM" versus "11:08 PM tomorrow" — the difference matters a great deal when the answer
     * is when your battery runs out.
     */
    fun clockTimeWithDay(atMs: Long?, nowMs: Long, zoneId: ZoneId = ZoneId.systemDefault()): String {
        if (atMs == null) return "—"
        val target = ZonedDateTime.ofInstant(Instant.ofEpochMilli(atMs), zoneId)
        val today = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMs), zoneId).toLocalDate()
        val targetDate: LocalDate = target.toLocalDate()
        val time = timeFormatter.format(target)
        return when (targetDate) {
            today -> time
            today.plusDays(1) -> "$time tomorrow"
            else -> "$time ${dayFormatter.format(target)}"
        }
    }

    /**
     * A duration in words.
     *
     * Rounded to five minutes beyond an hour, because a forecast that claims "7 hours and 23
     * minutes" is asserting a precision no battery model has.
     */
    fun duration(durationMs: Long?): String {
        if (durationMs == null) return "—"
        if (durationMs < 0) return "—"
        val totalMinutes = (durationMs / 60_000.0).roundToInt()
        if (totalMinutes < 1) return "under a minute"
        if (totalMinutes < 60) return "$totalMinutes min"

        val hours = totalMinutes / 60
        val minutes = ((totalMinutes % 60) / 5.0).roundToInt() * 5
        val carriedHours = if (minutes == 60) hours + 1 else hours
        val displayMinutes = if (minutes == 60) 0 else minutes

        return when {
            displayMinutes == 0 && carriedHours == 1 -> "1 hour"
            displayMinutes == 0 -> "$carriedHours hours"
            carriedHours == 1 -> "1 hr $displayMinutes min"
            else -> "$carriedHours hr $displayMinutes min"
        }
    }

    /** Compact relative age, used for the data-freshness line. */
    fun relativeAge(ageMs: Long): String {
        val minutes = (ageMs / 60_000.0).roundToInt()
        return when {
            minutes < 1 -> "just now"
            minutes == 1 -> "1 minute ago"
            minutes < 60 -> "$minutes minutes ago"
            minutes < 120 -> "1 hour ago"
            minutes < 60 * 24 -> "${minutes / 60} hours ago"
            else -> "${minutes / (60 * 24)} days ago"
        }
    }

    /** A percentage-point range, e.g. "11%–24%". */
    fun percentRange(low: Double?, high: Double?): String {
        if (low == null || high == null) return "—"
        return "${low.roundToInt()}%–${high.roundToInt()}%"
    }

    /** Signed difference in percentage points. */
    fun signedPercentPoints(value: Double?): String {
        if (value == null) return "—"
        val rounded = value.roundToInt()
        return when {
            rounded > 0 -> "+$rounded pts"
            rounded < 0 -> "$rounded pts"
            else -> "0 pts"
        }
    }

    /** Relative change as a percentage, e.g. "28% faster". */
    fun relativeChange(ratio: Double?): String {
        if (ratio == null) return "—"
        val difference = ((ratio - 1.0) * 100).roundToInt()
        return when {
            difference > 0 -> "$difference% faster"
            difference < 0 -> "${abs(difference)}% slower"
            else -> "unchanged"
        }
    }

    /** Plain-language description of a probability, for screen readers and supporting text. */
    fun probabilityWords(probability: Double): String = when {
        probability >= 0.95 -> "almost certain"
        probability >= 0.85 -> "very likely"
        probability >= 0.65 -> "likely"
        probability >= 0.45 -> "roughly even"
        probability >= 0.25 -> "unlikely"
        else -> "very unlikely"
    }
}

package com.adcbtracker.data

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

val UAE_ZONE: ZoneId = ZoneId.of("Asia/Dubai")

fun LocalDate.startMillis(): Long = atStartOfDay(UAE_ZONE).toInstant().toEpochMilli()

fun Long.toUaeDate(): LocalDate = Instant.ofEpochMilli(this).atZone(UAE_ZONE).toLocalDate()

/** A billing cycle: [start, endExclusive). With startDay = 1 it is a plain calendar month. */
data class Cycle(val start: LocalDate, val endExclusive: LocalDate) {
    val startMillis: Long get() = start.startMillis()
    val endMillis: Long get() = endExclusive.startMillis()
    val lengthDays: Int get() = ChronoUnit.DAYS.between(start, endExclusive).toInt()
    val lastDay: LocalDate get() = endExclusive.minusDays(1)

    fun contains(date: LocalDate): Boolean = !date.isBefore(start) && date.isBefore(endExclusive)

    /** 0-based index of [date] within this cycle. */
    fun dayIndex(date: LocalDate): Int = ChronoUnit.DAYS.between(start, date).toInt()

    fun label(includeYear: Boolean = true): String {
        if (start.dayOfMonth == 1 && lastDay.month == start.month) {
            return start.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US))
        }
        val dm = DateTimeFormatter.ofPattern("d MMM", Locale.US)
        val dmy = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.US)
        val left = if (start.year == lastDay.year) start.format(dm) else start.format(dmy)
        return "$left – ${lastDay.format(if (includeYear) dmy else dm)}"
    }

    fun shortLabel(): String =
        if (start.dayOfMonth == 1) start.format(DateTimeFormatter.ofPattern("MMM", Locale.US))
        else start.format(DateTimeFormatter.ofPattern("d MMM", Locale.US))
}

object Cycles {
    private fun startIn(ym: YearMonth, startDay: Int): LocalDate = ym.atDay(minOf(startDay, ym.lengthOfMonth()))

    private fun fromStartMonth(ym: YearMonth, startDay: Int): Cycle =
        Cycle(startIn(ym, startDay), startIn(ym.plusMonths(1), startDay))

    /** The cycle that contains [date]. */
    fun cycleFor(date: LocalDate, startDay: Int): Cycle {
        val ym = YearMonth.from(date)
        val startYm = if (date.isBefore(startIn(ym, startDay))) ym.minusMonths(1) else ym
        return fromStartMonth(startYm, startDay)
    }

    /** The cycle [cyclesAgo] cycles before the one containing [today] (0 = current). */
    fun cycleAgo(today: LocalDate, startDay: Int, cyclesAgo: Int): Cycle {
        val current = cycleFor(today, startDay)
        return fromStartMonth(YearMonth.from(current.start).minusMonths(cyclesAgo.toLong()), startDay)
    }
}

package com.adcbtracker

import com.adcbtracker.data.Cycles
import com.adcbtracker.data.Projections
import com.adcbtracker.data.TransactionEntity
import com.adcbtracker.data.TransactionWithCategory
import com.adcbtracker.data.UAE_ZONE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ProjectionTest {
    private var id = 0L
    private fun tx(date: LocalDate, aed: Long) = TransactionWithCategory(
        TransactionEntity(
            id = ++id, source = "sms", cardId = null, amountMinor = aed * 100, currency = "AED", merchant = "M$id",
            location = null, tsEpochMillis = date.atTime(12, 0).atZone(UAE_ZONE).toInstant().toEpochMilli(),
            avlCreditLimitMinor = null, rawText = "", dedupeKey = "k$id",
        ),
        null,
    )

    @Test
    fun oneOffIsNotExtrapolated() {
        val today = LocalDate.of(2026, 10, 4) // day 11 of the 24 Sep - 23 Oct cycle
        val cycle = Cycles.cycleFor(today, 24)
        val txs = (0 until 11).map { tx(cycle.start.plusDays(it.toLong()), 100) } + tx(cycle.start, 4000)
        val p = Projections.project(cycle, txs, today, emptyList(), 100_000)
        // 1100 regular + 4000 one-off spent; 100/day for the 19 remaining days.
        assertEquals(19, p.daysLeft)
        assertEquals(10_000L, p.usualDailyMinor)
        assertEquals((1100 + 4000 + 1900) * 100L, p.totalMinor)
        assertEquals(1, p.oneOffCount)
        // The old naive method (total / elapsed × length) would have said ~AED 13,909.
        val naive = (5100.0 / 11 * 30).toLong()
        assertTrue(p.totalMinor / 100 < naive)
    }

    @Test
    fun blendsWithPastCyclesEarlyInCycle() {
        val today = LocalDate.of(2026, 9, 26) // day 3 of 24 Sep - 23 Oct
        val cycle = Cycles.cycleFor(today, 24)
        val past = Cycles.cycleAgo(today, 24, 1)
        val pastTxs = (0 until past.lengthDays).map { tx(past.start.plusDays(it.toLong()), 200) }
        val curTxs = (0 until 3).map { tx(cycle.start.plusDays(it.toLong()), 50) }
        val p = Projections.project(cycle, pastTxs + curTxs, today, listOf(past), 100_000)
        // weight 3/30 on this cycle's 50/day, 27/30 on last cycle's 200/day = 185/day
        assertEquals(18_500L, p.usualDailyMinor)
        assertEquals(1, p.basedOnCycles)
    }
}

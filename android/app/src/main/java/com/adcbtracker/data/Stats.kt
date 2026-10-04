package com.adcbtracker.data

import java.time.LocalDate

/** Only positive amounts count as spending; reversals are stored as negatives. */
val TransactionEntity.spendMinor: Long get() = if (amountMinor > 0) amountMinor else 0L

data class DaySummary(
    val date: LocalDate,
    val totalMinor: Long,
    val count: Int,
    val transactions: List<TransactionWithCategory>,
)

data class CategoryTotal(
    val category: Category?,
    val totalMinor: Long,
    val count: Int,
)

data class MerchantTotal(
    val merchant: String,
    val totalMinor: Long,
    val count: Int,
    val category: Category?,
)

data class CycleStats(
    val cycle: Cycle,
    val totalMinor: Long,
    val count: Int,
    /** Spend per day of the cycle, indexed by [Cycle.dayIndex]. */
    val dailyMinor: LongArray,
    val categories: List<CategoryTotal>,
    val topMerchants: List<MerchantTotal>,
    val biggest: List<TransactionWithCategory>,
    /** Average spend per weekday, Monday = 0. */
    val weekdayAvgMinor: LongArray,
) {
    fun cumulative(): LongArray {
        val out = LongArray(dailyMinor.size)
        var running = 0L
        for (i in dailyMinor.indices) {
            running += dailyMinor[i]
            out[i] = running
        }
        return out
    }
}

object Stats {
    fun groupByDay(txs: List<TransactionWithCategory>): List<DaySummary> =
        txs.groupBy { it.tx.tsEpochMillis.toUaeDate() }
            .map { (date, list) ->
                DaySummary(
                    date = date,
                    totalMinor = list.sumOf { it.tx.spendMinor },
                    count = list.size,
                    transactions = list.sortedByDescending { it.tx.tsEpochMillis },
                )
            }
            .sortedByDescending { it.date }

    /** Last [days] days ending today (today first), including days with no spending. */
    fun recentDays(txs: List<TransactionWithCategory>, today: LocalDate, days: Int): List<DaySummary> {
        val byDay = groupByDay(txs).associateBy { it.date }
        return (0 until days).map { offset ->
            val d = today.minusDays(offset.toLong())
            byDay[d] ?: DaySummary(d, 0L, 0, emptyList())
        }
    }

    fun forCycle(cycle: Cycle, txs: List<TransactionWithCategory>, today: LocalDate): CycleStats {
        val inCycle = txs.filter { cycle.contains(it.tx.tsEpochMillis.toUaeDate()) }
        val daily = LongArray(cycle.lengthDays)
        inCycle.forEach { t ->
            val idx = cycle.dayIndex(t.tx.tsEpochMillis.toUaeDate())
            if (idx in daily.indices) daily[idx] += t.tx.spendMinor
        }

        val categories = inCycle.groupBy { it.category?.id }
            .map { (_, list) -> CategoryTotal(list.first().category, list.sumOf { it.tx.spendMinor }, list.size) }
            .filter { it.totalMinor > 0 }
            .sortedByDescending { it.totalMinor }

        val merchants = inCycle.filter { !it.tx.merchant.isNullOrBlank() && it.tx.amountMinor > 0 }
            .groupBy { TransactionRepository.normalizeMerchant(it.tx.merchant) }
            .map { (_, list) ->
                MerchantTotal(list.first().tx.merchant!!.trim(), list.sumOf { it.tx.spendMinor }, list.size, list.first().category)
            }
            .sortedByDescending { it.totalMinor }
            .take(5)

        val biggest = inCycle.filter { it.tx.amountMinor > 0 }.sortedByDescending { it.tx.amountMinor }.take(5)

        // Average per weekday over the days of the cycle that have already happened.
        val lastCounted = minOf(today, cycle.lastDay)
        val weekdayTotals = LongArray(7)
        val weekdayDays = IntArray(7)
        var d = cycle.start
        while (!d.isAfter(lastCounted)) {
            val wd = d.dayOfWeek.value - 1
            weekdayTotals[wd] += daily[cycle.dayIndex(d)]
            weekdayDays[wd]++
            d = d.plusDays(1)
        }
        val weekdayAvg = LongArray(7) { if (weekdayDays[it] > 0) weekdayTotals[it] / weekdayDays[it] else 0L }

        return CycleStats(
            cycle = cycle,
            totalMinor = inCycle.sumOf { it.tx.spendMinor },
            count = inCycle.size,
            dailyMinor = daily,
            categories = categories,
            topMerchants = merchants,
            biggest = biggest,
            weekdayAvgMinor = weekdayAvg,
        )
    }

    /** Spend in [cycle] up to and including day index [dayIndex]. */
    fun spentThrough(stats: CycleStats, dayIndex: Int): Long {
        var sum = 0L
        for (i in 0..minOf(dayIndex, stats.dailyMinor.size - 1)) sum += stats.dailyMinor[i]
        return sum
    }
}

/**
 * Statement projection that is not thrown off by one-off big purchases.
 *
 * total = spent so far + (usual daily spend × days left), where "usual daily spend" leaves out
 * one-off purchases at or above [Projection.oneOffThresholdMinor] and blends this cycle's pace
 * with the average of recent complete cycles (this cycle gets more weight as it progresses).
 */
data class Projection(
    val totalMinor: Long,
    val spentSoFarMinor: Long,
    val oneOffsMinor: Long,
    val oneOffCount: Int,
    val usualDailyMinor: Long,
    val daysLeft: Int,
    val oneOffThresholdMinor: Long,
    val basedOnCycles: Int,
    /** Average big-purchase total per past cycle. */
    val typicalOneOffsPerCycleMinor: Long = 0,
    /** Big purchases still expected this cycle, based on [typicalOneOffsPerCycleMinor]. */
    val expectedMoreOneOffsMinor: Long = 0,
)

object Projections {
    fun project(
        current: Cycle,
        txs: List<TransactionWithCategory>,
        today: LocalDate,
        pastCycles: List<Cycle>,
        oneOffThresholdMinor: Long,
    ): Projection {
        fun regular(list: List<TransactionWithCategory>) =
            list.filter { it.tx.amountMinor in 1 until oneOffThresholdMinor }.sumOf { it.tx.amountMinor }

        val inCycle = txs.filter { current.contains(it.tx.tsEpochMillis.toUaeDate()) }
        val spent = inCycle.sumOf { it.tx.spendMinor }
        val oneOffs = inCycle.filter { it.tx.amountMinor >= oneOffThresholdMinor }
        val elapsed = (current.dayIndex(minOf(today, current.lastDay)) + 1).coerceAtLeast(1)
        val daysLeft = (current.lengthDays - elapsed).coerceAtLeast(0)
        val currentRate = regular(inCycle).toDouble() / elapsed

        // Only use past cycles that actually have data (the app may be newer than a year).
        val pastLists = pastCycles.mapNotNull { c ->
            val list = txs.filter { c.contains(it.tx.tsEpochMillis.toUaeDate()) }
            if (list.isEmpty()) null else c to list
        }
        val pastRates = pastLists.map { (c, list) -> regular(list).toDouble() / c.lengthDays }

        // Big purchases happen too, just not daily: expect the typical amount for the rest of the
        // cycle, minus what has already been spent on big purchases this cycle.
        val typicalOneOffs = if (pastLists.isEmpty()) 0.0 else pastLists.map { (_, list) ->
            list.filter { it.tx.amountMinor >= oneOffThresholdMinor }.sumOf { it.tx.amountMinor }
        }.average()
        val oneOffsSoFar = oneOffs.sumOf { it.tx.amountMinor }
        val expectedMoreOneOffs = minOf(
            typicalOneOffs * daysLeft / current.lengthDays,
            (typicalOneOffs - oneOffsSoFar).coerceAtLeast(0.0),
        ).toLong()

        val rate = if (pastRates.isEmpty()) currentRate else {
            val w = elapsed.toDouble() / current.lengthDays
            w * currentRate + (1 - w) * pastRates.average()
        }
        return Projection(
            totalMinor = spent + (rate * daysLeft).toLong() + expectedMoreOneOffs,
            spentSoFarMinor = spent,
            oneOffsMinor = oneOffsSoFar,
            oneOffCount = oneOffs.size,
            usualDailyMinor = rate.toLong(),
            daysLeft = daysLeft,
            oneOffThresholdMinor = oneOffThresholdMinor,
            basedOnCycles = pastRates.size,
            typicalOneOffsPerCycleMinor = typicalOneOffs.toLong(),
            expectedMoreOneOffsMinor = expectedMoreOneOffs,
        )
    }
}

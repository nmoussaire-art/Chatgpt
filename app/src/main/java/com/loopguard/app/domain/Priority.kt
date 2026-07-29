package com.loopguard.app.domain

import com.loopguard.app.data.Loop
import com.loopguard.app.data.Side
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.min

enum class PriorityBand(val label: String) {
    CRITICAL("Critical"),
    HIGH("High"),
    MEDIUM("Medium"),
    LOW("Low"),
}

/**
 * One named contribution to a loop's score.
 *
 * v1 produced a bare number with no way to argue with it. Keeping the
 * contributions around is what lets the detail screen say *why* something is
 * at the top of the queue, which is the difference between a score the user
 * trusts and a score they ignore.
 */
data class PriorityFactor(
    val label: String,
    val points: Int,
    val detail: String,
)

data class Priority(
    val score: Int,
    val band: PriorityBand,
    val headline: String,
    val factors: List<PriorityFactor>,
) {
    val topFactor: PriorityFactor? get() = factors.maxByOrNull { it.points }
}

object PriorityEngine {

    private const val MAX_SCORE = 100

    fun evaluate(loop: Loop, today: LocalDate = LocalDate.now()): Priority {
        val factors = buildList {
            add(impactFactor(loop))
            add(deadlineFactor(loop, today))
            silenceFactor(loop, today)?.let { add(it) }
            add(responsibilityFactor(loop))
            fatigueFactor(loop)?.let { add(it) }
            if (loop.pinned) {
                add(PriorityFactor("Pinned", 8, "You marked this as something you do not want to lose track of."))
            }
        }

        val raw = factors.sumOf { it.points }
        val score = raw.coerceIn(0, MAX_SCORE)
        val band = bandFor(score)
        return Priority(
            score = score,
            band = band,
            headline = headlineFor(loop, factors, band, today),
            factors = factors.sortedByDescending { it.points },
        )
    }

    private fun impactFactor(loop: Loop): PriorityFactor {
        val impact = loop.impact.coerceIn(1, 5)
        val points = impact * 8
        val detail = when (impact) {
            5 -> "Critical consequences if this is dropped."
            4 -> "High cost in money, health or time if this slips."
            3 -> "Moderate consequences."
            2 -> "Minor consequences."
            else -> "Little cost if this waits."
        }
        return PriorityFactor("Impact", points, detail)
    }

    private fun deadlineFactor(loop: Loop, today: LocalDate): PriorityFactor {
        val due = loop.due
            ?: return PriorityFactor("Deadline", 5, "No deadline set, so nothing is forcing this forward.")

        val days = ChronoUnit.DAYS.between(today, due).toInt()
        return when {
            days < 0 -> {
                val overdue = -days
                PriorityFactor(
                    "Deadline",
                    (30 + min(15, overdue * 2)),
                    "Overdue by $overdue ${plural(overdue, "day", "days")}.",
                )
            }
            days == 0 -> PriorityFactor("Deadline", 28, "Due today.")
            days <= 2 -> PriorityFactor("Deadline", 22, "Due in $days ${plural(days, "day", "days")}.")
            days <= 5 -> PriorityFactor("Deadline", 15, "Due in $days days.")
            days <= 10 -> PriorityFactor("Deadline", 8, "Due in $days days, still comfortable.")
            else -> PriorityFactor("Deadline", 3, "Due in $days days, no pressure yet.")
        }
    }

    private fun silenceFactor(loop: Loop, today: LocalDate): PriorityFactor? {
        if (loop.sideEnum != Side.THEM) return null
        val quiet = daysSilent(loop, today)
        if (quiet <= 1) return null
        val points = min(20, (quiet * 3) / 2)
        val who = loop.counterparty.ifBlank { "the other side" }
        return PriorityFactor(
            "Silence",
            points,
            "No movement from $who for $quiet ${plural(quiet, "day", "days")}.",
        )
    }

    private fun responsibilityFactor(loop: Loop): PriorityFactor =
        if (loop.sideEnum == Side.ME) {
            PriorityFactor("Your move", 10, "Nothing happens here until you do something.")
        } else {
            PriorityFactor("Their move", 2, "You are waiting; a nudge is the only lever you have.")
        }

    private fun fatigueFactor(loop: Loop): PriorityFactor? {
        if (loop.followUpCount < 2) return null
        val points = min(12, 3 + loop.followUpCount * 2)
        return PriorityFactor(
            "Chased already",
            points,
            "You have followed up ${loop.followUpCount} times without resolution. Politeness has been exhausted.",
        )
    }

    fun daysSilent(loop: Loop, today: LocalDate = LocalDate.now()): Int =
        ChronoUnit.DAYS.between(loop.lastActivityDate, today).toInt().coerceAtLeast(0)

    fun daysUntilDue(loop: Loop, today: LocalDate = LocalDate.now()): Int? =
        loop.due?.let { ChronoUnit.DAYS.between(today, it).toInt() }

    fun bandFor(score: Int): PriorityBand = when {
        score >= 75 -> PriorityBand.CRITICAL
        score >= 55 -> PriorityBand.HIGH
        score >= 35 -> PriorityBand.MEDIUM
        else -> PriorityBand.LOW
    }

    private fun headlineFor(
        loop: Loop,
        factors: List<PriorityFactor>,
        band: PriorityBand,
        today: LocalDate,
    ): String {
        val days = daysUntilDue(loop, today)
        val quiet = daysSilent(loop, today)
        return when {
            days != null && days < 0 && loop.sideEnum == Side.ME ->
                "Overdue and it is your move. This is the one to clear first."
            days != null && days < 0 ->
                "Overdue and still with ${loop.counterparty.ifBlank { "them" }}. Time to escalate."
            days == 0 ->
                "Due today. Anything else can wait an hour."
            loop.sideEnum == Side.THEM && quiet >= 7 ->
                "Quiet for $quiet days. A firm, specific nudge usually breaks this open."
            loop.sideEnum == Side.ME && (band == PriorityBand.CRITICAL || band == PriorityBand.HIGH) ->
                "High impact and waiting on you. Do the smallest next step today."
            band == PriorityBand.LOW ->
                "Under control. Nothing here needs you right now."
            else ->
                "Worth a look this week before it becomes urgent."
        }
    }

    /** Concrete suggestion for the "next action" affordance. */
    fun nextAction(loop: Loop, today: LocalDate = LocalDate.now()): String {
        val days = daysUntilDue(loop, today)
        return when {
            loop.sideEnum == Side.ME && loop.notes.isNotBlank() -> loop.notes.lineSequence().first()
            loop.sideEnum == Side.ME && days != null && days < 0 ->
                "This is overdue and yours. Do the smallest step that makes it real: send the file, make the call, pay the amount."
            loop.sideEnum == Side.ME ->
                "Complete the smallest concrete action that moves this forward, then hand it back."
            loop.followUpCount >= 2 ->
                "You have chased ${loop.followUpCount} times. Escalate: ask for a named owner and a dated commitment."
            else ->
                "Send a short follow-up that restates the ask and a specific date."
        }
    }

    private fun plural(n: Int, one: String, many: String) = if (n == 1) one else many
}

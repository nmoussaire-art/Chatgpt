package com.loopguard.app.domain

import com.loopguard.app.data.Loop
import com.loopguard.app.data.Side
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class PriorityEngineTest {

    private val today: LocalDate = LocalDate.of(2026, 3, 10)

    private fun loop(
        side: Side = Side.THEM,
        dueInDays: Long? = 5,
        impact: Int = 3,
        silentDays: Long = 0,
        followUps: Int = 0,
        pinned: Boolean = false,
    ) = Loop(
        id = 1,
        title = "Test loop",
        counterparty = "Rebecca",
        side = side.storageKey,
        dueDate = dueInDays?.let { today.plusDays(it).toEpochDay() },
        impact = impact,
        lastActivity = today.minusDays(silentDays).toEpochDay(),
        followUpCount = followUps,
        pinned = pinned,
    )

    @Test
    fun `overdue scores higher than upcoming`() {
        val overdue = PriorityEngine.evaluate(loop(dueInDays = -4), today)
        val upcoming = PriorityEngine.evaluate(loop(dueInDays = 12), today)
        assertTrue("overdue=${overdue.score} upcoming=${upcoming.score}", overdue.score > upcoming.score)
    }

    @Test
    fun `critical impact outranks trivial impact`() {
        val critical = PriorityEngine.evaluate(loop(impact = 5), today)
        val trivial = PriorityEngine.evaluate(loop(impact = 1), today)
        assertTrue(critical.score > trivial.score)
    }

    @Test
    fun `silence only counts when the other side owns the move`() {
        val theirs = PriorityEngine.evaluate(loop(side = Side.THEM, silentDays = 10), today)
        val mine = PriorityEngine.evaluate(loop(side = Side.ME, silentDays = 10), today)
        assertTrue(theirs.factors.any { it.label == "Silence" })
        assertTrue(mine.factors.none { it.label == "Silence" })
    }

    @Test
    fun `score is always within bounds`() {
        val extreme = PriorityEngine.evaluate(
            loop(dueInDays = -400, impact = 5, silentDays = 400, followUps = 20, pinned = true),
            today,
        )
        assertTrue(extreme.score in 0..100)

        val gentle = PriorityEngine.evaluate(loop(dueInDays = 900, impact = 1), today)
        assertTrue(gentle.score in 0..100)
    }

    @Test
    fun `every factor is explained and the points add up to the score`() {
        val priority = PriorityEngine.evaluate(loop(dueInDays = -2, impact = 4, silentDays = 6), today)
        assertTrue(priority.factors.isNotEmpty())
        priority.factors.forEach {
            assertTrue("empty label", it.label.isNotBlank())
            assertTrue("empty detail for ${it.label}", it.detail.isNotBlank())
        }
        val sum = priority.factors.sumOf { it.points }.coerceIn(0, 100)
        assertEquals(sum, priority.score)
    }

    @Test
    fun `bands follow the documented thresholds`() {
        assertEquals(PriorityBand.CRITICAL, PriorityEngine.bandFor(75))
        assertEquals(PriorityBand.HIGH, PriorityEngine.bandFor(55))
        assertEquals(PriorityBand.MEDIUM, PriorityEngine.bandFor(35))
        assertEquals(PriorityBand.LOW, PriorityEngine.bandFor(34))
    }

    @Test
    fun `a loop with no deadline is not treated as urgent`() {
        val none = PriorityEngine.evaluate(loop(dueInDays = null, impact = 3), today)
        assertTrue(none.factors.any { it.label == "Deadline" && it.points <= 5 })
        assertEquals(null, PriorityEngine.daysUntilDue(loop(dueInDays = null), today))
    }

    @Test
    fun `days silent never goes negative`() {
        val futureActivity = loop(silentDays = -5)
        assertEquals(0, PriorityEngine.daysSilent(futureActivity, today))
    }
}

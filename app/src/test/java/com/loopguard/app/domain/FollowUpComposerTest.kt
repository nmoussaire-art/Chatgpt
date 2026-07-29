package com.loopguard.app.domain

import com.loopguard.app.data.Loop
import com.loopguard.app.data.Side
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class FollowUpComposerTest {

    private val today: LocalDate = LocalDate.of(2026, 3, 10)

    private fun loop(
        dueInDays: Long? = -3,
        silentDays: Long = 9,
        followUps: Int = 0,
        reference: String = "ADM-2291",
        notes: String = "Needed before the enrolment form can be completed.",
    ) = Loop(
        id = 1,
        title = "the Arabic stream confirmation",
        counterparty = "Rebecca",
        organisation = "Admissions office",
        side = Side.THEM.storageKey,
        dueDate = dueInDays?.let { today.plusDays(it).toEpochDay() },
        impact = 4,
        lastActivity = today.minusDays(silentDays).toEpochDay(),
        notes = notes,
        reference = reference,
        followUpCount = followUps,
    )

    @Test
    fun `every tone produces a usable message`() {
        Tone.entries.forEach { tone ->
            val message = FollowUpComposer.compose(loop(), tone, "Noussaire", today)
            assertTrue("$tone too short", message.length > 80)
            assertTrue("$tone missing greeting", message.contains("Rebecca"))
            assertTrue("$tone missing subject matter", message.contains("Arabic stream"))
            assertTrue("$tone missing signature", message.contains("Noussaire"))
        }
    }

    @Test
    fun `tones escalate in firmness`() {
        val friendly = FollowUpComposer.compose(loop(), Tone.FRIENDLY, "N", today)
        val final = FollowUpComposer.compose(loop(followUps = 4), Tone.FINAL, "N", today)

        assertTrue(friendly.contains("hope you are doing well", ignoreCase = true))
        assertTrue(final.contains("final follow-up", ignoreCase = true))
        assertTrue(final.contains("escalate", ignoreCase = true))
        assertTrue("final notice should carry a subject line", final.startsWith("Subject:"))
    }

    @Test
    fun `reference number is quoted when present and omitted when not`() {
        assertTrue(FollowUpComposer.compose(loop(), Tone.PROFESSIONAL, "", today).contains("ADM-2291"))
        assertTrue(
            !FollowUpComposer.compose(loop(reference = ""), Tone.PROFESSIONAL, "", today)
                .contains("Reference:")
        )
    }

    @Test
    fun `overdue loops state that the date has passed`() {
        val overdue = FollowUpComposer.compose(loop(dueInDays = -6), Tone.PROFESSIONAL, "", today)
        assertTrue(overdue.contains("passed", ignoreCase = true))
    }

    @Test
    fun `silence history appears only once there is real silence`() {
        val quiet = FollowUpComposer.compose(loop(silentDays = 12), Tone.FIRM, "", today)
        assertTrue(quiet.contains("12 days"))

        val fresh = FollowUpComposer.compose(loop(silentDays = 0), Tone.FIRM, "", today)
        assertTrue(!fresh.contains("0 days"))
    }

    @Test
    fun `suggested tone escalates with the number of previous chases`() {
        assertEquals(
            Tone.FINAL,
            FollowUpComposer.suggestTone(
                loop(followUps = 4),
                PriorityEngine.evaluate(loop(followUps = 4), today),
            ),
        )
        val second = loop(followUps = 2)
        assertEquals(Tone.FIRM, FollowUpComposer.suggestTone(second, PriorityEngine.evaluate(second, today)))
    }

    @Test
    fun `handles a missing contact name without producing Hello null`() {
        val anonymous = loop().copy(counterparty = "")
        Tone.entries.forEach { tone ->
            val message = FollowUpComposer.compose(anonymous, tone, "", today)
            assertTrue(!message.contains("null"))
            assertTrue(!message.contains("Hello ,"))
        }
    }

    @Test
    fun `handles a loop with no deadline and no notes`() {
        val bare = Loop(
            id = 2,
            title = "the missing paperwork",
            counterparty = "Ali",
            side = Side.THEM.storageKey,
            dueDate = null,
            lastActivity = today.toEpochDay(),
        )
        val message = FollowUpComposer.compose(bare, Tone.PROFESSIONAL, "", today)
        assertTrue(message.contains("Ali"))
        assertTrue(!message.contains("null"))
    }
}

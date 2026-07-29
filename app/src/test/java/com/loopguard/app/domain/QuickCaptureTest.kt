package com.loopguard.app.domain

import com.loopguard.app.data.Categories
import com.loopguard.app.data.Side
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

class QuickCaptureTest {

    // A Tuesday, so "friday" and "next monday" are unambiguous.
    private val today: LocalDate = LocalDate.of(2026, 3, 10)

    @Test
    fun `parses tomorrow`() {
        val draft = QuickCapture.parse("Send the form tomorrow", today)
        assertEquals(today.plusDays(1), draft.dueDate)
        assertTrue(draft.title.lowercase().contains("send the form"))
        assertTrue("tomorrow should be stripped", !draft.title.lowercase().contains("tomorrow"))
    }

    @Test
    fun `parses in N days`() {
        assertEquals(today.plusDays(3), QuickCapture.parse("Chase the quote in 3 days", today).dueDate)
        assertEquals(today.plusWeeks(2), QuickCapture.parse("Renew licence in 2 weeks", today).dueDate)
    }

    @Test
    fun `parses a weekday as the next occurrence`() {
        val draft = QuickCapture.parse("Call the clinic by friday", today)
        assertEquals(DayOfWeek.FRIDAY, draft.dueDate?.dayOfWeek)
        assertTrue(draft.dueDate!!.isAfter(today))
    }

    @Test
    fun `parses a numeric date and rolls to next year when already past`() {
        val draft = QuickCapture.parse("Pay the bill 01/01", today)
        assertEquals(LocalDate.of(2027, 1, 1), draft.dueDate)
    }

    @Test
    fun `parses a month name`() {
        assertEquals(LocalDate.of(2026, 5, 15), QuickCapture.parse("Lease ends 15 May", today).dueDate)
    }

    @Test
    fun `extracts explicit person, tags and reference`() {
        val draft = QuickCapture.parse(
            "Chase @Rebecca about the school place ref ADM-2291 #school #urgentish",
            today,
        )
        assertEquals("Rebecca", draft.counterparty)
        assertEquals("ADM-2291", draft.reference)
        assertTrue(draft.tags.containsAll(listOf("school", "urgentish")))
        assertTrue("markers should be stripped", !draft.title.contains("@"))
        assertTrue(!draft.title.contains("#"))
    }

    @Test
    fun `bangs and keywords raise impact`() {
        assertEquals(5, QuickCapture.parse("Insurance approval !!", today).impact)
        assertEquals(5, QuickCapture.parse("Urgent: pay the fine", today).impact)
        assertEquals(1, QuickCapture.parse("Sort the photos whenever", today).impact)
        assertEquals(3, QuickCapture.parse("Book a haircut", today).impact)
    }

    @Test
    fun `guesses the responsibility side`() {
        assertEquals(Side.ME, QuickCapture.parse("Send documents to the accountant", today).side)
        assertEquals(Side.THEM, QuickCapture.parse("Waiting for the landlord to reply", today).side)
    }

    @Test
    fun `guesses a sensible category`() {
        assertEquals(Categories.SCHOOL, QuickCapture.parse("School admission decision", today).category)
        assertEquals(Categories.HEALTH, QuickCapture.parse("Insurance pre-approval for the clinic", today).category)
        assertEquals(Categories.PROPERTY, QuickCapture.parse("Landlord repair request", today).category)
        assertEquals(Categories.MONEY, QuickCapture.parse("Chase the refund invoice", today).category)
    }

    @Test
    fun `never loses the sentence when nothing is recognised`() {
        val text = "Ring the man about the thing"
        val draft = QuickCapture.parse(text, today)
        assertEquals(text, draft.title)
        assertNull(draft.dueDate)
    }

    @Test
    fun `empty input is handled`() {
        val draft = QuickCapture.parse("   ", today)
        assertEquals("", draft.title)
    }

    @Test
    fun `reports what it understood`() {
        val draft = QuickCapture.parse("Chase @Mégane for the estimate by friday !!", today)
        assertTrue(draft.understood.any { it.startsWith("due") })
        assertTrue(draft.understood.any { it.contains("impact") })
        assertTrue(draft.understood.any { it.contains("Mégane") })
    }
}

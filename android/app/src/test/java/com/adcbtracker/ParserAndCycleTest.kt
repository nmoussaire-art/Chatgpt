package com.adcbtracker

import com.adcbtracker.data.Cycles
import com.adcbtracker.parser.AdcbAlertParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class ParserAndCycleTest {

    @Test
    fun parsesNewAvailableLimitWording() {
        val p = AdcbAlertParser.parse(
            "Credit Card XX1332 was used for AED29.00 on 01/10/2026 18:17:11 at ADNOC SHAMS 310 BK,\nABUDHABI-AE. Available limit  AED51220.40"
        )!!
        assertEquals("ADNOC SHAMS 310 BK", p.merchant)
        assertEquals("ABUDHABI-AE", p.location)
        assertEquals(2900L, p.amountMinor)
        assertEquals(5122040L, p.avlCreditLimitMinor)
        assertEquals("XX1332", p.cardId)
    }

    @Test
    fun parsesOldWordingAndDottedMerchant() {
        val old = AdcbAlertParser.parse("Cr.Card XX1332 was used for AED48.00 on 01/10/2026 15:13:37 at MAX,DUBAI-AE. Avl.Cr.limit is AED51,389.40")!!
        assertEquals("MAX", old.merchant)
        assertEquals(5138940L, old.avlCreditLimitMinor)
        val dotted = AdcbAlertParser.parse("Credit Card XX1332 was used for AED10.00 on 01/10/2026 15:13:37 at AMAZON.AE, DUBAI-AE. Available limit AED1.00")!!
        assertEquals("AMAZON.AE", dotted.merchant)
    }

    @Test
    fun reversalIsNegative() {
        val p = AdcbAlertParser.parse("Your Cr.Card XX1332 txn of AED20.00 on 01/10/2026 10:00:00 has been reversed by NOON, DUBAI-AE. Avl.Cr.limit is AED1,000.00")!!
        assertEquals(-2000L, p.amountMinor)
        assertEquals("NOON", p.merchant)
    }

    @Test
    fun ignoresSalary() {
        assertNull(AdcbAlertParser.parse("Your salary of AED10,000.00 has been credited to your account XXX1234 on 01/10/2026 10:00:00"))
    }

    @Test
    fun cycleStartsOn24th() {
        val c = Cycles.cycleFor(LocalDate.of(2026, 10, 1), 24)
        assertEquals(LocalDate.of(2026, 9, 24), c.start)
        assertEquals(LocalDate.of(2026, 10, 24), c.endExclusive)
        assertEquals(30, c.lengthDays)
        val onStart = Cycles.cycleFor(LocalDate.of(2026, 10, 24), 24)
        assertEquals(LocalDate.of(2026, 10, 24), onStart.start)
        val prev = Cycles.cycleAgo(LocalDate.of(2026, 10, 1), 24, 1)
        assertEquals(LocalDate.of(2026, 8, 24), prev.start)
        assertEquals(LocalDate.of(2026, 9, 24), prev.endExclusive)
        assertEquals("24 Sep – 23 Oct 2026", c.label())
    }

    @Test
    fun cycleDayOneIsCalendarMonth() {
        val c = Cycles.cycleFor(LocalDate.of(2026, 2, 15), 1)
        assertEquals(LocalDate.of(2026, 2, 1), c.start)
        assertEquals(LocalDate.of(2026, 3, 1), c.endExclusive)
        assertEquals("February 2026", c.label())
    }
}

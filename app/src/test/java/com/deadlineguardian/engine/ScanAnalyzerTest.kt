package com.deadlineguardian.engine

import com.deadlineguardian.data.DeadlineKind
import com.deadlineguardian.data.ItemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * These fixtures are written the way OCR actually returns text: upper case, ragged
 * spacing, mixed French/English, no reliable line order.
 */
class ScanAnalyzerTest {

    private val today = LocalDate.of(2025, 7, 20)

    @Test
    fun `french receipt with stated return policy computes the closing date`() {
        val text = """
            ELECTRO PLANET
            TICKET DE CAISSE
            Date: 15/07/2025  14:32
            CASQUE BLUETOOTH        899,00
            TOTAL TTC           899,00 MAD
            Retour sous 30 jours avec ticket
        """.trimIndent()

        val r = ScanAnalyzer.analyze(text, today = today)

        assertEquals(LocalDate.of(2025, 7, 15), r.purchaseDate)
        val ret = r.proposals.first { it.kind == DeadlineKind.RETURN_WINDOW }
        assertEquals(LocalDate.of(2025, 8, 14), ret.date)
        assertTrue("policy was printed, so it must not be flagged as assumed", !ret.assumed)
        assertEquals(89900L, r.amountMinor)
        assertEquals("MAD", r.currency)
    }

    @Test
    fun `electronics with no printed warranty still gets a two year warranty`() {
        val text = """
            TECHNO STORE
            FACTURE N 4471
            Date 02/03/2025
            MACBOOK AIR M2          12999.00 MAD
            TOTAL                   12999.00 MAD
        """.trimIndent()

        val r = ScanAnalyzer.analyze(text, today = today)
        val warranty = r.proposals.first { it.kind == DeadlineKind.WARRANTY_END }

        assertEquals(LocalDate.of(2027, 3, 2), warranty.date)
        assertTrue("an inferred warranty must be marked assumed", warranty.assumed)
    }

    @Test
    fun `groceries get no invented warranty`() {
        val text = """
            SUPERMARCHE ATLAS
            Ticket 8823
            Date 10/07/2025
            PAIN COMPLET             6,50
            LAIT 1L                 12,00
            TOTAL                   18,50 MAD
        """.trimIndent()

        val r = ScanAnalyzer.analyze(text, today = today)
        assertNull(
            "guessing a warranty on groceries would train the user to ignore the app",
            r.proposals.firstOrNull { it.kind == DeadlineKind.WARRANTY_END }
        )
    }

    @Test
    fun `medicine expiry printed as month and year resolves to end of that month`() {
        val text = """
            AMOXICILLINE 500 mg
            Boite de 12 comprimes
            Lot: 4471B
            EXP 03/2027
            Voie orale - Posologie: 1 comprime
        """.trimIndent()

        val r = ScanAnalyzer.analyze(text, today = today)

        assertEquals(ItemKind.MEDICINE, r.kind)
        val expiry = r.proposals.first { it.kind == DeadlineKind.EXPIRY }
        assertEquals(LocalDate.of(2027, 3, 31), expiry.date)
    }

    @Test
    fun `passport gets a long runway because renewing one takes weeks`() {
        val text = """
            ROYAUME DU MAROC
            PASSEPORT
            Date of expiry / Date d'expiration
            14/11/2025
        """.trimIndent()

        val r = ScanAnalyzer.analyze(text, today = today)

        assertEquals(ItemKind.DOCUMENT, r.kind)
        val expiry = r.proposals.first { it.kind == DeadlineKind.EXPIRY }
        assertEquals(LocalDate.of(2025, 11, 14), expiry.date)
        assertEquals(90, expiry.leadDays)
    }

    @Test
    fun `stated warranty in years beats the electronics default`() {
        val text = """
            HIFI CENTER
            Date: 01/06/2025
            TELEVISION 55 POUCES     7500,00 MAD
            Garantie 3 ans piece et main d'oeuvre
        """.trimIndent()

        val r = ScanAnalyzer.analyze(text, today = today)
        val warranty = r.proposals.first { it.kind == DeadlineKind.WARRANTY_END }

        assertEquals(LocalDate.of(2028, 5, 31), warranty.date)
        assertTrue(!warranty.assumed)
    }
}

class DateExtractorTest {

    @Test
    fun `day first and month first are both honoured when ambiguous`() {
        val dayFirst = DateExtractor.extract("Date 03/04/2025", preferDayFirst = true)
        assertEquals(LocalDate.of(2025, 4, 3), dayFirst.first().date)

        val monthFirst = DateExtractor.extract("Date 03/04/2025", preferDayFirst = false)
        assertEquals(LocalDate.of(2025, 3, 4), monthFirst.first().date)
    }

    @Test
    fun `an impossible month resolves itself regardless of preference`() {
        // 25 can only be a day, so the layout is unambiguous even in US mode.
        val r = DateExtractor.extract("25/12/2025", preferDayFirst = false)
        assertEquals(LocalDate.of(2025, 12, 25), r.first().date)
    }

    @Test
    fun `iso and textual dates are recognised`() {
        assertEquals(
            LocalDate.of(2025, 3, 14),
            DateExtractor.extract("Issued 2025-03-14").first().date
        )
        assertEquals(
            LocalDate.of(2025, 3, 14),
            DateExtractor.extract("le 14 mars 2025").first().date
        )
    }

    @Test
    fun `context words assign the right meaning to each date`() {
        val text = "Date: 01/06/2025  Garantie jusqu'au 01/06/2027"
        val dates = DateExtractor.extract(text)

        assertEquals(DateRole.PURCHASE, dates.first().role)
        assertEquals(DateRole.WARRANTY_END, dates.last().role)
    }

    @Test
    fun `the nearest label wins, not the loudest one nearby`() {
        // A warranty mentioned on the line above must not steal the purchase date.
        val dates = DateExtractor.extract("Garantie 2 ans\nDate: 01/06/2025")
        assertEquals(DateRole.PURCHASE, dates.first().role)
    }

    @Test
    fun `bare four digit numbers are not mistaken for dates`() {
        // Till numbers and prices must not become deadlines.
        val dates = DateExtractor.extract("Ticket 8823  CAISSE 04  TOTAL 1250")
        assertTrue("found spurious dates: $dates", dates.isEmpty())
    }
}

class TextSignalsTest {

    @Test
    fun `total line wins over line items`() {
        val text = """
            ARTICLE A     150,00 MAD
            ARTICLE B     299,00 MAD
            TOTAL TTC     449,00 MAD
        """.trimIndent()

        val best = TextSignals.bestAmount(text)
        assertNotNull(best)
        assertEquals(44900L, best!!.minor)
        assertTrue(best.isTotal)
    }

    @Test
    fun `both decimal conventions parse to the same minor units`() {
        assertEquals(129900L, TextSignals.bestAmount("TOTAL 1 299,00 MAD")!!.minor)
        assertEquals(129900L, TextSignals.bestAmount("TOTAL 1,299.00 MAD")!!.minor)
        assertEquals(129900L, TextSignals.bestAmount("TOTAL 1299 MAD")!!.minor)
    }

    @Test
    fun `durations are only picked up near a policy word`() {
        val relevant = TextSignals.durations("Retour sous 14 jours")
        assertEquals(1, relevant.size)
        assertEquals(14, relevant.first().days)
        assertEquals(DateRole.RETURN_BY, relevant.first().role)

        // "12 mois" here describes the product, not a policy.
        assertTrue(TextSignals.durations("Abonnement 12 mois offert").isEmpty())
    }
}

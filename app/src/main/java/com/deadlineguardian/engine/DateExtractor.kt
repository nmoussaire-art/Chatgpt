package com.deadlineguardian.engine

import java.time.LocalDate
import java.time.YearMonth

/** How precisely the date was printed. "EXP 03/2027" only pins down a month. */
enum class DatePrecision { DAY, MONTH }

/** What the surrounding words say this date *means*. */
enum class DateRole { PURCHASE, EXPIRY, WARRANTY_END, RETURN_BY, UNKNOWN }

data class FoundDate(
    val date: LocalDate,
    val raw: String,
    val range: IntRange,
    val precision: DatePrecision,
    val role: DateRole
)

/**
 * Pulls dates out of raw OCR text.
 *
 * OCR output is messy: no reliable line order, stray spaces inside numbers, and dates
 * printed in whatever convention the shop's till uses. So rather than trying one
 * strict format, we run several patterns, drop overlapping hits, and then read the
 * words *around* each date to work out what it means.
 */
object DateExtractor {

    private val MONTHS: Map<String, Int> = buildMap {
        fun put(month: Int, vararg names: String) = names.forEach { put(it, month) }
        put(1, "jan", "janv", "january", "janvier")
        put(2, "feb", "fev", "fevr", "february", "fevrier", "février", "févr")
        put(3, "mar", "mars", "march")
        put(4, "apr", "avr", "april", "avril")
        put(5, "may", "mai")
        put(6, "jun", "juin", "june")
        put(7, "jul", "juil", "july", "juillet")
        put(8, "aug", "aou", "aout", "août", "august")
        put(9, "sep", "sept", "september", "septembre")
        put(10, "oct", "october", "octobre")
        put(11, "nov", "november", "novembre")
        put(12, "dec", "december", "decembre", "décembre", "déc")
    }

    private val monthAlternation = MONTHS.keys.sortedByDescending { it.length }.joinToString("|")

    // 2025-03-14  (ISO — unambiguous, so it wins)
    private val ISO = Regex("""\b(\d{4})[/.\-](\d{1,2})[/.\-](\d{1,2})\b""")

    // 14/03/2025, 14-03-25, 14.03.2025
    private val NUMERIC = Regex("""\b(\d{1,2})\s?[/.\-]\s?(\d{1,2})\s?[/.\-]\s?(\d{2,4})\b""")

    // 14 Mars 2025 / 14 MAR 2025
    private val DAY_MONTH_NAME =
        Regex("""\b(\d{1,2})\s*[.\-]?\s*($monthAlternation)[a-zé.]*\s*,?\s*(\d{2,4})\b""", RegexOption.IGNORE_CASE)

    // Mars 2025 — month precision only
    private val MONTH_NAME_YEAR =
        Regex("""\b($monthAlternation)[a-zé.]*\s*,?\s*(\d{4})\b""", RegexOption.IGNORE_CASE)

    // 03/2027 — month precision, common on medicine boxes
    private val MONTH_YEAR = Regex("""\b(\d{1,2})\s?[/.\-]\s?(\d{4})\b""")

    // "EXP 0327" / "EXP 03 27" — packaging shorthand, only trusted next to an EXP keyword
    private val COMPACT_MONTH_YEAR = Regex("""\b(\d{2})\s?[/.\-]?\s?(\d{2})\b""")

    private val EXPIRY_WORDS = listOf(
        "exp", "expiry", "expires", "expiration", "expire", "peremption", "péremption",
        "best before", "bestbefore", "use by", "useby", "consommer", "dlc", "dluo",
        "valid until", "valable jusqu", "à consommer", "a consommer", "bb", "eod",
        "valid thru", "date limite"
    )
    private val WARRANTY_WORDS = listOf(
        "warranty", "warrantee", "guarantee", "guaranty", "garantie", "garanti"
    )
    private val RETURN_WORDS = listOf(
        "return", "returns", "retour", "retours", "echange", "échange", "exchange",
        "refund", "remboursement", "rembours"
    )
    private val PURCHASE_WORDS = listOf(
        "date", "invoice", "facture", "receipt", "ticket", "achat", "purchase",
        "sold", "vendu", "caisse", "commande", "order", "bon"
    )

    /**
     * @param preferDayFirst true for dd/MM (most of the world), false for MM/dd (US).
     *   Only consulted when both numbers are <= 12 and genuinely ambiguous.
     */
    fun extract(text: String, preferDayFirst: Boolean = true): List<FoundDate> {
        val lower = text.lowercase()
        val found = mutableListOf<FoundDate>()

        fun add(range: IntRange, raw: String, date: LocalDate?, precision: DatePrecision) {
            if (date == null) return
            // Keep the first (most specific) match for any overlapping span.
            if (found.any { it.range.first <= range.last && range.first <= it.range.last }) return
            found += FoundDate(date, raw, range, precision, roleFor(lower, range))
        }

        ISO.findAll(text).forEach { m ->
            val (y, mo, d) = m.destructured
            add(m.range, m.value, safeDate(y.toInt(), mo.toInt(), d.toInt()), DatePrecision.DAY)
        }

        DAY_MONTH_NAME.findAll(text).forEach { m ->
            val (d, name, y) = m.destructured
            val month = MONTHS[name.lowercase().trimEnd('.')] ?: return@forEach
            add(m.range, m.value, safeDate(expandYear(y.toInt()), month, d.toInt()), DatePrecision.DAY)
        }

        NUMERIC.findAll(text).forEach { m ->
            val a = m.groupValues[1].toInt()
            val b = m.groupValues[2].toInt()
            val year = expandYear(m.groupValues[3].toInt())
            // If one of them can't be a month, the layout decides itself.
            val (day, month) = when {
                a > 12 && b <= 12 -> a to b
                b > 12 && a <= 12 -> b to a
                preferDayFirst -> a to b
                else -> b to a
            }
            add(m.range, m.value, safeDate(year, month, day), DatePrecision.DAY)
        }

        MONTH_NAME_YEAR.findAll(text).forEach { m ->
            val (name, y) = m.destructured
            val month = MONTHS[name.lowercase().trimEnd('.')] ?: return@forEach
            add(m.range, m.value, endOfMonth(y.toInt(), month), DatePrecision.MONTH)
        }

        MONTH_YEAR.findAll(text).forEach { m ->
            val month = m.groupValues[1].toInt()
            val year = m.groupValues[2].toInt()
            add(m.range, m.value, endOfMonth(year, month), DatePrecision.MONTH)
        }

        // Bare 4-digit shorthand is far too greedy to trust on its own (it matches
        // prices, quantities, till numbers), so only accept it right after an EXP word.
        COMPACT_MONTH_YEAR.findAll(text).forEach { m ->
            if (roleFor(lower, m.range) != DateRole.EXPIRY) return@forEach
            val month = m.groupValues[1].toInt()
            val year = expandYear(m.groupValues[2].toInt())
            if (month !in 1..12) return@forEach
            add(m.range, m.value, endOfMonth(year, month), DatePrecision.MONTH)
        }

        return found.sortedBy { it.range.first }
    }

    /**
     * Ordered so that at an equal position the more specific reading wins — "date
     * limite" must beat the bare "date" it contains.
     */
    private val KEYWORDS: List<Pair<String, DateRole>> =
        WARRANTY_WORDS.map { it to DateRole.WARRANTY_END } +
            RETURN_WORDS.map { it to DateRole.RETURN_BY } +
            EXPIRY_WORDS.map { it to DateRole.EXPIRY } +
            PURCHASE_WORDS.map { it to DateRole.PURCHASE }

    /**
     * Works out what a date means from the label next to it.
     *
     * Crucially this takes the *nearest preceding* keyword rather than the
     * highest-priority one anywhere nearby: on a line like
     * "Date: 01/06/2025  Garantie jusqu'au 01/06/2027" both dates sit near both labels,
     * and only proximity tells them apart. Reading ahead is a last resort, capped at
     * the end of the line, because the next field's label is usually just after.
     */
    private fun roleFor(lowerText: String, range: IntRange): DateRole {
        val backStart = (range.first - 46).coerceAtLeast(0)
        if (backStart < range.first) {
            val back = lowerText.substring(backStart, range.first)
            var bestIndex = -1
            var bestRole = DateRole.UNKNOWN
            for ((word, role) in KEYWORDS) {
                val at = back.lastIndexOf(word)
                if (at > bestIndex) {
                    bestIndex = at
                    bestRole = role
                }
            }
            if (bestRole != DateRole.UNKNOWN) return bestRole
        }

        val fwdStart = (range.last + 1).coerceAtMost(lowerText.length)
        val fwdEnd = (fwdStart + 16).coerceAtMost(lowerText.length)
        val ahead = lowerText.substring(fwdStart, fwdEnd).substringBefore('\n')
        for ((word, role) in KEYWORDS) {
            if (ahead.contains(word)) return role
        }
        return DateRole.UNKNOWN
    }

    /** Two-digit years: 27 -> 2027. Receipts never mean 1927. */
    private fun expandYear(y: Int): Int = when {
        y in 0..79 -> 2000 + y
        y in 80..99 -> 1900 + y
        else -> y
    }

    private fun safeDate(year: Int, month: Int, day: Int): LocalDate? = runCatching {
        if (year !in 1900..2200 || month !in 1..12 || day !in 1..31) return null
        LocalDate.of(year, month, day)
    }.getOrNull()

    /** "EXP 03/2027" means good *through* March, so the deadline is the 31st. */
    private fun endOfMonth(year: Int, month: Int): LocalDate? = runCatching {
        val y = expandYear(year)
        if (y !in 1900..2200 || month !in 1..12) return null
        YearMonth.of(y, month).atEndOfMonth()
    }.getOrNull()
}

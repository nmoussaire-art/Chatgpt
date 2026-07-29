package com.deadlineguardian.engine

/** A stated period like "30 jours" / "2 year", tied to what it applies to. */
data class FoundDuration(
    val days: Int,
    val raw: String,
    val role: DateRole
)

data class FoundAmount(
    val minor: Long,
    val currency: String,
    val raw: String,
    /** True when the line said TOTAL / TTC / NET À PAYER — the number that matters. */
    val isTotal: Boolean
)

/**
 * Secondary signals scraped from receipt text: how long a policy lasts, how much was
 * paid, and who sold it. These turn a bare date into an actionable deadline.
 */
object TextSignals {

    private val UNIT_DAYS = mapOf(
        "jour" to 1, "jours" to 1, "day" to 1, "days" to 1, "j" to 1,
        "semaine" to 7, "semaines" to 7, "week" to 7, "weeks" to 7,
        "mois" to 30, "month" to 30, "months" to 30, "mo" to 30,
        "an" to 365, "ans" to 365, "annee" to 365, "annees" to 365,
        "année" to 365, "années" to 365,
        "year" to 365, "years" to 365, "yr" to 365, "yrs" to 365
    )

    private val unitAlternation = UNIT_DAYS.keys.sortedByDescending { it.length }.joinToString("|")

    // "30 jours", "2 ans", "24-month", "12 mo"
    private val DURATION = Regex(
        """\b(\d{1,3})\s?[\-–]?\s?($unitAlternation)\b""",
        RegexOption.IGNORE_CASE
    )

    private val WARRANTY_WORDS = listOf("warranty", "guarantee", "garantie", "garanti", "warrantee")
    private val RETURN_WORDS = listOf(
        "return", "returns", "retour", "retours", "echange", "échange",
        "exchange", "refund", "remboursement", "satisfait"
    )

    private val CURRENCIES = mapOf(
        "MAD" to "MAD", "DH" to "MAD", "DHS" to "MAD", "DIRHAM" to "MAD", "DHM" to "MAD",
        "EUR" to "EUR", "€" to "EUR",
        "USD" to "USD", "$" to "USD",
        "GBP" to "GBP", "£" to "GBP",
        "TND" to "TND", "DZD" to "DZD", "AED" to "AED", "SAR" to "SAR",
        "CAD" to "CAD", "CHF" to "CHF", "XOF" to "XOF", "EGP" to "EGP"
    )

    private val TOTAL_WORDS = listOf(
        "total", "ttc", "net a payer", "net à payer", "montant", "amount due",
        "amount", "a payer", "à payer", "grand total", "somme", "due"
    )

    /**
     * Finds durations that are actually *about* a warranty or a return policy.
     * A bare "30 days" elsewhere on the receipt is ignored — receipts are full of
     * numbers that mean nothing.
     */
    fun durations(text: String): List<FoundDuration> {
        val lower = text.lowercase()
        return DURATION.findAll(text).mapNotNull { m ->
            val n = m.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            val unit = UNIT_DAYS[m.groupValues[2].lowercase()] ?: return@mapNotNull null
            val days = n * unit
            if (days !in 1..(365 * 10)) return@mapNotNull null

            val start = (m.range.first - 55).coerceAtLeast(0)
            val end = (m.range.last + 55).coerceAtMost(lower.length - 1)
            if (start > end) return@mapNotNull null
            val ctx = lower.substring(start, end + 1)

            val role = when {
                WARRANTY_WORDS.any { ctx.contains(it) } -> DateRole.WARRANTY_END
                RETURN_WORDS.any { ctx.contains(it) } -> DateRole.RETURN_BY
                else -> return@mapNotNull null
            }
            FoundDuration(days, m.value.trim(), role)
        }.toList()
    }

    /**
     * Amounts, flagged by whether their line looks like a total. OCR mangles decimal
     * separators freely, so we normalise both "1 299,00" and "1,299.00".
     */
    fun amounts(text: String): List<FoundAmount> {
        val out = mutableListOf<FoundAmount>()
        val symbols = CURRENCIES.keys.sortedByDescending { it.length }
            .joinToString("|") { Regex.escape(it) }
        val number = """\d{1,3}(?:[  .,]\d{3})*(?:[.,]\d{1,2})?|\d+(?:[.,]\d{1,2})?"""

        val after = Regex("""($number)\s*($symbols)\b""", RegexOption.IGNORE_CASE)
        val before = Regex("""($symbols)\s*($number)""", RegexOption.IGNORE_CASE)

        fun record(raw: String, numStr: String, curRaw: String, at: Int) {
            val minor = toMinor(numStr) ?: return
            if (minor <= 0) return
            val currency = CURRENCIES[curRaw.uppercase()] ?: return
            val lineStart = text.lastIndexOf('\n', at.coerceAtMost(text.length - 1)).let { if (it < 0) 0 else it }
            val lineEnd = text.indexOf('\n', at).let { if (it < 0) text.length else it }
            val line = text.substring(lineStart, lineEnd).lowercase()
            out += FoundAmount(minor, currency, raw.trim(), TOTAL_WORDS.any { line.contains(it) })
        }

        after.findAll(text).forEach { record(it.value, it.groupValues[1], it.groupValues[2], it.range.first) }
        before.findAll(text).forEach { m ->
            if (out.any { o -> o.raw == m.value }) return@forEach
            record(m.value, m.groupValues[2], m.groupValues[1], m.range.first)
        }
        return out
    }

    /** The single amount worth showing: a flagged total, else the largest seen. */
    fun bestAmount(text: String): FoundAmount? {
        val all = amounts(text)
        return all.filter { it.isTotal }.maxByOrNull { it.minor } ?: all.maxByOrNull { it.minor }
    }

    private fun toMinor(s: String): Long? {
        var t = s.replace(" ", "").replace(" ", "").trim()
        val lastComma = t.lastIndexOf(',')
        val lastDot = t.lastIndexOf('.')
        val sep = maxOf(lastComma, lastDot)
        // Only the final separator can be decimal, and only with 1-2 digits after it.
        if (sep >= 0 && t.length - sep - 1 in 1..2) {
            val intPart = t.substring(0, sep).replace(Regex("""[.,]"""), "")
            val frac = t.substring(sep + 1).padEnd(2, '0')
            return runCatching { intPart.toLong() * 100 + frac.toLong() }.getOrNull()
        }
        t = t.replace(Regex("""[.,]"""), "")
        return t.toLongOrNull()?.times(100)
    }

    /**
     * Guesses the shop name. Tills print it big at the top, so the first few lines
     * that look like a name — not an address, not a number — are the best candidates.
     */
    fun merchant(text: String): String? {
        val noise = Regex("""(?i)\b(ticket|facture|invoice|receipt|caisse|tel|tél|phone|rc|ice|if|patente|tva|vat|siret|adresse|address|www|http|@)\b""")
        return text.lineSequence()
            .take(8)
            .map { it.trim() }
            .filter { it.length in 3..34 }
            .filterNot { it.contains(noise) }
            .filterNot { it.count { ch -> ch.isDigit() } > it.length / 3 }
            .firstOrNull { it.any { ch -> ch.isLetter() } }
            ?.replace(Regex("""\s+"""), " ")
            ?.trim(' ', '-', '*', ':', '.')
    }
}

package com.loopguard.app.domain

import com.loopguard.app.data.Categories
import com.loopguard.app.data.Side
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * What the parser understood, plus the human-readable notes about it so the
 * capture sheet can show "understood: due Friday, waiting on Rebecca" rather
 * than silently guessing.
 */
data class CaptureDraft(
    val title: String,
    val counterparty: String = "",
    val dueDate: LocalDate? = null,
    val impact: Int = 3,
    val side: Side = Side.THEM,
    val category: String = Categories.ADMIN,
    val tags: List<String> = emptyList(),
    val reference: String = "",
    val understood: List<String> = emptyList(),
)

/**
 * Turns a single typed or dictated line into a structured loop.
 *
 * Deliberately conservative: anything it is not sure about is left in the
 * title rather than silently dropped, because losing a word from the user's
 * own sentence is worse than failing to parse a date.
 */
object QuickCapture {

    private val WEEKDAYS = mapOf(
        "monday" to DayOfWeek.MONDAY, "mon" to DayOfWeek.MONDAY,
        "tuesday" to DayOfWeek.TUESDAY, "tue" to DayOfWeek.TUESDAY, "tues" to DayOfWeek.TUESDAY,
        "wednesday" to DayOfWeek.WEDNESDAY, "wed" to DayOfWeek.WEDNESDAY,
        "thursday" to DayOfWeek.THURSDAY, "thu" to DayOfWeek.THURSDAY, "thurs" to DayOfWeek.THURSDAY,
        "friday" to DayOfWeek.FRIDAY, "fri" to DayOfWeek.FRIDAY,
        "saturday" to DayOfWeek.SATURDAY, "sat" to DayOfWeek.SATURDAY,
        "sunday" to DayOfWeek.SUNDAY, "sun" to DayOfWeek.SUNDAY,
    )

    private val MONTHS = mapOf(
        "january" to 1, "jan" to 1, "february" to 2, "feb" to 2, "march" to 3, "mar" to 3,
        "april" to 4, "apr" to 4, "may" to 5, "june" to 6, "jun" to 6, "july" to 7, "jul" to 7,
        "august" to 8, "aug" to 8, "september" to 9, "sep" to 9, "sept" to 9,
        "october" to 10, "oct" to 10, "november" to 11, "nov" to 11, "december" to 12, "dec" to 12,
    )

    private val CATEGORY_HINTS = listOf(
        Categories.SCHOOL to listOf(
            "school", "teacher", "nursery", "admission", "enrol", "enroll", "tuition",
            "classroom", "principal", "headmaster", "university", "college", "exam", "report card",
        ),
        Categories.HEALTH to listOf(
            "doctor", "dr ", "clinic", "hospital", "insurance", "pharmacy", "prescription",
            "appointment", "scan", "blood test", "results", "dentist", "vaccine", "referral", "medical",
        ),
        Categories.PROPERTY to listOf(
            "landlord", "tenant", "lease", "rent", "property", "apartment", "condo", "plumber",
            "electrician", "repair", "maintenance", "estate agent", "mortgage", "deposit", "villa",
        ),
        Categories.MONEY to listOf(
            "invoice", "refund", "payment", "bank", "accountant", "tax", "salary", "bill",
            "subscription", "charge", "transfer", "reimburse", "receipt", "vat",
        ),
        Categories.ADMIN to listOf(
            "visa", "passport", "licence", "license", "registration", "certificate", "government",
            "municipality", "embassy", "council", "renewal", "form", "application",
        ),
        Categories.WORK to listOf(
            "client", "manager", "meeting", "proposal", "contract", "deliverable", "colleague",
            "hr", "onboarding", "review", "deadline", "project",
        ),
        Categories.TRAVEL to listOf(
            "flight", "hotel", "booking", "airline", "itinerary", "baggage", "trip", "visa run",
        ),
        Categories.FAMILY to listOf(
            "mum", "mom", "dad", "sister", "brother", "family", "wife", "husband", "son", "daughter",
        ),
    )

    private val ME_VERBS = listOf(
        "send", "submit", "pay", "upload", "complete", "fill", "call", "email", "book",
        "sign", "return", "renew", "apply", "prepare", "write", "collect", "drop off", "reply",
    )

    private val THEM_VERBS = listOf(
        "waiting", "chase", "follow up", "confirm from", "awaiting", "expecting", "hear back",
    )

    fun parse(raw: String, today: LocalDate = LocalDate.now()): CaptureDraft {
        var text = raw.trim()
        if (text.isEmpty()) return CaptureDraft(title = "")

        val understood = mutableListOf<String>()

        // --- tags: #school ------------------------------------------------
        val tags = mutableListOf<String>()
        text = Regex("#([\\p{L}0-9_-]+)").replace(text) { m ->
            tags += m.groupValues[1]
            " "
        }
        if (tags.isNotEmpty()) understood += "tagged ${tags.joinToString(", ") { "#$it" }}"

        // --- reference: ref ABC-123 / #12345 ------------------------------
        var reference = ""
        text = Regex("(?i)\\b(?:ref|reference|case|policy|claim|order)[:# ]+([A-Za-z0-9][A-Za-z0-9/_-]{2,})")
            .replace(text) { m ->
                reference = m.groupValues[1]
                " "
            }
        if (reference.isNotBlank()) understood += "reference $reference"

        // --- explicit person: @Rebecca, @Marie Dupont ---------------------
        // The second word must be capitalised, otherwise "@Rebecca about the
        // school" would capture "Rebecca about" as the contact name.
        var person = ""
        text = Regex("@(\\p{L}[\\p{L}'.-]*(?: \\p{Lu}[\\p{L}'.-]*)?)").replace(text) { m ->
            person = m.groupValues[1].trim()
            " "
        }

        // --- urgency: !! or keywords --------------------------------------
        var impact = 3
        val bangs = Regex("!+").findAll(text).map { it.value.length }.maxOrNull() ?: 0
        if (bangs > 0) {
            impact = (3 + bangs).coerceAtMost(5)
            text = text.replace(Regex("\\s*!+"), " ")
        }
        val lowerForImpact = text.lowercase(Locale.ROOT)
        when {
            listOf("urgent", "asap", "critical", "emergency").any { it in lowerForImpact } -> impact = 5
            listOf("important", "priority").any { it in lowerForImpact } -> impact = maxOf(impact, 4)
            listOf("whenever", "no rush", "sometime", "low priority").any { it in lowerForImpact } -> impact = 1
        }
        if (impact != 3) understood += "impact ${impactWord(impact)}"

        // --- due date ------------------------------------------------------
        val dateResult = extractDate(text, today)
        text = dateResult.remaining
        val due = dateResult.date
        if (due != null) understood += "due ${describe(due, today)}"

        // --- side ----------------------------------------------------------
        val lower = text.lowercase(Locale.ROOT)
        val side = when {
            THEM_VERBS.any { it in lower } -> Side.THEM
            ME_VERBS.any { lower.startsWith("$it ") || " $it " in lower } -> Side.ME
            else -> Side.THEM
        }
        understood += if (side == Side.ME) "waiting on you" else "waiting on them"

        // --- implicit person: "from X", "to X", "with X", "chase X" --------
        if (person.isBlank()) {
            person = extractPerson(text)
        }
        if (person.isNotBlank()) understood += "contact $person"

        // --- category -------------------------------------------------------
        val category = guessCategory(raw)
        understood += "filed under $category"

        val title = cleanTitle(text)

        return CaptureDraft(
            title = title,
            counterparty = person,
            dueDate = due,
            impact = impact,
            side = side,
            category = category,
            tags = tags,
            reference = reference,
            understood = understood,
        )
    }

    fun guessCategory(text: String): String {
        val lower = " ${text.lowercase(Locale.ROOT)} "
        val hit = CATEGORY_HINTS.firstOrNull { (_, words) -> words.any { it in lower } }
        return hit?.first ?: Categories.ADMIN
    }

    private data class DateResult(val date: LocalDate?, val remaining: String)

    private fun extractDate(text: String, today: LocalDate): DateResult {
        var working = text
        var found: LocalDate? = null

        fun consume(regex: Regex, resolve: (MatchResult) -> LocalDate?): Boolean {
            if (found != null) return false
            val match = regex.find(working) ?: return false
            val resolved = resolve(match) ?: return false
            found = resolved
            working = working.removeRange(match.range).replace(Regex("\\s{2,}"), " ")
            return true
        }

        // "in 3 days" / "in 2 weeks" / "in a month"
        consume(Regex("(?i)\\bin (\\d{1,3}) (day|days|week|weeks|month|months)\\b")) { m ->
            val n = m.groupValues[1].toLongOrNull() ?: return@consume null
            when (m.groupValues[2].lowercase(Locale.ROOT)) {
                "day", "days" -> today.plusDays(n)
                "week", "weeks" -> today.plusWeeks(n)
                else -> today.plusMonths(n)
            }
        }

        // "15/03" or "15/03/2026" or "15-03"
        consume(Regex("\\b(\\d{1,2})[/.-](\\d{1,2})(?:[/.-](\\d{2,4}))?\\b")) { m ->
            val day = m.groupValues[1].toIntOrNull() ?: return@consume null
            val month = m.groupValues[2].toIntOrNull() ?: return@consume null
            if (day !in 1..31 || month !in 1..12) return@consume null
            val yearRaw = m.groupValues[3].toIntOrNull()
            val year = when {
                yearRaw == null -> today.year
                yearRaw < 100 -> 2000 + yearRaw
                else -> yearRaw
            }
            runCatching { LocalDate.of(year, month, day) }.getOrNull()
                ?.let { if (yearRaw == null && it.isBefore(today)) it.plusYears(1) else it }
        }

        // "15 March" / "March 15"
        consume(Regex("(?i)\\b(\\d{1,2})(?:st|nd|rd|th)? (${MONTHS.keys.joinToString("|")})\\b")) { m ->
            val day = m.groupValues[1].toIntOrNull() ?: return@consume null
            val month = MONTHS[m.groupValues[2].lowercase(Locale.ROOT)] ?: return@consume null
            runCatching { LocalDate.of(today.year, month, day) }.getOrNull()
                ?.let { if (it.isBefore(today)) it.plusYears(1) else it }
        }
        consume(Regex("(?i)\\b(${MONTHS.keys.joinToString("|")}) (\\d{1,2})(?:st|nd|rd|th)?\\b")) { m ->
            val month = MONTHS[m.groupValues[1].lowercase(Locale.ROOT)] ?: return@consume null
            val day = m.groupValues[2].toIntOrNull() ?: return@consume null
            runCatching { LocalDate.of(today.year, month, day) }.getOrNull()
                ?.let { if (it.isBefore(today)) it.plusYears(1) else it }
        }

        consume(Regex("(?i)\\b(?:by |before |on )?end of (?:the )?month\\b")) {
            today.with(TemporalAdjusters.lastDayOfMonth())
        }
        consume(Regex("(?i)\\b(?:by |before |on )?end of (?:the )?week\\b")) {
            today.with(TemporalAdjusters.nextOrSame(DayOfWeek.FRIDAY))
        }
        consume(Regex("(?i)\\bnext week\\b")) { today.plusWeeks(1) }
        consume(Regex("(?i)\\bnext month\\b")) { today.plusMonths(1) }
        consume(Regex("(?i)\\btomorrow\\b")) { today.plusDays(1) }
        consume(Regex("(?i)\\btoday\\b|\\btonight\\b")) { today }

        // "next friday" is matched before a bare "friday" so the word "next"
        // does not get stranded in the title.
        consume(Regex("(?i)\\bnext (${WEEKDAYS.keys.joinToString("|")})\\b")) { m ->
            WEEKDAYS[m.groupValues[1].lowercase(Locale.ROOT)]
                ?.let { today.with(TemporalAdjusters.next(it)) }
        }
        consume(Regex("(?i)\\b(?:by |before |on )?(${WEEKDAYS.keys.joinToString("|")})\\b")) { m ->
            WEEKDAYS[m.groupValues[1].lowercase(Locale.ROOT)]?.let {
                today.with(TemporalAdjusters.next(it))
            }
        }

        return DateResult(found, working)
    }

    private fun extractPerson(text: String): String {
        val patterns = listOf(
            Regex("(?i)\\b(?:from|with|to|for|chase|ask|remind) ((?:[A-Z][\\p{L}'.-]+)(?: [A-Z][\\p{L}'.-]+)?)"),
            Regex("(?i)\\bwaiting on ((?:[A-Z][\\p{L}'.-]+)(?: [A-Z][\\p{L}'.-]+)?)"),
        )
        for (p in patterns) {
            val m = p.find(text) ?: continue
            val candidate = m.groupValues[1].trim()
            if (candidate.length >= 2 && candidate.lowercase(Locale.ROOT) !in STOPWORDS) return candidate
        }
        return ""
    }

    private val STOPWORDS = setOf(
        "the", "a", "an", "my", "me", "them", "him", "her", "it", "this", "that", "monday",
        "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday",
    )

    private fun cleanTitle(text: String): String {
        var t = text.replace(Regex("\\s{2,}"), " ").trim()
        t = t.removePrefix("-").removePrefix("•").trim()
        t = t.trim(' ', ',', ';', ':', '.', '-')
        if (t.isEmpty()) return t
        return t.replaceFirstChar { it.titlecase(Locale.ROOT) }
    }

    private fun impactWord(impact: Int) = when (impact) {
        5 -> "critical"
        4 -> "high"
        2 -> "low"
        1 -> "minimal"
        else -> "medium"
    }

    private fun describe(date: LocalDate, today: LocalDate): String = when (date) {
        today -> "today"
        today.plusDays(1) -> "tomorrow"
        else -> {
            val days = java.time.temporal.ChronoUnit.DAYS.between(today, date)
            if (days in 2..6) date.dayOfWeek.getDisplayName(
                java.time.format.TextStyle.FULL, Locale.ENGLISH,
            ) else date.toString()
        }
    }
}

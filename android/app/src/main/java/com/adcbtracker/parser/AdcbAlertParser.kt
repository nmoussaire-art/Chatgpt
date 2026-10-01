package com.adcbtracker.parser

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

data class ParsedTransaction(
    val cardId: String?,
    val amountMinor: Long,
    val currency: String,
    val merchant: String?,
    val location: String?,
    val timestamp: LocalDateTime,
    val avlCreditLimitMinor: Long?,
    val raw: String,
)

/**
 * Parses ADCB card alerts (SMS or app notification text), e.g.
 * "Credit Card XX1332 was used for AED29.00 on 01/10/2026 18:17:11 at ADNOC SHAMS 310 BK,
 *  ABUDHABI-AE. Available limit AED51220.40"
 */
object AdcbAlertParser {

    enum class TransactionType { PURCHASE, REVERSAL, TRANSFER, PAYMENT_RECEIVED, CREDIT, UNKNOWN }

    private val TS_FMT_SLASH = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss", Locale.US)
    private val TS_FMT_DASH = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss", Locale.US)
    private val TS_FMT_MONTH = listOf("MMM d yyyy h:mma", "MMM dd yyyy h:mma", "MMM d yyyy hh:mma", "MMM dd yyyy hh:mma")
        .map { DateTimeFormatter.ofPattern(it, Locale.US) }

    private const val CURRENCIES = "AED|CAD|USD|EUR|GBP|SAR"
    private const val MONEY = "([0-9]+(?:,[0-9]{3})*(?:\\.[0-9]{1,2})?)"

    private val CARD_CR = Regex("(?i)\\bCr\\.?\\s*Card\\s+([A-Z]{0,3}\\d{4,})\\b")
    private val CARD_CREDIT = Regex("(?i)\\bCredit\\s+Card\\s+([A-Z]{0,3}\\d{4,})\\b")
    private val CARD_ACC = Regex("(?i)\\bacc\\.?\\s*(?:no\\.?)?\\s*([A-Z]{0,3}\\d{4,})\\b")
    private val CARD_ACCOUNT = Regex("(?i)\\baccount\\s+([A-Z]{0,3}\\d{4,})\\b")
    private val AMOUNT_RE = Regex("(?i)\\b($CURRENCIES)\\s*$MONEY\\b")
    private val TS_SLASH_RE = Regex("\\b(\\d{2}/\\d{2}/\\d{4}\\s+\\d{2}:\\d{2}:\\d{2})\\b")
    private val TS_DASH_RE = Regex("\\b(\\d{2}-\\d{2}-\\d{4}\\s+\\d{2}:\\d{2}:\\d{2})\\b")
    private val TS_MONTH_RE = Regex("(?i)\\b(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\\s+(\\d{1,2})\\s+(\\d{4})\\s+(\\d{1,2}:\\d{2})(AM|PM)\\b")

    // Accepts both the old "Avl.Cr.limit is AED..." and the newer "Available limit AED..." wording.
    private val AVL_CR_LIMIT_RE = Regex("(?i)\\b(?:Avl\\.?\\s*Cr\\.?|Available(?:\\s+Cr\\.?|\\s+credit)?)\\s*limit\\s*(?:is)?\\s*($CURRENCIES)?\\s*$MONEY\\b")
    private val AVL_BAL_RE = Regex("(?i)\\bAvl\\.?\\s*(?:Acc\\.?)?\\s*[Bb]al\\.?\\s*($CURRENCIES)?\\s*$MONEY\\b")

    // Merchant runs up to the first "." followed by whitespace/end, so it does not depend
    // on the wording that follows it and can itself contain dots (e.g. AMAZON.AE).
    private val MERCHANT_AT_RE = Regex("(?i)\\bat\\s+(.+?)\\.(?:\\s|$)")
    private val MERCHANT_BY_RE = Regex("(?i)\\bby\\s+(.+?)\\.(?:\\s|$)")

    private val IS_REVERSAL = Regex("(?i)\\bhas been reversed\\b")
    private val IS_TRANSFER = Regex("(?i)\\btransferred via\\b")
    private val IS_PAYMENT_RECEIVED = Regex("(?i)\\breceived towards\\b")
    private val IS_WIO_CONFIRMATION = Regex("(?i)\\bConfirmation recd\\. from WIO\\b")
    private val IS_CREDIT = Regex("(?i)(your salary|has been credited|credited to your account)")

    fun normalize(raw: String): String =
        raw.replace("\n", " ").replace("\r", " ").replace(Regex("\\s+"), " ").trim()

    fun detectType(raw: String): TransactionType = when {
        IS_WIO_CONFIRMATION.containsMatchIn(raw) -> TransactionType.UNKNOWN
        IS_CREDIT.containsMatchIn(raw) -> TransactionType.CREDIT
        IS_REVERSAL.containsMatchIn(raw) -> TransactionType.REVERSAL
        IS_TRANSFER.containsMatchIn(raw) -> TransactionType.TRANSFER
        IS_PAYMENT_RECEIVED.containsMatchIn(raw) -> TransactionType.PAYMENT_RECEIVED
        raw.contains("was used for", ignoreCase = true) -> TransactionType.PURCHASE
        else -> TransactionType.UNKNOWN
    }

    /** Returns a parsed expense (purchase or reversal) or null for anything that isn't spending. */
    fun parse(rawInput: String): ParsedTransaction? {
        val raw = normalize(rawInput)
        if (IS_WIO_CONFIRMATION.containsMatchIn(raw)) return null
        val type = detectType(raw)
        if (type != TransactionType.PURCHASE && type != TransactionType.REVERSAL) return null
        val timestamp = parseTimestamp(raw) ?: return null
        val amountMatch = AMOUNT_RE.find(raw) ?: return null
        val currency = amountMatch.groupValues[1].uppercase(Locale.ROOT)
        val amountMinor = moneyToMinor(amountMatch.groupValues[2]) ?: return null
        if (amountMinor <= 0) return null
        val (merchant, location) = extractMerchantAndLocation(raw, type)
        return ParsedTransaction(
            cardId = extractCardId(raw),
            amountMinor = if (type == TransactionType.REVERSAL) -amountMinor else amountMinor,
            currency = currency,
            merchant = merchant,
            location = location,
            timestamp = timestamp,
            avlCreditLimitMinor = extractAvailableLimit(raw),
            raw = raw,
        )
    }

    private fun parseTimestamp(raw: String): LocalDateTime? {
        TS_SLASH_RE.find(raw)?.groupValues?.getOrNull(1)?.let { s ->
            runCatching { LocalDateTime.parse(s, TS_FMT_SLASH) }.getOrNull()?.let { return it }
        }
        TS_DASH_RE.find(raw)?.groupValues?.getOrNull(1)?.let { s ->
            runCatching { LocalDateTime.parse(s, TS_FMT_DASH) }.getOrNull()?.let { return it }
        }
        val m = TS_MONTH_RE.find(raw) ?: return null
        val (month, day, year, time, ampm) = m.destructured
        val month3 = month.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }
        val s = "$month3 $day $year $time${ampm.uppercase(Locale.ROOT)}"
        for (fmt in TS_FMT_MONTH) {
            runCatching { LocalDateTime.parse(s, fmt) }.getOrNull()?.let { return it }
        }
        return null
    }

    private fun extractCardId(raw: String): String? =
        listOf(CARD_CR, CARD_CREDIT, CARD_ACC, CARD_ACCOUNT).firstNotNullOfOrNull { it.find(raw)?.groupValues?.getOrNull(1) }

    private fun extractMerchantAndLocation(raw: String, type: TransactionType): Pair<String?, String?> {
        val re = if (type == TransactionType.REVERSAL) MERCHANT_BY_RE else MERCHANT_AT_RE
        val text = re.find(raw)?.groupValues?.getOrNull(1)
        if (text != null) {
            val comma = text.lastIndexOf(',')
            if (comma > 0) {
                val merchant = text.substring(0, comma).trim().ifBlank { null }
                val location = text.substring(comma + 1).trim().ifBlank { null }
                return merchant to location
            }
            return text.trim().ifBlank { null } to null
        }
        return when (type) {
            TransactionType.TRANSFER -> "ADCB Transfer" to null
            TransactionType.PAYMENT_RECEIVED -> "Card Payment" to null
            else -> null to null
        }
    }

    private fun extractAvailableLimit(raw: String): Long? {
        AVL_CR_LIMIT_RE.find(raw)?.let { m -> moneyToMinor(m.groupValues[2])?.let { return it } }
        AVL_BAL_RE.find(raw)?.let { m -> moneyToMinor(m.groupValues[2])?.let { return it } }
        return null
    }

    fun moneyToMinor(s: String): Long? {
        val parts = s.replace(",", "").trim().split(".")
        return when (parts.size) {
            1 -> parts[0].toLongOrNull()?.times(100)
            2 -> {
                val whole = parts[0].toLongOrNull() ?: return null
                val frac = parts[1].padEnd(2, '0').take(2).toLongOrNull() ?: return null
                whole * 100 + frac
            }
            else -> null
        }
    }

    fun looksLikeAdcbAlert(title: String, body: String): Boolean {
        val t = title.lowercase(Locale.ROOT)
        val b = body.lowercase(Locale.ROOT)
        if ("confirmation recd. from wio" in b) return false
        val hasAdcbMarker = "adcb" in t || "adcb" in b || "cr.card" in b || "cr card" in b || "credit card" in b
        val hasCurrency = listOf("aed", "cad", "usd", "eur", "gbp").any { it in b }
        val hasTxnWord = listOf("was used for", "has been reversed", "transferred via", "received towards",
            "spent", "purchase", "payment", "debited").any { it in b }
        val hasLimitHint = listOf("avl.", "avl ", "limit", "bal.").any { it in b }
        return hasAdcbMarker || (hasCurrency && hasTxnWord) || (hasCurrency && hasLimitHint)
    }
}

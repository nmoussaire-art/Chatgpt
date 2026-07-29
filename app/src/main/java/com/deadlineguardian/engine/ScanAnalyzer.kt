package com.deadlineguardian.engine

import com.deadlineguardian.data.DeadlineKind
import com.deadlineguardian.data.ItemKind
import java.time.LocalDate

/** One deadline the app proposes, with a plain-language reason the user can sanity-check. */
data class ProposedDeadline(
    val kind: DeadlineKind,
    val date: LocalDate,
    val leadDays: Int,
    val moneyAtRiskMinor: Long? = null,
    /** e.g. "receipt says 30-day returns" vs "assumed 30 days — no policy printed". */
    val reason: String,
    val assumed: Boolean
)

data class ScanResult(
    val kind: ItemKind,
    val confidence: Float,
    val title: String,
    val merchant: String?,
    val purchaseDate: LocalDate?,
    val amountMinor: Long?,
    val currency: String?,
    val proposals: List<ProposedDeadline>,
    val rawText: String
)

/**
 * Turns OCR text into deadlines.
 *
 * The core insight of the app lives here: a receipt is not one deadline, it's several
 * overlapping clocks that start on the day you paid. Shops print the purchase date but
 * almost never print the date your return window shuts — so we compute it. That gap is
 * exactly where people lose money.
 */
object ScanAnalyzer {

    /** Fallbacks used when the paperwork doesn't state a policy. User-tunable in Settings. */
    data class Policy(
        val defaultReturnDays: Int = 30,
        val defaultWarrantyDays: Int = 365,
        val electronicsWarrantyDays: Int = 730,
        val preferDayFirst: Boolean = true,
        val documentLeadDays: Int = 90
    )

    fun analyze(
        text: String,
        policy: Policy = Policy(),
        today: LocalDate = LocalDate.now()
    ): ScanResult {
        val cls = Classifier.classify(text)
        val dates = DateExtractor.extract(text, policy.preferDayFirst)
        val durations = TextSignals.durations(text)
        val amount = TextSignals.bestAmount(text)
        val merchant = TextSignals.merchant(text)

        val purchase = pickPurchaseDate(dates, today)
        val proposals = mutableListOf<ProposedDeadline>()

        // A date explicitly labelled on the paper always beats anything we compute.
        dates.firstOrNull { it.role == DateRole.EXPIRY }?.let {
            proposals += ProposedDeadline(
                kind = DeadlineKind.EXPIRY,
                date = it.date,
                leadDays = DeadlineKind.EXPIRY.defaultLeadDays,
                reason = "expiry date printed on the item (${it.raw.trim()})",
                assumed = false
            )
        }
        dates.firstOrNull { it.role == DateRole.RETURN_BY }?.let {
            proposals += ProposedDeadline(
                kind = DeadlineKind.RETURN_WINDOW,
                date = it.date,
                leadDays = DeadlineKind.RETURN_WINDOW.defaultLeadDays,
                moneyAtRiskMinor = amount?.minor,
                reason = "return-by date printed on the receipt (${it.raw.trim()})",
                assumed = false
            )
        }
        dates.firstOrNull { it.role == DateRole.WARRANTY_END }?.let {
            proposals += ProposedDeadline(
                kind = DeadlineKind.WARRANTY_END,
                date = it.date,
                leadDays = DeadlineKind.WARRANTY_END.defaultLeadDays,
                reason = "warranty end date printed (${it.raw.trim()})",
                assumed = false
            )
        }

        when (cls.kind) {
            ItemKind.RECEIPT, ItemKind.WARRANTY, ItemKind.OTHER -> {
                if (purchase != null) {
                    addReturnWindow(proposals, purchase, durations, amount, policy)
                    addWarranty(proposals, purchase, durations, cls.isElectronics, policy)
                }
            }

            ItemKind.MEDICINE, ItemKind.FOOD -> {
                // Nothing to compute: these carry a real printed expiry or none at all.
                // A shorter lead for food — a 14-day warning on yoghurt is useless.
                if (cls.kind == ItemKind.FOOD) {
                    proposals.replaceAll { p ->
                        if (p.kind == DeadlineKind.EXPIRY) p.copy(leadDays = 3) else p
                    }
                }
            }

            ItemKind.DOCUMENT -> {
                // Renewing a passport takes weeks, so widen the warning even though the
                // date itself was found the same way as any other expiry.
                proposals.replaceAll { p ->
                    if (p.kind == DeadlineKind.EXPIRY) {
                        p.copy(leadDays = policy.documentLeadDays)
                    } else p
                }
                if (proposals.none { it.kind == DeadlineKind.EXPIRY }) {
                    // Documents rarely label their date; take the furthest future one.
                    dates.filter { it.date.isAfter(today) }.maxByOrNull { it.date }?.let {
                        proposals += ProposedDeadline(
                            kind = DeadlineKind.EXPIRY,
                            date = it.date,
                            leadDays = policy.documentLeadDays,
                            reason = "latest future date found (${it.raw.trim()}) — check this is the expiry",
                            assumed = true
                        )
                    }
                }
            }

            ItemKind.SUBSCRIPTION -> {
                dates.filter { it.date.isAfter(today) }.minByOrNull { it.date }?.let {
                    proposals += ProposedDeadline(
                        kind = DeadlineKind.RENEWAL,
                        date = it.date,
                        leadDays = DeadlineKind.RENEWAL.defaultLeadDays,
                        moneyAtRiskMinor = amount?.minor,
                        reason = "next billing date found (${it.raw.trim()})",
                        assumed = true
                    )
                }
            }
        }

        return ScanResult(
            kind = cls.kind,
            confidence = cls.confidence,
            title = buildTitle(cls.kind, merchant),
            merchant = merchant,
            purchaseDate = purchase,
            amountMinor = amount?.minor,
            currency = amount?.currency,
            proposals = proposals.distinctBy { it.kind }.sortedBy { it.date },
            rawText = text
        )
    }

    private fun addReturnWindow(
        into: MutableList<ProposedDeadline>,
        purchase: LocalDate,
        durations: List<FoundDuration>,
        amount: FoundAmount?,
        policy: Policy
    ) {
        if (into.any { it.kind == DeadlineKind.RETURN_WINDOW }) return
        val stated = durations.firstOrNull { it.role == DateRole.RETURN_BY }
        val days = stated?.days ?: policy.defaultReturnDays
        into += ProposedDeadline(
            kind = DeadlineKind.RETURN_WINDOW,
            date = purchase.plusDays(days.toLong()),
            leadDays = DeadlineKind.RETURN_WINDOW.defaultLeadDays,
            moneyAtRiskMinor = amount?.minor,
            reason = if (stated != null) {
                "receipt states ${stated.raw} to return"
            } else {
                "assumed $days-day return window — no policy printed, edit if the shop differs"
            },
            assumed = stated == null
        )
    }

    private fun addWarranty(
        into: MutableList<ProposedDeadline>,
        purchase: LocalDate,
        durations: List<FoundDuration>,
        isElectronics: Boolean,
        policy: Policy
    ) {
        if (into.any { it.kind == DeadlineKind.WARRANTY_END }) return
        val stated = durations.firstOrNull { it.role == DateRole.WARRANTY_END }
        // Without a stated warranty we only guess for electronics; proposing a warranty
        // on a bag of groceries would train the user to ignore the app.
        if (stated == null && !isElectronics) return
        val days = stated?.days ?: policy.electronicsWarrantyDays
        into += ProposedDeadline(
            kind = DeadlineKind.WARRANTY_END,
            date = purchase.plusDays(days.toLong()),
            leadDays = DeadlineKind.WARRANTY_END.defaultLeadDays,
            reason = if (stated != null) {
                "receipt states ${stated.raw} warranty"
            } else {
                "assumed ${days / 365}-year warranty for electronics"
            },
            assumed = stated == null
        )
    }

    /**
     * The purchase date is the anchor every computed deadline hangs off, so getting it
     * wrong shifts everything. A receipt is printed on the day you buy, so among the
     * plausible candidates the most recent past date is almost always right.
     */
    private fun pickPurchaseDate(dates: List<FoundDate>, today: LocalDate): LocalDate? {
        dates.firstOrNull { it.role == DateRole.PURCHASE && !it.date.isAfter(today) }
            ?.let { return it.date }

        val cutoff = today.minusYears(3)
        return dates
            .filter { it.precision == DatePrecision.DAY }
            .filter { it.role == DateRole.UNKNOWN || it.role == DateRole.PURCHASE }
            .filter { !it.date.isAfter(today) && it.date.isAfter(cutoff) }
            .maxByOrNull { it.date }
            ?.date
    }

    private fun buildTitle(kind: ItemKind, merchant: String?): String {
        val base = when (kind) {
            ItemKind.RECEIPT -> "Purchase"
            ItemKind.WARRANTY -> "Warranty"
            ItemKind.MEDICINE -> "Medicine"
            ItemKind.FOOD -> "Food item"
            ItemKind.DOCUMENT -> "Document"
            ItemKind.SUBSCRIPTION -> "Subscription"
            ItemKind.OTHER -> "Item"
        }
        return if (merchant.isNullOrBlank()) base else "$base — $merchant"
    }
}

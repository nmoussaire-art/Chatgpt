package com.deadlineguardian.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** What kind of thing the user scanned. Drives which deadlines we propose. */
enum class ItemKind {
    RECEIPT,     // shop receipt / invoice -> return window + warranty
    WARRANTY,    // warranty card / guarantee slip
    MEDICINE,    // medicine box with an EXP date
    FOOD,        // perishable with a best-before
    DOCUMENT,    // passport, ID, licence, insurance
    SUBSCRIPTION,
    OTHER
}

/**
 * The specific clock that is ticking. One scanned item can start several of these:
 * a laptop receipt starts a 14-day return window *and* a 2-year warranty.
 */
enum class DeadlineKind {
    RETURN_WINDOW,
    WARRANTY_END,
    EXPIRY,
    RENEWAL,
    CUSTOM;

    /**
     * How many days before the date we start shouting. Return windows are short and
     * money-bearing, so they get a tight, loud lead. Documents need months of runway
     * because renewing one is itself a multi-week errand.
     */
    val defaultLeadDays: Int
        get() = when (this) {
            RETURN_WINDOW -> 5
            WARRANTY_END -> 30
            EXPIRY -> 14
            RENEWAL -> 7
            CUSTOM -> 7
        }

    val label: String
        get() = when (this) {
            RETURN_WINDOW -> "Return window"
            WARRANTY_END -> "Warranty ends"
            EXPIRY -> "Expires"
            RENEWAL -> "Renews"
            CUSTOM -> "Deadline"
        }
}

@Entity(tableName = "items")
data class TrackedItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val kind: ItemKind,
    val merchant: String? = null,
    val purchaseDate: LocalDate? = null,
    /** Stored in minor units (centimes/cents) to avoid float drift. */
    val amountMinor: Long? = null,
    val currency: String? = null,
    val imagePath: String? = null,
    val rawText: String? = null,
    val notes: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "deadlines",
    foreignKeys = [ForeignKey(
        entity = TrackedItem::class,
        parentColumns = ["id"],
        childColumns = ["itemId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("itemId"), Index("date")]
)
data class Deadline(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val itemId: Long,
    val kind: DeadlineKind,
    val date: LocalDate,
    val leadDays: Int,
    /** Money you still get back if you act before [date] — what makes this urgent. */
    val moneyAtRiskMinor: Long? = null,
    /** User swiped it away; keep the record, stop the nagging. */
    val dismissed: Boolean = false,
    val doneAt: Long? = null,
    val notifiedAt: Long? = null
)

/** How loud a deadline should be right now. */
enum class Urgency { OVERDUE, ACT_NOW, SOON, LATER, DONE }

data class DeadlineWithItem(
    val deadline: Deadline,
    val item: TrackedItem
) {
    fun daysLeft(today: LocalDate = LocalDate.now()): Long =
        ChronoUnit.DAYS.between(today, deadline.date)

    fun urgency(today: LocalDate = LocalDate.now()): Urgency {
        if (deadline.doneAt != null) return Urgency.DONE
        val days = daysLeft(today)
        return when {
            days < 0 -> Urgency.OVERDUE
            days <= deadline.leadDays -> Urgency.ACT_NOW
            days <= 90 -> Urgency.SOON
            else -> Urgency.LATER
        }
    }

    /** "closes in 3 days", "expired 2 days ago" — the phrasing users actually parse. */
    fun countdownText(today: LocalDate = LocalDate.now()): String {
        val days = daysLeft(today)
        return when {
            days == 0L -> "today"
            days == 1L -> "tomorrow"
            days == -1L -> "yesterday"
            days > 0 && days < 45 -> "in $days days"
            days > 0 && days < 365 -> "in ${days / 30} months"
            days > 0 -> "in ${days / 365} yr ${(days % 365) / 30} mo"
            else -> "${-days} days ago"
        }
    }
}

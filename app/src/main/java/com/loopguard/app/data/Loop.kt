package com.loopguard.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDate

/** Who currently owns the next move. */
enum class Side(val storageKey: String, val label: String) {
    ME("me", "Waiting on me"),
    THEM("them", "Waiting on them");

    companion object {
        fun from(key: String?): Side =
            if (key.equals("me", ignoreCase = true)) ME else THEM
    }
}

enum class LoopStatus(val storageKey: String) {
    OPEN("open"),
    DONE("done");

    companion object {
        fun from(key: String?): LoopStatus =
            if (key.equals("done", ignoreCase = true)) DONE else OPEN
    }
}

/**
 * A single open loop.
 *
 * Dates that only matter at day granularity are stored as epoch-days so that
 * comparisons never depend on the device time zone drifting mid-day.
 */
@Entity(tableName = "loops")
data class Loop(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    /** Person who owns the other side of the loop, e.g. "Rebecca". */
    val counterparty: String = "",
    /** Organisation or department, e.g. "Admissions office". */
    val organisation: String = "",
    @ColumnInfo(defaultValue = "them") val side: String = Side.THEM.storageKey,
    val category: String = Categories.ADMIN,
    /** Epoch day, or null when the loop has no hard deadline. */
    val dueDate: Long? = null,
    /** 1 = low, 5 = critical. Matches the v1 scale. */
    val impact: Int = 3,
    /** Epoch day of the most recent movement on this loop. */
    val lastActivity: Long,
    val notes: String = "",
    @ColumnInfo(defaultValue = "open") val status: String = LoopStatus.OPEN.storageKey,
    val createdAt: Long = System.currentTimeMillis(),
    val closedAt: Long? = null,
    /** Reference / case / policy number so the follow-up can quote it. */
    val reference: String = "",
    /** Comma separated free-form tags. */
    val tags: String = "",
    /** Epoch day; the loop stays out of the focus queue until this date. */
    val snoozedUntil: Long? = null,
    val followUpCount: Int = 0,
    val pinned: Boolean = false,
    /** Preserved id from a v1.0 JSON backup, so re-imports do not duplicate. */
    val legacyId: String? = null,
) {
    val sideEnum: Side get() = Side.from(side)
    val statusEnum: LoopStatus get() = LoopStatus.from(status)
    val due: LocalDate? get() = dueDate?.let { LocalDate.ofEpochDay(it) }
    val lastActivityDate: LocalDate get() = LocalDate.ofEpochDay(lastActivity)
    val snoozedUntilDate: LocalDate? get() = snoozedUntil?.let { LocalDate.ofEpochDay(it) }

    val tagList: List<String>
        get() = tags.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    /** "Rebecca · Admissions office" style subtitle. */
    val whoLabel: String
        get() = listOf(counterparty, organisation)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
            .ifBlank { "No contact yet" }

    fun isSnoozed(today: LocalDate): Boolean =
        snoozedUntilDate?.isAfter(today) == true
}

@Entity(
    tableName = "events",
    indices = [Index("loopId")],
    foreignKeys = [
        ForeignKey(
            entity = Loop::class,
            parentColumns = ["id"],
            childColumns = ["loopId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
)
data class LoopEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val loopId: Long,
    val text: String,
    /** Epoch day the event happened on. */
    val date: Long,
    /** Millisecond timestamp, used purely for stable ordering. */
    val at: Long = System.currentTimeMillis(),
    val kind: String = EventKind.NOTE,
)

object EventKind {
    const val CREATED = "created"
    const val EDITED = "edited"
    const val ACTION = "action"
    const val FOLLOW_UP = "followup"
    const val HANDOVER = "handover"
    const val SNOOZE = "snooze"
    const val COMPLETED = "completed"
    const val REOPENED = "reopened"
    const val NOTE = "note"
    const val IMPORTED = "imported"
}

object Categories {
    const val SCHOOL = "School"
    const val HEALTH = "Health"
    const val PROPERTY = "Property"
    const val MONEY = "Money"
    const val ADMIN = "Admin"
    const val WORK = "Work"
    const val FAMILY = "Family"
    const val TRAVEL = "Travel"

    val ALL = listOf(SCHOOL, HEALTH, PROPERTY, MONEY, ADMIN, WORK, FAMILY, TRAVEL)

    /** v1 backups used a slightly different set; keep anything we do not know. */
    fun normalise(raw: String?): String {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return ADMIN
        return ALL.firstOrNull { it.equals(value, ignoreCase = true) } ?: value
    }
}

/** A loop plus its timeline, used by the detail screen. */
data class LoopWithEvents(
    val loop: Loop,
    val events: List<LoopEvent>,
)

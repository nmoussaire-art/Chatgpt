package com.deadlineguardian.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/** Room can't build [DeadlineWithItem] directly; this mirrors it for the join. */
data class DeadlineItemRow(
    @Embedded(prefix = "d_") val deadline: Deadline,
    @Embedded(prefix = "i_") val item: TrackedItem
) {
    fun toDomain() = DeadlineWithItem(deadline, item)
}

private const val JOIN_COLUMNS = """
    d.id AS d_id, d.itemId AS d_itemId, d.kind AS d_kind, d.date AS d_date,
    d.leadDays AS d_leadDays, d.moneyAtRiskMinor AS d_moneyAtRiskMinor,
    d.dismissed AS d_dismissed, d.doneAt AS d_doneAt, d.notifiedAt AS d_notifiedAt,
    i.id AS i_id, i.title AS i_title, i.kind AS i_kind, i.merchant AS i_merchant,
    i.purchaseDate AS i_purchaseDate, i.amountMinor AS i_amountMinor,
    i.currency AS i_currency, i.imagePath AS i_imagePath, i.rawText AS i_rawText,
    i.notes AS i_notes, i.createdAt AS i_createdAt
"""

@Dao
interface GuardianDao {

    @Insert suspend fun insertItem(item: TrackedItem): Long
    @Update suspend fun updateItem(item: TrackedItem)
    @Delete suspend fun deleteItem(item: TrackedItem)

    @Insert suspend fun insertDeadlines(deadlines: List<Deadline>)
    @Insert suspend fun insertDeadline(deadline: Deadline): Long
    @Update suspend fun updateDeadline(deadline: Deadline)
    @Delete suspend fun deleteDeadline(deadline: Deadline)

    @Transaction
    suspend fun saveScan(item: TrackedItem, deadlines: List<Deadline>): Long {
        val id = insertItem(item)
        insertDeadlines(deadlines.map { it.copy(itemId = id) })
        return id
    }

    @Query("SELECT $JOIN_COLUMNS FROM deadlines d JOIN items i ON i.id = d.itemId ORDER BY d.date ASC")
    fun activeDeadlines(): Flow<List<DeadlineItemRow>>

    @Query("SELECT $JOIN_COLUMNS FROM deadlines d JOIN items i ON i.id = d.itemId WHERE d.id = :id")
    fun deadlineById(id: Long): Flow<DeadlineItemRow?>

    @Query("SELECT * FROM items WHERE id = :id")
    fun itemById(id: Long): Flow<TrackedItem?>

    @Query("SELECT * FROM deadlines WHERE itemId = :itemId ORDER BY date ASC")
    fun deadlinesForItem(itemId: Long): Flow<List<Deadline>>

    @Query("SELECT * FROM items ORDER BY createdAt DESC")
    fun allItems(): Flow<List<TrackedItem>>

    /**
     * Snapshot (not a Flow) for the background worker: it wakes up, reads once,
     * notifies, and dies.
     */
    @Query(
        """SELECT $JOIN_COLUMNS FROM deadlines d JOIN items i ON i.id = d.itemId
           WHERE d.doneAt IS NULL AND d.dismissed = 0 AND d.date <= :horizon
           ORDER BY d.date ASC"""
    )
    suspend fun dueBy(horizon: LocalDate): List<DeadlineItemRow>

    @Query("UPDATE deadlines SET notifiedAt = :now WHERE id IN (:ids)")
    suspend fun markNotified(ids: List<Long>, now: Long)

    @Query("SELECT COUNT(*) FROM items")
    suspend fun itemCount(): Int
}

package com.loopguard.app.data

import android.content.Context
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/** How an import should treat data that is already on the device. */
enum class ImportMode {
    /** Keep existing loops and add the imported ones that are not already present. */
    MERGE,

    /** Wipe everything first. Used for a true "restore from backup". */
    REPLACE,
}

data class ImportResult(
    val added: Int,
    val skipped: Int,
    val replaced: Boolean,
    val legacy: Boolean,
)

class LoopRepository(private val dao: LoopDao) {

    fun observeAll(): Flow<List<Loop>> = dao.observeAll()
    fun observeLoop(id: Long): Flow<Loop?> = dao.observeLoop(id)
    fun observeEvents(loopId: Long): Flow<List<LoopEvent>> = dao.observeEvents(loopId)

    suspend fun eventsFor(loopId: Long): List<LoopEvent> = dao.eventsFor(loopId)

    /** Puts a deleted loop and its whole timeline back, for undo. */
    suspend fun restore(loop: Loop, events: List<LoopEvent>) {
        val newId = dao.insert(loop.copy(id = 0))
        events.forEach { dao.insertEvent(it.copy(id = 0, loopId = newId)) }
    }

    suspend fun find(id: Long): Loop? = dao.findById(id)
    suspend fun openLoops(): List<Loop> = dao.openLoops()
    suspend fun count(): Int = dao.count()

    suspend fun create(loop: Loop, today: LocalDate = LocalDate.now()): Long =
        dao.insertWithEvent(loop, "Loop captured", EventKind.CREATED, today.toEpochDay())

    suspend fun update(loop: Loop) = dao.update(loop)

    suspend fun delete(loop: Loop) = dao.delete(loop)

    suspend fun addEvent(
        loopId: Long,
        text: String,
        kind: String,
        today: LocalDate = LocalDate.now(),
    ) {
        dao.insertEvent(LoopEvent(loopId = loopId, text = text, date = today.toEpochDay(), kind = kind))
    }

    /** Record real-world movement: resets the silence clock. */
    suspend fun logAction(
        loopId: Long,
        text: String,
        handOver: Boolean,
        today: LocalDate = LocalDate.now(),
    ) {
        val loop = dao.findById(loopId) ?: return
        val newSide = if (handOver) Side.THEM else loop.sideEnum
        dao.update(
            loop.copy(
                lastActivity = today.toEpochDay(),
                side = newSide.storageKey,
                snoozedUntil = null,
            )
        )
        addEvent(loopId, text, if (handOver) EventKind.HANDOVER else EventKind.ACTION, today)
    }

    suspend fun recordFollowUp(loopId: Long, tone: String, today: LocalDate = LocalDate.now()) {
        val loop = dao.findById(loopId) ?: return
        dao.update(
            loop.copy(
                lastActivity = today.toEpochDay(),
                followUpCount = loop.followUpCount + 1,
                snoozedUntil = null,
            )
        )
        addEvent(loopId, "Follow-up sent ($tone)", EventKind.FOLLOW_UP, today)
    }

    suspend fun snooze(loopId: Long, until: LocalDate, today: LocalDate = LocalDate.now()) {
        val loop = dao.findById(loopId) ?: return
        dao.update(loop.copy(snoozedUntil = until.toEpochDay()))
        addEvent(loopId, "Snoozed until $until", EventKind.SNOOZE, today)
    }

    suspend fun unsnooze(loopId: Long, today: LocalDate = LocalDate.now()) {
        val loop = dao.findById(loopId) ?: return
        dao.update(loop.copy(snoozedUntil = null))
        addEvent(loopId, "Brought back into the queue", EventKind.NOTE, today)
    }

    suspend fun setSide(loopId: Long, side: Side, today: LocalDate = LocalDate.now()) {
        val loop = dao.findById(loopId) ?: return
        if (loop.sideEnum == side) return
        dao.update(loop.copy(side = side.storageKey, lastActivity = today.toEpochDay()))
        addEvent(
            loopId,
            if (side == Side.ME) "Responsibility moved to you" else "Handed back to ${loop.counterparty.ifBlank { "them" }}",
            EventKind.HANDOVER,
            today,
        )
    }

    suspend fun complete(loopId: Long, note: String = "", today: LocalDate = LocalDate.now()) {
        val loop = dao.findById(loopId) ?: return
        dao.update(
            loop.copy(
                status = LoopStatus.DONE.storageKey,
                closedAt = System.currentTimeMillis(),
                lastActivity = today.toEpochDay(),
                snoozedUntil = null,
            )
        )
        addEvent(loopId, note.ifBlank { "Loop closed" }, EventKind.COMPLETED, today)
    }

    suspend fun reopen(loopId: Long, today: LocalDate = LocalDate.now()) {
        val loop = dao.findById(loopId) ?: return
        dao.update(
            loop.copy(
                status = LoopStatus.OPEN.storageKey,
                closedAt = null,
                lastActivity = today.toEpochDay(),
            )
        )
        addEvent(loopId, "Loop reopened", EventKind.REOPENED, today)
    }

    suspend fun togglePin(loopId: Long) {
        val loop = dao.findById(loopId) ?: return
        dao.update(loop.copy(pinned = !loop.pinned))
    }

    // ------------------------------------------------------------------
    // Backup / restore
    // ------------------------------------------------------------------

    suspend fun exportJson(appVersion: String): String {
        val loops = dao.allLoops()
        val events = dao.allEvents().groupBy { it.loopId }
        return BackupSerializer.export(loops, events, appVersion)
    }

    suspend fun import(payload: BackupPayload, mode: ImportMode): ImportResult {
        if (mode == ImportMode.REPLACE) {
            dao.deleteAllEvents()
            dao.deleteAllLoops()
        }

        var added = 0
        var skipped = 0

        payload.loops.forEach { imported ->
            val existing = imported.loop.legacyId?.let { dao.findByLegacyId(it) }
            if (mode == ImportMode.MERGE && existing != null) {
                skipped++
                return@forEach
            }
            val newId = dao.insert(imported.loop.copy(id = 0))
            if (imported.events.isEmpty()) {
                dao.insertEvent(
                    LoopEvent(
                        loopId = newId,
                        text = "Imported from backup",
                        date = imported.loop.lastActivity,
                        kind = EventKind.IMPORTED,
                    )
                )
            } else {
                imported.events.forEach { dao.insertEvent(it.copy(id = 0, loopId = newId)) }
            }
            added++
        }

        return ImportResult(
            added = added,
            skipped = skipped,
            replaced = mode == ImportMode.REPLACE,
            legacy = payload.isLegacy,
        )
    }

    suspend fun seedStarterLoops(today: LocalDate = LocalDate.now()) {
        if (dao.count() > 0) return
        StarterData.loops(today).forEach { (loop, firstEvent) ->
            dao.insertWithEvent(loop, firstEvent, EventKind.CREATED, loop.lastActivity)
        }
    }

    companion object {
        @Volatile
        private var instance: LoopRepository? = null

        fun get(context: Context): LoopRepository =
            instance ?: synchronized(this) {
                instance ?: LoopRepository(LoopDatabase.get(context).loopDao()).also { instance = it }
            }
    }
}

/** Small, believable examples so the first run is not an empty screen. */
object StarterData {
    fun loops(today: LocalDate): List<Pair<Loop, String>> = listOf(
        Loop(
            title = "Confirm the Arabic stream place",
            counterparty = "Rebecca",
            organisation = "Admissions office",
            side = Side.THEM.storageKey,
            category = Categories.SCHOOL,
            dueDate = today.plusDays(2).toEpochDay(),
            impact = 4,
            lastActivity = today.minusDays(4).toEpochDay(),
            notes = "Needed before the enrolment form can be completed.",
            reference = "ADM-2291",
        ) to "Request sent",
        Loop(
            title = "Insurance pre-approval for the next injection",
            counterparty = "Claims team",
            organisation = "Insurer",
            side = Side.ME.storageKey,
            category = Categories.HEALTH,
            dueDate = today.plusDays(1).toEpochDay(),
            impact = 5,
            lastActivity = today.minusDays(1).toEpochDay(),
            notes = "Upload the prescription and the last report.",
            reference = "POL-77431",
        ) to "Documents requested",
        Loop(
            title = "Repainting estimate for the condo",
            counterparty = "Mégane",
            organisation = "Rentalys",
            side = Side.THEM.storageKey,
            category = Categories.PROPERTY,
            dueDate = today.plusDays(5).toEpochDay(),
            impact = 3,
            lastActivity = today.minusDays(7).toEpochDay(),
            notes = "Needed before the listing refresh.",
        ) to "Asked for estimate",
    )
}

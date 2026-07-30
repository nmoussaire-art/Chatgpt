package com.loopguard.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface LoopDao {

    @Query("SELECT * FROM loops ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<Loop>>

    @Query("SELECT * FROM loops WHERE id = :id")
    fun observeLoop(id: Long): Flow<Loop?>

    @Query("SELECT * FROM loops WHERE id = :id")
    suspend fun findById(id: Long): Loop?

    @Query("SELECT * FROM loops WHERE legacyId = :legacyId LIMIT 1")
    suspend fun findByLegacyId(legacyId: String): Loop?

    @Query("SELECT * FROM loops WHERE status = 'open'")
    suspend fun openLoops(): List<Loop>

    @Query("SELECT * FROM loops ORDER BY createdAt DESC")
    suspend fun allLoops(): List<Loop>

    @Query("SELECT COUNT(*) FROM loops")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(loop: Loop): Long

    @Update
    suspend fun update(loop: Loop)

    @Delete
    suspend fun delete(loop: Loop)

    @Query("DELETE FROM loops")
    suspend fun deleteAllLoops()

    @Query("SELECT * FROM events WHERE loopId = :loopId ORDER BY at DESC, id DESC")
    fun observeEvents(loopId: Long): Flow<List<LoopEvent>>

    @Query("SELECT * FROM events ORDER BY at DESC, id DESC")
    suspend fun allEvents(): List<LoopEvent>

    @Query("SELECT * FROM events WHERE loopId = :loopId ORDER BY at DESC, id DESC")
    suspend fun eventsFor(loopId: Long): List<LoopEvent>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvent(event: LoopEvent): Long

    @Query("DELETE FROM events")
    suspend fun deleteAllEvents()

    @Transaction
    suspend fun insertWithEvent(loop: Loop, eventText: String, kind: String, day: Long): Long {
        val id = insert(loop)
        insertEvent(LoopEvent(loopId = id, text = eventText, date = day, kind = kind))
        return id
    }

    @Transaction
    suspend fun replaceEverything(loops: List<Loop>, events: List<LoopEvent>) {
        deleteAllEvents()
        deleteAllLoops()
        loops.forEach { insert(it) }
        events.forEach { insertEvent(it) }
    }
}

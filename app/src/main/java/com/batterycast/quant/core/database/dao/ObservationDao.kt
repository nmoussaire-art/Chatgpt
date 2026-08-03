package com.batterycast.quant.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.batterycast.quant.core.database.entity.ObservationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ObservationDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(observation: ObservationEntity): Long

    @Query("SELECT * FROM observations ORDER BY timestampMs DESC LIMIT 1")
    suspend fun latest(): ObservationEntity?

    @Query("SELECT * FROM observations ORDER BY timestampMs DESC LIMIT 1")
    fun latestFlow(): Flow<ObservationEntity?>

    @Query("SELECT * FROM observations WHERE timestampMs >= :sinceMs ORDER BY timestampMs ASC")
    suspend fun since(sinceMs: Long): List<ObservationEntity>

    @Query("SELECT * FROM observations WHERE timestampMs >= :sinceMs ORDER BY timestampMs ASC")
    fun sinceFlow(sinceMs: Long): Flow<List<ObservationEntity>>

    @Query(
        "SELECT * FROM observations WHERE timestampMs BETWEEN :fromMs AND :toMs ORDER BY timestampMs ASC",
    )
    suspend fun between(fromMs: Long, toMs: Long): List<ObservationEntity>

    @Query("SELECT * FROM observations ORDER BY timestampMs DESC LIMIT :limit")
    suspend fun mostRecent(limit: Int): List<ObservationEntity>

    @Query("SELECT COUNT(*) FROM observations")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM observations")
    fun countFlow(): Flow<Int>

    @Query("SELECT MIN(timestampMs) FROM observations")
    suspend fun earliestTimestamp(): Long?

    /**
     * The observation closest in time to [targetMs] within [toleranceMs].
     *
     * Used to score a past forecast against what really happened. Returns null when no reading
     * covers the target — an unevaluated forecast is dropped rather than scored against a guess.
     */
    @Query(
        """
        SELECT * FROM observations
        WHERE ABS(timestampMs - :targetMs) <= :toleranceMs
        ORDER BY ABS(timestampMs - :targetMs) ASC
        LIMIT 1
        """,
    )
    suspend fun nearest(targetMs: Long, toleranceMs: Long): ObservationEntity?

    @Query("DELETE FROM observations WHERE timestampMs < :beforeMs")
    suspend fun deleteBefore(beforeMs: Long): Int

    @Query("DELETE FROM observations")
    suspend fun deleteAll()
}

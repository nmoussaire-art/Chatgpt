package com.batterycast.quant.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.batterycast.quant.core.database.entity.CapabilityEvidenceEntity
import com.batterycast.quant.core.database.entity.ForecastRecordEntity
import com.batterycast.quant.core.database.entity.ModelCellEntity
import com.batterycast.quant.core.database.entity.NotificationStateEntity
import com.batterycast.quant.core.database.entity.PrecisionSessionEntity
import com.batterycast.quant.core.database.entity.RegimeTransitionEntity
import com.batterycast.quant.core.database.entity.ResidualEntity
import com.batterycast.quant.forecasting.model.DrainMetric
import com.batterycast.quant.forecasting.model.ModelNamespace
import kotlinx.coroutines.flow.Flow

@Dao
interface ModelCellDao {

    @Upsert
    suspend fun upsert(cells: List<ModelCellEntity>)

    @Query("SELECT * FROM model_cells WHERE namespace = :namespace AND metric = :metric")
    suspend fun cells(namespace: ModelNamespace, metric: DrainMetric): List<ModelCellEntity>

    @Query("SELECT * FROM model_cells WHERE namespace = :namespace")
    suspend fun cells(namespace: ModelNamespace): List<ModelCellEntity>

    @Query("SELECT * FROM model_cells")
    suspend fun all(): List<ModelCellEntity>

    @Query(
        "SELECT * FROM model_cells WHERE namespace = :namespace AND cellKey = :cellKey AND metric = :metric",
    )
    suspend fun cell(namespace: ModelNamespace, cellKey: String, metric: DrainMetric): ModelCellEntity?

    @Query("DELETE FROM model_cells")
    suspend fun deleteAll()
}

@Dao
interface RegimeTransitionDao {

    @Upsert
    suspend fun upsert(transitions: List<RegimeTransitionEntity>)

    @Query("SELECT * FROM regime_transitions")
    suspend fun all(): List<RegimeTransitionEntity>

    @Query("DELETE FROM regime_transitions")
    suspend fun deleteAll()
}

@Dao
interface ResidualDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(residual: ResidualEntity)

    @Query("SELECT * FROM residuals ORDER BY createdAtMs DESC LIMIT :limit")
    suspend fun mostRecent(limit: Int): List<ResidualEntity>

    @Query("SELECT COUNT(*) FROM residuals")
    suspend fun count(): Int

    @Query("DELETE FROM residuals WHERE createdAtMs < :beforeMs")
    suspend fun deleteBefore(beforeMs: Long): Int

    @Query("DELETE FROM residuals")
    suspend fun deleteAll()
}

@Dao
interface ForecastRecordDao {

    @Insert
    suspend fun insert(record: ForecastRecordEntity): Long

    @Query("SELECT * FROM forecast_records WHERE evaluatedAtMs IS NULL AND targetMs <= :nowMs")
    suspend fun pendingEvaluation(nowMs: Long): List<ForecastRecordEntity>

    @Query(
        """
        UPDATE forecast_records
        SET actualPercent = :actualPercent,
            actualWasCharging = :actualWasCharging,
            evaluatedAtMs = :evaluatedAtMs
        WHERE id = :id
        """,
    )
    suspend fun recordOutcome(id: Long, actualPercent: Double, actualWasCharging: Boolean, evaluatedAtMs: Long)

    @Query("DELETE FROM forecast_records WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM forecast_records WHERE evaluatedAtMs IS NOT NULL ORDER BY targetMs DESC LIMIT :limit")
    suspend fun evaluated(limit: Int): List<ForecastRecordEntity>

    @Query("SELECT * FROM forecast_records WHERE evaluatedAtMs IS NOT NULL ORDER BY targetMs DESC LIMIT :limit")
    fun evaluatedFlow(limit: Int): Flow<List<ForecastRecordEntity>>

    @Query("SELECT COUNT(*) FROM forecast_records WHERE evaluatedAtMs IS NOT NULL")
    suspend fun evaluatedCount(): Int

    @Query("DELETE FROM forecast_records WHERE issuedAtMs < :beforeMs")
    suspend fun deleteBefore(beforeMs: Long): Int

    @Query("DELETE FROM forecast_records")
    suspend fun deleteAll()
}

@Dao
interface CapabilityEvidenceDao {

    @Upsert
    suspend fun upsert(entity: CapabilityEvidenceEntity)

    @Query("SELECT * FROM capability_evidence WHERE id = :id")
    suspend fun get(id: Int = CapabilityEvidenceEntity.SINGLETON_ID): CapabilityEvidenceEntity?

    @Query("SELECT * FROM capability_evidence WHERE id = 1")
    fun observe(): Flow<CapabilityEvidenceEntity?>

    @Query("DELETE FROM capability_evidence")
    suspend fun deleteAll()
}

@Dao
interface PrecisionSessionDao {

    @Insert
    suspend fun insert(session: PrecisionSessionEntity): Long

    @Query("SELECT * FROM precision_sessions WHERE endedAtMs IS NULL ORDER BY startedAtMs DESC LIMIT 1")
    suspend fun active(): PrecisionSessionEntity?

    @Query("SELECT * FROM precision_sessions ORDER BY startedAtMs DESC LIMIT 1")
    fun latestFlow(): Flow<PrecisionSessionEntity?>

    @Query(
        """
        UPDATE precision_sessions
        SET endedAtMs = :endedAtMs, endPercent = :endPercent, sampleCount = :sampleCount, completed = :completed
        WHERE id = :id
        """,
    )
    suspend fun finish(id: Long, endedAtMs: Long, endPercent: Double?, sampleCount: Int, completed: Boolean)

    @Query("DELETE FROM precision_sessions")
    suspend fun deleteAll()
}

@Dao
interface NotificationStateDao {

    @Upsert
    suspend fun upsert(state: NotificationStateEntity)

    @Query("SELECT * FROM notification_state WHERE triggerKey = :key")
    suspend fun get(key: String): NotificationStateEntity?

    @Query("DELETE FROM notification_state")
    suspend fun deleteAll()
}

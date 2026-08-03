package com.batterycast.quant.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.batterycast.quant.forecasting.model.DrainMetric
import com.batterycast.quant.forecasting.model.ModelNamespace
import com.batterycast.quant.forecasting.model.UsageRegime

/**
 * One exponentially weighted cell of the learned model.
 *
 * A cell holds a running mean and variance for one metric in one device state, together with the
 * effective number of observations behind it. The effective count is what drives Bayesian
 * shrinkage: a cell with little evidence is pulled towards its more general parent, so a single
 * unusual half hour can never take over the forecast.
 *
 * All namespaces share this table because they share exactly this shape — drain rates by state,
 * charging rates by charger and charge level, the discharge-curve shape, and the thermal and
 * power-save multipliers.
 */
@Entity(tableName = "model_cells", primaryKeys = ["namespace", "cellKey", "metric"])
data class ModelCellEntity(
    val namespace: ModelNamespace,
    val cellKey: String,
    val metric: DrainMetric,
    val mean: Double,
    /** Population variance of the metric within this cell. */
    val variance: Double,
    /** Effective sample count after time decay; fractional by design. */
    val weight: Double,
    /** Raw number of updates ever applied, for diagnostics and honest "based on N" wording. */
    val rawSamples: Long,
    val lastUpdateMs: Long,
)

/**
 * Decayed transition counts between usage regimes.
 *
 * The Monte Carlo simulator samples future regimes from this chain instead of assuming the
 * current regime persists for the whole horizon, which is what makes the forecast intervals widen
 * realistically over long horizons.
 */
@Entity(tableName = "regime_transitions", primaryKeys = ["fromRegime", "toRegime"])
data class RegimeTransitionEntity(
    val fromRegime: UsageRegime,
    val toRegime: UsageRegime,
    val decayedCount: Double,
    val lastUpdateMs: Long,
)

/**
 * A recorded forecast error.
 *
 * Residuals are the empirical basis for the uncertainty model: their median absolute deviation
 * sets the scale, and their quantiles are resampled directly by the simulator so the app does not
 * have to pretend errors are normally distributed.
 */
@Entity(tableName = "residuals")
data class ResidualEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val createdAtMs: Long,
    /** How far ahead the prediction reached. */
    val horizonMinutes: Double,
    /** Actual minus predicted, in percentage points. Negative means the battery drained faster. */
    val residualPercentPoints: Double,
    /** Residual normalised by horizon, in percentage points per hour. */
    val residualPercentPerHour: Double,
    val regime: UsageRegime,
    val wasCharging: Boolean,
)

/**
 * A forecast as it was issued, so accuracy can be reported honestly rather than asserted.
 *
 * [actualPercent] is filled in later by the evaluator once real observations cover the target
 * time; until then it stays null and the forecast is simply not counted.
 */
@Entity(tableName = "forecast_records")
data class ForecastRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val issuedAtMs: Long,
    val targetMs: Long,
    val horizonMinutes: Double,
    val batteryPercentAtIssue: Double,
    val predictedMedian: Double,
    val predictedP10: Double,
    val predictedP25: Double,
    val predictedP75: Double,
    val predictedP90: Double,
    val predictedSurvivalProbability: Double,
    val reservePercent: Double,
    val maturity: String,
    val wasChargingAtIssue: Boolean,
    val actualPercent: Double? = null,
    val actualWasCharging: Boolean? = null,
    val evaluatedAtMs: Long? = null,
)

/** Persisted capability evidence: what this device's sensors have actually done so far. */
@Entity(tableName = "capability_evidence")
data class CapabilityEvidenceEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val samples: Int,
    val currentNowPresent: Int,
    val currentNowAbsent: Int,
    val currentAveragePresent: Int,
    val currentAverageAbsent: Int,
    val chargeCounterPresent: Int,
    val chargeCounterAbsent: Int,
    val energyPresent: Int,
    val energyAbsent: Int,
    val voltagePresent: Int,
    val voltageAbsent: Int,
    val temperaturePresent: Int,
    val temperatureAbsent: Int,
    val thermalPresent: Int,
    val thermalAbsent: Int,
    val chargingPolicyPresent: Int,
    val chargingPolicyAbsent: Int,
    val currentMicroampLike: Int,
    val currentMilliampLike: Int,
    val currentPositiveWhileCharging: Int,
    val currentNegativeWhileCharging: Int,
    val currentPositiveWhileDischarging: Int,
    val currentNegativeWhileDischarging: Int,
    val smallestPercentStep: Double,
    val designCapacityMilliAh: Double?,
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}

/** A user-started, time-boxed high-cadence measurement session. */
@Entity(tableName = "precision_sessions")
data class PrecisionSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val startedAtMs: Long,
    val plannedDurationMs: Long,
    val endedAtMs: Long? = null,
    val sampleCount: Int = 0,
    val startPercent: Double,
    val endPercent: Double? = null,
    val completed: Boolean = false,
)

/** Debounce state for a notification trigger, so the app cannot become a nagging app. */
@Entity(tableName = "notification_state")
data class NotificationStateEntity(
    @PrimaryKey val triggerKey: String,
    val lastFiredAtMs: Long,
    val lastValue: Double,
)

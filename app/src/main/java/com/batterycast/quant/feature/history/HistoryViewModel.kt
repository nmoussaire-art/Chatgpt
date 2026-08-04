package com.batterycast.quant.feature.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.batterycast.quant.core.database.dao.ForecastRecordDao
import com.batterycast.quant.core.database.entity.ForecastRecordEntity
import com.batterycast.quant.di.DefaultDispatcher
import com.batterycast.quant.forecasting.clean.BatterySegment
import com.batterycast.quant.forecasting.clean.CleanedHistory
import com.batterycast.quant.forecasting.clean.ObservationCleaner
import com.batterycast.quant.forecasting.clean.SegmentKind
import com.batterycast.quant.forecasting.drain.DrainRateEstimator
import com.batterycast.quant.forecasting.stats.RobustStats
import com.batterycast.quant.telemetry.model.BatteryObservation
import com.batterycast.quant.telemetry.repository.ObservationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** A charging session, summarised for display. */
data class ChargingSessionSummary(
    val startMs: Long,
    val endMs: Long,
    val startPercent: Double,
    val endPercent: Double,
    val ratePercentPerHour: Double?,
    val plugType: String,
)

/** A stretch where drain was well above this phone's own normal range. */
data class UnusualDrainPeriod(
    val startMs: Long,
    val endMs: Long,
    val ratePercentPerHour: Double,
    val typicalPercentPerHour: Double,
    val screenOnFraction: Double,
)

data class HistoryUiState(
    val observations: List<BatteryObservation> = emptyList(),
    val chargingSessions: List<ChargingSessionSummary> = emptyList(),
    val unusualPeriods: List<UnusualDrainPeriod> = emptyList(),
    val scoredForecasts: List<ForecastRecordEntity> = emptyList(),
    val windowHours: Int = 24,
    val loading: Boolean = true,
)

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val observationRepository: ObservationRepository,
    private val forecastRecordDao: ForecastRecordDao,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val _state = MutableStateFlow(HistoryUiState())
    val state: StateFlow<HistoryUiState> = _state.asStateFlow()

    fun load(windowHours: Int = _state.value.windowHours) {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, windowHours = windowHours)
            val nowMs = System.currentTimeMillis()
            val raw = observationRepository.since(nowMs - windowHours * 3_600_000L)
            val scored = forecastRecordDao.evaluated(30)

            val analysis = withContext(defaultDispatcher) {
                val cleaned = ObservationCleaner.clean(raw)
                cleaned to analyse(cleaned)
            }

            _state.value = HistoryUiState(
                observations = analysis.first.observations,
                chargingSessions = analysis.second.first,
                unusualPeriods = analysis.second.second,
                scoredForecasts = scored,
                windowHours = windowHours,
                loading = false,
            )
        }
    }

    /**
     * Summarises charging sessions and flags unusual discharge.
     *
     * "Unusual" is defined against this phone's own distribution — the upper quartile of its
     * observed discharge rates, plus a margin — not against any assumed normal figure.
     */
    private fun analyse(
        cleaned: CleanedHistory,
    ): Pair<List<ChargingSessionSummary>, List<UnusualDrainPeriod>> {
        val sessions = cleaned.chargeSegments
            .filter { it.isUsable(minimumHours = MIN_SESSION_HOURS, minimumPoints = 2) }
            .map { segment ->
                ChargingSessionSummary(
                    startMs = segment.startMs,
                    endMs = segment.endMs,
                    startPercent = segment.startPercent,
                    endPercent = segment.endPercent,
                    ratePercentPerHour = DrainRateEstimator.chargeRateEstimate(segment)?.ratePerHour,
                    plugType = segment.plugType.name.lowercase().replaceFirstChar { it.uppercase() },
                )
            }

        val dischargeSegments = cleaned.dischargeSegments
            .filter { it.isUsable(minimumHours = MIN_SESSION_HOURS, minimumPoints = 3) }
        val rates = dischargeSegments.mapNotNull { rateOf(it) }
        val typical = RobustStats.median(rates)
        val upper = RobustStats.quantile(rates, 0.75)

        val unusual = if (typical == null || upper == null || rates.size < MIN_SEGMENTS_FOR_UNUSUAL) {
            emptyList()
        } else {
            val boundary = maxOf(upper * UNUSUAL_MULTIPLE, typical + MIN_UNUSUAL_MARGIN)
            dischargeSegments.mapNotNull { segment ->
                val rate = rateOf(segment) ?: return@mapNotNull null
                if (rate < boundary) return@mapNotNull null
                UnusualDrainPeriod(
                    startMs = segment.startMs,
                    endMs = segment.endMs,
                    ratePercentPerHour = rate,
                    typicalPercentPerHour = typical,
                    screenOnFraction = segment.screenOnFraction,
                )
            }
        }

        return sessions to unusual
    }

    private fun rateOf(segment: BatterySegment): Double? {
        if (segment.kind != SegmentKind.DISCHARGE) return null
        val hours = segment.durationHours
        if (hours <= 0.0) return null
        val rate = segment.percentDrop / hours
        return rate.takeIf { it.isFinite() && it >= 0.0 }
    }

    private companion object {
        const val MIN_SESSION_HOURS = 0.15
        const val MIN_SEGMENTS_FOR_UNUSUAL = 5
        const val UNUSUAL_MULTIPLE = 1.4
        const val MIN_UNUSUAL_MARGIN = 2.0
    }
}

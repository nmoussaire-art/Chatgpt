package com.batterycast.quant.feature.shared

import com.batterycast.quant.core.datastore.SettingsStore
import com.batterycast.quant.core.datastore.UserSettings
import com.batterycast.quant.di.ApplicationScope
import com.batterycast.quant.forecasting.ForecastContext
import com.batterycast.quant.forecasting.ForecastEngine
import com.batterycast.quant.forecasting.ForecastRequest
import com.batterycast.quant.forecasting.ModelUpdater
import com.batterycast.quant.forecasting.accuracy.AccuracyEvaluator
import com.batterycast.quant.forecasting.model.BatteryForecast
import com.batterycast.quant.forecasting.model.DataMaturity
import com.batterycast.quant.telemetry.model.ObservationSource
import com.batterycast.quant.telemetry.repository.ObservationRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton

/** How the target time was chosen. */
enum class TargetKind { BEDTIME, MIDNIGHT, TWO_HOURS, CALENDAR, CUSTOM }

data class TargetSelection(
    val label: String,
    val atMs: Long,
    val kind: TargetKind,
)

sealed interface ForecastUiState {
    data object Loading : ForecastUiState

    /**
     * Not enough has been observed to forecast anything.
     *
     * This state exists so the app can be useful and honest at the same time: it says what it is
     * doing and what will appear next, instead of rendering a confident-looking curve over no
     * evidence.
     */
    data class Collecting(
        val observationCount: Int,
        val headline: String,
        val detail: String,
        val progress: Float,
    ) : ForecastUiState

    data class Ready(
        val forecast: BatteryForecast,
        val settings: UserSettings,
        val target: TargetSelection,
    ) : ForecastUiState
}

/**
 * Single source of forecast state for the whole UI.
 *
 * Every screen reads from here rather than running its own simulation, for two reasons: a Monte
 * Carlo run is genuinely expensive and should happen once per refresh, and two screens showing
 * two different numbers for the same question would undermine the whole app.
 */
@Singleton
class ForecastCoordinator @Inject constructor(
    private val observationRepository: ObservationRepository,
    private val forecastEngine: ForecastEngine,
    private val modelUpdater: ModelUpdater,
    private val accuracyEvaluator: AccuracyEvaluator,
    private val settingsStore: SettingsStore,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<ForecastUiState>(ForecastUiState.Loading)
    val state: StateFlow<ForecastUiState> = _state.asStateFlow()

    private val _target = MutableStateFlow<TargetSelection?>(null)
    val target: StateFlow<TargetSelection?> = _target.asStateFlow()

    /** Latest context, shared with the scenario and charge-planner screens. */
    @Volatile
    var latestContext: ForecastContext? = null
        private set

    private val refreshMutex = Mutex()
    private var lastRefreshMs = 0L

    /**
     * Takes a fresh reading, updates the model, and re-forecasts.
     *
     * Called when the app comes to the foreground, when the user changes the target, and when the
     * user pulls to refresh. Throttled so returning to the app repeatedly does not run the
     * simulation over and over.
     */
    fun refresh(force: Boolean = false) {
        scope.launch { refreshSuspending(force) }
    }

    suspend fun refreshSuspending(force: Boolean = false) {
        refreshMutex.withLock {
            val nowMs = System.currentTimeMillis()
            if (!force && nowMs - lastRefreshMs < THROTTLE_MS) return
            lastRefreshMs = nowMs

            val settings = settingsStore.current()

            // Sample first: the forecast should describe the battery as it is now, not as it was
            // at the last background run.
            observationRepository.recordSample(ObservationSource.APP_FOREGROUND)
            modelUpdater.refresh(nowMs)

            val selection = _target.value ?: restoreTarget(settings, nowMs)
            _target.value = selection

            val context = forecastEngine.buildContext(nowMs)
            latestContext = context

            if (context == null || context.maturity == DataMaturity.COLLECTING) {
                _state.value = collectingState(context, nowMs)
                return
            }

            val horizonMs = ((selection.atMs - nowMs) + TARGET_PADDING_MS)
                .coerceIn(MIN_HORIZON_MS, ForecastRequest.DEFAULT_HORIZON_MS)

            val forecast = forecastEngine.buildForecast(
                context,
                ForecastRequest(
                    horizonMs = horizonMs,
                    reservePercent = settings.reservePercent,
                    targetMs = selection.atMs,
                    targetLabel = selection.label,
                ),
                nowMs,
            )

            _state.value = ForecastUiState.Ready(forecast, settings, selection)

            // File the forecast so it can be scored later, and score anything now due. This is
            // what makes the accuracy screen report real out-of-sample performance.
            accuracyEvaluator.record(forecast, selection.atMs, settings.reservePercent)
            accuracyEvaluator.evaluatePending(nowMs)
        }
    }

    private suspend fun collectingState(context: ForecastContext?, nowMs: Long): ForecastUiState.Collecting {
        val count = observationRepository.count()
        val earliest = observationRepository.earliestTimestamp()
        val spanHours = earliest?.let { (nowMs - it) / 3_600_000.0 } ?: 0.0
        val observedChange = context?.cleaned?.dischargeSegments
            ?.sumOf { it.percentDrop.coerceAtLeast(0.0) }
            ?: 0.0

        // Progress towards the first real forecast, measured on the two things that actually
        // gate it: elapsed observation and observed battery movement.
        val progress = minOf(
            1f,
            maxOf(
                (spanHours / ForecastEngine.MIN_HOURS_FOR_BASIC).toFloat(),
                (observedChange / ForecastEngine.MIN_PERCENT_FOR_BASIC).toFloat(),
            ) * 0.5f + (count / 8f).coerceAtMost(1f) * 0.5f,
        )

        return ForecastUiState.Collecting(
            observationCount = count,
            headline = "BatteryCast is collecting live battery behaviour",
            detail = buildString {
                append("A preliminary forecast will become available after enough change has been ")
                append("observed on this device. ")
                if (count == 0) {
                    append("No readings have been recorded yet — this normally takes a moment.")
                } else {
                    append("So far: $count reading${if (count == 1) "" else "s"}")
                    if (spanHours >= 0.1) {
                        append(" over ${String.format("%.1f", spanHours)} hours")
                    }
                    if (observedChange > 0) {
                        append(", ${String.format("%.0f", observedChange)} percentage points of discharge")
                    }
                    append(".")
                }
            },
            progress = progress,
        )
    }

    fun selectTarget(selection: TargetSelection) {
        _target.value = selection
        scope.launch {
            settingsStore.setSelectedTarget(selection.label, selection.atMs)
            refreshSuspending(force = true)
        }
    }

    /** The quick targets offered on the dashboard, computed against the current clock. */
    suspend fun quickTargets(nowMs: Long = System.currentTimeMillis()): List<TargetSelection> {
        val settings = settingsStore.current()
        val zone = ZoneId.systemDefault()
        val now = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMs), zone)

        val bedtime = now.with(LocalTime.of(settings.bedtimeHour, settings.bedtimeMinute))
            .let { if (it.isAfter(now)) it else it.plusDays(1) }
        val midnight = now.toLocalDate().plusDays(1).atStartOfDay(zone)

        return listOf(
            TargetSelection("Bedtime", bedtime.toInstant().toEpochMilli(), TargetKind.BEDTIME),
            TargetSelection("Midnight", midnight.toInstant().toEpochMilli(), TargetKind.MIDNIGHT),
            TargetSelection("In 2 hours", nowMs + 2 * 60 * 60 * 1000L, TargetKind.TWO_HOURS),
        )
    }

    private suspend fun restoreTarget(settings: UserSettings, nowMs: Long): TargetSelection {
        val storedMs = settings.selectedTargetMs
        val storedLabel = settings.selectedTargetLabel
        if (storedMs != null && storedLabel != null && storedMs > nowMs) {
            return TargetSelection(storedLabel, storedMs, TargetKind.CUSTOM)
        }
        return quickTargets(nowMs).first()
    }

    /** Clears cached state after the user deletes their data. */
    fun invalidate() {
        latestContext = null
        lastRefreshMs = 0L
        _state.value = ForecastUiState.Loading
    }

    companion object {
        /** Re-simulating more often than this adds nothing the user can perceive. */
        const val THROTTLE_MS = 30 * 1000L

        /** Simulate a little past the target so the chart does not end exactly on the marker. */
        const val TARGET_PADDING_MS = 60 * 60 * 1000L

        const val MIN_HORIZON_MS = 2 * 60 * 60 * 1000L
    }
}

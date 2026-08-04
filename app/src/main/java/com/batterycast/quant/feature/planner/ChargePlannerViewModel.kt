package com.batterycast.quant.feature.planner

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.batterycast.quant.core.datastore.SettingsStore
import com.batterycast.quant.core.system.CalendarEventProvider
import com.batterycast.quant.core.system.CalendarTarget
import com.batterycast.quant.feature.shared.ForecastCoordinator
import com.batterycast.quant.feature.shared.ForecastUiState
import com.batterycast.quant.forecasting.planner.ChargePlan
import com.batterycast.quant.forecasting.planner.ChargePlanRequest
import com.batterycast.quant.forecasting.planner.ChargePlanner
import com.batterycast.quant.telemetry.model.PlugType
import com.batterycast.quant.telemetry.work.ObservationScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PlannerInputs(
    val eventMs: Long,
    val eventLabel: String,
    val targetPercent: Double,
    val confidence: Double,
    val plugType: PlugType,
)

@HiltViewModel
class ChargePlannerViewModel @Inject constructor(
    private val coordinator: ForecastCoordinator,
    private val chargePlanner: ChargePlanner,
    private val calendarEventProvider: CalendarEventProvider,
    private val settingsStore: SettingsStore,
    private val observationScheduler: ObservationScheduler,
) : ViewModel() {

    val forecastState: StateFlow<ForecastUiState> = coordinator.state

    private val _inputs = MutableStateFlow<PlannerInputs?>(null)
    val inputs: StateFlow<PlannerInputs?> = _inputs.asStateFlow()

    private val _plan = MutableStateFlow<ChargePlan?>(null)
    val plan: StateFlow<ChargePlan?> = _plan.asStateFlow()

    private val _calendarTargets = MutableStateFlow<List<CalendarTarget>>(emptyList())
    val calendarTargets: StateFlow<List<CalendarTarget>> = _calendarTargets.asStateFlow()

    private val _computing = MutableStateFlow(false)
    val computing: StateFlow<Boolean> = _computing.asStateFlow()

    /** Start time a reminder is currently scheduled for, so the button can show its own state. */
    private val _reminderSetFor = MutableStateFlow<Long?>(null)
    val reminderSetFor: StateFlow<Long?> = _reminderSetFor.asStateFlow()

    /**
     * Schedules a one-off reminder at the recommended plug-in time.
     *
     * The user asked for this one, so it bypasses the threshold and cooldown rules that keep the
     * automatic alerts quiet.
     */
    fun setReminder() {
        val plan = _plan.value ?: return
        val startMs = plan.latestStartMs ?: return
        observationScheduler.scheduleChargeReminder(
            atMs = startMs,
            eventLabel = plan.request.eventLabel,
            targetPercent = plan.request.targetPercent.toInt(),
            durationMinutes = ((plan.requiredDurationMs ?: 0L) / 60_000L).toInt(),
        )
        _reminderSetFor.value = startMs
    }

    fun load() {
        viewModelScope.launch {
            coordinator.refreshSuspending()
            val settings = settingsStore.current()
            val target = coordinator.target.value
            if (_inputs.value == null && target != null) {
                _inputs.value = PlannerInputs(
                    eventMs = target.atMs,
                    eventLabel = target.label,
                    targetPercent = settings.desiredBatteryAtTarget,
                    confidence = settings.targetConfidence,
                    plugType = PlugType.AC,
                )
            }
            _calendarTargets.value = calendarEventProvider.upcomingEvents()
            computePlan()
        }
    }

    fun update(transform: (PlannerInputs) -> PlannerInputs) {
        val current = _inputs.value ?: return
        _inputs.value = transform(current)
        computePlan()
    }

    fun selectEvent(event: CalendarTarget) {
        update { it.copy(eventMs = event.startMs, eventLabel = event.title) }
    }

    private fun computePlan() {
        val inputs = _inputs.value ?: return
        viewModelScope.launch {
            val context = coordinator.latestContext ?: return@launch
            _computing.value = true
            _plan.value = chargePlanner.plan(
                context,
                ChargePlanRequest(
                    eventMs = inputs.eventMs,
                    eventLabel = inputs.eventLabel,
                    targetPercent = inputs.targetPercent,
                    confidence = inputs.confidence,
                    plugType = inputs.plugType,
                ),
            )
            if (_reminderSetFor.value != _plan.value?.latestStartMs) _reminderSetFor.value = null
            settingsStore.setDesiredBatteryAtTarget(inputs.targetPercent)
            settingsStore.setTargetConfidence(inputs.confidence)
            _computing.value = false
        }
    }
}

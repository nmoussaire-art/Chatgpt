package com.batterycast.quant.feature.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.batterycast.quant.core.system.CalendarEventProvider
import com.batterycast.quant.core.system.CalendarTarget
import com.batterycast.quant.feature.shared.ForecastCoordinator
import com.batterycast.quant.feature.shared.ForecastUiState
import com.batterycast.quant.feature.shared.TargetKind
import com.batterycast.quant.feature.shared.TargetSelection
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val coordinator: ForecastCoordinator,
    private val calendarEventProvider: CalendarEventProvider,
) : ViewModel() {

    val state: StateFlow<ForecastUiState> = coordinator.state
    val target: StateFlow<TargetSelection?> = coordinator.target

    private val _quickTargets = MutableStateFlow<List<TargetSelection>>(emptyList())
    val quickTargets: StateFlow<List<TargetSelection>> = _quickTargets.asStateFlow()

    private val _calendarTargets = MutableStateFlow<List<CalendarTarget>>(emptyList())
    val calendarTargets: StateFlow<List<CalendarTarget>> = _calendarTargets.asStateFlow()

    private val _calendarPermissionGranted = MutableStateFlow(false)
    val calendarPermissionGranted: StateFlow<Boolean> = _calendarPermissionGranted.asStateFlow()

    init {
        refresh()
        loadTargets()
    }

    fun refresh(force: Boolean = false) {
        coordinator.refresh(force)
    }

    fun loadTargets() {
        viewModelScope.launch {
            _quickTargets.value = coordinator.quickTargets()
            _calendarPermissionGranted.value = calendarEventProvider.hasPermission()
            _calendarTargets.value = calendarEventProvider.upcomingEvents()
        }
    }

    fun selectTarget(selection: TargetSelection) {
        coordinator.selectTarget(selection)
    }

    fun selectCalendarTarget(event: CalendarTarget) {
        coordinator.selectTarget(TargetSelection(event.title, event.startMs, TargetKind.CALENDAR))
    }

    fun selectCustomTime(atMs: Long, label: String) {
        coordinator.selectTarget(TargetSelection(label, atMs, TargetKind.CUSTOM))
    }
}

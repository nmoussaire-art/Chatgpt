package com.batterycast.quant.feature.scenarios

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.batterycast.quant.di.DefaultDispatcher
import com.batterycast.quant.feature.shared.ForecastCoordinator
import com.batterycast.quant.feature.shared.ForecastUiState
import com.batterycast.quant.forecasting.scenario.ScenarioEngine
import com.batterycast.quant.forecasting.scenario.ScenarioOutcome
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class ScenarioViewModel @Inject constructor(
    private val coordinator: ForecastCoordinator,
    private val scenarioEngine: ScenarioEngine,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
) : ViewModel() {

    val forecastState: StateFlow<ForecastUiState> = coordinator.state

    private val _scenarios = MutableStateFlow<List<ScenarioOutcome>>(emptyList())
    val scenarios: StateFlow<List<ScenarioOutcome>> = _scenarios.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    fun load() {
        viewModelScope.launch {
            coordinator.refreshSuspending()
            val context = coordinator.latestContext ?: return@launch
            val target = coordinator.target.value ?: return@launch
            val state = coordinator.state.value
            val reserve = (state as? ForecastUiState.Ready)?.settings?.reservePercent ?: 10.0

            _loading.value = true
            // Each scenario is its own Monte Carlo run, so this stays off the main thread.
            _scenarios.value = withContext(defaultDispatcher) {
                scenarioEngine.evaluateAll(context, target.atMs, reserve)
            }
            _loading.value = false
        }
    }
}

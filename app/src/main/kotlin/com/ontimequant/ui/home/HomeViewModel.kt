package com.ontimequant.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ontimequant.data.nav.NavigationLauncher
import com.ontimequant.data.prefs.SettingsRepository
import com.ontimequant.data.prefs.UserSettings
import com.ontimequant.data.repository.DemoSeeder
import com.ontimequant.data.repository.ForecastOutcome
import com.ontimequant.data.repository.ForecastRepository
import com.ontimequant.data.repository.JourneyRepository
import com.ontimequant.data.repository.ProviderRegistry
import com.ontimequant.data.repository.TripRepository
import com.ontimequant.forecast.JourneyForecast
import com.ontimequant.model.Appointment
import com.ontimequant.model.DataProvenance
import com.ontimequant.model.ProviderNotice
import com.ontimequant.notifications.NotificationScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

/**
 * The forecast the whole app revolves around, plus everything the home card needs to
 * explain it. Deliberately a plain immutable state object: the screen renders it and
 * nothing else.
 */
data class HomeUiState(
    val loading: Boolean = true,
    val recalculating: Boolean = false,
    val appointments: List<Appointment> = emptyList(),
    val selected: Appointment? = null,
    val forecast: JourneyForecast? = null,
    val error: String? = null,
    val notices: List<ProviderNotice> = emptyList(),
    val settings: UserSettings = UserSettings(),
    val demoMode: Boolean = true,
    val demoForced: Boolean = false,
    val journeyInFlight: Boolean = false,
    val lastMessage: String? = null,
    val now: Instant = Instant.now(),
) {
    val hasForecast: Boolean get() = forecast != null
    val provenance: DataProvenance get() = forecast?.routeProvenance ?: DataProvenance.UNAVAILABLE
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val journeys: JourneyRepository,
    private val forecasts: ForecastRepository,
    private val trips: TripRepository,
    private val settingsRepository: SettingsRepository,
    private val providers: ProviderRegistry,
    private val seeder: DemoSeeder,
    private val launcher: NavigationLauncher,
    private val notifications: NotificationScheduler,
) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    val settings: StateFlow<UserSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, UserSettings())

    init {
        viewModelScope.launch {
            // Demo data is seeded on first launch so the app is never an empty shell.
            seeder.seedIfNeeded()
            combine(
                journeys.upcomingAppointments,
                settingsRepository.settings,
                trips.activeObservation,
            ) { appointments, userSettings, observation ->
                Triple(appointments, userSettings, observation != null)
            }.collect { (appointments, userSettings, inFlight) ->
                val previouslySelected = _state.value.selected?.id
                val selected = appointments.firstOrNull { it.id == previouslySelected }
                    ?: appointments.firstOrNull { it.requiredArrival.isAfter(Instant.now()) }
                    ?: appointments.firstOrNull()
                val changed = selected?.id != previouslySelected
                _state.update {
                    it.copy(
                        appointments = appointments,
                        selected = selected,
                        settings = userSettings,
                        demoMode = userSettings.demoMode || providers.demoForcedByMissingKey(),
                        demoForced = providers.demoForcedByMissingKey(),
                        journeyInFlight = inFlight,
                        loading = false,
                    )
                }
                if (selected != null && (changed || _state.value.forecast == null)) {
                    recalculate()
                }
            }
        }
    }

    fun selectAppointment(appointment: Appointment) {
        _state.update { it.copy(selected = appointment, forecast = null, error = null) }
        recalculate()
    }

    fun recalculate() {
        val appointment = _state.value.selected ?: return
        viewModelScope.launch {
            _state.update { it.copy(recalculating = true, error = null, now = Instant.now()) }
            when (val outcome = forecasts.forecast(appointment)) {
                is ForecastOutcome.Ready -> {
                    _state.update {
                        it.copy(
                            forecast = outcome.forecast,
                            notices = outcome.forecast.notices,
                            recalculating = false,
                            loading = false,
                            now = Instant.now(),
                        )
                    }
                    notifications.evaluate(outcome.forecast)
                }
                is ForecastOutcome.Unavailable -> _state.update {
                    it.copy(
                        forecast = null,
                        notices = outcome.notices,
                        error = outcome.message,
                        recalculating = false,
                        loading = false,
                    )
                }
            }
        }
    }

    /** Ticks the clock so the countdown stays live without recomputing the forecast. */
    fun tick() {
        _state.update { it.copy(now = Instant.now()) }
    }

    fun setConfidenceTarget(target: Double) {
        val appointment = _state.value.selected ?: return
        viewModelScope.launch {
            journeys.saveAppointment(appointment.copy(confidenceTarget = target))
            settingsRepository.setConfidenceTarget(target)
            _state.update { it.copy(selected = appointment.copy(confidenceTarget = target)) }
            recalculate()
        }
    }

    /**
     * Starts the journey: records the decision instant (which is what makes the preparation
     * model learnable) and opens the navigation app.
     */
    fun startJourney(onNoNavigationApp: () -> Unit = {}) {
        val current = _state.value
        val appointment = current.selected ?: return
        val forecast = current.forecast ?: return
        viewModelScope.launch {
            val headline = forecast.recommended ?: forecast.bestEffort
            trips.beginJourney(
                appointment = appointment,
                predictedTravelSeconds = headline.componentMeans.baselineTravelSeconds,
                weatherSeverity = forecast.weatherSeverity,
                eventPressure = forecast.eventPressure,
                trafficRegime = forecast.trafficRegime,
            )
            val launched = launcher.launch(appointment.destination, current.settings.navigationApp)
            if (!launched) onNoNavigationApp()
            _state.update {
                it.copy(
                    lastMessage = if (launched) {
                        "Journey started. Confirm your arrival when you get there so the model can learn."
                    } else {
                        "No navigation app could be opened. The journey has still been started for tracking."
                    },
                )
            }
        }
    }

    fun declareDeparted() {
        viewModelScope.launch {
            trips.recordMovement()
            _state.update { it.copy(lastMessage = "Departure recorded.") }
        }
    }

    fun confirmArrival() {
        val appointment = _state.value.selected ?: return
        viewModelScope.launch {
            val trip = trips.completeJourney(appointment)
            _state.update {
                it.copy(
                    lastMessage = if (trip != null) {
                        "Trip saved. The model has been updated with this journey."
                    } else {
                        "Could not record that trip."
                    },
                )
            }
        }
    }

    fun cancelJourney() {
        viewModelScope.launch {
            trips.cancelJourney()
            _state.update { it.copy(lastMessage = "Journey tracking cancelled.") }
        }
    }

    fun consumeMessage() = _state.update { it.copy(lastMessage = null) }
}

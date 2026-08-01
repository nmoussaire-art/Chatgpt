package com.ontimequant.ui.calendar

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ontimequant.data.calendar.CalendarEvent
import com.ontimequant.data.calendar.CalendarRepository
import com.ontimequant.data.calendar.DeviceCalendar
import com.ontimequant.data.prefs.SettingsRepository
import com.ontimequant.data.repository.ForecastOutcome
import com.ontimequant.data.repository.ForecastRepository
import com.ontimequant.data.repository.JourneyRepository
import com.ontimequant.data.repository.ProviderRegistry
import com.ontimequant.model.Appointment
import com.ontimequant.model.LocationKind
import com.ontimequant.model.PlaceSuggestion
import com.ontimequant.model.ProviderResult
import com.ontimequant.model.SavedLocation
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.inject.Inject

data class CalendarRow(
    val event: CalendarEvent,
    val tracked: Boolean,
    val appointmentId: String?,
    val remindersEnabled: Boolean,
    val recommendedDeparture: Instant? = null,
    val onTimeProbability: Double? = null,
)

data class CalendarUiState(
    val permissionGranted: Boolean = false,
    val calendars: List<DeviceCalendar> = emptyList(),
    val selectedCalendarIds: Set<Long> = emptySet(),
    val events: List<CalendarRow> = emptyList(),
    val use24Hour: Boolean? = null,
    val message: String? = null,
)

@HiltViewModel
class CalendarViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val calendar: CalendarRepository,
    private val settings: SettingsRepository,
    private val journeys: JourneyRepository,
    private val forecasts: ForecastRepository,
    private val providers: ProviderRegistry,
) : ViewModel() {

    private val _state = MutableStateFlow(CalendarUiState())
    val state: StateFlow<CalendarUiState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            val userSettings = settings.current()
            val granted = calendar.hasPermission()
            if (!granted) {
                _state.update { it.copy(permissionGranted = false, use24Hour = userSettings.use24HourClock) }
                return@launch
            }
            val calendars = calendar.calendars()
            val selected = userSettings.selectedCalendarIds.ifEmpty { calendars.map { it.id }.toSet() }
            val now = Instant.now()
            val events = calendar.events(selected, now, now.plus(Duration.ofDays(7)))
            val rows = events.map { event ->
                val appointment = journeys.appointmentForCalendarEvent(event.eventId)
                CalendarRow(
                    event = event,
                    tracked = appointment != null,
                    appointmentId = appointment?.id,
                    remindersEnabled = appointment?.remindersEnabled ?: true,
                )
            }
            _state.update {
                it.copy(
                    permissionGranted = true,
                    calendars = calendars,
                    selectedCalendarIds = selected,
                    events = rows,
                    use24Hour = userSettings.use24HourClock,
                )
            }
            // Fill in forecasts for tracked events that are close enough to matter.
            rows.filter { it.tracked }.forEach { row -> loadForecast(row) }
        }
    }

    private fun loadForecast(row: CalendarRow) {
        val appointmentId = row.appointmentId ?: return
        viewModelScope.launch {
            val appointment = journeys.appointment(appointmentId) ?: return@launch
            if (Duration.between(Instant.now(), appointment.requiredArrival) > Duration.ofHours(24)) return@launch
            when (val outcome = forecasts.forecast(appointment, persist = false)) {
                is ForecastOutcome.Ready -> _state.update { state ->
                    state.copy(
                        events = state.events.map {
                            if (it.event.eventId == row.event.eventId) {
                                it.copy(
                                    recommendedDeparture = outcome.forecast.recommendedDeparture,
                                    onTimeProbability = outcome.forecast.onTimeProbability,
                                )
                            } else it
                        },
                    )
                }
                is ForecastOutcome.Unavailable -> Unit
            }
        }
    }

    fun toggleCalendar(id: Long) {
        viewModelScope.launch {
            val current = _state.value.selectedCalendarIds
            val updated = if (id in current) current - id else current + id
            settings.setSelectedCalendars(updated)
            refresh()
        }
    }

    /**
     * Turns a calendar event into a tracked appointment.
     *
     * The event's location string is resolved through the Places provider (or the demo
     * gazetteer). If it cannot be resolved, nothing is created and the user is told —
     * inventing coordinates would produce a confident forecast for the wrong place.
     */
    fun track(row: CalendarRow) {
        val locationText = row.event.location ?: return
        viewModelScope.launch {
            val origin = journeys.locationOfKind(LocationKind.HOME)
                ?: journeys.savedLocations.first().firstOrNull()
            if (origin == null) {
                _state.update { it.copy(message = "Set a Home location first.") }
                return@launch
            }

            val places = providers.places()
            val destination: SavedLocation? = when (val search = places.autocomplete(locationText, origin.point)) {
                is ProviderResult.Success -> search.value.firstOrNull()?.let { suggestion ->
                    resolve(suggestion)
                }
                is ProviderResult.Failure -> null
            }
            if (destination == null) {
                _state.update {
                    it.copy(message = "Could not find “$locationText”. Create the journey by hand instead.")
                }
                return@launch
            }

            journeys.saveAppointment(
                Appointment(
                    id = UUID.randomUUID().toString(),
                    title = row.event.title,
                    startTime = row.event.start,
                    zone = row.event.zone,
                    origin = origin,
                    destination = destination,
                    entryBufferMinutes = settings.current().entryBufferMinutes,
                    confidenceTarget = settings.current().confidenceTarget,
                    journeyId = "journey:${origin.id}->${destination.id}",
                    calendarEventId = row.event.eventId,
                    calendarId = row.event.calendarId,
                ),
            )
            refresh()
        }
    }

    private suspend fun resolve(suggestion: PlaceSuggestion): SavedLocation? =
        when (val result = providers.places().resolve(suggestion)) {
            is ProviderResult.Success -> result.value.also { journeys.saveLocation(it) }
            is ProviderResult.Failure -> null
        }

    fun untrack(row: CalendarRow) {
        val id = row.appointmentId ?: return
        viewModelScope.launch {
            journeys.deleteAppointment(id)
            refresh()
        }
    }

    fun setReminders(row: CalendarRow, enabled: Boolean) {
        val id = row.appointmentId ?: return
        viewModelScope.launch {
            journeys.setReminders(id, enabled)
            refresh()
        }
    }

    /** Android does not allow re-requesting a permanently denied permission; open settings. */
    fun requestPermissionFromSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }
}

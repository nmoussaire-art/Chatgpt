package com.ontimequant.ui.appointment

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ontimequant.data.location.LocationTracker
import com.ontimequant.data.prefs.ConfidencePresets
import com.ontimequant.data.prefs.SettingsRepository
import com.ontimequant.data.repository.JourneyRepository
import com.ontimequant.data.repository.ProviderRegistry
import com.ontimequant.model.Appointment
import com.ontimequant.model.LocationKind
import com.ontimequant.model.PlaceSuggestion
import com.ontimequant.model.ProviderResult
import com.ontimequant.model.SavedJourney
import com.ontimequant.model.SavedLocation
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject

data class AppointmentUiState(
    val title: String = "",
    val dateText: String = "",
    val timeText: String = "",
    val dateError: Boolean = false,
    val timeError: Boolean = false,
    val zoneId: String = ZoneId.systemDefault().id,
    val origin: SavedLocation? = null,
    val destination: SavedLocation? = null,
    val savedLocations: List<SavedLocation> = emptyList(),
    val destinationQuery: String = "",
    val suggestions: List<PlaceSuggestion> = emptyList(),
    val entryBufferMinutes: Int = 5,
    val confidenceTarget: Double = ConfidencePresets.SAFE,
    val parkingText: String = "",
    val walkingText: String = "",
    val locatingOrigin: Boolean = false,
    val error: String? = null,
    val saved: Boolean = false,
    val editingId: String? = null,
) {
    val canSave: Boolean
        get() = title.isNotBlank() && origin != null && destination != null &&
            !dateError && !timeError && dateText.isNotBlank() && timeText.isNotBlank()
}

@HiltViewModel
class AppointmentViewModel @Inject constructor(
    private val journeys: JourneyRepository,
    private val providers: ProviderRegistry,
    private val settings: SettingsRepository,
    private val location: LocationTracker,
) : ViewModel() {

    private val _state = MutableStateFlow(AppointmentUiState())
    val state: StateFlow<AppointmentUiState> = _state.asStateFlow()

    private var searchJob: Job? = null
    private var loaded = false

    fun load(appointmentId: String?) {
        if (loaded) return
        loaded = true
        viewModelScope.launch {
            val userSettings = settings.current()
            val places = journeys.savedLocations.first()
            val existing = appointmentId?.let { journeys.appointment(it) }
            val zone = existing?.zone ?: ZoneId.systemDefault()
            val start = existing?.startTime ?: defaultStart(zone)
            val local = start.atZone(zone)
            _state.update {
                it.copy(
                    editingId = existing?.id,
                    title = existing?.title ?: "",
                    dateText = local.toLocalDate().toString(),
                    timeText = "%02d:%02d".format(local.hour, local.minute),
                    zoneId = zone.id,
                    savedLocations = places,
                    origin = existing?.origin
                        ?: places.firstOrNull { p -> p.kind == LocationKind.HOME }
                        ?: places.firstOrNull(),
                    destination = existing?.destination,
                    entryBufferMinutes = existing?.entryBufferMinutes ?: userSettings.entryBufferMinutes,
                    confidenceTarget = existing?.confidenceTarget ?: userSettings.confidenceTarget,
                    parkingText = existing?.destination?.defaultParkingMinutes?.toInt()?.toString().orEmpty(),
                    walkingText = existing?.destination?.defaultWalkingMinutes?.toInt()?.toString().orEmpty(),
                )
            }
        }
    }

    private fun defaultStart(zone: ZoneId): Instant =
        LocalDate.now(zone).plusDays(1).atTime(9, 0).atZone(zone).toInstant()

    fun setTitle(value: String) = _state.update { it.copy(title = value) }

    fun setDateText(value: String) = _state.update {
        it.copy(dateText = value, dateError = runCatching { LocalDate.parse(value) }.isFailure)
    }

    fun setTimeText(value: String) = _state.update {
        it.copy(timeText = value, timeError = runCatching { LocalTime.parse(value) }.isFailure)
    }

    fun setOrigin(place: SavedLocation) = _state.update { it.copy(origin = place) }

    fun setEntryBuffer(minutes: Int) = _state.update { it.copy(entryBufferMinutes = minutes) }

    fun setConfidence(value: Double) = _state.update { it.copy(confidenceTarget = value) }

    fun setParkingText(value: String) = _state.update { it.copy(parkingText = value.filter(Char::isDigit)) }

    fun setWalkingText(value: String) = _state.update { it.copy(walkingText = value.filter(Char::isDigit)) }

    /** Debounced so typing does not fire one autocomplete request per keystroke. */
    fun searchDestination(query: String) {
        _state.update { it.copy(destinationQuery = query) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(280)
            val near = _state.value.origin?.point
            when (val result = providers.places().autocomplete(query, near)) {
                is ProviderResult.Success -> _state.update { it.copy(suggestions = result.value, error = null) }
                is ProviderResult.Failure -> _state.update {
                    it.copy(suggestions = emptyList(), error = result.notice.message)
                }
            }
        }
    }

    fun chooseSuggestion(suggestion: PlaceSuggestion) {
        viewModelScope.launch {
            when (val result = providers.places().resolve(suggestion)) {
                is ProviderResult.Success -> _state.update {
                    it.copy(
                        destination = result.value,
                        destinationQuery = result.value.label,
                        suggestions = emptyList(),
                        error = null,
                    )
                }
                is ProviderResult.Failure -> _state.update { it.copy(error = result.notice.message) }
            }
        }
    }

    fun useCurrentLocation() {
        viewModelScope.launch {
            _state.update { it.copy(locatingOrigin = true) }
            val point = location.currentLocation()
            if (point == null) {
                _state.update {
                    it.copy(
                        locatingOrigin = false,
                        error = "Could not get your location. Grant location access, or pick a saved place.",
                    )
                }
                return@launch
            }
            val place = SavedLocation(
                id = UUID.randomUUID().toString(),
                label = "Current location",
                address = "%.4f, %.4f".format(point.latitude, point.longitude),
                point = point,
            )
            journeys.saveLocation(place)
            _state.update {
                it.copy(
                    origin = place,
                    locatingOrigin = false,
                    savedLocations = it.savedLocations + place,
                    error = null,
                )
            }
        }
    }

    fun save() {
        val current = _state.value
        val origin = current.origin ?: return
        val destination = current.destination ?: return
        viewModelScope.launch {
            val zone = runCatching { ZoneId.of(current.zoneId) }.getOrDefault(ZoneId.systemDefault())
            val date = runCatching { LocalDate.parse(current.dateText) }.getOrNull()
            val time = runCatching { LocalTime.parse(current.timeText) }.getOrNull()
            if (date == null || time == null) {
                _state.update { it.copy(error = "Check the date and time.") }
                return@launch
            }

            val enrichedDestination = destination.copy(
                defaultParkingMinutes = current.parkingText.toDoubleOrNull(),
                defaultWalkingMinutes = current.walkingText.toDoubleOrNull(),
            )

            // A saved journey is created per origin/destination pair; it is the key under
            // which the model accumulates route-specific learning.
            val journeyId = "journey:${origin.id}->${enrichedDestination.id}"
            journeys.saveJourney(
                SavedJourney(
                    id = journeyId,
                    name = "${origin.label} to ${enrichedDestination.label}",
                    originId = origin.id,
                    destinationId = enrichedDestination.id,
                    defaultConfidence = current.confidenceTarget,
                    defaultEntryBufferMinutes = current.entryBufferMinutes,
                    createdAt = Instant.now(),
                ),
            )

            journeys.saveAppointment(
                Appointment(
                    id = current.editingId ?: UUID.randomUUID().toString(),
                    title = current.title.trim(),
                    startTime = date.atTime(time).atZone(zone).toInstant(),
                    zone = zone,
                    origin = origin,
                    destination = enrichedDestination,
                    entryBufferMinutes = current.entryBufferMinutes,
                    confidenceTarget = current.confidenceTarget,
                    journeyId = journeyId,
                ),
            )
            _state.update { it.copy(saved = true, error = null) }
        }
    }
}

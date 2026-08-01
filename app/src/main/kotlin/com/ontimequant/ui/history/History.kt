package com.ontimequant.ui.history

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.AssistChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.ontimequant.data.prefs.SettingsRepository
import com.ontimequant.data.repository.TripRepository
import com.ontimequant.model.CompletedTrip
import com.ontimequant.ui.components.EmptyState
import com.ontimequant.ui.components.ProvenanceChip
import com.ontimequant.ui.components.SectionCard
import com.ontimequant.ui.components.StatBlock
import com.ontimequant.ui.format.formatDuration
import com.ontimequant.ui.format.formatPercent
import com.ontimequant.ui.format.rememberTimeFormatter
import com.ontimequant.model.DataProvenance
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

data class HistoryUiState(
    val trips: List<CompletedTrip> = emptyList(),
    val use24Hour: Boolean? = null,
    val loading: Boolean = true,
)

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val trips: TripRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(HistoryUiState())
    val state: StateFlow<HistoryUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val userSettings = settings.current()
            trips.allTrips.collect { list ->
                _state.update {
                    it.copy(trips = list, use24Hour = userSettings.use24HourClock, loading = false)
                }
            }
        }
    }

    fun setExcluded(trip: CompletedTrip, excluded: Boolean) {
        viewModelScope.launch { trips.setExcludedFromLearning(trip.id, excluded) }
    }

    fun delete(trip: CompletedTrip) {
        viewModelScope.launch { trips.deleteTrip(trip.id) }
    }

    fun markUnusual(trip: CompletedTrip, note: String) {
        viewModelScope.launch {
            trips.updateTrip(trip.copy(unusualCircumstances = note.takeIf { it.isNotBlank() }))
        }
    }

    /** Corrects the recorded departure or arrival and recomputes the derived road time. */
    fun correctTimes(trip: CompletedTrip, departure: Instant, arrival: Instant) {
        if (!arrival.isAfter(departure)) return
        viewModelScope.launch {
            val total = (arrival.epochSecond - departure.epochSecond).toDouble()
            val overhead = (trip.parkingSeconds ?: 0.0) + (trip.walkingSeconds ?: 0.0)
            trips.updateTrip(
                trip.copy(
                    actualDeparture = departure,
                    actualArrival = arrival,
                    actualTravelSeconds = (total - overhead).coerceAtLeast(60.0),
                ),
            )
        }
    }

    fun editParkingWalking(trip: CompletedTrip, parkingMinutes: Double?, walkingMinutes: Double?) {
        viewModelScope.launch {
            val park = parkingMinutes?.times(60)
            val walk = walkingMinutes?.times(60)
            val total = (trip.actualArrival.epochSecond - trip.actualDeparture.epochSecond).toDouble()
            trips.updateTrip(
                trip.copy(
                    parkingSeconds = park,
                    walkingSeconds = walk,
                    actualTravelSeconds = (total - (park ?: 0.0) - (walk ?: 0.0)).coerceAtLeast(60.0),
                ),
            )
        }
    }
}

@Composable
fun HistoryScreen(viewModel: HistoryViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    if (state.trips.isEmpty() && !state.loading) {
        EmptyState(
            icon = Icons.Outlined.History,
            title = "No completed trips yet",
            body = "Once you finish a journey the app will record when you left, when you arrived, " +
                "and how far off the forecast was. That is what makes the next forecast better.",
            modifier = modifier.testTag("history_screen"),
        )
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().testTag("history_screen"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionCard(title = "${state.trips.size} completed trips") {
                Text(
                    "Everything here is stored only on this device. Correcting a trip immediately " +
                        "changes what the model has learned; excluding one removes its influence " +
                        "without deleting the record.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(state.trips, key = { it.id }) { trip ->
            TripRow(
                trip = trip,
                use24Hour = state.use24Hour,
                onExclude = { viewModel.setExcluded(trip, it) },
                onDelete = { viewModel.delete(trip) },
                onMarkUnusual = { viewModel.markUnusual(trip, it) },
                onEditParkWalk = { p, w -> viewModel.editParkingWalking(trip, p, w) },
            )
        }
    }
}

@Composable
private fun TripRow(
    trip: CompletedTrip,
    use24Hour: Boolean?,
    onExclude: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onMarkUnusual: (String) -> Unit,
    onEditParkWalk: (Double?, Double?) -> Unit,
) {
    val formatter = rememberTimeFormatter(trip.zone, use24Hour)
    var expanded by remember { mutableStateOf(false) }
    var note by remember(trip.id) { mutableStateOf(trip.unusualCircumstances.orEmpty()) }
    var parkText by remember(trip.id) {
        mutableStateOf(trip.parkingSeconds?.div(60)?.toInt()?.toString().orEmpty())
    }
    var walkText by remember(trip.id) {
        mutableStateOf(trip.walkingSeconds?.div(60)?.toInt()?.toString().orEmpty())
    }

    val errorSeconds = trip.actualTravelSeconds - trip.predictedTravelSeconds

    SectionCard(
        modifier = Modifier.clickable { expanded = !expanded },
        title = trip.appointmentTitle,
        subtitle = "${formatter.dayLabel(trip.actualDeparture)} · " +
            "${formatter.clock(trip.actualDeparture)} → ${formatter.clock(trip.actualArrival)}",
        trailing = {
            ProvenanceChip(
                if (trip.arrivedOnTime) DataProvenance.LIVE else DataProvenance.UNAVAILABLE,
                text = if (trip.arrivedOnTime) "On time" else "Late",
            )
        },
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            StatBlock(
                "Predicted drive",
                formatDuration(trip.predictedTravelSeconds),
                Modifier.weight(1f),
            )
            StatBlock("Actual drive", formatDuration(trip.actualTravelSeconds), Modifier.weight(1f))
            StatBlock(
                "Error",
                formatDuration(errorSeconds, alwaysSigned = true),
                Modifier.weight(1f),
            )
        }

        AnimatedVisibility(expanded) {
            Column(Modifier.padding(top = 16.dp)) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    StatBlock(
                        "Deadline",
                        formatter.clock(trip.requiredArrival),
                        Modifier.weight(1f),
                    )
                    StatBlock(
                        "Recommended departure",
                        trip.recommendedDeparture?.let(formatter::clock) ?: "—",
                        Modifier.weight(1.3f),
                    )
                    StatBlock(
                        "Lateness",
                        if (trip.arrivedOnTime) "—" else formatDuration(trip.latenessSeconds),
                        Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    StatBlock(
                        "Preparation",
                        trip.preparationSeconds?.let(::formatDuration) ?: "not recorded",
                        Modifier.weight(1f),
                    )
                    StatBlock(
                        "On-time probability",
                        trip.onTimeProbabilityAtDeparture?.let { formatPercent(it) } ?: "—",
                        Modifier.weight(1.2f),
                    )
                    StatBlock("Model", trip.modelVersion, Modifier.weight(1.2f))
                }

                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = parkText,
                        onValueChange = { parkText = it.filter(Char::isDigit) },
                        label = { Text("Parking (min)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = walkText,
                        onValueChange = { walkText = it.filter(Char::isDigit) },
                        label = { Text("Walk (min)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = { onEditParkWalk(parkText.toDoubleOrNull(), walkText.toDoubleOrNull()) },
                ) { Text("Save parking and walking") }

                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Unusual circumstances") },
                    placeholder = { Text("Road closed, took a detour…") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { onMarkUnusual(note) }) { Text("Save note") }

                Spacer(Modifier.height(10.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AssistChip(
                        onClick = { onExclude(!trip.excludedFromLearning) },
                        label = {
                            Text(
                                if (trip.excludedFromLearning) "Include in learning"
                                else "Exclude from learning",
                            )
                        },
                    )
                    AssistChip(onClick = onDelete, label = { Text("Delete") })
                }
                if (trip.excludedFromLearning) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "This trip is kept for your records but does not influence any forecast.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

package com.ontimequant.ui.appointment

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ontimequant.data.prefs.ConfidencePresets
import com.ontimequant.model.PlaceSuggestion
import com.ontimequant.ui.components.NoticeBanner
import com.ontimequant.ui.components.SectionCard
import com.ontimequant.ui.format.formatPercent

/**
 * Create or edit an appointment: where, when, how early you want to be there, and how
 * confident you want to be. Everything else the model works out.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppointmentScreen(
    viewModel: AppointmentViewModel,
    appointmentId: String?,
    onSaved: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(appointmentId) { viewModel.load(appointmentId) }
    LaunchedEffect(state.saved) { if (state.saved) onSaved() }

    LazyColumn(
        modifier = modifier.fillMaxSize().testTag("appointment_screen"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            SectionCard(title = "What and when") {
                OutlinedTextField(
                    value = state.title,
                    onValueChange = viewModel::setTitle,
                    label = { Text("Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("appointment_title"),
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = state.dateText,
                        onValueChange = viewModel::setDateText,
                        label = { Text("Date (yyyy-mm-dd)") },
                        singleLine = true,
                        isError = state.dateError,
                        modifier = Modifier.weight(1.3f),
                    )
                    OutlinedTextField(
                        value = state.timeText,
                        onValueChange = viewModel::setTimeText,
                        label = { Text("Time (HH:mm)") },
                        singleLine = true,
                        isError = state.timeError,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Times are stored with the zone ${state.zoneId}, so this appointment stays correct " +
                        "even if you travel.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            SectionCard(
                title = "Where from",
                subtitle = state.origin?.address ?: "Choose a starting point",
            ) {
                state.savedLocations.forEach { place ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.setOrigin(place) }
                            .padding(vertical = 11.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column {
                            Text(place.label, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                place.address,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (place.id == state.origin?.id) {
                            Text("Selected", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                Spacer(Modifier.height(10.dp))
                Button(onClick = viewModel::useCurrentLocation, enabled = !state.locatingOrigin) {
                    Text(if (state.locatingOrigin) "Locating…" else "Use my current location")
                }
            }
        }

        item {
            SectionCard(
                title = "Where to",
                subtitle = state.destination?.address ?: "Search for a destination",
            ) {
                OutlinedTextField(
                    value = state.destinationQuery,
                    onValueChange = viewModel::searchDestination,
                    label = { Text("Search") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("destination_search"),
                )
                Spacer(Modifier.height(10.dp))
                state.suggestions.forEach { suggestion: PlaceSuggestion ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.chooseSuggestion(suggestion) }
                            .padding(vertical = 11.dp),
                    ) {
                        Column {
                            Text(suggestion.primaryText, style = MaterialTheme.typography.bodyLarge)
                            if (suggestion.secondaryText.isNotBlank()) {
                                Text(
                                    suggestion.secondaryText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                state.destination?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Destination: ${it.label}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        item {
            SectionCard(
                title = "How early do you want to be there?",
                subtitle = "The deadline becomes the start time minus this",
            ) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(listOf(0, 5, 10, 15, 30)) { minutes ->
                        FilterChip(
                            selected = state.entryBufferMinutes == minutes,
                            onClick = { viewModel.setEntryBuffer(minutes) },
                            label = {
                                Text(if (minutes == 0) "Exactly on time" else "$minutes min early")
                            },
                        )
                    }
                }
            }
        }

        item {
            SectionCard(title = "Confidence target") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(ConfidencePresets.all) { (label, value, _) ->
                        FilterChip(
                            selected = kotlin.math.abs(state.confidenceTarget - value) < 0.005,
                            onClick = { viewModel.setConfidence(value) },
                            label = { Text("$label ${formatPercent(value)}") },
                        )
                    }
                }
            }
        }

        item {
            SectionCard(
                title = "Parking and walking",
                subtitle = "Leave blank to let OnTime Quant learn them",
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = state.parkingText,
                        onValueChange = viewModel::setParkingText,
                        label = { Text("Parking (min)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = state.walkingText,
                        onValueChange = viewModel::setWalkingText,
                        label = { Text("Walk in (min)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        state.error?.let {
            item { NoticeBanner(it, com.ontimequant.model.DataProvenance.UNAVAILABLE) }
        }

        item {
            Button(
                onClick = viewModel::save,
                enabled = state.canSave,
                modifier = Modifier.fillMaxWidth().height(52.dp).testTag("save_appointment"),
            ) {
                Text("Save journey")
            }
        }
    }
}

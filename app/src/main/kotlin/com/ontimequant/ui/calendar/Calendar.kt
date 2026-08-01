package com.ontimequant.ui.calendar

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ontimequant.ui.components.EmptyState
import com.ontimequant.ui.components.ProvenanceChip
import com.ontimequant.ui.components.SectionCard
import com.ontimequant.ui.format.formatPercent
import com.ontimequant.ui.format.rememberTimeFormatter
import com.ontimequant.model.DataProvenance

/**
 * Upcoming calendar events, with a forecast status for each.
 *
 * Events without a usable location are shown rather than hidden — a missing destination
 * is exactly the thing the user needs to know about before the morning of the meeting.
 */
@Composable
fun CalendarScreen(
    viewModel: CalendarViewModel,
    onOpenAppointment: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val formatter = rememberTimeFormatter(java.time.ZoneId.systemDefault(), state.use24Hour)

    if (!state.permissionGranted) {
        EmptyState(
            icon = Icons.Outlined.CalendarMonth,
            title = "Calendar access is off",
            body = "OnTime Quant can read the calendars you choose, on your device only, to find " +
                "appointments with a destination. Nothing is uploaded. You can also keep entering " +
                "appointments by hand.",
            modifier = modifier.testTag("calendar_screen"),
            action = {
                Button(onClick = viewModel::requestPermissionFromSettings) {
                    Text("Open app settings")
                }
            },
        )
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().testTag("calendar_screen"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            SectionCard(
                title = "Which calendars?",
                subtitle = "Only the ones you tick are ever read",
            ) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.calendars) { calendar ->
                        FilterChip(
                            selected = calendar.id in state.selectedCalendarIds,
                            onClick = { viewModel.toggleCalendar(calendar.id) },
                            label = { Text(calendar.displayName) },
                        )
                    }
                }
            }
        }

        if (state.events.isEmpty()) {
            item {
                SectionCard(title = "Nothing upcoming") {
                    Text(
                        "No timed events in the next week in the calendars you selected. " +
                            "All-day events are skipped because they have no arrival deadline.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        items(state.events, key = { it.event.eventId }) { row ->
            SectionCard(
                title = row.event.title,
                subtitle = "${formatter.dayLabel(row.event.start)} at " +
                    formatter.clock(row.event.start, row.event.zone),
                trailing = {
                    ProvenanceChip(
                        if (row.event.hasUsableDestination) DataProvenance.LIVE else DataProvenance.UNAVAILABLE,
                        text = if (row.event.hasUsableDestination) "Has location" else "No location",
                    )
                },
            ) {
                if (row.event.hasUsableDestination) {
                    Text(
                        row.event.location.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(10.dp))
                    when {
                        row.recommendedDeparture != null -> {
                            Text(
                                "Leave at ${formatter.clock(row.recommendedDeparture)} for " +
                                    "${formatPercent(row.onTimeProbability ?: 0.0)} on time",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        row.tracked -> Text(
                            "Tracked. Forecast will appear once conditions are close enough to matter.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        else -> Text(
                            "Not tracked yet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Departure reminders", style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = row.remindersEnabled,
                            onCheckedChange = { viewModel.setReminders(row, it) },
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!row.tracked) {
                            Button(onClick = { viewModel.track(row) }) { Text("Track this") }
                        } else {
                            OutlinedButton(onClick = { viewModel.untrack(row) }) { Text("Stop tracking") }
                            row.appointmentId?.let { id ->
                                Button(onClick = { onOpenAppointment(id) }) { Text("Forecast") }
                            }
                        }
                    }
                } else {
                    Text(
                        "This event has no location, so no departure can be forecast for it. " +
                            "Add a location in your calendar, or create the journey by hand.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            SectionCard(title = "What is read") {
                Text(
                    "Only the title, start time, time zone, location and whether a reminder exists. " +
                        "Descriptions, attendees and organisers are never read, and nothing from your " +
                        "calendar leaves the device unless you ask for a forecast — in which case only " +
                        "the destination address is sent to the routing provider.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

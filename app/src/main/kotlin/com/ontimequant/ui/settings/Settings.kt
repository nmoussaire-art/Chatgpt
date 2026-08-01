package com.ontimequant.ui.settings

import android.content.Context
import android.content.Intent
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ontimequant.data.prefs.ConfidencePresets
import com.ontimequant.data.prefs.DistanceUnits
import com.ontimequant.data.prefs.NavigationApp
import com.ontimequant.data.prefs.ThemeMode
import com.ontimequant.ui.components.NoticeBanner
import com.ontimequant.ui.components.SectionCard
import com.ontimequant.ui.format.formatPercent
import com.ontimequant.model.DataProvenance

@Composable
fun SettingsScreen(viewModel: SettingsViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings = state.settings
    val context = LocalContext.current
    var confirmDelete by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier.fillMaxSize().testTag("settings_screen"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (state.demoForced) {
            item {
                NoticeBanner(
                    "No routing API key is configured in this build, so demo mode cannot be turned " +
                        "off. See the README for how to add a key in local.properties.",
                    DataProvenance.DEMO,
                )
            }
        }

        item {
            SectionCard(title = "Data source") {
                SwitchRow(
                    "Demo mode",
                    "Runs the real forecasting engine over a built-in Abu Dhabi journey, with no " +
                        "network calls. Everything is labelled DEMO.",
                    settings.demoMode,
                    enabled = !state.demoForced,
                    onChange = viewModel::setDemoMode,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = viewModel::resetDemo, modifier = Modifier.testTag("reset_demo")) {
                        Text("Reset demo data")
                    }
                }
            }
        }

        item {
            SectionCard(title = "Forecast defaults") {
                Text("Default confidence target", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(ConfidencePresets.all) { (label, value, _) ->
                        FilterChip(
                            selected = kotlin.math.abs(settings.confidenceTarget - value) < 0.005,
                            onClick = { viewModel.setConfidence(value) },
                            label = { Text("$label ${formatPercent(value)}") },
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text("Default arrive-early buffer", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(listOf(0, 5, 10, 15, 30)) { minutes ->
                        FilterChip(
                            selected = settings.entryBufferMinutes == minutes,
                            onClick = { viewModel.setEntryBuffer(minutes) },
                            label = { Text(if (minutes == 0) "On time" else "$minutes min") },
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MinuteField(
                        "Parking",
                        settings.defaultParkingMinutes,
                        viewModel::setParking,
                        Modifier.weight(1f),
                    )
                    MinuteField(
                        "Walk in",
                        settings.defaultWalkingMinutes,
                        viewModel::setWalking,
                        Modifier.weight(1f),
                    )
                    MinuteField(
                        "Get ready",
                        settings.defaultPreparationMinutes,
                        viewModel::setPreparation,
                        Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Leave these blank to let the model learn them from your journeys. A value here " +
                        "overrides what has been learned.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            SectionCard(title = "Learning") {
                SwitchRow(
                    "Learn how long you take to set off",
                    "OnTime Quant measures the gap between deciding to leave and actually moving, " +
                        "and includes it in the recommendation. This is a timing measurement, not a " +
                        "judgement, and you can turn it off.",
                    settings.preparationLearningEnabled,
                    onChange = viewModel::setPreparationLearning,
                )
                Spacer(Modifier.height(12.dp))
                SwitchRow(
                    "Background journey tracking",
                    "Detects arrival without the app being open. Without it you can still confirm " +
                        "arrival manually from the home screen.",
                    settings.backgroundTrackingEnabled,
                    onChange = viewModel::setBackgroundTracking,
                )
            }
        }

        item {
            SectionCard(title = "Notifications") {
                SwitchRow(
                    "Notifications",
                    "Master switch.",
                    settings.notificationsEnabled,
                    onChange = viewModel::setNotifications,
                )
                Spacer(Modifier.height(12.dp))
                SwitchRow(
                    "Departure reminders",
                    "One alert when it is time to leave.",
                    settings.departureRemindersEnabled,
                    enabled = settings.notificationsEnabled,
                    onChange = viewModel::setDepartureReminders,
                )
                Spacer(Modifier.height(12.dp))
                SwitchRow(
                    "Forecast change alerts",
                    "Only when the recommendation moves by three minutes or more, crosses your " +
                        "confidence target, or the risk level changes.",
                    settings.forecastChangeAlertsEnabled,
                    enabled = settings.notificationsEnabled,
                    onChange = viewModel::setForecastAlerts,
                )
            }
        }

        item {
            SectionCard(title = "Calendar") {
                SwitchRow(
                    "Use my calendar",
                    "Read-only, and only the calendars you tick on the Calendar screen.",
                    settings.calendarEnabled,
                    onChange = viewModel::setCalendarEnabled,
                )
                if (state.calendars.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(state.calendars) { calendar ->
                            FilterChip(
                                selected = calendar.id in settings.selectedCalendarIds,
                                onClick = { viewModel.toggleCalendar(calendar.id) },
                                label = { Text(calendar.displayName) },
                            )
                        }
                    }
                }
            }
        }

        item {
            SectionCard(title = "Navigation app") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.availableNavigationApps) { app ->
                        FilterChip(
                            selected = settings.navigationApp == app,
                            onClick = { viewModel.setNavigationApp(app) },
                            label = { Text(app.label) },
                        )
                    }
                }
            }
        }

        item {
            SectionCard(title = "Appearance") {
                Text("Theme", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(ThemeMode.entries.toList()) { mode ->
                        FilterChip(
                            selected = settings.themeMode == mode,
                            onClick = { viewModel.setTheme(mode) },
                            label = {
                                Text(mode.name.lowercase().replaceFirstChar(Char::titlecase))
                            },
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text("Clock", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(listOf<Boolean?>(null, false, true)) { option ->
                        FilterChip(
                            selected = settings.use24HourClock == option,
                            onClick = { viewModel.setClock(option) },
                            label = {
                                Text(
                                    when (option) {
                                        null -> "Follow device"
                                        true -> "24-hour"
                                        false -> "12-hour"
                                    },
                                )
                            },
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text("Units", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(DistanceUnits.entries.toList()) { units ->
                        FilterChip(
                            selected = settings.units == units,
                            onClick = { viewModel.setUnits(units) },
                            label = {
                                Text(units.name.lowercase().replaceFirstChar(Char::titlecase))
                            },
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                SwitchRow(
                    "Reduce motion",
                    "Disables chart and progress animations.",
                    settings.reducedMotion,
                    onChange = viewModel::setReducedMotion,
                )
            }
        }

        item {
            SectionCard(title = "Your data") {
                Text(
                    "Everything OnTime Quant stores lives on this device: your saved places, " +
                        "appointments, completed trips and the model state learned from them. There " +
                        "is no account and no server. Trip history is excluded from cloud backup.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "When live providers are enabled, the only things sent off the device are the " +
                        "coordinates of a journey you asked to forecast (to the routing provider), the " +
                        "destination coordinates (to the weather and event providers) and what you " +
                        "type into destination search.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { viewModel.exportData(context) }) { Text("Export my data") }
                    Button(
                        onClick = { confirmDelete = true },
                        modifier = Modifier.testTag("delete_data"),
                    ) { Text("Delete everything") }
                }
                state.exportMessage?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        item {
            SectionCard(title = "About") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Model version", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        com.ontimequant.forecast.MODEL_VERSION,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Simulations per candidate", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "${settings.simulationDraws}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete all local data?") },
            text = {
                Text(
                    "This removes every saved place, appointment, completed trip and everything the " +
                        "model has learned. It cannot be undone. The demo journey will be reloaded " +
                        "so the app still works.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.deleteEverything(); confirmDelete = false },
                    modifier = Modifier.testTag("confirm_delete"),
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SwitchRow(
    title: String,
    body: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(3.dp))
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun MinuteField(
    label: String,
    value: Double?,
    onChange: (Double?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by remember(value) { mutableStateOf(value?.toInt()?.toString().orEmpty()) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it.filter(Char::isDigit)
            onChange(text.toDoubleOrNull())
        },
        label = { Text(label) },
        placeholder = { Text("auto") },
        singleLine = true,
        modifier = modifier,
    )
}

@Suppress("unused")
private fun keepContextImport(c: Context, i: Intent) = c to i

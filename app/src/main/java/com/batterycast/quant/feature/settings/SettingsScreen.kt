package com.batterycast.quant.feature.settings

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.batterycast.quant.core.ui.components.BatteryCastCard
import com.batterycast.quant.core.ui.components.SectionHeader
import com.batterycast.quant.core.ui.components.StatRow
import com.batterycast.quant.core.ui.components.StatusChip
import com.batterycast.quant.core.ui.format.Formatters
import com.batterycast.quant.core.ui.theme.BatteryCastTheme
import com.batterycast.quant.telemetry.model.FieldSupport
import com.batterycast.quant.telemetry.work.ObservationScheduler
import kotlin.math.roundToInt

/**
 * Permissions, privacy and diagnostics.
 *
 * Each optional permission is explained in terms of what it buys and what still works without it,
 * because both of those are true and the user deserves to decide on the facts.
 */
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val permissions by viewModel.permissions.collectAsStateWithLifecycle()
    val diagnostics by viewModel.diagnostics.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    var confirmDelete by remember { mutableStateOf(false) }

    val calendarLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { viewModel.refresh() }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { viewModel.refresh() }

    LaunchedEffect(Unit) { viewModel.refresh() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            SectionHeader(
                title = "Permissions and privacy",
                subtitle = "All battery observations and forecasts remain on your device.",
            )
        }

        item {
            BatteryCastCard {
                SectionHeader(title = "What leaves your phone")
                Text(
                    text = "Nothing. BatteryCast holds no internet permission, so the app is " +
                        "structurally unable to transmit anything. There are no analytics, no " +
                        "advertising, no crash reporting that uploads device data, no accounts, " +
                        "and no cloud database. Backups are disabled so the observation history " +
                        "is not copied off the device by the system either.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            BatteryCastCard {
                SectionHeader(
                    title = "Usage access",
                    subtitle = "Optional. The forecast works fully without it.",
                )
                StatusChip(
                    text = if (permissions.usageAccessGranted) "Granted" else "Not granted",
                    color = if (permissions.usageAccessGranted) {
                        BatteryCastTheme.semanticColors.positive
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Text(
                    text = "Grants aggregate foreground time and a broad app category — video, " +
                        "game, maps and so on. It never stores which apps you used, and " +
                        "BatteryCast never claims an app consumed a specific amount of battery, " +
                        "because Android does not provide evidence that would support it. With " +
                        "it, the app can distinguish navigation-like from gaming-like usage " +
                        "rather than calling both 'heavy use'.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = {
                        runCatching {
                            context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                        }
                    },
                ) { Text(if (permissions.usageAccessGranted) "Manage in settings" else "Open settings") }
            }
        }

        item {
            BatteryCastCard {
                SectionHeader(
                    title = "Calendar",
                    subtitle = "Optional. Lets you target a real event.",
                )
                StatusChip(
                    text = if (permissions.calendarGranted) "Granted" else "Not granted",
                    color = if (permissions.calendarGranted) {
                        BatteryCastTheme.semanticColors.positive
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Text(
                    text = "Reads only the title, start and end of events in the next two days, " +
                        "so you can pick 'flight at 8 pm' instead of typing a time. Nothing is " +
                        "stored and nothing is uploaded.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!permissions.calendarGranted) {
                    OutlinedButton(onClick = { calendarLauncher.launch(Manifest.permission.READ_CALENDAR) }) {
                        Text("Grant calendar access")
                    }
                }
            }
        }

        item {
            BatteryCastCard {
                SectionHeader(title = "Notifications")
                StatusChip(
                    text = if (permissions.notificationsGranted) "Allowed" else "Not allowed",
                    color = if (permissions.notificationsGranted) {
                        BatteryCastTheme.semanticColors.positive
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Survival threshold alerts", style = MaterialTheme.typography.bodyLarge)
                    Switch(
                        checked = settings?.notificationsEnabled ?: true,
                        onCheckedChange = { viewModel.setNotificationsEnabled(it) },
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Unusual drain alerts", style = MaterialTheme.typography.bodyLarge)
                    Switch(
                        checked = settings?.unusualDrainAlerts ?: true,
                        onCheckedChange = { viewModel.setUnusualDrainAlerts(it) },
                    )
                }
                Text(
                    text = "Alerts fire only when a probability crosses a threshold, and never " +
                        "more than once every few hours. They are silent by design.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!permissions.notificationsGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    OutlinedButton(
                        onClick = { notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                    ) { Text("Allow notifications") }
                }
            }
        }

        item {
            BatteryCastCard {
                SectionHeader(title = "Forecast preferences")
                Text(
                    text = "Reserve level: ${(settings?.reservePercent ?: 10.0).roundToInt()}%",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = "The level the survival probability is measured against.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = (settings?.reservePercent ?: 10.0).toFloat(),
                    onValueChange = { viewModel.setReservePercent(it.toDouble()) },
                    valueRange = 0f..30f,
                    steps = 5,
                    modifier = Modifier.fillMaxWidth(),
                )
                StatRow(
                    label = "Bedtime",
                    supporting = "Used for the 'bedtime' quick target",
                    value = String.format(
                        "%02d:%02d",
                        settings?.bedtimeHour ?: 23,
                        settings?.bedtimeMinute ?: 0,
                    ),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(21, 22, 23, 0).forEach { hour ->
                        OutlinedButton(onClick = { viewModel.setBedtime(hour, 0) }) {
                            Text(String.format("%02d:00", hour))
                        }
                    }
                }
            }
        }

        item {
            BatteryCastCard {
                SectionHeader(
                    title = "Measurement",
                    subtitle = "How often BatteryCast reads the battery.",
                )
                StatRow(
                    label = "Background sampling",
                    supporting = "Android may defer background work, so this is a target, not a guarantee",
                    value = "about every ${ObservationScheduler.PERIOD_MINUTES} min",
                )
                StatRow(
                    label = "Event sampling",
                    supporting = "Charging start and stop, power-save changes, app opened",
                    value = "immediate",
                )
                StatRow(label = "Readings stored", value = "${diagnostics.observationCount}")
                if (diagnostics.earliestObservationMs != null) {
                    StatRow(
                        label = "Observing since",
                        value = Formatters.dateAndTime(diagnostics.earliestObservationMs),
                    )
                }
            }
        }

        item {
            BatteryCastCard {
                SectionHeader(
                    title = "Precision session",
                    subtitle = "A short, high-cadence measurement you start yourself.",
                )
                Text(
                    text = "Samples the battery every 30 seconds for up to 20 minutes to pin down " +
                        "your current drain rate more precisely. It shows an ongoing notification " +
                        "the whole time, uses slightly more power than normal, and stops itself " +
                        "at the end. It never starts on its own.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (diagnostics.precisionSessionActive) {
                    Button(onClick = { viewModel.stopPrecisionSession() }) { Text("Stop session") }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(10, 15, 20).forEach { minutes ->
                            OutlinedButton(onClick = { viewModel.startPrecisionSession(minutes) }) {
                                Text("$minutes min")
                            }
                        }
                    }
                }
            }
        }

        item {
            BatteryCastCard {
                SectionHeader(
                    title = "This device's sensors",
                    subtitle = "Learned from real readings, not assumed from the Android version.",
                )
                val capabilities = diagnostics.capabilities
                SensorRow("Instantaneous current", capabilities.currentNow)
                SensorRow("Average current", capabilities.currentAverage)
                SensorRow("Charge counter", capabilities.chargeCounter)
                SensorRow("Energy counter", capabilities.energyCounter)
                SensorRow("Voltage", capabilities.voltage)
                SensorRow("Temperature", capabilities.temperature)
                SensorRow("Thermal status", capabilities.thermalStatus)
                SensorRow("Charging policy", capabilities.chargingPolicy)
                StatRow(
                    label = "Current sign convention",
                    value = capabilities.currentSign.name.lowercase().replace('_', ' '),
                )
                StatRow(
                    label = "Percentage resolution",
                    value = "${capabilities.percentGranularity} points",
                )
                StatRow(label = "Readings assessed", value = "${capabilities.samplesConsidered}")
            }
        }

        item {
            BatteryCastCard {
                SectionHeader(title = "Your data")
                Text(
                    text = "Export writes every stored observation to a CSV file you can share " +
                        "wherever you like. Deleting removes all observations, everything the " +
                        "model has learned, and every scored forecast.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            viewModel.exportData { uri -> shareCsv(context, uri) }
                        },
                    ) { Text("Export CSV") }
                    Button(onClick = { confirmDelete = true }) { Text("Delete all data") }
                }
            }
        }

        item {
            Text(
                text = message.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = BatteryCastTheme.semanticColors.positive,
            )
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete all local data?") },
            text = {
                Text(
                    "This removes every battery observation, everything the forecasting model has " +
                        "learned about this phone, and every recorded forecast. It cannot be " +
                        "undone, and forecasting starts again from scratch.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteAllData()
                        confirmDelete = false
                    },
                ) { Text("Delete everything") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SensorRow(label: String, support: FieldSupport) {
    val semantic = BatteryCastTheme.semanticColors
    StatRow(
        label = label,
        value = when (support) {
            FieldSupport.SUPPORTED -> "Supported"
            FieldSupport.LOW_PRECISION -> "Low precision"
            FieldSupport.UNSUPPORTED -> "Not reported"
            FieldSupport.UNKNOWN -> "Still assessing"
        },
        valueColor = when (support) {
            FieldSupport.SUPPORTED -> semantic.positive
            FieldSupport.LOW_PRECISION -> semantic.caution
            FieldSupport.UNSUPPORTED -> MaterialTheme.colorScheme.onSurfaceVariant
            FieldSupport.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
}

private fun shareCsv(context: android.content.Context, uri: Uri) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/csv"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(Intent.createChooser(intent, "Export battery observations")) }
}

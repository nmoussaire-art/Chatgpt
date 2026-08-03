package com.batterycast.quant.ui

import android.Manifest
import android.app.AppOpsManager
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.*
import com.batterycast.quant.model.*
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import kotlin.math.abs
import kotlin.math.roundToInt

private data class Destination(val route: String, val label: String, val icon: ImageVector)
private val bottomDestinations = listOf(
    Destination("home", "Home", Icons.Outlined.Home),
    Destination("survival", "Curve", Icons.Outlined.ShowChart),
    Destination("planner", "Charge", Icons.Outlined.BatteryChargingFull),
    Destination("scenarios", "Scenarios", Icons.Outlined.Tune),
    Destination("more", "More", Icons.Outlined.MoreHoriz)
)

@Composable
fun BatteryCastApp(viewModel: MainViewModel = hiltViewModel()) = BatteryCastTheme {
    val navigation = rememberNavController()
    val entry by navigation.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val snackbarHostState = remember { SnackbarHostState() }
    val status by viewModel.status.collectAsStateWithLifecycle()

    LaunchedEffect(status) {
        status?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearStatus()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar {
                bottomDestinations.forEach { destination ->
                    NavigationBarItem(
                        selected = route == destination.route,
                        onClick = {
                            navigation.navigate(destination.route) {
                                popUpTo("home") { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(destination.icon, contentDescription = null) },
                        label = { Text(destination.label) }
                    )
                }
            }
        }
    ) { padding ->
        NavHost(navigation, startDestination = "home", modifier = Modifier.padding(padding)) {
            composable("home") { HomeScreen(viewModel) }
            composable("survival") { SurvivalScreen(viewModel) }
            composable("planner") { PlannerScreen(viewModel) }
            composable("scenarios") { ScenarioScreen(viewModel) }
            composable("more") { MoreScreen(navigation) }
            composable("changed") { ChangedScreen(viewModel) }
            composable("history") { HistoryScreen(viewModel) }
            composable("accuracy") { AccuracyScreen(viewModel) }
            composable("settings") { SettingsScreen(viewModel) }
        }
    }
}

@Composable
private fun Screen(
    title: String,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            subtitle?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        item { Column(verticalArrangement = Arrangement.spacedBy(16.dp), content = content) }
    }
}

@Composable
private fun CardBox(content: @Composable ColumnScope.() -> Unit) {
    Card(shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content
        )
    }
}

private fun probability(value: Double?) = value?.let { "${(it * 100).roundToInt()}%" } ?: "—"
private fun battery(value: Double?) = value?.let { "${it.roundToInt()}%" } ?: "—"
private fun time(value: Long?) = value?.let {
    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it))
} ?: "—"

private fun durationUntil(start: Long?, end: Long?): String {
    if (start == null || end == null || end <= start) return "Not yet estimable"
    val minutes = (end - start) / 60_000L
    val hours = minutes / 60
    val remainder = minutes % 60
    return when {
        hours > 0 && remainder > 0 -> "${hours}h ${remainder}m"
        hours > 0 -> "${hours}h"
        else -> "${remainder}m"
    }
}

@Composable
private fun HomeScreen(viewModel: MainViewModel) {
    val latest by viewModel.latest.collectAsStateWithLifecycle()
    val forecast by viewModel.forecast.collectAsStateWithLifecycle()
    val target by viewModel.target.collectAsStateWithLifecycle()
    val reserve by viewModel.reserve.collectAsStateWithLifecycle()
    val events by viewModel.calendarTargets.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Screen("BatteryCast Quant", "Predictive battery survival from live on-device measurements") {
        CardBox {
            Text("Chance of lasting until ${time(target)}", style = MaterialTheme.typography.titleMedium)
            AnimatedContent(probability(forecast?.survivalProbability), label = "probability") {
                Text(
                    it,
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Text("Current ${battery(latest?.batteryPercent)}  •  reserve above $reserve%")
            val emptyTime = forecast?.thresholds?.firstOrNull { it.threshold == 0 }?.medianTimestamp
            Text(
                "Predicted time remaining: ${durationUntil(forecast?.generatedAt, emptyTime)}",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            HorizontalDivider()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Metric("Median", battery(forecast?.medianAtTarget), Modifier.weight(1f))
                Metric(
                    "Likely range",
                    if (forecast?.targetP10 != null) "${battery(forecast?.targetP10)}–${battery(forecast?.targetP90)}" else "—",
                    Modifier.weight(1.35f)
                )
                Metric("Conservative", battery(forecast?.conservativeAtTarget), Modifier.weight(1f))
            }
            AssistChip(
                onClick = {},
                label = { Text(forecast?.confidence?.name?.replace('_', ' ') ?: "COLLECTING") },
                leadingIcon = { Icon(Icons.Outlined.VerifiedUser, contentDescription = null) }
            )
            Text(
                forecast?.qualityMessage
                    ?: "BatteryCast is collecting live battery behaviour. A preliminary forecast will become available after enough change has been observed.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        CardBox {
            Text("Quick target", style = MaterialTheme.typography.titleMedium)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(listOf("2h" to "Two hours", "bedtime" to "Bedtime", "midnight" to "Midnight")) { (key, label) ->
                    FilterChip(selected = false, onClick = { viewModel.setQuickTarget(key) }, label = { Text(label) })
                }
                item {
                    FilterChip(
                        selected = false,
                        onClick = {
                            val calendar = Calendar.getInstance()
                            TimePickerDialog(
                                context,
                                { _, hour, minute ->
                                    calendar.set(Calendar.HOUR_OF_DAY, hour)
                                    calendar.set(Calendar.MINUTE, minute)
                                    calendar.set(Calendar.SECOND, 0)
                                    if (calendar.timeInMillis <= System.currentTimeMillis()) calendar.add(Calendar.DAY_OF_YEAR, 1)
                                    viewModel.setTarget(calendar.timeInMillis)
                                },
                                calendar.get(Calendar.HOUR_OF_DAY),
                                calendar.get(Calendar.MINUTE),
                                false
                            ).show()
                        },
                        label = { Text("Custom") }
                    )
                }
                items(events.take(3)) { event ->
                    FilterChip(
                        selected = false,
                        onClick = { viewModel.setTarget(event.endTime.takeIf { it > event.startTime } ?: event.startTime) },
                        label = { Text(event.title, maxLines = 1) }
                    )
                }
            }
            Text("Reserve threshold", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0, 5, 10, 20).forEach { value ->
                    FilterChip(
                        selected = reserve == value,
                        onClick = { viewModel.setReserve(value) },
                        label = { Text("$value%") }
                    )
                }
            }
        }

        CardBox {
            Text("What drives this forecast", style = MaterialTheme.typography.titleMedium)
            Text(forecast?.mainDriver ?: "No model explanation is available until a real forecast can be calculated.")
            Text(
                "Updated ${time(latest?.timestamp)}  •  ${forecast?.sampleCount ?: 0} live observations used",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(onClick = viewModel::capture) {
                Icon(Icons.Outlined.Refresh, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Capture live reading")
            }
        }

        CardBox {
            Text("Threshold outlook", style = MaterialTheme.typography.titleMedium)
            listOf(20, 10, 5, 0).forEach { threshold ->
                val distribution = forecast?.thresholds?.firstOrNull { it.threshold == threshold }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(if (threshold == 0) "Estimated empty" else "Reach $threshold%")
                    Text(time(distribution?.medianTimestamp), fontWeight = FontWeight.SemiBold)
                }
            }
        }

        CardBox {
            Text("Live model inputs", style = MaterialTheme.typography.titleMedium)
            InputRow("Drain", forecast?.baseDrainPercentPerHour?.let { "${oneDecimal(it)} percentage points/hour" } ?: "Not yet estimable")
            InputRow("Charge counter", forecast?.baseDrainMicroAhPerHour?.let { "${it.roundToInt()} µAh/hour" } ?: "Unsupported or insufficient")
            InputRow("Energy", forecast?.baseDrainMilliWhPerHour?.let { "${oneDecimal(it)} mWh/hour" } ?: "Unsupported or insufficient")
            InputRow("Current sensor", latest?.currentMicroA?.let { "$it µA" } ?: "Unsupported or invalid")
            InputRow("Temperature", latest?.temperatureDeciC?.let { "${oneDecimal(it / 10.0)} °C" } ?: "Unsupported")
        }
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun InputRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Medium)
    }
}

private fun oneDecimal(value: Double) = String.format("%.1f", value)

@Composable
private fun SurvivalScreen(viewModel: MainViewModel) {
    val forecast by viewModel.forecast.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<ForecastPoint?>(null) }
    Screen("Battery survival curve", "Median path with 50%, 80%, and 90% uncertainty bands") {
        CardBox {
            if (forecast?.points.orEmpty().size >= 2) {
                ForecastChart(forecast!!.points, forecast!!.targetTime, onSelect = { selected = it })
                selected?.let {
                    Text("${time(it.timestamp)}: median ${battery(it.median)}, 80% range ${battery(it.p10)}–${battery(it.p90)}")
                }
            } else {
                Text("A curve will appear after sufficient live change has been observed.")
            }
        }
        CardBox {
            Text("Hourly survival", style = MaterialTheme.typography.titleMedium)
            val points = forecast?.points.orEmpty()
            val interval = maxOf(1, 60 / 5)
            points.filterIndexed { index, _ -> index % interval == 0 }.drop(1).forEach { point ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(time(point.timestamp))
                    Text(probability(point.probabilityAboveReserve), fontWeight = FontWeight.SemiBold)
                }
            }
            if (points.isEmpty()) Text("Hourly probabilities will appear with the forecast curve.")
        }
        CardBox {
            Text("Interpretation", style = MaterialTheme.typography.titleMedium)
            Text("The centre line is the median of at least 2,000 simulated battery paths. Shaded areas show empirical uncertainty, not guaranteed bounds.")
        }
    }
}

@Composable
private fun PlannerScreen(viewModel: MainViewModel) {
    val target by viewModel.target.collectAsStateWithLifecycle()
    val plan by viewModel.chargePlan.collectAsStateWithLifecycle()
    var desired by remember { mutableIntStateOf(50) }
    var confidence by remember { mutableStateOf(.90) }
    Screen("Charge planner", "Uses observed charger behaviour and charging taper") {
        CardBox {
            Text("Event ${time(target)}", style = MaterialTheme.typography.titleMedium)
            Text("Desired battery: $desired%")
            Slider(desired.toFloat(), { desired = it.roundToInt() }, valueRange = 20f..100f, steps = 15)
            Text("Desired confidence: ${(confidence * 100).roundToInt()}%")
            Slider(confidence.toFloat(), { confidence = it.toDouble() }, valueRange = .60f..(.95f), steps = 6)
            Button(onClick = { viewModel.planCharge(target, desired, confidence) }) {
                Text("Calculate safe charge plan")
            }
        }
        CardBox {
            Text("Recommendation", style = MaterialTheme.typography.titleMedium)
            Text(plan?.message ?: "Choose a target and calculate a plan.")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Metric("Start by", time(plan?.latestSafeStart), Modifier.weight(1f))
                Metric("Charge for", plan?.recommendedDurationMinutes?.let { "$it min" } ?: "—", Modifier.weight(1f))
                Metric("After charge", battery(plan?.expectedBatteryAfterCharging), Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Metric("Expected at event", battery(plan?.expectedBatteryAtEvent), Modifier.weight(1f))
                Metric("Conservative at event", battery(plan?.conservativeBatteryAtEvent), Modifier.weight(1f))
            }
            Text(
                if (plan?.chargerKnown == true) "This charger has a sufficiently observed live curve."
                else "This charger is not yet sufficiently learned.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ScenarioScreen(viewModel: MainViewModel) {
    val results by viewModel.scenarioResults.collectAsStateWithLifecycle()
    val customDuration by viewModel.customDurationMinutes.collectAsStateWithLifecycle()
    val customActiveShare by viewModel.customActiveShare.collectAsStateWithLifecycle()
    val customHeavyShare by viewModel.customHeavyShare.collectAsStateWithLifecycle()
    Screen("Scenario laboratory", "Scenario rates come from this device's observed behaviour when available") {
        CardBox {
            Text("Custom mixed usage", style = MaterialTheme.typography.titleMedium)
            Text("Duration: $customDuration minutes")
            Slider(
                value = customDuration.toFloat(),
                onValueChange = { viewModel.setCustomDuration((it / 15f).roundToInt() * 15) },
                valueRange = 15f..240f,
                steps = 14
            )
            Text("Active-use share: $customActiveShare%")
            Slider(
                value = customActiveShare.toFloat(),
                onValueChange = { viewModel.setCustomActiveShare(it.roundToInt()) },
                valueRange = 0f..100f,
                steps = 9
            )
            Text("Heavy/media share within active use: $customHeavyShare%")
            Slider(
                value = customHeavyShare.toFloat(),
                onValueChange = { viewModel.setCustomHeavyShare(it.roundToInt()) },
                valueRange = 0f..100f,
                steps = 9
            )
            Text(
                "The remaining time is treated as standby. Every component is learned from your device; missing regimes use the closest observed state with wider uncertainty.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (results.isEmpty()) {
            CardBox { Text("Scenario comparisons unlock after a preliminary live forecast can be calculated.") }
        }
        results.forEach { result ->
            CardBox {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(result.scenario.title, style = MaterialTheme.typography.titleMedium)
                    Text(probability(result.survivalProbability), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
                Text("Median at target ${battery(result.medianAtTarget)}")
                Text(result.note, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = { viewModel.setScenario(result.scenario) }) { Text("Use on dashboard") }
            }
        }
        OutlinedButton(onClick = { viewModel.setScenario(null) }) { Text("Restore live baseline") }
    }
}

@Composable
private fun MoreScreen(navigation: NavHostController) = Screen("More", "Explanations, history, accuracy, and privacy") {
    listOf(
        Triple("changed", "What changed", Icons.Outlined.AutoGraph),
        Triple("history", "Battery history", Icons.Outlined.History),
        Triple("accuracy", "Model accuracy", Icons.Outlined.FactCheck),
        Triple("settings", "Permissions & privacy", Icons.Outlined.PrivacyTip)
    ).forEach { (route, label, icon) ->
        Card(onClick = { navigation.navigate(route) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
            Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null)
                Spacer(Modifier.width(16.dp))
                Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Icon(Icons.Outlined.ChevronRight, contentDescription = null)
            }
        }
    }
}

@Composable
private fun ChangedScreen(viewModel: MainViewModel) {
    val forecast by viewModel.forecast.collectAsStateWithLifecycle()
    val latest by viewModel.latest.collectAsStateWithLifecycle()
    Screen("What changed", "Plain-language explanation of the current forecast") {
        CardBox {
            Text(forecast?.mainDriver ?: "No change explanation is available yet.", style = MaterialTheme.typography.titleMedium)
            Text(
                "Observed state: ${latest?.usageRegime?.name?.replace('_', ' ') ?: "unknown"}, " +
                    "${latest?.networkType?.name?.lowercase() ?: "unknown network"}, thermal status ${latest?.thermalStatus ?: "unsupported"}."
            )
        }
        CardBox {
            Text("Sensor limits", style = MaterialTheme.typography.titleMedium)
            Text(
                if (latest?.currentMicroA == null) {
                    "Instantaneous current is unavailable or invalid on this device, so the model falls back to observed percentage and supported counter changes."
                } else {
                    "A validated instantaneous-current reading is available and can support preliminary estimates."
                }
            )
        }
    }
}

@Composable
private fun HistoryScreen(viewModel: MainViewModel) {
    val history by viewModel.history.collectAsStateWithLifecycle()
    Screen("Battery history", "Only real observations stored locally on this device") {
        CardBox {
            Text("${history.size} observations in the current window", style = MaterialTheme.typography.titleMedium)
            if (history.size >= 2) {
                BatteryHistoryChart(history)
                Text(
                    "Battery line • shaded charging sessions • bottom bars for interactive screen use • unusual-drain intervals highlighted relative to this device's own median rate",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text("More observations are needed for the history chart.")
            }
        }
        history.takeLast(20).reversed().forEach { observation ->
            CardBox {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(time(observation.timestamp))
                    Text(battery(observation.batteryPercent), fontWeight = FontWeight.Bold)
                }
                Text(
                    "${observation.usageRegime.name.replace('_', ' ')} • ${observation.overallQuality.name.replace('_', ' ')} • ${observation.source}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun AccuracyScreen(viewModel: MainViewModel) {
    val accuracy by viewModel.accuracy.collectAsStateWithLifecycle()
    Screen("Model accuracy", "Honest performance against later live outcomes") {
        CardBox {
            Text("${accuracy.evaluatedForecasts} forecasts evaluated", style = MaterialTheme.typography.titleLarge)
            if (accuracy.evaluatedForecasts == 0) {
                Text("Accuracy statistics unlock after forecasts reach their target times and can be compared with actual battery readings.")
            } else {
                Text("Forecasts have typically been within ${battery(accuracy.medianAbsoluteErrorPercent)} of the actual battery level.")
            }
        }
        CardBox {
            Metric("Median absolute error", battery(accuracy.medianAbsoluteErrorPercent))
            Metric("Typical time-to-20% error", accuracy.medianTimeTo20ErrorMinutes?.let { "${it.roundToInt()} min" } ?: "—")
            Metric("Forecast bias", signedBattery(accuracy.biasPercent))
            HorizontalDivider()
            Metric("50% interval coverage", probability(accuracy.coverage50))
            Metric("80% interval coverage", probability(accuracy.coverage80))
            Metric("90% interval coverage", probability(accuracy.coverage90))
            Metric(
                "Probability calibration error",
                accuracy.calibrationScore?.let { "${(it * 100).roundToInt()} pp" } ?: "Needs at least 10 outcomes"
            )
            Text("Lower calibration error is better; interval coverage should approach its stated level over many forecasts.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun signedBattery(value: Double?) = value?.let {
    val sign = if (it > 0) "+" else ""
    "$sign${it.roundToInt()} pp"
} ?: "—"

@Composable
private fun SettingsScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val createDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri: Uri? ->
        if (uri != null) scope.launch {
            context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(viewModel.exportCsv()) }
        }
    }
    val calendarPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { viewModel.refreshCalendar() }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val bluetoothPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { viewModel.capture() }

    Screen("Permissions & privacy", "All battery observations and forecasts remain on your device") {
        CardBox {
            Text("Optional usage access", style = MaterialTheme.typography.titleMedium)
            Text(permissionStatus(hasUsageAccess(context)))
            Text("Used only for aggregated foreground-use intensity and broad app categories. The app remains functional without it.")
            Button(onClick = { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }) {
                Text("Open usage access settings")
            }
        }
        CardBox {
            Text("Optional calendar", style = MaterialTheme.typography.titleMedium)
            Text(permissionStatus(ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED))
            Text("Lets you select an upcoming event as a battery target. Event data is never uploaded.")
            Button(onClick = { calendarPermission.launch(Manifest.permission.READ_CALENDAR) }) { Text("Grant calendar permission") }
        }
        if (Build.VERSION.SDK_INT >= 31) {
            CardBox {
                Text("Optional Bluetooth state", style = MaterialTheme.typography.titleMedium)
                Text(permissionStatus(ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED))
                Text("Used only as a broad local device-state signal; connected-device identities are not stored.")
                Button(onClick = { bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT) }) { Text("Allow Bluetooth state") }
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            CardBox {
                Text("Optional notifications", style = MaterialTheme.typography.titleMedium)
                Text(permissionStatus(ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED))
                Text("Used only for meaningful forecast-threshold changes and requested reminders.")
                Button(onClick = { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Allow notifications") }
            }
        }
        CardBox {
            Text("Measurement", style = MaterialTheme.typography.titleMedium)
            Text("Background observations are requested about every 15 minutes, but Android may defer them. Battery and power-state events also trigger observations.")
            Text("A precision session temporarily samples more often for 10–20 minutes and may use slightly more power.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { viewModel.startPrecision(15) }) { Text("Start 15 min") }
                OutlinedButton(onClick = viewModel::stopPrecision) { Text("Stop") }
            }
        }
        CardBox {
            Text("Local data", style = MaterialTheme.typography.titleMedium)
            Button(onClick = { createDocument.launch("batterycast-observations.csv") }) { Text("Export CSV") }
            OutlinedButton(onClick = viewModel::clearData) { Text("Delete all local data") }
            Text(
                "No analytics, advertising, crash-upload, account, cloud database, remote model, or INTERNET permission is included.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun permissionStatus(granted: Boolean) = if (granted) "Granted" else "Not granted"

private fun hasUsageAccess(context: Context): Boolean {
    val appOps = context.getSystemService(AppOpsManager::class.java)
    val mode = if (Build.VERSION.SDK_INT >= 29) {
        appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
    } else {
        @Suppress("DEPRECATION")
        appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
    }
    return mode == AppOpsManager.MODE_ALLOWED
}

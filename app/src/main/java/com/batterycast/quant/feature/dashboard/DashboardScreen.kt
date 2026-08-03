package com.batterycast.quant.feature.dashboard

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.batterycast.quant.core.ui.chart.ForecastFanChart
import com.batterycast.quant.core.ui.components.BatteryCastCard
import com.batterycast.quant.core.ui.components.CollectingDataCard
import com.batterycast.quant.core.ui.components.DataFreshnessFooter
import com.batterycast.quant.core.ui.components.SectionHeader
import com.batterycast.quant.core.ui.components.StatRow
import com.batterycast.quant.core.ui.components.StatusChip
import com.batterycast.quant.core.ui.format.Formatters
import com.batterycast.quant.core.ui.theme.BatteryCastTheme
import com.batterycast.quant.core.ui.theme.HeadlineProbabilityStyle
import com.batterycast.quant.feature.shared.ForecastUiState
import com.batterycast.quant.feature.shared.TargetSelection
import com.batterycast.quant.forecasting.model.BatteryForecast
import com.batterycast.quant.forecasting.model.DataMaturity
import kotlin.math.roundToInt

/**
 * The home screen.
 *
 * One question dominates it: will my phone last? The probability is the largest thing on the
 * screen, the target it refers to sits directly underneath, and everything else — the expected
 * level, the interval, the thresholds — is support for that one answer.
 */
@Composable
fun DashboardScreen(
    onOpenSurvival: () -> Unit,
    onOpenWhatChanged: () -> Unit,
    onOpenPlanner: () -> Unit,
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val quickTargets by viewModel.quickTargets.collectAsStateWithLifecycle()
    val calendarTargets by viewModel.calendarTargets.collectAsStateWithLifecycle()
    val selectedTarget by viewModel.target.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.refresh()
        viewModel.loadTargets()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Column {
                Text(
                    text = "Will my phone last?",
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = "Forecast from this device's own battery behaviour.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        when (val current = state) {
            is ForecastUiState.Loading -> item {
                CollectingDataCard(
                    headline = "Reading the battery…",
                    detail = "Taking a live measurement from this device.",
                )
            }

            is ForecastUiState.Collecting -> {
                item {
                    CollectingDataCard(
                        headline = current.headline,
                        detail = current.detail,
                        progress = current.progress,
                        progressLabel = "Nothing is estimated until real change has been measured.",
                    )
                }
                item { TargetPicker(quickTargets, calendarTargets, selectedTarget, viewModel) }
            }

            is ForecastUiState.Ready -> {
                item { SurvivalHeadlineCard(current.forecast, current.target, onOpenWhatChanged) }
                item { TargetPicker(quickTargets, calendarTargets, selectedTarget, viewModel) }
                item { ExpectedLevelCard(current.forecast) }
                item { ThresholdCard(current.forecast) }
                item { MiniCurveCard(current.forecast, onOpenSurvival) }
                item { DrainCard(current.forecast) }
                item { PlannerPromptCard(onOpenPlanner) }
                item { PrivacyFooter() }
            }
        }
    }
}

@Composable
private fun SurvivalHeadlineCard(
    forecast: BatteryForecast,
    target: TargetSelection,
    onOpenWhatChanged: () -> Unit,
) {
    val semantic = BatteryCastTheme.semanticColors
    val outcome = forecast.target

    BatteryCastCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MaturityChip(forecast.maturity)
            StatusChip(
                text = if (forecast.isCharging) "Charging" else "On battery",
                color = if (forecast.isCharging) semantic.chargingAccent else MaterialTheme.colorScheme.secondary,
            )
        }

        if (outcome == null) {
            Text(
                text = "Target outside the forecast window",
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = "Pick a target within the next 24 hours to see a survival probability.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@BatteryCastCard
        }

        val animated by animateFloatAsState(
            outcome.survivalProbability.toFloat(),
            tween(700),
            label = "survivalProbability",
        )
        val probabilityColor = semantic.forProbability(outcome.survivalProbability)

        Spacer(Modifier.height(4.dp))
        Text(
            text = "${(animated * 100).roundToInt()}%",
            style = HeadlineProbabilityStyle,
            color = probabilityColor,
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription = "${(outcome.survivalProbability * 100).roundToInt()} percent " +
                        "chance of staying above ${outcome.reservePercent.roundToInt()} percent " +
                        "until ${target.label}"
                },
        )
        Text(
            text = "chance of staying above ${Formatters.percent(outcome.reservePercent)} until " +
                "${target.label.lowercase()} (${Formatters.clockTimeWithDay(target.atMs, forecast.generatedAtMs)})",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(4.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        StatRow(
            label = "Battery now",
            value = Formatters.percent(forecast.currentPercent),
        )

        val topDriver = forecast.drivers.firstOrNull()
        if (topDriver != null) {
            TextButton(onClick = onOpenWhatChanged, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = topDriver.headline,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Text("Why", style = MaterialTheme.typography.labelLarge)
            }
        }

        DataFreshnessFooter(
            text = "Measured ${Formatters.relativeAge(forecast.dataQuality.newestObservationAgeMs)} · " +
                "${forecast.dataQuality.usableObservationCount} readings on this device",
            isStale = forecast.dataQuality.isStale,
        )
    }
}

@Composable
private fun MaturityChip(maturity: DataMaturity) {
    val semantic = BatteryCastTheme.semanticColors
    val color = when (maturity) {
        DataMaturity.COLLECTING, DataMaturity.PRELIMINARY -> semantic.preliminaryAccent
        DataMaturity.BASIC -> MaterialTheme.colorScheme.secondary
        DataMaturity.PERSONALISED, DataMaturity.MATURE -> semantic.positive
    }
    StatusChip(text = maturity.displayName, color = color)
}

@Composable
private fun ExpectedLevelCard(forecast: BatteryForecast) {
    val outcome = forecast.target ?: return
    BatteryCastCard {
        SectionHeader(
            title = "Expected battery at ${Formatters.clockTime(outcome.targetMs)}",
            subtitle = "From ${SIMULATED_PATHS_LABEL} simulated battery paths.",
        )
        StatRow(label = "Median", value = Formatters.percent(outcome.median))
        StatRow(
            label = "Likely range",
            supporting = "80% of simulated outcomes",
            value = Formatters.percentRange(outcome.p10, outcome.p90),
        )
        StatRow(
            label = "Conservative estimate",
            supporting = "10th percentile — the number to plan around",
            value = Formatters.percent(outcome.conservative),
        )
    }
}

@Composable
private fun ThresholdCard(forecast: BatteryForecast) {
    val reportable = forecast.thresholds.filter { it.thresholdPercent >= 5.0 && it.medianMs != null }
    BatteryCastCard {
        SectionHeader(title = "Expected to reach")
        if (reportable.isEmpty()) {
            Text(
                text = if (forecast.isCharging) {
                    "The battery is charging, so no discharge thresholds are expected within the " +
                        "forecast window."
                } else {
                    "None of these levels are reached within the forecast window in most simulated paths."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@BatteryCastCard
        }
        reportable.forEach { threshold ->
            StatRow(
                label = "${threshold.thresholdPercent.roundToInt()}%",
                supporting = if (threshold.p10Ms != null && threshold.p90Ms != null) {
                    "between ${Formatters.clockTime(forecast.generatedAtMs + threshold.p10Ms)} and " +
                        Formatters.clockTime(forecast.generatedAtMs + threshold.p90Ms)
                } else {
                    null
                },
                value = Formatters.clockTimeWithDay(
                    forecast.generatedAtMs + threshold.medianMs!!,
                    forecast.generatedAtMs,
                ),
            )
        }
    }
}

@Composable
private fun MiniCurveCard(forecast: BatteryForecast, onOpenSurvival: () -> Unit) {
    BatteryCastCard {
        SectionHeader(
            title = "Survival curve",
            subtitle = "Median projection with 50%, 80% and 90% forecast intervals.",
        )
        ForecastFanChart(
            points = forecast.curve,
            nowMs = forecast.generatedAtMs,
            targetMs = forecast.target?.targetMs,
            height = 180.dp,
        )
        TextButton(onClick = onOpenSurvival) { Text("Open full chart") }
    }
}

@Composable
private fun DrainCard(forecast: BatteryForecast) {
    val drain = forecast.drain
    BatteryCastCard {
        SectionHeader(title = "Current drain")
        StatRow(
            label = "Recent rate",
            supporting = drain.method?.let { "estimated from $it" },
            value = Formatters.ratePerHour(drain.currentPercentPerHour),
        )
        if (drain.baselinePercentPerHour != null) {
            StatRow(
                label = "Typical for this state",
                supporting = forecast.currentRegime.displayName,
                value = Formatters.ratePerHour(drain.baselinePercentPerHour),
            )
        }
        if (drain.milliAmpsNow != null) {
            StatRow(label = "Live current", value = Formatters.milliAmps(drain.milliAmpsNow))
        }
        if (drain.wattsNow != null) {
            StatRow(label = "Live power", value = Formatters.watts(drain.wattsNow))
        }
        if (forecast.dataQuality.unsupportedFields.isNotEmpty()) {
            Text(
                text = "Not reported by this device: ${forecast.dataQuality.unsupportedFields.joinToString()}.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PlannerPromptCard(onOpenPlanner: () -> Unit) {
    BatteryCastCard {
        SectionHeader(
            title = "Need it to last?",
            subtitle = "Work out the latest safe time to start charging for an event.",
        )
        TextButton(onClick = onOpenPlanner) { Text("Open charge planner") }
    }
}

@Composable
private fun PrivacyFooter() {
    Text(
        text = "All battery observations and forecasts remain on your device.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    )
}

@Composable
private fun TargetPicker(
    quickTargets: List<TargetSelection>,
    calendarTargets: List<com.batterycast.quant.core.system.CalendarTarget>,
    selected: TargetSelection?,
    viewModel: DashboardViewModel,
) {
    BatteryCastCard {
        SectionHeader(title = "Target time")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(quickTargets, key = { it.label }) { option ->
                FilterChip(
                    selected = selected?.atMs == option.atMs,
                    onClick = { viewModel.selectTarget(option) },
                    label = { Text("${option.label} · ${Formatters.clockTime(option.atMs)}") },
                )
            }
            if (calendarTargets.isNotEmpty()) {
                items(calendarTargets, key = { it.id }) { event ->
                    FilterChip(
                        selected = selected?.atMs == event.startMs,
                        onClick = { viewModel.selectCalendarTarget(event) },
                        label = { Text("${event.title} · ${Formatters.clockTime(event.startMs)}") },
                    )
                }
            }
            item {
                AssistChip(
                    onClick = {
                        val now = System.currentTimeMillis()
                        viewModel.selectCustomTime(now + 4 * 60 * 60 * 1000L, "In 4 hours")
                    },
                    label = { Text("In 4 hours") },
                    colors = AssistChipDefaults.assistChipColors(),
                )
            }
        }
        if (calendarTargets.isEmpty()) {
            Text(
                text = "Grant calendar access in Settings to target a real event, such as a flight " +
                    "or the end of a meeting.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private const val SIMULATED_PATHS_LABEL = "2,400"

package com.batterycast.quant.feature.planner

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.batterycast.quant.core.ui.components.BatteryCastCard
import com.batterycast.quant.core.ui.components.CollectingDataCard
import com.batterycast.quant.core.ui.components.SectionHeader
import com.batterycast.quant.core.ui.components.StatRow
import com.batterycast.quant.core.ui.components.StatusChip
import com.batterycast.quant.core.ui.format.Formatters
import com.batterycast.quant.core.ui.theme.BatteryCastTheme
import com.batterycast.quant.feature.shared.ForecastUiState
import com.batterycast.quant.forecasting.planner.PlanOutcome
import com.batterycast.quant.telemetry.model.PlugType
import kotlin.math.roundToInt

/**
 * The charge planner.
 *
 * Answers the question the dashboard cannot: given that you need 40 % at eight o'clock and you
 * want to be 90 % sure, when is the latest you can plug in? The answer comes from a bisection
 * over charging start times, each evaluated by a full simulation.
 */
@Composable
fun ChargePlannerScreen(viewModel: ChargePlannerViewModel = hiltViewModel()) {
    val state by viewModel.forecastState.collectAsStateWithLifecycle()
    val inputs by viewModel.inputs.collectAsStateWithLifecycle()
    val plan by viewModel.plan.collectAsStateWithLifecycle()
    val calendarTargets by viewModel.calendarTargets.collectAsStateWithLifecycle()
    val computing by viewModel.computing.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.load() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            SectionHeader(
                title = "Charge planner",
                subtitle = "The latest safe time to start charging for what is coming up.",
            )
        }

        if (state is ForecastUiState.Collecting || state is ForecastUiState.Loading) {
            item {
                val collecting = state as? ForecastUiState.Collecting
                CollectingDataCard(
                    headline = collecting?.headline ?: "Reading the battery…",
                    detail = (collecting?.detail ?: "Taking a live measurement.") +
                        " Charge planning needs an observed charging session before it can " +
                        "estimate charging time.",
                    progress = collecting?.progress,
                )
            }
            return@LazyColumn
        }

        val currentInputs = inputs ?: return@LazyColumn

        item {
            BatteryCastCard {
                SectionHeader(title = "Event")
                Text(
                    text = "${currentInputs.eventLabel} · " +
                        Formatters.clockTimeWithDay(currentInputs.eventMs, System.currentTimeMillis()),
                    style = MaterialTheme.typography.titleMedium,
                )
                if (calendarTargets.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(calendarTargets, key = { it.id }) { event ->
                            FilterChip(
                                selected = currentInputs.eventMs == event.startMs,
                                onClick = { viewModel.selectEvent(event) },
                                label = { Text("${event.title} · ${Formatters.clockTime(event.startMs)}") },
                            )
                        }
                    }
                } else {
                    Text(
                        text = "Grant calendar access to pick a real event. Otherwise the target " +
                            "you chose on the home screen is used.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            BatteryCastCard {
                SectionHeader(title = "What you need")
                Text(
                    text = "Battery at the event: ${currentInputs.targetPercent.roundToInt()}%",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Slider(
                    value = currentInputs.targetPercent.toFloat(),
                    onValueChange = { value ->
                        viewModel.update { it.copy(targetPercent = value.toDouble()) }
                    },
                    valueRange = 5f..95f,
                    steps = 17,
                    modifier = Modifier.fillMaxWidth(),
                )

                Text(
                    text = "Confidence: ${(currentInputs.confidence * 100).roundToInt()}%",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Slider(
                    value = currentInputs.confidence.toFloat(),
                    onValueChange = { value ->
                        viewModel.update { it.copy(confidence = value.toDouble()) }
                    },
                    valueRange = 0.5f..0.98f,
                    steps = 15,
                    modifier = Modifier.fillMaxWidth(),
                )

                Text("Charger", style = MaterialTheme.typography.bodyLarge)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(
                        listOf(PlugType.AC, PlugType.USB, PlugType.WIRELESS),
                        key = { it.name },
                    ) { plug ->
                        FilterChip(
                            selected = currentInputs.plugType == plug,
                            onClick = { viewModel.update { it.copy(plugType = plug) } },
                            label = { Text(plug.name.lowercase().replaceFirstChar { c -> c.uppercase() }) },
                        )
                    }
                }
            }
        }

        if (computing) {
            item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
        }

        val currentPlan = plan
        if (currentPlan != null) {
            item { PlanResultCard(currentPlan) }
        }
    }
}

@Composable
private fun PlanResultCard(plan: com.batterycast.quant.forecasting.planner.ChargePlan) {
    val semantic = BatteryCastTheme.semanticColors
    BatteryCastCard {
        SectionHeader(title = "Recommendation")

        StatusChip(
            text = when (plan.outcome) {
                PlanOutcome.NO_CHARGING_NEEDED -> "No charging needed"
                PlanOutcome.PLAN_FOUND -> "Plan found"
                PlanOutcome.NOT_ACHIEVABLE -> "Target not reachable"
                PlanOutcome.OUT_OF_RANGE -> "Outside forecast window"
                PlanOutcome.CHARGER_UNKNOWN -> "Charger not yet observed"
            },
            color = when (plan.outcome) {
                PlanOutcome.NO_CHARGING_NEEDED, PlanOutcome.PLAN_FOUND -> semantic.positive
                PlanOutcome.NOT_ACHIEVABLE -> semantic.risk
                else -> semantic.caution
            },
        )

        if (plan.latestStartMs != null) {
            StatRow(
                label = "Latest safe start",
                value = Formatters.clockTimeWithDay(plan.latestStartMs, System.currentTimeMillis()),
            )
        }
        if (plan.requiredDurationMs != null) {
            StatRow(label = "Charging time", value = Formatters.duration(plan.requiredDurationMs))
        }
        StatRow(
            label = "Expected at the event",
            value = Formatters.percent(plan.expectedPercentAtEvent),
        )
        StatRow(
            label = "Conservative outcome",
            supporting = "10th percentile",
            value = Formatters.percent(plan.conservativePercentAtEvent),
        )
        StatRow(
            label = "Achieved confidence",
            value = Formatters.probability(plan.achievedProbability),
        )
        StatRow(
            label = "Without charging",
            value = Formatters.probability(plan.probabilityWithoutCharging),
        )
        StatRow(
            label = "Charger behaviour",
            value = if (plan.chargerBehaviourKnown) "Observed on this phone" else "Not yet observed",
        )

        plan.notes.forEach { note ->
            Text(
                text = note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

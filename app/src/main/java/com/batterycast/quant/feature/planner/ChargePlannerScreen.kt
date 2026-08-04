package com.batterycast.quant.feature.planner

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.batterycast.quant.core.ui.components.BatteryCastCard
import com.batterycast.quant.core.ui.components.BatteryCastSlider
import com.batterycast.quant.core.ui.components.CollectingDataCard
import com.batterycast.quant.core.ui.components.CompactChip
import com.batterycast.quant.core.ui.components.SectionHeader
import com.batterycast.quant.core.ui.components.SelectableChip
import com.batterycast.quant.core.ui.components.StatRow
import com.batterycast.quant.core.ui.components.StatusChip
import com.batterycast.quant.core.ui.format.Formatters
import com.batterycast.quant.core.ui.theme.BatteryCastTheme
import com.batterycast.quant.core.ui.theme.MetricLabelStyle
import com.batterycast.quant.core.ui.theme.MonospaceNumberStyle
import com.batterycast.quant.feature.shared.ForecastUiState
import com.batterycast.quant.forecasting.planner.ChargePlan
import com.batterycast.quant.forecasting.planner.PlanOutcome
import com.batterycast.quant.telemetry.model.PlugType
import kotlin.math.roundToInt

/**
 * The charge planner.
 *
 * Answers the question the dashboard cannot: given that you need 40 % at eight o'clock and you want
 * to be 90 % sure, when is the latest you can plug in? The answer comes from a bisection over
 * charging start times, each evaluated by a full simulation.
 */
@Composable
fun ChargePlannerScreen(viewModel: ChargePlannerViewModel = hiltViewModel()) {
    val state by viewModel.forecastState.collectAsStateWithLifecycle()
    val inputs by viewModel.inputs.collectAsStateWithLifecycle()
    val plan by viewModel.plan.collectAsStateWithLifecycle()
    val calendarTargets by viewModel.calendarTargets.collectAsStateWithLifecycle()
    val computing by viewModel.computing.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current

    LaunchedEffect(Unit) { viewModel.load() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp),
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

        // The answer first, the controls underneath as adjustments rather than prerequisites.
        val currentPlan = plan
        if (currentPlan != null) {
            item { PlanResultCard(currentPlan) }
        }
        if (computing) {
            item {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = BatteryCastTheme.semanticColors.positive,
                    trackColor = BatteryCastTheme.semanticColors.trackInactive,
                )
            }
        }

        item {
            BatteryCastCard {
                SectionHeader(title = "Event")
                Text(
                    text = "${currentInputs.eventLabel} · " +
                        Formatters.clockTimeWithDay(currentInputs.eventMs, System.currentTimeMillis()),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (calendarTargets.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(calendarTargets, key = { it.id }) { event ->
                            SelectableChip(
                                text = event.title,
                                supporting = Formatters.clockTime(event.startMs),
                                selected = currentInputs.eventMs == event.startMs,
                                onClick = { viewModel.selectEvent(event) },
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

                LabelledValue(
                    label = "Battery at the event",
                    value = "${currentInputs.targetPercent.roundToInt()}%",
                )
                BatteryCastSlider(
                    value = currentInputs.targetPercent.toFloat(),
                    onValueChange = { value ->
                        viewModel.update { it.copy(targetPercent = value.toDouble()) }
                    },
                    valueRange = 5f..95f,
                    steps = 17,
                    onValueChangeFinished = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    },
                )

                LabelledValue(
                    label = "Confidence",
                    value = "${(currentInputs.confidence * 100).roundToInt()}%",
                )
                BatteryCastSlider(
                    value = currentInputs.confidence.toFloat(),
                    onValueChange = { value ->
                        viewModel.update { it.copy(confidence = value.toDouble()) }
                    },
                    valueRange = 0.5f..0.98f,
                    steps = 15,
                    onValueChangeFinished = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    },
                )

                Text(
                    text = "CHARGER",
                    style = MetricLabelStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(
                        listOf(PlugType.AC, PlugType.USB, PlugType.WIRELESS),
                        key = { it.name },
                    ) { plug ->
                        CompactChip(
                            text = plug.label,
                            selected = currentInputs.plugType == plug,
                            onClick = { viewModel.update { it.copy(plugType = plug) } },
                        )
                    }
                }
            }
        }
    }
}

/** A control's caption and its live value on one line, with the value in tabular figures. */
@Composable
private fun LabelledValue(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = value,
            style = MonospaceNumberStyle,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun PlanResultCard(plan: ChargePlan) {
    val semantic = BatteryCastTheme.semanticColors
    val tone = when (plan.outcome) {
        PlanOutcome.NO_CHARGING_NEEDED, PlanOutcome.PLAN_FOUND -> semantic.positive
        PlanOutcome.NOT_ACHIEVABLE -> semantic.risk
        else -> semantic.caution
    }

    BatteryCastCard {
        StatusChip(
            text = when (plan.outcome) {
                PlanOutcome.NO_CHARGING_NEEDED -> "No charging needed"
                PlanOutcome.PLAN_FOUND -> "Plan found"
                PlanOutcome.NOT_ACHIEVABLE -> "Target not reachable"
                PlanOutcome.OUT_OF_RANGE -> "Outside forecast window"
                PlanOutcome.CHARGER_UNKNOWN -> "Charger not yet observed"
            },
            color = tone,
        )

        // The decision, at headline weight, before any of the supporting numbers.
        if (plan.latestStartMs != null) {
            Text(
                text = "Start charging by " +
                    Formatters.clockTimeWithDay(plan.latestStartMs, System.currentTimeMillis()),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (plan.requiredDurationMs != null) {
                Text(
                    text = "About ${Formatters.duration(plan.requiredDurationMs)} on the charger.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Text(
                text = when (plan.outcome) {
                    PlanOutcome.NO_CHARGING_NEEDED -> "You can leave it unplugged."
                    PlanOutcome.NOT_ACHIEVABLE -> "This target cannot be reached in time."
                    else -> "No charging time can be recommended yet."
                },
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
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
            value = if (plan.chargerBehaviourKnown) "Observed" else "Not yet observed",
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

/**
 * Human-readable charger names.
 *
 * `PlugType.name.lowercase().replaceFirstChar { it.uppercase() }` produced "Ac" and "Usb", which
 * are initialisms, not words. Kept in the UI layer so the telemetry model stays a pure record of
 * what Android reported.
 */
private val PlugType.label: String
    get() = when (this) {
        PlugType.NONE -> "Not plugged in"
        PlugType.AC -> "AC"
        PlugType.USB -> "USB"
        PlugType.WIRELESS -> "Wireless"
        PlugType.DOCK -> "Dock"
        PlugType.UNKNOWN -> "Unknown"
    }

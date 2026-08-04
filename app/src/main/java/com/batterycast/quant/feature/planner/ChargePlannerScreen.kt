package com.batterycast.quant.feature.planner

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.batterycast.quant.core.ui.components.CompactRow
import com.batterycast.quant.core.ui.components.HeroSurface
import com.batterycast.quant.core.ui.components.InfoCard
import com.batterycast.quant.core.ui.components.InlineStatus
import com.batterycast.quant.core.ui.components.MetricLabel
import com.batterycast.quant.core.ui.components.QuietBlock
import com.batterycast.quant.core.ui.components.SectionHeader
import com.batterycast.quant.core.ui.components.StatTile
import com.batterycast.quant.core.ui.format.Formatters
import com.batterycast.quant.core.ui.theme.BatteryCastTheme
import com.batterycast.quant.core.ui.theme.MetricStyle
import com.batterycast.quant.forecasting.planner.ChargePlan
import com.batterycast.quant.forecasting.planner.PlanOutcome
import com.batterycast.quant.feature.shared.ForecastUiState
import com.batterycast.quant.telemetry.model.PlugType
import kotlin.math.roundToInt

/**
 * Charge.
 *
 * v1 was a settings form followed by a row of em-dashes when the charger had not been observed.
 * v2 leads with the decision — when to plug in, for how long, and what you end up with — and puts
 * the controls underneath as adjustments to that decision rather than as a prerequisite for it.
 */
@Composable
fun ChargePlannerScreen(
    contentPadding: PaddingValues,
    viewModel: ChargePlannerViewModel = hiltViewModel(),
) {
    val state by viewModel.forecastState.collectAsStateWithLifecycle()
    val inputs by viewModel.inputs.collectAsStateWithLifecycle()
    val plan by viewModel.plan.collectAsStateWithLifecycle()
    val calendarTargets by viewModel.calendarTargets.collectAsStateWithLifecycle()
    val computing by viewModel.computing.collectAsStateWithLifecycle()
    val reminderSetFor by viewModel.reminderSetFor.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.load() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 20.dp,
            end = 20.dp,
            top = 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 28.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            Column(modifier = Modifier.statusBarsPadding()) {
                Text(
                    text = "Charge",
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = "The latest safe time to plug in.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (state !is ForecastUiState.Ready) {
            item {
                HeroSurface {
                    Text(
                        text = "Still learning your battery",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "Charge planning needs an observed discharge and one charging " +
                            "session before it can advise you.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            return@LazyColumn
        }

        val currentInputs = inputs ?: return@LazyColumn

        item {
            RecommendationHero(
                plan = plan,
                computing = computing,
                reminderSetFor = reminderSetFor,
                onSetReminder = { viewModel.setReminder() },
            )
        }

        item {
            InfoCard {
                SectionHeader(title = "Event")
                Text(
                    text = Formatters.clockTimeWithDay(currentInputs.eventMs, System.currentTimeMillis()),
                    style = MetricStyle,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = currentInputs.eventLabel,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (calendarTargets.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(calendarTargets, key = { it.id }) { event ->
                            ChoiceChip(
                                label = "${event.title} · ${Formatters.clockTime(event.startMs)}",
                                selected = currentInputs.eventMs == event.startMs,
                                onClick = { viewModel.selectEvent(event) },
                            )
                        }
                    }
                } else {
                    Text(
                        text = "Grant calendar access in Settings to plan around a real event.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            InfoCard {
                SectionHeader(title = "What you need")

                MetricLabel("Battery at the event")
                Text(
                    text = "${currentInputs.targetPercent.roundToInt()}%",
                    style = MetricStyle,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                PresetRow(
                    options = listOf(20, 40, 60, 80),
                    selected = currentInputs.targetPercent.roundToInt(),
                    format = { "$it%" },
                    onSelect = { value ->
                        viewModel.update { it.copy(targetPercent = value.toDouble()) }
                    },
                )

                Spacer(Modifier.height(8.dp))
                MetricLabel("How sure you want to be")
                Text(
                    text = "${(currentInputs.confidence * 100).roundToInt()}%",
                    style = MetricStyle,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                PresetRow(
                    options = listOf(80, 90, 95),
                    selected = (currentInputs.confidence * 100).roundToInt(),
                    format = { value ->
                        when (value) {
                            80 -> "Balanced · 80%"
                            90 -> "Safe · 90%"
                            else -> "Very safe · 95%"
                        }
                    },
                    onSelect = { value ->
                        viewModel.update { it.copy(confidence = value / 100.0) }
                    },
                )

                Spacer(Modifier.height(8.dp))
                MetricLabel("Charger")
                PresetRow(
                    options = listOf(PlugType.AC, PlugType.USB, PlugType.WIRELESS),
                    selected = currentInputs.plugType,
                    format = { it.displayName },
                    onSelect = { plug -> viewModel.update { it.copy(plugType = plug) } },
                )
            }
        }

        val currentPlan = plan
        if (currentPlan != null && currentPlan.outcome == PlanOutcome.PLAN_FOUND) {
            item { PlanDetailCard(currentPlan) }
        }
    }
}

/**
 * The decision, stated first.
 *
 * A timeline rather than a table: "now → plug in → event" is a shape people already understand,
 * and it makes the length of the charging window legible at a glance.
 */
@Composable
private fun RecommendationHero(
    plan: ChargePlan?,
    computing: Boolean,
    reminderSetFor: Long?,
    onSetReminder: () -> Unit,
) {
    val semantic = BatteryCastTheme.semanticColors
    val haptics = LocalHapticFeedback.current

    if (plan == null || computing) {
        HeroSurface {
            Text(
                text = "Working out your charge window…",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        return
    }

    val tone = when (plan.outcome) {
        PlanOutcome.NO_CHARGING_NEEDED -> semantic.healthy
        PlanOutcome.PLAN_FOUND -> semantic.healthy
        PlanOutcome.NOT_ACHIEVABLE -> semantic.risk
        PlanOutcome.CHARGER_UNKNOWN, PlanOutcome.OUT_OF_RANGE -> semantic.caution
    }

    HeroSurface(glow = tone) {
        when (plan.outcome) {
            PlanOutcome.NO_CHARGING_NEEDED -> {
                Text(
                    text = "No charging needed",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "You already reach your target with " +
                        "${Formatters.probability(plan.probabilityWithoutCharging)} confidence.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            PlanOutcome.PLAN_FOUND -> {
                MetricLabel("Start charging by")
                Text(
                    text = Formatters.clockTime(plan.latestStartMs),
                    style = MaterialTheme.typography.displaySmall,
                    color = tone,
                )
                Text(
                    text = "About ${Formatters.duration(plan.requiredDurationMs)} of charging.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(18.dp))
                ChargeTimeline(
                    nowMs = System.currentTimeMillis(),
                    startMs = plan.latestStartMs!!,
                    eventMs = plan.request.eventMs,
                )

                Spacer(Modifier.height(18.dp))
                if (reminderSetFor == plan.latestStartMs) {
                    InlineStatus(
                        text = "Reminder set for ${Formatters.clockTime(plan.latestStartMs)}",
                        color = semantic.healthy,
                    )
                } else {
                    Button(
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onSetReminder()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = tone,
                            contentColor = MaterialTheme.colorScheme.surface,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            "Remind me at ${Formatters.clockTime(plan.latestStartMs)}",
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }

            PlanOutcome.CHARGER_UNKNOWN -> {
                Text(
                    text = "Plug in once to learn your charger",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "BatteryCast has not seen this phone charging yet, so it cannot say how " +
                        "long a top-up would take. One charging session is enough.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                InlineStatus(
                    text = "Without charging: ${Formatters.probability(plan.probabilityWithoutCharging)} chance",
                    color = semantic.caution,
                )
            }

            PlanOutcome.NOT_ACHIEVABLE -> {
                Text(
                    text = "Not reachable in time",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                plan.notes.firstOrNull()?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            PlanOutcome.OUT_OF_RANGE -> {
                Text(
                    text = "Pick a time within the next day",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                plan.notes.firstOrNull()?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** now ──────── plug in ═══════ event, drawn to scale. */
@Composable
private fun ChargeTimeline(nowMs: Long, startMs: Long, eventMs: Long) {
    val semantic = BatteryCastTheme.semanticColors
    val outline = MaterialTheme.colorScheme.outlineVariant
    val density = LocalDensity.current
    val total = (eventMs - nowMs).coerceAtLeast(1L)
    val startFraction = ((startMs - nowMs).toFloat() / total).coerceIn(0f, 1f)

    Column(modifier = Modifier.fillMaxWidth()) {
        Canvas(modifier = Modifier.fillMaxWidth().height(28.dp)) {
            val y = size.height / 2f
            val stroke = with(density) { 5.dp.toPx() }
            val splitX = size.width * startFraction

            drawLine(
                color = outline,
                start = Offset(0f, y),
                end = Offset(splitX, y),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = semantic.healthy,
                start = Offset(splitX, y),
                end = Offset(size.width, y),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
            listOf(0f to outline, startFraction to semantic.healthy, 1f to semantic.healthy)
                .forEach { (fraction, color) ->
                    drawCircle(
                        color = color,
                        radius = with(density) { 5.dp.toPx() },
                        center = Offset(size.width * fraction, y),
                    )
                }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TimelineLabel("Now", Formatters.clockTime(nowMs), Alignment.Start)
            TimelineLabel("Plug in", Formatters.clockTime(startMs), Alignment.CenterHorizontally)
            TimelineLabel("Event", Formatters.clockTime(eventMs), Alignment.End)
        }
    }
}

@Composable
private fun TimelineLabel(label: String, value: String, alignment: Alignment.Horizontal) {
    Column(horizontalAlignment = alignment) {
        MetricLabel(label)
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PlanDetailCard(plan: ChargePlan) {
    val semantic = BatteryCastTheme.semanticColors
    InfoCard {
        SectionHeader(title = "If you follow this plan")
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            StatTile(
                label = "Expected",
                value = Formatters.percent(plan.expectedPercentAtEvent),
                valueColor = semantic.healthy,
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "Plan around",
                value = Formatters.percent(plan.conservativePercentAtEvent),
                modifier = Modifier.weight(1f),
            )
        }
        CompactRow(
            label = "Achieved confidence",
            value = Formatters.probability(plan.achievedProbability),
        )
        CompactRow(
            label = "Without charging",
            value = Formatters.probability(plan.probabilityWithoutCharging),
        )
        plan.notes.forEach {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun <T> PresetRow(
    options: List<T>,
    selected: T,
    format: (T) -> String,
    onSelect: (T) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(options) { option ->
            ChoiceChip(
                label = format(option),
                selected = option == selected,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onSelect(option)
                },
            )
        }
    }
}

@Composable
private fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelLarge,
            )
        },
        shape = MaterialTheme.shapes.small,
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
            selectedLabelColor = MaterialTheme.colorScheme.primary,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        border = null,
    )
}

package com.batterycast.quant.feature.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.batterycast.quant.core.ui.chart.BatteryHistoryChart
import com.batterycast.quant.core.ui.components.DetailScaffold
import com.batterycast.quant.core.ui.components.InfoCard
import com.batterycast.quant.core.ui.components.SectionHeader
import com.batterycast.quant.core.ui.components.CompactRow
import com.batterycast.quant.core.ui.format.Formatters
import com.batterycast.quant.core.ui.theme.BatteryCastTheme
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun HistoryScreen(
    onBack: () -> Unit,
    viewModel: HistoryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.load() }

    DetailScaffold(
        title = "Battery history",
        subtitle = "Only what was measured. Gaps stay gaps.",
        onBack = onBack,
    ) {
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(listOf(6, 24, 72, 168)) { hours ->
                    FilterChip(
                        selected = state.windowHours == hours,
                        onClick = { viewModel.load(hours) },
                        label = {
                            Text(if (hours >= 24) "${hours / 24}d" else "${hours}h")
                        },
                    )
                }
            }
        }

        if (state.observations.size < 2) {
            item {
                InfoCard {
                    Text(
                        text = "Not enough history in this window",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "A reading is taken roughly every 15 minutes, plus one whenever the " +
                            "battery or power state changes. Try a longer window.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            return@DetailScaffold
        }

        item {
            InfoCard {
                SectionHeader(
                    title = "Battery level",
                    subtitle = "Shaded areas are charging; the strip underneath marks screen-on periods.",
                )
                BatteryHistoryChart(observations = state.observations)
                Text(
                    text = "${state.observations.size} readings between " +
                        "${Formatters.dateAndTime(state.observations.first().timestampMs)} and " +
                        Formatters.dateAndTime(state.observations.last().timestampMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            InfoCard {
                SectionHeader(title = "Charging sessions")
                if (state.chargingSessions.isEmpty()) {
                    Text(
                        "No charging observed in this window.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                state.chargingSessions.forEach { session ->
                    CompactRow(
                        label = "${Formatters.clockTime(session.startMs)} – " +
                            Formatters.clockTime(session.endMs),
                        supporting = "${session.plugType} · " +
                            "${session.startPercent.roundToInt()}% → ${session.endPercent.roundToInt()}%",
                        value = Formatters.ratePerHour(session.ratePercentPerHour),
                    )
                }
            }
        }

        item {
            InfoCard {
                SectionHeader(
                    title = "Unusual drain",
                    subtitle = "Periods well above this phone's own normal discharge range.",
                )
                if (state.unusualPeriods.isEmpty()) {
                    Text(
                        "Nothing unusual in this window.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                state.unusualPeriods.forEach { period ->
                    CompactRow(
                        label = "${Formatters.clockTime(period.startMs)} – " +
                            Formatters.clockTime(period.endMs),
                        supporting = "screen on ${(period.screenOnFraction * 100).roundToInt()}% of " +
                            "the time · typical ${Formatters.ratePerHour(period.typicalPercentPerHour)}",
                        value = Formatters.ratePerHour(period.ratePercentPerHour),
                        valueColor = BatteryCastTheme.semanticColors.risk,
                    )
                }
            }
        }

        item {
            InfoCard {
                SectionHeader(
                    title = "Forecasts versus outcomes",
                    subtitle = "Predictions this app made, scored against what actually happened.",
                )
                if (state.scoredForecasts.isEmpty()) {
                    Text(
                        "No forecasts have been scored yet. Each one is scored once its target " +
                            "time passes and a real reading covers it.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                state.scoredForecasts.take(10).forEach { record ->
                    val actual = record.actualPercent ?: return@forEach
                    val error = actual - record.predictedMedian
                    CompactRow(
                        label = "For ${Formatters.dateAndTime(record.targetMs)}",
                        supporting = "predicted ${record.predictedMedian.roundToInt()}%, " +
                            "actual ${actual.roundToInt()}%",
                        value = Formatters.signedPercentPoints(error),
                        valueColor = if (abs(error) <= 5) {
                            BatteryCastTheme.semanticColors.healthy
                        } else {
                            BatteryCastTheme.semanticColors.caution
                        },
                    )
                }
            }
        }
    }
}

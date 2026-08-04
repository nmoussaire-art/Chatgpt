package com.batterycast.quant.feature.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
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
import com.batterycast.quant.core.ui.components.BatteryCastCard
import com.batterycast.quant.core.ui.components.CollectingDataCard
import com.batterycast.quant.core.ui.components.SectionHeader
import com.batterycast.quant.core.ui.components.StatRow
import com.batterycast.quant.core.ui.format.Formatters
import com.batterycast.quant.core.ui.theme.BatteryCastTheme
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun HistoryScreen(viewModel: HistoryViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.load() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            SectionHeader(
                title = "Battery history",
                subtitle = "Only what was measured on this device. Gaps are shown as gaps.",
            )
        }

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
                CollectingDataCard(
                    headline = "Not enough history in this window",
                    detail = "BatteryCast records a reading roughly every 15 minutes, plus one " +
                        "whenever the battery or power state changes. Try a longer window, or " +
                        "come back after a while.",
                )
            }
            return@LazyColumn
        }

        item {
            BatteryCastCard {
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
            BatteryCastCard {
                SectionHeader(title = "Charging sessions")
                if (state.chargingSessions.isEmpty()) {
                    Text(
                        "No charging observed in this window.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                state.chargingSessions.forEach { session ->
                    StatRow(
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
            BatteryCastCard {
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
                    StatRow(
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
            BatteryCastCard {
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
                    StatRow(
                        label = "For ${Formatters.dateAndTime(record.targetMs)}",
                        supporting = "predicted ${record.predictedMedian.roundToInt()}%, " +
                            "actual ${actual.roundToInt()}%",
                        value = Formatters.signedPercentPoints(error),
                        valueColor = if (abs(error) <= 5) {
                            BatteryCastTheme.semanticColors.positive
                        } else {
                            BatteryCastTheme.semanticColors.caution
                        },
                    )
                }
            }
        }
    }
}

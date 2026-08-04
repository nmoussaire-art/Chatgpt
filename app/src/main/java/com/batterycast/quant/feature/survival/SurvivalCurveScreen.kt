package com.batterycast.quant.feature.survival

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.batterycast.quant.core.ui.chart.ForecastFanChart
import com.batterycast.quant.core.ui.components.BatteryCastCard
import com.batterycast.quant.core.ui.components.CollectingDataCard
import com.batterycast.quant.core.ui.components.CompactChip
import com.batterycast.quant.core.ui.components.RangeBar
import com.batterycast.quant.core.ui.components.SectionHeader
import com.batterycast.quant.core.ui.components.StatRow
import com.batterycast.quant.core.ui.format.Formatters
import com.batterycast.quant.core.ui.theme.BatteryCastTheme
import com.batterycast.quant.feature.dashboard.DashboardViewModel
import com.batterycast.quant.feature.shared.ForecastUiState
import com.batterycast.quant.forecasting.model.BatteryForecast
import com.batterycast.quant.forecasting.sim.ForecastPoint

/**
 * The full survival curve.
 *
 * Scrubbing the chart reads out the exact percentile fan at any moment, which is the honest way to
 * answer "what will I have at 7pm" — a median with the interval it sits inside, never a bare
 * number. The 80 % band is on by default because it carries the message on its own; the other two
 * are chips, because three nested translucent fills on a dark surface merge into one indistinct
 * triangle no matter how carefully the alphas are chosen.
 */
@Composable
fun SurvivalCurveScreen(viewModel: DashboardViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var scrubbed by remember { mutableStateOf<ForecastPoint?>(null) }
    var showFifty by remember { mutableStateOf(false) }
    var showNinety by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            SectionHeader(
                title = "Battery survival curve",
                subtitle = "Drag across the chart to read the forecast at any time.",
            )
        }

        when (val current = state) {
            is ForecastUiState.Ready -> {
                item {
                    BatteryCastCard {
                        ForecastFanChart(
                            points = current.forecast.curve,
                            targetMs = current.forecast.target?.targetMs,
                            height = 280.dp,
                            showFiftyBand = showFifty,
                            showNinetyBand = showNinety,
                            onScrub = { scrubbed = it },
                        )
                        BandControls(
                            showFifty = showFifty,
                            showNinety = showNinety,
                            onToggleFifty = { showFifty = !showFifty },
                            onToggleNinety = { showNinety = !showNinety },
                        )
                    }
                }

                item {
                    val point = scrubbed ?: current.forecast.curve.lastOrNull()
                    if (point != null) {
                        BatteryCastCard {
                            SectionHeader(
                                title = if (scrubbed != null) {
                                    "At ${Formatters.clockTimeWithDay(point.atMs, current.forecast.generatedAtMs)}"
                                } else {
                                    "End of forecast window"
                                },
                            )
                            StatRow("Median", Formatters.percent(point.median))
                            StatRow("50% interval", Formatters.percentRange(point.p25, point.p75))
                            StatRow("80% interval", Formatters.percentRange(point.p10, point.p90))
                            StatRow("90% interval", Formatters.percentRange(point.p05, point.p95))
                            StatRow("Mean", Formatters.percent(point.mean))
                        }
                    }
                }

                item { ThresholdCrossingsCard(current.forecast) }
            }

            is ForecastUiState.Collecting -> item {
                CollectingDataCard(
                    headline = current.headline,
                    detail = current.detail,
                    progress = current.progress,
                )
            }

            ForecastUiState.Loading -> item {
                CollectingDataCard(headline = "Reading the battery…", detail = "Taking a live measurement.")
            }
        }
    }
}

/**
 * Threshold crossings as bars on a shared timeline rather than a column of timestamps.
 *
 * Each bar spans the whole forecast window, the tick is the median crossing time, and the wash
 * around it is the 10th-to-90th-percentile interval. Stacked, they make the one thing a table
 * cannot show obvious at a glance: each successive level is predicted less precisely than the last.
 */
@Composable
private fun ThresholdCrossingsCard(forecast: BatteryForecast) {
    val semantic = BatteryCastTheme.semanticColors
    val horizonMs = forecast.horizonMs.coerceAtLeast(1L).toFloat()
    val reportable = forecast.thresholds.filter { it.medianMs != null }

    BatteryCastCard {
        SectionHeader(
            title = "Threshold crossings",
            subtitle = "When each level is reached, across the next " +
                Formatters.duration(forecast.horizonMs) + ".",
        )

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
            RangeBar(
                fraction = threshold.medianMs!! / horizonMs,
                lowFraction = threshold.p10Ms?.let { it / horizonMs },
                highFraction = threshold.p90Ms?.let { it / horizonMs },
                color = semantic.forProbability(threshold.probabilityOfReaching),
                label = "Reaches ${threshold.thresholdPercent.toInt()}%",
                value = Formatters.clockTimeWithDay(
                    forecast.generatedAtMs + threshold.medianMs,
                    forecast.generatedAtMs,
                ),
            )
        }

        Text(
            text = "The tick is the median crossing time; the band around it is where 80% of " +
                "simulated paths crossed.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BandControls(
    showFifty: Boolean,
    showNinety: Boolean,
    onToggleFifty: () -> Unit,
    onToggleNinety: () -> Unit,
) {
    val semantic = BatteryCastTheme.semanticColors
    // Scrollable rather than wrapping: at a large display size the legend plus two chips is wider
    // than the card, and a legend that reflows onto two lines looks like a layout fault.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LegendSwatch(semantic.medianLine, "Median")
        LegendSwatch(semantic.band80, "80%")
        CompactChip(text = "50%", selected = showFifty, onClick = onToggleFifty)
        CompactChip(text = "90%", selected = showNinety, onClick = onToggleNinety)
    }
}

@Composable
private fun LegendSwatch(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(color),
        )
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

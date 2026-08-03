package com.batterycast.quant.feature.survival

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.batterycast.quant.core.ui.components.SectionHeader
import com.batterycast.quant.core.ui.components.StatRow
import com.batterycast.quant.core.ui.format.Formatters
import com.batterycast.quant.core.ui.theme.BatteryCastTheme
import com.batterycast.quant.feature.dashboard.DashboardViewModel
import com.batterycast.quant.feature.shared.ForecastUiState
import com.batterycast.quant.forecasting.sim.ForecastPoint

/**
 * The full survival curve.
 *
 * Scrubbing the chart reads out the exact percentile fan at any moment, which is the honest way
 * to answer "what will I have at 7pm" — a median with the interval it sits inside, never a bare
 * number.
 */
@Composable
fun SurvivalCurveScreen(viewModel: DashboardViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var scrubbed by remember { mutableStateOf<ForecastPoint?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 20.dp),
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
                            nowMs = current.forecast.generatedAtMs,
                            targetMs = current.forecast.target?.targetMs,
                            height = 280.dp,
                            onScrub = { scrubbed = it },
                        )
                        ChartLegend()
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

                item {
                    BatteryCastCard {
                        SectionHeader(title = "Threshold crossings")
                        current.forecast.thresholds.forEach { threshold ->
                            StatRow(
                                label = "Reaches ${threshold.thresholdPercent.toInt()}%",
                                supporting = "probability within the window: " +
                                    Formatters.probability(threshold.probabilityOfReaching),
                                value = threshold.medianMs?.let {
                                    Formatters.clockTimeWithDay(
                                        current.forecast.generatedAtMs + it,
                                        current.forecast.generatedAtMs,
                                    )
                                } ?: "not reached",
                            )
                        }
                    }
                }
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

@Composable
private fun ChartLegend() {
    val semantic = BatteryCastTheme.semanticColors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        LegendSwatch(semantic.medianLine, "Median")
        LegendSwatch(semantic.band50, "50%")
        LegendSwatch(semantic.band80, "80%")
        LegendSwatch(semantic.band90, "90%")
    }
}

@Composable
private fun LegendSwatch(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(color),
        )
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

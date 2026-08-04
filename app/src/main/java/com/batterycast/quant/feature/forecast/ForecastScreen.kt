package com.batterycast.quant.feature.forecast

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.batterycast.quant.core.ui.chart.BandVisibility
import com.batterycast.quant.core.ui.chart.ForecastFanChart
import com.batterycast.quant.core.ui.components.CompactRow
import com.batterycast.quant.core.ui.components.HeroSurface
import com.batterycast.quant.core.ui.components.InfoCard
import com.batterycast.quant.core.ui.components.MetricLabel
import com.batterycast.quant.core.ui.components.QuietBlock
import com.batterycast.quant.core.ui.components.SectionHeader
import com.batterycast.quant.core.ui.components.StatTile
import com.batterycast.quant.core.ui.format.Formatters
import com.batterycast.quant.core.ui.theme.BatteryCastTheme
import com.batterycast.quant.core.ui.theme.MetricStyle
import com.batterycast.quant.feature.home.HomeViewModel
import com.batterycast.quant.feature.shared.ForecastUiState
import com.batterycast.quant.forecasting.model.BatteryForecast
import com.batterycast.quant.forecasting.model.Recommendations
import com.batterycast.quant.forecasting.sim.ForecastPoint
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Forecast.
 *
 * The consumer layer sits at the top — one curve, one plain sentence about where you land — and
 * every percentile, interval and path count lives below a disclosure. The quantitative depth is
 * fully preserved; it simply no longer greets the user before they have asked for it.
 */
@Composable
fun ForecastScreen(
    contentPadding: PaddingValues,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var scrubbed by remember { mutableStateOf<ForecastPoint?>(null) }
    var bands by remember { mutableStateOf(BandVisibility()) }
    var showDetails by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.refresh() }

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
                    text = "Forecast",
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = "Drag the chart to read any moment.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        when (val current = state) {
            is ForecastUiState.Ready -> {
                val forecast = current.forecast
                val reserve = current.settings.reservePercent

                item {
                    ChartCard(
                        forecast = forecast,
                        reserve = reserve,
                        bands = bands,
                        scrubbed = scrubbed,
                        onScrub = { scrubbed = it },
                        onBandsChange = { bands = it },
                    )
                }

                item { AtTargetCard(forecast) }

                item { ThresholdTimeline(forecast) }

                item { ConfidenceCard(forecast) }

                item {
                    TextButton(onClick = { showDetails = !showDetails }) {
                        Text(
                            text = if (showDetails) "Hide model details" else "View model details",
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }

                item {
                    AnimatedVisibility(visible = showDetails) {
                        ModelDetails(forecast, scrubbed)
                    }
                }
            }

            is ForecastUiState.Collecting -> item {
                HeroSurface {
                    Text(
                        text = current.headline,
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "The curve appears once real discharge has been observed.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            ForecastUiState.Loading -> item {
                HeroSurface {
                    Text(
                        text = "Reading your battery…",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

@Composable
private fun ChartCard(
    forecast: BatteryForecast,
    reserve: Double,
    bands: BandVisibility,
    scrubbed: ForecastPoint?,
    onScrub: (ForecastPoint?) -> Unit,
    onBandsChange: (BandVisibility) -> Unit,
) {
    val selectedIndex = scrubbed?.let { point -> forecast.curve.indexOfFirst { it.atMs == point.atMs } }
        ?.takeIf { it >= 0 }

    InfoCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp)) {
        // The read-out sits above the chart so the value never hides under the finger.
        ScrubReadout(point = scrubbed, forecast = forecast, reserve = reserve)

        ForecastFanChart(
            points = forecast.curve,
            nowMs = forecast.generatedAtMs,
            targetMs = forecast.target?.targetMs,
            reservePercent = reserve,
            bands = bands,
            height = 240.dp,
            selectedIndex = selectedIndex,
            onScrub = onScrub,
        )

        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BandChip("50%", bands.fifty) { onBandsChange(bands.copy(fifty = !bands.fifty)) }
            BandChip("80%", bands.eighty) { onBandsChange(bands.copy(eighty = !bands.eighty)) }
            BandChip("90%", bands.ninety) { onBandsChange(bands.copy(ninety = !bands.ninety)) }
        }
        Text(
            text = "Shaded bands are the range the simulated paths fell within.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ScrubReadout(point: ForecastPoint?, forecast: BatteryForecast, reserve: Double) {
    val semantic = BatteryCastTheme.semanticColors
    val shown = point ?: forecast.curve.minByOrNull {
        abs(it.atMs - (forecast.target?.targetMs ?: forecast.curve.last().atMs))
    }
    if (shown == null) return

    val aboveReserve = probabilityAbove(shown, reserve)

    Column(modifier = Modifier.animateContentSize()) {
        MetricLabel(
            text = if (point != null) {
                Formatters.clockTime(shown.atMs)
            } else {
                "At ${Formatters.clockTime(shown.atMs)}"
            },
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = "${shown.median.roundToInt()}%",
                style = MetricStyle,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = "${(aboveReserve * 100).roundToInt()}% above ${reserve.roundToInt()}%",
                style = MaterialTheme.typography.bodyMedium,
                color = semantic.forProbability(aboveReserve),
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        Text(
            text = "Range ${shown.p10.roundToInt()}–${shown.p90.roundToInt()}%",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BandChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
        shape = MaterialTheme.shapes.extraSmall,
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.18f),
            selectedLabelColor = MaterialTheme.colorScheme.secondary,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        border = null,
    )
}

@Composable
private fun AtTargetCard(forecast: BatteryForecast) {
    val outcome = forecast.target ?: return
    val semantic = BatteryCastTheme.semanticColors

    InfoCard {
        MetricLabel("At ${Formatters.clockTime(outcome.targetMs)}")
        Text(
            text = "${outcome.median.roundToInt()}%",
            style = MetricStyle,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Recommendations.expectedRange(outcome)?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            StatTile(
                label = "Chance above ${outcome.reservePercent.roundToInt()}%",
                value = Formatters.probability(outcome.survivalProbability),
                valueColor = semantic.forProbability(outcome.survivalProbability),
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "Plan around",
                value = if (outcome.conservative < Recommendations.EMPTY_THRESHOLD) {
                    "Empty"
                } else {
                    "${outcome.conservative.roundToInt()}%"
                },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ThresholdTimeline(forecast: BatteryForecast) {
    val reachable = forecast.thresholds.filter { it.thresholdPercent >= 5.0 }
    if (reachable.isEmpty()) return

    InfoCard {
        SectionHeader(title = "When you reach")
        reachable.forEach { threshold ->
            val time = threshold.medianMs?.let {
                Formatters.clockTimeWithDay(forecast.generatedAtMs + it, forecast.generatedAtMs)
            }
            CompactRow(
                label = "${threshold.thresholdPercent.roundToInt()}%",
                supporting = when {
                    time == null && threshold.probabilityOfReaching < 0.5 ->
                        "${(threshold.probabilityOfReaching * 100).roundToInt()}% chance within this window"
                    threshold.p10Ms != null && threshold.p90Ms != null ->
                        "between ${Formatters.clockTime(forecast.generatedAtMs + threshold.p10Ms)} " +
                            "and ${Formatters.clockTime(forecast.generatedAtMs + threshold.p90Ms)}"
                    else -> null
                },
                value = time ?: "Unlikely",
                valueColor = if (time == null) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        }
    }
}

@Composable
private fun ConfidenceCard(forecast: BatteryForecast) {
    val quality = forecast.dataQuality
    val semantic = BatteryCastTheme.semanticColors

    InfoCard {
        SectionHeader(title = "How sure is this?")
        Text(
            text = when {
                quality.observedSpanHours < 2 ->
                    "Based on well under a day of observation, so treat the range as wide."
                quality.unsupportedFields.isNotEmpty() ->
                    "This phone reports battery percentage but not " +
                        "${quality.unsupportedFields.joinToString()}, so drain is estimated from " +
                        "percentage changes. That is coarser, and the range reflects it."
                else ->
                    "Based on ${quality.usableObservationCount} readings over " +
                        "${quality.observedSpanHours.roundToInt()} hours of observation."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (quality.isStale) {
            Text(
                text = "The newest reading is " +
                    "${Formatters.relativeAge(quality.newestObservationAgeMs)} — Android may have " +
                    "deferred background work.",
                style = MaterialTheme.typography.bodySmall,
                color = semantic.caution,
            )
        }
    }
}

/** The advanced layer. Everything v1 showed by default now lives here. */
@Composable
private fun ModelDetails(forecast: BatteryForecast, scrubbed: ForecastPoint?) {
    val point = scrubbed ?: forecast.curve.minByOrNull {
        abs(it.atMs - (forecast.target?.targetMs ?: forecast.curve.last().atMs))
    } ?: return
    val drain = forecast.drain

    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        InfoCard {
            SectionHeader(
                title = "Distribution at ${Formatters.clockTime(point.atMs)}",
                subtitle = "From 2,400 simulated battery paths.",
            )
            CompactRow("Median", "${point.median.roundToInt()}%")
            CompactRow("Mean", "${point.mean.roundToInt()}%")
            CompactRow("50% interval", Formatters.percentRange(point.p25, point.p75))
            CompactRow("80% interval", Formatters.percentRange(point.p10, point.p90))
            CompactRow("90% interval", Formatters.percentRange(point.p05, point.p95))
        }

        InfoCard {
            SectionHeader(title = "Drain estimate")
            CompactRow(
                label = "Fitted rate",
                supporting = drain.method?.let { "from $it" },
                value = Formatters.ratePerHour(drain.currentPercentPerHour),
            )
            drain.baselinePercentPerHour?.let {
                CompactRow(
                    label = "Learned baseline",
                    supporting = forecast.currentRegime.displayName,
                    value = Formatters.ratePerHour(it),
                )
            }
            drain.byHorizon.forEach { (horizon, rate) ->
                CompactRow(horizon.replaceFirstChar { it.uppercase() }, Formatters.ratePerHour(rate))
            }
            drain.milliAmpsNow?.let { CompactRow("Live current", Formatters.milliAmps(it)) }
            drain.wattsNow?.let { CompactRow("Live power", Formatters.watts(it)) }
        }

        QuietBlock {
            Text(
                text = "Measurement quality",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "${forecast.dataQuality.usableObservationCount} usable readings · " +
                    "${forecast.dataQuality.rejectedCount} discarded as inconsistent · " +
                    "${forecast.dataQuality.totalPercentObserved.roundToInt()} percentage points of " +
                    "discharge observed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                overflow = TextOverflow.Visible,
            )
        }
    }
}

/**
 * Share of simulated paths above [level], interpolated from the stored percentile fan.
 *
 * The fan carries seven quantiles; a level between two of them is interpolated linearly, which is
 * accurate to well within the whole percentage point the app displays.
 */
internal fun probabilityAbove(point: ForecastPoint, level: Double): Double {
    val quantiles = listOf(
        0.05 to point.p05,
        0.10 to point.p10,
        0.25 to point.p25,
        0.50 to point.median,
        0.75 to point.p75,
        0.90 to point.p90,
        0.95 to point.p95,
    )
    if (level <= quantiles.first().second) return 1.0
    if (level >= quantiles.last().second) return 0.0

    for (index in 0 until quantiles.size - 1) {
        val (pLow, vLow) = quantiles[index]
        val (pHigh, vHigh) = quantiles[index + 1]
        if (level in vLow..vHigh) {
            val span = vHigh - vLow
            val fraction = if (span <= 0.0) 0.0 else (level - vLow) / span
            return 1.0 - (pLow + fraction * (pHigh - pLow))
        }
    }
    return 0.0
}

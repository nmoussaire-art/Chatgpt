package com.batterycast.quant.feature.probability

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
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
import com.batterycast.quant.core.ui.components.ProbabilityBar
import com.batterycast.quant.core.ui.components.SectionHeader
import com.batterycast.quant.core.ui.format.Formatters
import com.batterycast.quant.feature.scenarios.ScenarioViewModel
import com.batterycast.quant.feature.scenarios.icon
import com.batterycast.quant.feature.shared.ForecastUiState
import com.batterycast.quant.forecasting.scenario.ScenarioConfidence
import com.batterycast.quant.forecasting.sim.ForecastPoint
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Insights: how likely the battery is to hold up, and what changes it.
 *
 * Every bar is a count over simulated paths, not a curve fitted to a point estimate — so a bar at
 * 84 % means 84 % of the simulated futures were still above the reserve at that time. Bars rather
 * than a printed list, because the comparison between rows is the entire point of the screen and a
 * column of percentages makes the reader do that comparison in their head.
 */
@Composable
fun ProbabilityScreen(viewModel: ScenarioViewModel = hiltViewModel()) {
    val state by viewModel.forecastState.collectAsStateWithLifecycle()
    val scenarios by viewModel.scenarios.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.load() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            SectionHeader(
                title = "Insights",
                subtitle = "How likely your battery is to hold up, and what changes it.",
            )
        }

        when (val current = state) {
            is ForecastUiState.Ready -> {
                val reserve = current.settings.reservePercent

                item {
                    BatteryCastCard {
                        SectionHeader(
                            title = "Staying above ${Formatters.percent(reserve)}",
                            subtitle = "Probability at each hour from now.",
                        )
                        if (current.forecast.hourlySurvival.isEmpty()) {
                            Text(
                                "The forecast window is shorter than an hour.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        current.forecast.hourlySurvival.forEach { hourly ->
                            ProbabilityBar(
                                probability = hourly.probabilityAboveReserve,
                                label = Formatters.clockTimeWithDay(
                                    hourly.atMs,
                                    current.forecast.generatedAtMs,
                                ),
                                trailing = Formatters.probability(hourly.probabilityAboveReserve),
                            )
                        }
                    }
                }

                item {
                    BatteryCastCard {
                        SectionHeader(
                            title = "Reserve levels",
                            subtitle = "Chance of still being above each level at " +
                                Formatters.clockTime(current.target.atMs) + ".",
                        )
                        val point = current.forecast.curve.minByOrNull {
                            abs(it.atMs - current.target.atMs)
                        }
                        if (point == null) {
                            Text(
                                "Target is outside the forecast window.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            // Read straight off the percentile fan: the share of paths above a
                            // level is exactly one minus the quantile position of that level.
                            listOf(0.0, 5.0, 10.0, 20.0).forEach { reserveLevel ->
                                val probability = probabilityAbove(point, reserveLevel)
                                ProbabilityBar(
                                    probability = probability,
                                    label = "Above ${reserveLevel.roundToInt()}%",
                                    trailing = Formatters.probability(probability),
                                )
                            }
                        }
                    }
                }

                item {
                    BatteryCastCard {
                        SectionHeader(
                            title = "How risk changes",
                            subtitle = "Same target, different usage.",
                        )
                        if (scenarios.isEmpty()) {
                            Text(
                                "Working out scenarios…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        scenarios.forEach { outcome ->
                            val probability = outcome.survivalProbability
                            if (probability == null) {
                                Text(
                                    text = "${outcome.scenario.icon}  ${outcome.scenario.displayName}: " +
                                        outcome.confidence.label.lowercase(),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                ProbabilityBar(
                                    probability = probability,
                                    leadingIcon = outcome.scenario.icon,
                                    label = outcome.scenario.displayName +
                                        if (outcome.confidence == ScenarioConfidence.SUBSTITUTED) " *" else "",
                                    trailing = Formatters.probability(probability),
                                )
                            }
                        }
                        if (scenarios.any { it.confidence == ScenarioConfidence.SUBSTITUTED }) {
                            Text(
                                text = "* estimated from the closest behaviour observed on this phone, " +
                                    "with a wider interval.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
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

/**
 * Interpolates the share of simulated paths above [level] from the stored percentile fan.
 *
 * The fan carries seven quantiles; a level between two of them is interpolated linearly, which is
 * accurate to well within the percentage point the app displays.
 */
private fun probabilityAbove(point: ForecastPoint, level: Double): Double {
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

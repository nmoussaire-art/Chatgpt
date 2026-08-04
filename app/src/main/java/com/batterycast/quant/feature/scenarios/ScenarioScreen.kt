package com.batterycast.quant.feature.scenarios

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.batterycast.quant.core.ui.components.BatteryCastCard
import com.batterycast.quant.core.ui.components.CollectingDataCard
import com.batterycast.quant.core.ui.components.MiniRing
import com.batterycast.quant.core.ui.components.ProbabilityBar
import com.batterycast.quant.core.ui.components.SectionHeader
import com.batterycast.quant.core.ui.components.StatRow
import com.batterycast.quant.core.ui.components.StatusChip
import com.batterycast.quant.core.ui.format.Formatters
import com.batterycast.quant.core.ui.theme.BatteryCastTheme
import com.batterycast.quant.feature.shared.ForecastUiState
import com.batterycast.quant.forecasting.scenario.Scenario
import com.batterycast.quant.forecasting.scenario.ScenarioConfidence
import com.batterycast.quant.forecasting.scenario.ScenarioOutcome

/**
 * The scenario laboratory.
 *
 * Each card is a full re-simulation under a different assumed usage pattern, compared against the
 * baseline. Cards whose behaviour has never been observed on this phone say so on their face rather
 * than quietly substituting a plausible-looking number.
 */
@Composable
fun ScenarioScreen(viewModel: ScenarioViewModel = hiltViewModel()) {
    val state by viewModel.forecastState.collectAsStateWithLifecycle()
    val scenarios by viewModel.scenarios.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.load() }

    val baseline = scenarios.firstOrNull { it.scenario == Scenario.NORMAL }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            SectionHeader(
                title = "Scenario laboratory",
                subtitle = "What each kind of usage would do to your forecast.",
            )
        }

        when (val current = state) {
            is ForecastUiState.Ready -> {
                item {
                    BatteryCastCard {
                        SectionHeader(
                            title = "Baseline",
                            subtitle = "Target ${Formatters.clockTimeWithDay(
                                current.target.atMs,
                                current.forecast.generatedAtMs,
                            )}, reserve ${Formatters.percent(current.settings.reservePercent)}.",
                        )
                        StatRow(
                            label = "Chance of lasting",
                            value = Formatters.probability(current.forecast.target?.survivalProbability),
                        )
                        StatRow(
                            label = "Median at target",
                            value = Formatters.percent(current.forecast.target?.median),
                        )
                    }
                }

                if (loading) {
                    item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
                }

                items(scenarios.filter { it.scenario != Scenario.NORMAL }, key = { it.scenario.name }) { outcome ->
                    ScenarioCard(outcome, baseline)
                }
            }

            is ForecastUiState.Collecting -> item {
                CollectingDataCard(
                    headline = current.headline,
                    detail = current.detail + " Scenario comparisons unlock once several kinds of " +
                        "usage have actually been observed.",
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
private fun ScenarioCard(outcome: ScenarioOutcome, baseline: ScenarioOutcome?) {
    val semantic = BatteryCastTheme.semanticColors
    val probability = outcome.survivalProbability

    BatteryCastCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            MiniRing(
                fraction = (probability ?: 0.0).toFloat(),
                color = probability?.let { semantic.forProbability(it) } ?: semantic.trackInactive,
            )
            androidx.compose.foundation.layout.Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${outcome.scenario.icon}  ${outcome.scenario.displayName}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = outcome.scenario.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        StatusChip(
            text = outcome.confidence.label,
            color = when (outcome.confidence) {
                ScenarioConfidence.OBSERVED -> semantic.positive
                ScenarioConfidence.LIMITED -> semantic.caution
                ScenarioConfidence.SUBSTITUTED -> semantic.caution
                ScenarioConfidence.UNAVAILABLE -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )

        if (probability == null) {
            Text(
                text = outcome.note ?: "Not enough observed behaviour to model this yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@BatteryCastCard
        }

        ProbabilityBar(
            probability = probability,
            label = "Chance of lasting to your target",
            trailing = Formatters.probability(probability),
        )

        StatRow(label = "Median at target", value = Formatters.percent(outcome.medianPercentAtTarget))
        StatRow(
            label = "Conservative",
            supporting = "10th percentile",
            value = Formatters.percent(outcome.conservativePercentAtTarget),
        )

        val baselineProbability = baseline?.survivalProbability
        if (baselineProbability != null) {
            val delta = ((probability - baselineProbability) * 100).toInt()
            StatRow(
                label = "Versus normal use",
                value = when {
                    delta > 0 -> "+$delta points"
                    delta < 0 -> "$delta points"
                    else -> "no change"
                },
                valueColor = when {
                    delta > 0 -> semantic.positive
                    delta < 0 -> semantic.risk
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
        }

        if (outcome.note != null) {
            Text(
                text = outcome.note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A glyph per scenario.
 *
 * Emoji rather than vector icons: the set needed here — gaming, navigation, tethering, flight mode —
 * is exactly the set the system font already draws well, and shipping five more vector assets to
 * say the same thing would be weight for nothing.
 */
val Scenario.icon: String
    get() = when (this) {
        Scenario.NORMAL -> "📱"
        Scenario.VIDEO_ONE_HOUR -> "🎬"
        Scenario.GAMING_THIRTY_MINUTES -> "🎮"
        Scenario.NAVIGATION_FORTY_FIVE -> "🧭"
        Scenario.HOTSPOT_ONE_HOUR -> "📶"
        Scenario.STANDBY -> "🌙"
        Scenario.REDUCED_SCREEN -> "🔅"
        Scenario.POWER_SAVING -> "🔋"
        Scenario.AIRPLANE_MODE -> "✈️"
    }

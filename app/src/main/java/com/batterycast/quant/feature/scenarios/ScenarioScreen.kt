package com.batterycast.quant.feature.scenarios

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import com.batterycast.quant.core.ui.components.HeroSurface
import com.batterycast.quant.core.ui.components.InfoCard
import com.batterycast.quant.core.ui.components.InlineStatus
import com.batterycast.quant.core.ui.components.MetricLabel
import com.batterycast.quant.core.ui.components.MiniRing
import com.batterycast.quant.core.ui.components.QuietBlock
import com.batterycast.quant.core.ui.components.SectionHeader
import com.batterycast.quant.core.ui.format.Formatters
import com.batterycast.quant.core.ui.theme.BatteryCastTheme
import com.batterycast.quant.core.ui.theme.MetricStyle
import com.batterycast.quant.core.ui.theme.RiskTone
import com.batterycast.quant.feature.shared.ForecastUiState
import com.batterycast.quant.forecasting.scenario.Scenario
import com.batterycast.quant.forecasting.scenario.ScenarioConfidence
import com.batterycast.quant.forecasting.scenario.ScenarioOutcome
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Scenarios.
 *
 * v1 listed every scenario as a horizontal bar, which meant four different activities that had all
 * fallen back to the same substituted rate appeared as four identical 64 % bars — mathematically
 * correct, and indistinguishable from a broken calculation.
 *
 * v2 shows one scenario at a time against the baseline, states plainly where each estimate came
 * from, and groups the ones the model genuinely cannot yet tell apart rather than pretending they
 * are separate answers.
 */
@Composable
fun ScenarioScreen(
    contentPadding: PaddingValues,
    viewModel: ScenarioViewModel = hiltViewModel(),
) {
    val state by viewModel.forecastState.collectAsStateWithLifecycle()
    val scenarios by viewModel.scenarios.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf(Scenario.NAVIGATION_FORTY_FIVE) }

    LaunchedEffect(Unit) { viewModel.load() }

    val baseline = scenarios.firstOrNull { it.scenario == Scenario.NORMAL }
    val chosen = scenarios.firstOrNull { it.scenario == selected }

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
                    text = "Scenarios",
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = "How will you use your phone?",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (state !is ForecastUiState.Ready) {
            item {
                HeroSurface {
                    Text(
                        text = "Not enough observed behaviour yet",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "Scenario comparisons unlock once BatteryCast has seen a few " +
                            "different kinds of usage on this phone.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            return@LazyColumn
        }

        item {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = (-20).dp),
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(
                    Scenario.entries.filter { it != Scenario.NORMAL },
                    key = { it.name },
                ) { scenario ->
                    FilterChip(
                        selected = selected == scenario,
                        onClick = { selected = scenario },
                        label = {
                            Text(
                                scenario.shortName,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelLarge,
                            )
                        },
                        shape = MaterialTheme.shapes.small,
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.18f),
                            selectedLabelColor = MaterialTheme.colorScheme.secondary,
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                        border = null,
                    )
                }
            }
        }

        if (loading && scenarios.isEmpty()) {
            item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
        }

        if (chosen != null) {
            item { ComparisonCard(baseline = baseline, scenario = chosen) }
            item { ProvenanceCard(chosen) }
        }

        item { GroupedProvisional(scenarios) }
    }
}

/**
 * The single large before/after comparison.
 *
 * Two rings side by side with an arrow between them reads instantly; two rows in a table does not.
 */
@Composable
private fun ComparisonCard(baseline: ScenarioOutcome?, scenario: ScenarioOutcome) {
    val semantic = BatteryCastTheme.semanticColors
    val baseMedian = baseline?.medianPercentAtTarget
    val median = scenario.medianPercentAtTarget

    HeroSurface(
        glow = scenario.survivalProbability?.let { semantic.forProbability(it) },
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 22.dp),
    ) {
        Text(
            text = scenario.scenario.displayName,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = scenario.scenario.description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (median == null) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = scenario.note ?: "Not enough observed behaviour to model this yet.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@HeroSurface
        }

        Spacer(Modifier.height(18.dp))
        Row(
            modifier = Modifier.fillMaxWidth().animateContentSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            OutcomeColumn(
                label = "Normal use",
                percent = baseMedian,
                probability = baseline?.survivalProbability,
            )
            Icon(
                Icons.AutoMirrored.Rounded.ArrowForward,
                contentDescription = "compared with",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
            OutcomeColumn(
                label = scenario.scenario.shortName,
                percent = median,
                probability = scenario.survivalProbability,
            )
        }

        if (baseMedian != null) {
            Spacer(Modifier.height(16.dp))
            val cost = baseMedian - median
            Text(
                text = when {
                    cost > 1.0 -> "${scenario.scenario.shortName} may use about " +
                        "${cost.roundToInt()}% extra battery."
                    cost < -1.0 -> "${scenario.scenario.shortName} may save about " +
                        "${abs(cost).roundToInt()}% battery."
                    else -> "About the same as your normal use."
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun OutcomeColumn(label: String, percent: Double?, probability: Double?) {
    val semantic = BatteryCastTheme.semanticColors
    val tone = probability?.let { RiskTone.forProbability(it) } ?: RiskTone.TIGHT

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        MiniRing(percent = percent ?: 0.0, tone = tone, diameter = 56.dp)
        Spacer(Modifier.height(8.dp))
        Text(
            text = percent?.let { "${it.roundToInt()}%" } ?: "—",
            style = MetricStyle,
            color = MaterialTheme.colorScheme.onSurface,
        )
        MetricLabel(label)
        if (probability != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "${(probability * 100).roundToInt()}% chance",
                style = MaterialTheme.typography.bodySmall,
                color = semantic.forProbability(probability),
            )
        }
    }
}

/**
 * Where the estimate came from, stated under the number rather than as an asterisk with a footnote.
 */
@Composable
private fun ProvenanceCard(scenario: ScenarioOutcome) {
    val semantic = BatteryCastTheme.semanticColors
    val (label, color) = when (scenario.confidence) {
        ScenarioConfidence.OBSERVED -> "Based on your phone's own behaviour" to semantic.healthy
        ScenarioConfidence.LIMITED -> "Based on limited observations" to semantic.caution
        ScenarioConfidence.SUBSTITUTED -> "Provisional — estimated from similar usage" to semantic.caution
        ScenarioConfidence.UNAVAILABLE -> "Still learning this one" to semantic.learning
    }

    InfoCard {
        InlineStatus(text = label, color = color)
        scenario.note?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (scenario.confidence == ScenarioConfidence.SUBSTITUTED) {
            Text(
                text = "Use this pattern once and BatteryCast will measure it directly instead of " +
                    "borrowing from similar usage.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Scenarios the model cannot yet tell apart, stated once.
 *
 * This is the direct fix for the four identical 64 % bars: when several activities resolve to the
 * same borrowed rate, saying so once is honest, whereas listing them separately implies a
 * distinction the data does not support.
 */
@Composable
private fun GroupedProvisional(scenarios: List<ScenarioOutcome>) {
    val provisional = scenarios.filter {
        it.confidence == ScenarioConfidence.SUBSTITUTED && it.medianPercentAtTarget != null
    }
    val indistinguishable = provisional
        .groupBy { it.medianPercentAtTarget!!.roundToInt() }
        .values
        .firstOrNull { it.size > 1 }
        ?: return

    QuietBlock {
        SectionHeader(title = "Not yet told apart")
        Text(
            text = indistinguishable.joinToString(", ") { it.scenario.shortName } +
                " all fall back to the same observed behaviour, so BatteryCast currently predicts " +
                "the same outcome for each. They separate once it has seen them individually.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Short chip-friendly names. Full names stay on the comparison card. */
private val Scenario.shortName: String
    get() = when (this) {
        Scenario.NORMAL -> "Normal"
        Scenario.VIDEO_ONE_HOUR -> "Video"
        Scenario.GAMING_THIRTY_MINUTES -> "Gaming"
        Scenario.NAVIGATION_FORTY_FIVE -> "Navigation"
        Scenario.HOTSPOT_ONE_HOUR -> "Hotspot"
        Scenario.STANDBY -> "Standby"
        Scenario.REDUCED_SCREEN -> "Light use"
        Scenario.POWER_SAVING -> "Power saving"
        Scenario.AIRPLANE_MODE -> "Airplane"
    }

/** Exposed so the comparison card and the chip row agree on wording. */
internal val ScenarioShortNames: (Scenario) -> String = { it.shortName }

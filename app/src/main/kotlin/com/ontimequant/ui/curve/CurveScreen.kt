package com.ontimequant.ui.curve

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ShowChart
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ontimequant.data.prefs.UserSettings
import com.ontimequant.forecast.CandidateForecast
import com.ontimequant.forecast.JourneyForecast
import com.ontimequant.ui.components.DepartureCurveChart
import com.ontimequant.ui.components.EmptyState
import com.ontimequant.ui.components.SectionCard
import com.ontimequant.ui.components.StatBlock
import com.ontimequant.ui.format.formatDuration
import com.ontimequant.ui.format.formatPercent
import com.ontimequant.ui.format.rememberTimeFormatter
import com.ontimequant.ui.preview.PreviewData
import com.ontimequant.ui.theme.OnTimeQuantTheme
import java.time.Instant

/**
 * The interactive departure curve.
 *
 * Drag anywhere on the chart and every figure below updates to the model's answer for that
 * exact minute. There is no interpolation in the readout: each point is a full Monte Carlo
 * run that the solver already performed.
 */
@Composable
fun CurveScreen(
    forecast: JourneyForecast?,
    settings: UserSettings,
    now: Instant,
    modifier: Modifier = Modifier,
) {
    if (forecast == null) {
        EmptyState(
            icon = Icons.Outlined.ShowChart,
            title = "No forecast to chart yet",
            body = "Generate a forecast on the departure screen first.",
            modifier = modifier,
        )
        return
    }

    val formatter = rememberTimeFormatter(forecast.appointment.zone, settings.use24HourClock)
    var scrubbed by remember(forecast) {
        mutableStateOf(forecast.recommended ?: forecast.bestEffort)
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().testTag("curve_screen"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            SectionCard(
                title = "Probability of arriving on time",
                subtitle = "Deadline ${formatter.clock(forecast.appointment.requiredArrival)} · " +
                    "target ${formatPercent(forecast.confidenceTarget)}",
            ) {
                DepartureCurveChart(
                    curve = forecast.curve,
                    confidenceTarget = forecast.confidenceTarget,
                    recommendedDeparture = forecast.recommendedDeparture,
                    now = now,
                    formatter = formatter,
                    onScrub = { scrubbed = it },
                )
            }
        }

        item {
            SectionCard(
                title = "At ${formatter.clock(scrubbed.departure)}",
                subtitle = "Everything below is simulated for this departure minute",
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    StatBlock(
                        "On time",
                        formatPercent(scrubbed.onTimeProbability),
                        Modifier.weight(1f),
                    )
                    StatBlock(
                        "Over 5 late",
                        formatPercent(scrubbed.probabilityMoreThan5LateSeconds),
                        Modifier.weight(1f),
                    )
                    StatBlock(
                        "Over 10 late",
                        formatPercent(scrubbed.probabilityMoreThan10LateSeconds),
                        Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    StatBlock(
                        "Expected arrival",
                        formatter.clock(scrubbed.expectedArrival),
                        Modifier.weight(1f),
                        supporting = "median ${formatter.clock(scrubbed.medianArrival)}",
                    )
                    StatBlock(
                        "80% range",
                        formatter.range(
                            scrubbed.percentiles[10] ?: scrubbed.expectedArrival,
                            scrubbed.percentiles[90] ?: scrubbed.expectedArrival,
                        ),
                        Modifier.weight(1.3f),
                    )
                }
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    StatBlock(
                        "If late, by how much",
                        if (scrubbed.expectedLatenessIfLateSeconds <= 0) "—"
                        else formatDuration(scrubbed.expectedLatenessIfLateSeconds),
                        Modifier.weight(1.3f),
                        supporting = "average, conditional on being late",
                    )
                    StatBlock(
                        "Simulations",
                        "${scrubbed.draws}",
                        Modifier.weight(1f),
                        supporting = "±${formatPercent(scrubbed.standardError * 1.96, 1)} at 95%",
                    )
                }
            }
        }

        item {
            SectionCard(title = "Percentiles of arrival time") {
                val keys = scrubbed.percentiles.keys.sorted()
                keys.forEachIndexed { index, key ->
                    Row(
                        Modifier.fillMaxWidth().height(34.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            "${key}th percentile",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            formatter.clock(scrubbed.percentiles.getValue(key)),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (key == 50) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                    if (index < keys.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "Read as: “in ${100 - (keys.lastOrNull() ?: 95)}% of simulated journeys leaving at " +
                        "${formatter.clock(scrubbed.departure)}, arrival was later than the last row.”",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            SectionCard(title = "How to read this") {
                Text(
                    "The line falls as you wait because the deadline is fixed while the drive gets " +
                        "slower and less predictable. The dashed horizontal line is your confidence " +
                        "target; the marked point is the latest minute that still clears it. Where the " +
                        "line is steep, five minutes of hesitation is expensive.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Preview(name = "Curve", showBackground = true, heightDp = 1400)
@Composable
private fun CurvePreview() {
    OnTimeQuantTheme {
        CurveScreen(
            forecast = PreviewData.forecast,
            settings = UserSettings(use24HourClock = true),
            now = PreviewData.now,
        )
    }
}

@Suppress("unused")
private fun keepImports(f: CandidateForecast) = f

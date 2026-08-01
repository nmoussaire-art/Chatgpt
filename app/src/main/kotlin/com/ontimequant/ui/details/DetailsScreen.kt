package com.ontimequant.ui.details

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ontimequant.data.prefs.UserSettings
import com.ontimequant.forecast.ForecastComponent
import com.ontimequant.forecast.JourneyForecast
import com.ontimequant.model.DataProvenance
import com.ontimequant.ui.components.EmptyState
import com.ontimequant.ui.components.NoticeBanner
import com.ontimequant.ui.components.PersonalizationChip
import com.ontimequant.ui.components.ProvenanceChip
import com.ontimequant.ui.components.RiskBadge
import com.ontimequant.ui.components.SectionCard
import com.ontimequant.ui.components.StatBlock
import com.ontimequant.ui.format.description
import com.ontimequant.ui.format.explanation
import com.ontimequant.ui.format.formatDuration
import com.ontimequant.ui.format.formatPercent
import com.ontimequant.ui.format.rememberTimeFormatter
import com.ontimequant.ui.preview.PreviewData
import com.ontimequant.ui.theme.OnTimeQuantTheme

/**
 * The forecast, taken apart.
 *
 * Every row states its value, its contribution, where it came from and what it means in
 * plain language. This is the screen that has to survive the question "why should I
 * believe you?", so nothing here is unattributed.
 */
@Composable
fun DetailsScreen(
    forecast: JourneyForecast?,
    settings: UserSettings,
    modifier: Modifier = Modifier,
) {
    if (forecast == null) {
        EmptyState(
            icon = Icons.Outlined.Tune,
            title = "Nothing to break down yet",
            body = "Generate a forecast on the departure screen first.",
            modifier = modifier,
        )
        return
    }

    val formatter = rememberTimeFormatter(forecast.appointment.zone, settings.use24HourClock)
    val headline = forecast.recommended ?: forecast.bestEffort

    LazyColumn(
        modifier = modifier.fillMaxSize().testTag("details_screen"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            SectionCard(
                title = "Summary",
                trailing = { ProvenanceChip(forecast.routeProvenance) },
            ) {
                Text(forecast.summary, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    StatBlock(
                        "Leave at",
                        forecast.recommendedDeparture?.let(formatter::clock) ?: "Now",
                        Modifier.weight(1f),
                    )
                    StatBlock("On time", formatPercent(forecast.onTimeProbability), Modifier.weight(1f))
                    StatBlock(
                        "Deadline",
                        formatter.clock(forecast.appointment.requiredArrival),
                        Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RiskBadge(forecast.trafficRisk)
                    Spacer(Modifier.width(10.dp))
                    PersonalizationChip(forecast.personalization)
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    forecast.trafficRisk.description(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            SectionCard(
                title = "Journey components",
                subtitle = "Departure + preparation + drive + parking + walk = arrival",
            ) {
                forecast.components.forEachIndexed { index, component ->
                    ComponentRow(component, formatter.zone.id)
                    if (index < forecast.components.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }

        item {
            SectionCard(title = "Resulting distribution") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    StatBlock(
                        "Expected arrival",
                        formatter.clock(headline.expectedArrival),
                        Modifier.weight(1f),
                    )
                    StatBlock(
                        "Median arrival",
                        formatter.clock(headline.medianArrival),
                        Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    StatBlock(
                        "80% range",
                        formatter.range(
                            headline.percentiles[10] ?: headline.expectedArrival,
                            headline.percentiles[90] ?: headline.expectedArrival,
                        ),
                        Modifier.weight(1.4f),
                        supporting = "width ${formatDuration(headline.intervalWidthSeconds)}",
                    )
                    StatBlock(
                        "Safety margin",
                        formatDuration(forecast.recommendedSafetyMarginSeconds),
                        Modifier.weight(1f),
                        supporting = "median arrival to deadline",
                    )
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    "Produced from ${forecast.simulationDraws} Monte Carlo simulations " +
                        "(model ${forecast.modelVersion}, seed ${forecast.seed}). Re-running with the " +
                        "same inputs gives exactly the same answer.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            SectionCard(title = "How personalised is this?") {
                Text(forecast.personalizationDetail, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                Text(
                    "Personalisation is judged on effective sample size — recent journeys count for " +
                        "more than old ones — not on a raw trip count. Until there is enough evidence, " +
                        "the routing estimate and documented priors carry the forecast.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (forecast.notices.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    forecast.notices.forEach { NoticeBanner(it.message, it.provenance) }
                }
            }
        }
    }
}

@Composable
private fun ComponentRow(component: ForecastComponent, zoneId: String) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(vertical = 12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(component.label, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ProvenanceChip(component.provenance)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        component.sourceLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    when {
                        component.provenance == DataProvenance.UNAVAILABLE -> "—"
                        component.id == "uncertainty" -> "±${formatDuration(component.uncertaintySeconds)}"
                        component.valueSeconds == 0.0 && component.deltaSeconds == 0.0 -> "none"
                        component.id == "free_flow" -> formatDuration(component.valueSeconds)
                        else -> formatDuration(component.deltaSeconds, alwaysSigned = true)
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                if (component.uncertaintySeconds > 30 && component.id != "uncertainty") {
                    Text(
                        "±${formatDuration(component.uncertaintySeconds)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(6.dp))
            Icon(
                if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = if (expanded) "Collapse" else "Explain",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AnimatedVisibility(expanded) {
            Column(Modifier.padding(top = 10.dp)) {
                Text(
                    component.explanation,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    component.provenance.explanation(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Preview(name = "Details", showBackground = true, heightDp = 1800)
@Composable
private fun DetailsPreview() {
    OnTimeQuantTheme {
        DetailsScreen(
            forecast = PreviewData.forecast,
            settings = UserSettings(use24HourClock = true),
        )
    }
}

package com.ontimequant.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.ShowChart
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ontimequant.data.prefs.ConfidencePresets
import com.ontimequant.forecast.JourneyForecast
import com.ontimequant.model.Appointment
import com.ontimequant.model.DataProvenance
import com.ontimequant.ui.components.DepartureCurveSparkline
import com.ontimequant.ui.components.NoticeBanner
import com.ontimequant.ui.components.PersonalizationChip
import com.ontimequant.ui.components.ProbabilityBar
import com.ontimequant.ui.components.ProvenanceChip
import com.ontimequant.ui.components.RiskBadge
import com.ontimequant.ui.components.SectionCard
import com.ontimequant.ui.components.StatBlock
import com.ontimequant.ui.format.TimeFormatter
import com.ontimequant.ui.format.formatCountdown
import com.ontimequant.ui.format.formatDuration
import com.ontimequant.ui.format.formatPercent
import com.ontimequant.ui.format.label
import com.ontimequant.ui.format.rememberTimeFormatter
import com.ontimequant.ui.format.shortDescription
import com.ontimequant.ui.preview.PreviewData
import com.ontimequant.ui.theme.DepartureNumeralStyle
import com.ontimequant.ui.theme.LocalRiskColors
import com.ontimequant.ui.theme.OnTimeQuantTheme
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant

/**
 * The home screen answers one question, large: *when should I leave?*
 *
 * Everything else on the screen exists to make that number trustworthy — the probability
 * it buys, what it costs to wait, where the inputs came from, and how personalised the
 * model is. Technical statistics live one tap away, not here.
 */
@Composable
fun HomeScreen(
    state: HomeUiState,
    onRecalculate: () -> Unit,
    onOpenCurve: () -> Unit,
    onOpenDetails: () -> Unit,
    onSelectAppointment: (Appointment) -> Unit,
    onNewAppointment: () -> Unit,
    onConfidenceChange: (Double) -> Unit,
    onNavigate: () -> Unit,
    onDeclareDeparted: () -> Unit,
    onConfirmArrival: () -> Unit,
    onCancelJourney: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val zone = state.selected?.zone ?: java.time.ZoneId.systemDefault()
    val formatter = rememberTimeFormatter(zone, state.settings.use24HourClock)

    // The countdown ticks every ten seconds. The forecast is *not* recomputed on a timer —
    // that is the periodic worker's job, and doing it here would burn API quota.
    //
    // The clock advances from the instant the forecast was generated rather than from the
    // device clock directly. In the running app the two are the same thing; in a preview or
    // screenshot test, where the forecast is pinned to a fixed date, it keeps the countdown
    // meaningful instead of reporting several months ago.
    val wallClockAtComposition = remember(state.forecast) { System.currentTimeMillis() }
    var elapsedMillis by remember(state.forecast) { mutableStateOf(0L) }
    LaunchedEffect(state.forecast) {
        while (true) {
            elapsedMillis = System.currentTimeMillis() - wallClockAtComposition
            delay(10_000)
        }
    }
    val tick = (state.forecast?.generatedAt ?: state.now).plusMillis(elapsedMillis)

    LazyColumn(
        modifier = modifier.fillMaxSize().testTag("home_screen"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (state.appointments.size > 1) {
            item {
                AppointmentSelector(state.appointments, state.selected, formatter, onSelectAppointment)
            }
        }

        when {
            state.loading -> item {
                Box(Modifier.fillMaxWidth().height(320.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(strokeWidth = 3.dp)
                        Spacer(Modifier.height(16.dp))
                        Text("Simulating your journey…", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            state.selected == null -> item {
                SectionCard {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.CalendarMonth, null, Modifier.size(40.dp))
                        Spacer(Modifier.height(12.dp))
                        Text("No appointment yet", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Add where you need to be and when, and OnTime Quant will work out the " +
                                "latest safe time to leave.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = onNewAppointment) { Text("Add an appointment") }
                    }
                }
            }

            state.forecast != null -> {
                item {
                    DepartureCard(
                        appointment = state.selected,
                        forecast = state.forecast,
                        now = tick,
                        formatter = formatter,
                        recalculating = state.recalculating,
                        onNavigate = onNavigate,
                        onRecalculate = onRecalculate,
                    )
                }
                item { WaitingCostCard(state.forecast, formatter, onOpenCurve) }
                item { ConfidenceCard(state.selected!!, onConfidenceChange) }
                item {
                    ExplainCard(
                        forecast = state.forecast,
                        formatter = formatter,
                        onOpenDetails = onOpenDetails,
                    )
                }
                if (state.journeyInFlight) {
                    item { JourneyInFlightCard(onDeclareDeparted, onConfirmArrival, onCancelJourney) }
                }
            }

            else -> item {
                SectionCard(title = "No forecast available") {
                    Text(
                        state.error ?: "Something went wrong.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(14.dp))
                    OutlinedButton(onClick = onRecalculate) { Text("Try again") }
                }
            }
        }

        if (state.notices.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.notices.forEach { NoticeBanner(it.message, it.provenance) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppointmentSelector(
    appointments: List<Appointment>,
    selected: Appointment?,
    formatter: TimeFormatter,
    onSelect: (Appointment) -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(appointments, key = { it.id }) { appointment ->
            FilterChip(
                selected = appointment.id == selected?.id,
                onClick = { onSelect(appointment) },
                label = {
                    Text("${appointment.title} · ${formatter.clock(appointment.startTime)}")
                },
            )
        }
    }
}

/** The dominant element: the recommended departure time. */
@Composable
private fun DepartureCard(
    appointment: Appointment?,
    forecast: JourneyForecast,
    now: Instant,
    formatter: TimeFormatter,
    recalculating: Boolean,
    onNavigate: () -> Unit,
    onRecalculate: () -> Unit,
) {
    val risk = LocalRiskColors.current
    val headline = forecast.recommended ?: forecast.bestEffort
    val departure = forecast.recommendedDeparture
    val imminent = departure != null &&
        Duration.between(now, departure) <= Duration.ofMinutes(15)

    SectionCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text(
                    appointment?.title.orEmpty(),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                )
                Text(
                    "${formatter.dayLabel(forecast.appointment.startTime, now)} at " +
                        formatter.clock(forecast.appointment.startTime) +
                        " · ${forecast.appointment.destination.label}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ProvenanceChip(forecast.routeProvenance)
        }

        Spacer(Modifier.height(22.dp))

        if (departure != null) {
            Text(
                "LEAVE AT",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                formatter.clock(departure),
                style = DepartureNumeralStyle,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics {
                    contentDescription = "Recommended departure ${formatter.clock(departure)}, " +
                        formatCountdown(now, departure)
                }.testTag("recommended_departure"),
            )
            Text(
                formatCountdown(now, departure),
                style = MaterialTheme.typography.titleMedium,
                color = if (imminent) risk.elevated else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                "Leave now",
                style = DepartureNumeralStyle,
                color = risk.high,
                modifier = Modifier.testTag("recommended_departure"),
            )
            Text(
                "No departure in the window reaches your ${formatPercent(forecast.confidenceTarget)} target.",
                style = MaterialTheme.typography.bodyMedium,
                color = risk.high,
            )
        }

        Spacer(Modifier.height(20.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                formatPercent(forecast.onTimeProbability),
                style = MaterialTheme.typography.headlineMedium,
                color = if (forecast.onTimeProbability >= forecast.confidenceTarget) risk.low else risk.elevated,
                modifier = Modifier.testTag("on_time_probability"),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                "chance of arriving by ${formatter.clock(forecast.appointment.requiredArrival)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(10.dp))
        ProbabilityBar(forecast.onTimeProbability, forecast.confidenceTarget)

        Spacer(Modifier.height(20.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(16.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            StatBlock(
                label = "Expected arrival",
                value = formatter.clock(headline.expectedArrival),
                supporting = "median ${formatter.clock(headline.medianArrival)}",
                modifier = Modifier.weight(1f),
            )
            StatBlock(
                label = "80% arrival range",
                value = formatter.range(
                    headline.percentiles[10] ?: headline.expectedArrival,
                    headline.percentiles[90] ?: headline.expectedArrival,
                ),
                modifier = Modifier.weight(1.4f),
            )
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            StatBlock(
                label = "Over 5 min late",
                value = formatPercent(headline.probabilityMoreThan5LateSeconds),
                modifier = Modifier.weight(1f),
            )
            StatBlock(
                label = "Over 10 min late",
                value = formatPercent(headline.probabilityMoreThan10LateSeconds),
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            StatBlock(
                label = "Safety buffer",
                value = formatDuration(forecast.recommendedSafetyMarginSeconds),
                supporting = "median arrival to deadline",
                modifier = Modifier.weight(1.3f),
            )
            StatBlock(
                label = "Your target",
                value = formatPercent(forecast.confidenceTarget),
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(18.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            RiskBadge(forecast.trafficRisk)
            Spacer(Modifier.width(10.dp))
            PersonalizationChip(forecast.personalization)
        }

        Spacer(Modifier.height(20.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onNavigate,
                modifier = Modifier.weight(1f).height(52.dp).testTag("navigate_button"),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (imminent) risk.elevated else MaterialTheme.colorScheme.primary,
                ),
            ) {
                Icon(Icons.Filled.Navigation, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (imminent) "Leave now" else "Navigate", fontWeight = FontWeight.SemiBold)
            }
            OutlinedButton(
                onClick = onRecalculate,
                enabled = !recalculating,
                modifier = Modifier.height(52.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                if (recalculating) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Outlined.Refresh, contentDescription = "Recalculate", Modifier.size(18.dp))
                }
            }
        }
    }
}

/** "What does waiting cost me?" — the comparison table plus a sparkline. */
@Composable
private fun WaitingCostCard(
    forecast: JourneyForecast,
    formatter: TimeFormatter,
    onOpenCurve: () -> Unit,
) {
    val risk = LocalRiskColors.current
    SectionCard(
        title = "The cost of waiting",
        subtitle = "Every extra five minutes, priced in probability",
        trailing = {
            TextButton(onClick = onOpenCurve, modifier = Modifier.testTag("open_curve")) {
                Icon(Icons.Outlined.ShowChart, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Curve")
            }
        },
    ) {
        DepartureCurveSparkline(
            curve = forecast.curve,
            confidenceTarget = forecast.confidenceTarget,
            recommendedDeparture = forecast.recommendedDeparture,
        )
        Spacer(Modifier.height(14.dp))
        forecast.comparisons.forEach { row ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 7.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (row.offsetMinutes == 0L) {
                        "Leave at ${formatter.clock(row.departure)}  (recommended)"
                    } else {
                        "Leave at ${formatter.clock(row.departure)}"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (row.isRecommended) FontWeight.SemiBold else FontWeight.Normal,
                )
                Text(
                    formatPercent(row.onTimeProbability),
                    style = MaterialTheme.typography.titleSmall,
                    color = when {
                        row.onTimeProbability >= forecast.confidenceTarget -> risk.low
                        row.onTimeProbability >= forecast.confidenceTarget - 0.15 -> risk.elevated
                        else -> risk.high
                    },
                )
            }
        }
    }
}

@Composable
private fun ConfidenceCard(appointment: Appointment, onChange: (Double) -> Unit) {
    SectionCard(
        title = "How safe do you want to be?",
        subtitle = "Your target probability of arriving on time",
    ) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(ConfidencePresets.all) { (label, value, _) ->
                FilterChip(
                    selected = kotlin.math.abs(appointment.confidenceTarget - value) < 0.005,
                    onClick = { onChange(value) },
                    label = { Text("$label · ${formatPercent(value)}") },
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            ConfidencePresets.all
                .firstOrNull { kotlin.math.abs(it.second - appointment.confidenceTarget) < 0.005 }
                ?.third
                ?: "A custom target of ${formatPercent(appointment.confidenceTarget)}.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ExplainCard(
    forecast: JourneyForecast,
    formatter: TimeFormatter,
    onOpenDetails: () -> Unit,
) {
    SectionCard(
        title = "Why this time",
        trailing = {
            TextButton(onClick = onOpenDetails, modifier = Modifier.testTag("open_details")) {
                Icon(Icons.Outlined.Tune, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Details")
            }
        },
    ) {
        Text(
            forecast.personalizationDetail,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            forecast.personalization.shortDescription(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(14.dp))
        val top = forecast.components
            .filter { it.deltaSeconds > 60 }
            .sortedByDescending { it.deltaSeconds }
            .take(3)
        top.forEach { component ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 5.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(component.label, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.width(8.dp))
                    ProvenanceChip(component.provenance)
                }
                Text(
                    formatDuration(component.deltaSeconds, alwaysSigned = true),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun JourneyInFlightCard(
    onDeclareDeparted: () -> Unit,
    onConfirmArrival: () -> Unit,
    onCancel: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text("Journey in progress", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                "Tell OnTime Quant when you actually set off and when you arrive. Those two " +
                    "timestamps are what make the next forecast better.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = onDeclareDeparted, label = { Text("I've set off") })
                AssistChip(
                    onClick = onConfirmArrival,
                    label = { Text("I've arrived") },
                    modifier = Modifier.testTag("confirm_arrival"),
                )
                AssistChip(onClick = onCancel, label = { Text("Cancel") })
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------

@Preview(name = "Home · light", showBackground = true, heightDp = 1500)
@Composable
private fun HomePreviewLight() {
    OnTimeQuantTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            HomeScreen(
                state = PreviewData.homeState(),
                onRecalculate = {}, onOpenCurve = {}, onOpenDetails = {},
                onSelectAppointment = {}, onNewAppointment = {}, onConfidenceChange = {},
                onNavigate = {}, onDeclareDeparted = {}, onConfirmArrival = {}, onCancelJourney = {},
            )
        }
    }
}

@Preview(name = "Home · dark", showBackground = true, heightDp = 1500)
@Composable
private fun HomePreviewDark() {
    OnTimeQuantTheme(themeMode = com.ontimequant.data.prefs.ThemeMode.DARK) {
        Surface(color = MaterialTheme.colorScheme.background) {
            HomeScreen(
                state = PreviewData.homeState(),
                onRecalculate = {}, onOpenCurve = {}, onOpenDetails = {},
                onSelectAppointment = {}, onNewAppointment = {}, onConfidenceChange = {},
                onNavigate = {}, onDeclareDeparted = {}, onConfirmArrival = {}, onCancelJourney = {},
            )
        }
    }
}

@Preview(name = "Home · degraded", showBackground = true, heightDp = 900)
@Composable
private fun HomePreviewDegraded() {
    OnTimeQuantTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            HomeScreen(
                state = PreviewData.homeState().copy(
                    forecast = null,
                    error = "No route estimate is available for this journey, so no forecast can be produced.",
                    notices = listOf(
                        com.ontimequant.model.ProviderNotice(
                            "Routing", DataProvenance.UNAVAILABLE,
                            "Live traffic unavailable. Forecast uses the most recent route estimate and wider uncertainty.",
                        ),
                    ),
                ),
                onRecalculate = {}, onOpenCurve = {}, onOpenDetails = {},
                onSelectAppointment = {}, onNewAppointment = {}, onConfidenceChange = {},
                onNavigate = {}, onDeclareDeparted = {}, onConfirmArrival = {}, onCancelJourney = {},
            )
        }
    }
}

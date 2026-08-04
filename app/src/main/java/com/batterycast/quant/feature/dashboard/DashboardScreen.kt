package com.batterycast.quant.feature.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.batterycast.quant.core.system.CalendarTarget
import com.batterycast.quant.core.ui.chart.ForecastFanChart
import com.batterycast.quant.core.ui.components.BatteryCastCard
import com.batterycast.quant.core.ui.components.CollectingDataCard
import com.batterycast.quant.core.ui.components.DataFreshnessFooter
import com.batterycast.quant.core.ui.components.EdgeFadedRow
import com.batterycast.quant.core.ui.components.HairlineDivider
import com.batterycast.quant.core.ui.components.MetricTile
import com.batterycast.quant.core.ui.components.RingGauge
import com.batterycast.quant.core.ui.components.SectionHeader
import com.batterycast.quant.core.ui.components.SelectableChip
import com.batterycast.quant.core.ui.components.StatRow
import com.batterycast.quant.core.ui.components.StatusChip
import com.batterycast.quant.core.ui.format.Formatters
import com.batterycast.quant.core.ui.theme.BatteryCastTheme
import com.batterycast.quant.core.ui.theme.MetricLabelStyle
import com.batterycast.quant.feature.shared.ForecastUiState
import com.batterycast.quant.feature.shared.TargetSelection
import com.batterycast.quant.forecasting.ForecastEngine
import com.batterycast.quant.forecasting.model.BatteryForecast
import com.batterycast.quant.forecasting.model.DataMaturity
import kotlin.math.roundToInt

/** The page gutter. Content sits at this inset; the chip row bleeds past it. */
private val Gutter = 16.dp

/**
 * The home screen.
 *
 * One question dominates it: will my phone last? The hero answers it in a single glyph — an arc
 * gauge whose colour *is* the confidence — and the three metrics beneath give the supporting facts
 * without competing for attention. Everything quantitative lives one tap away on Curve and
 * Insights rather than stacking up here.
 */
@Composable
fun DashboardScreen(
    onOpenSurvival: () -> Unit,
    onOpenWhatChanged: () -> Unit,
    onOpenPlanner: () -> Unit,
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val quickTargets by viewModel.quickTargets.collectAsStateWithLifecycle()
    val calendarTargets by viewModel.calendarTargets.collectAsStateWithLifecycle()
    val selectedTarget by viewModel.target.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.refresh()
        viewModel.loadTargets()
    }

    // No horizontal content padding on the list: the target chips run edge to edge and every other
    // item takes the gutter itself. Compose rejects negative padding, so a child cannot cancel a
    // container's inset — the inset has to be applied per item instead.
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 18.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Column(modifier = Modifier.padding(horizontal = Gutter)) {
                Text(
                    text = "Will my phone last?",
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "Forecast from this device's own battery behaviour.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            TargetPicker(
                quickTargets = quickTargets,
                calendarTargets = calendarTargets,
                selected = selectedTarget,
                onSelect = viewModel::selectTarget,
                onSelectEvent = viewModel::selectCalendarTarget,
                onSelectCustom = {
                    val now = System.currentTimeMillis()
                    viewModel.selectCustomTime(now + 4 * 60 * 60 * 1000L, "In 4 hours")
                },
            )
        }

        when (val current = state) {
            is ForecastUiState.Loading -> gutterItem {
                CollectingDataCard(
                    headline = "Reading the battery…",
                    detail = "Taking a live measurement from this device.",
                )
            }

            is ForecastUiState.Collecting -> gutterItem {
                CollectingDataCard(
                    headline = current.headline,
                    detail = current.detail,
                    progress = current.progress,
                    progressLabel = "Nothing is estimated until real change has been measured.",
                )
            }

            is ForecastUiState.Ready -> {
                gutterItem { SurvivalHero(current.forecast, current.target, onOpenWhatChanged) }
                gutterItem { ExpectedLevelCard(current.forecast) }
                gutterItem { MiniCurveCard(current.forecast, onOpenSurvival) }
                gutterItem { DrainCard(current.forecast) }
                gutterItem { PlannerPromptCard(onOpenPlanner) }
                gutterItem { PrivacyFooter() }
            }
        }
    }
}

/** A list item inset by the page gutter. */
private fun androidx.compose.foundation.lazy.LazyListScope.gutterItem(
    content: @Composable () -> Unit,
) {
    item {
        Column(modifier = Modifier.padding(horizontal = Gutter)) { content() }
    }
}

/**
 * The hero.
 *
 * Badges across the top, the answer in the middle, the evidence along the bottom. The gauge and the
 * probability sentence share one colour, taken from the same thresholds, so the ring and the words
 * can never tell different stories.
 */
@Composable
private fun SurvivalHero(
    forecast: BatteryForecast,
    target: TargetSelection,
    onOpenWhatChanged: () -> Unit,
) {
    val semantic = BatteryCastTheme.semanticColors
    val outcome = forecast.target

    BatteryCastCard(contentPadding = PaddingValues(horizontal = 18.dp, vertical = 20.dp)) {
        // --- Top row: what kind of estimate this is, and what the phone is doing right now. -----
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusChip(text = forecast.maturity.badge, color = forecast.maturity.badgeColor())
            StatusChip(
                text = if (forecast.isCharging) "Charging" else "On battery",
                color = if (forecast.isCharging) semantic.chargingAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                leading = if (forecast.isCharging) "⚡" else "▾",
            )
        }

        if (outcome == null) {
            Text(
                text = "Target outside the forecast window",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Pick a target within the next 24 hours to see a survival probability.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@BatteryCastCard
        }

        val probabilityColor = semantic.forProbability(outcome.survivalProbability)

        // --- Centre: the answer. -----------------------------------------------------------------
        Spacer(Modifier.height(2.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            RingGauge(
                fraction = outcome.survivalProbability.toFloat(),
                caption = "chance of lasting",
                color = probabilityColor,
                contentDescription = "${(outcome.survivalProbability * 100).roundToInt()} percent " +
                    "chance of remaining above ${outcome.reservePercent.roundToInt()} percent " +
                    "until ${Formatters.clockTime(target.atMs)}",
            )
        }

        Text(
            text = "Chance of remaining above ${Formatters.percent(outcome.reservePercent)} until " +
                Formatters.clockTimeWithDay(target.atMs, forecast.generatedAtMs),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )

        // --- Bottom: the three facts behind the number. -------------------------------------------
        Spacer(Modifier.height(2.dp))
        HairlineDivider()
        Spacer(Modifier.height(2.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            MetricTile(
                modifier = Modifier.weight(1f),
                value = Formatters.percent(forecast.currentPercent),
                label = "Battery now",
            )
            MetricTile(
                modifier = Modifier.weight(1f),
                value = Formatters.ratePerHour(forecast.drain.currentPercentPerHour),
                label = if (forecast.isCharging) "Charge rate" else "Current drain",
                valueColor = MaterialTheme.colorScheme.onSurface,
            )
            val learning = forecast.learningProgress()
            MetricTile(
                modifier = Modifier.weight(1f),
                value = learning.first,
                label = learning.second,
            )
        }

        val topDriver = forecast.drivers.firstOrNull()
        if (topDriver != null) {
            HairlineDivider()
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = topDriver.headline,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                QuietLink(text = "Why", onClick = onOpenWhatChanged)
            }
        }

        DataFreshnessFooter(
            text = "Measured ${Formatters.relativeAge(forecast.dataQuality.newestObservationAgeMs)} · " +
                "${forecast.dataQuality.usableObservationCount} readings on this device",
            isStale = forecast.dataQuality.isStale,
        )
    }
}

/**
 * A helper link.
 *
 * Deliberately not tinted with a status hue. Green in this app means "your battery is fine"; a
 * green "Why" or "Open charge planner" spends that meaning on navigation chrome and dilutes it
 * everywhere else. Secondary text with an underline reads unmistakably as a link without it.
 */
@Composable
private fun QuietLink(text: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textDecoration = TextDecoration.Underline,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** Short, screaming-caps badge text for the maturity pill. */
private val DataMaturity.badge: String
    get() = when (this) {
        DataMaturity.COLLECTING -> "Collecting"
        DataMaturity.PRELIMINARY -> "Preliminary"
        DataMaturity.BASIC -> "Live estimate"
        DataMaturity.PERSONALISED -> "Personalised"
        DataMaturity.MATURE -> "Fully learned"
    }

@Composable
private fun DataMaturity.badgeColor(): androidx.compose.ui.graphics.Color {
    val semantic = BatteryCastTheme.semanticColors
    return when (this) {
        DataMaturity.COLLECTING, DataMaturity.PRELIMINARY -> semantic.caution
        DataMaturity.BASIC -> MaterialTheme.colorScheme.onSurfaceVariant
        DataMaturity.PERSONALISED, DataMaturity.MATURE -> semantic.positive
    }
}

/**
 * How far the model is from the next tier, as a fraction and a caption.
 *
 * Both numbers are real: the numerator is the effective sample weight the model actually carries,
 * and the denominator is the constant the engine tests against to promote the forecast. Nothing
 * here is a progress bar invented to look busy.
 */
private fun BatteryForecast.learningProgress(): Pair<String, String> {
    val samples = dataQuality.modelEffectiveSamples.roundToInt()
    val mature = ForecastEngine.MATURE_SAMPLES.roundToInt()
    return when (maturity) {
        DataMaturity.MATURE -> "$samples/$mature" to "Learned"
        DataMaturity.PERSONALISED -> "$samples/$mature" to "Learning"
        else -> "$samples/${ForecastEngine.PERSONALISED_SAMPLES.roundToInt()}" to "Learning"
    }
}

@Composable
private fun ExpectedLevelCard(forecast: BatteryForecast) {
    val outcome = forecast.target ?: return
    BatteryCastCard {
        SectionHeader(
            title = "Expected battery at ${Formatters.clockTime(outcome.targetMs)}",
            subtitle = "From $SIMULATED_PATHS_LABEL simulated battery paths.",
        )
        StatRow(label = "Median", value = Formatters.percent(outcome.median))
        StatRow(
            label = "Likely range",
            supporting = "80% of simulated outcomes",
            value = Formatters.percentRange(outcome.p10, outcome.p90),
        )
        StatRow(
            label = "Conservative estimate",
            supporting = "10th percentile — the number to plan around",
            value = Formatters.percent(outcome.conservative),
        )
    }
}

@Composable
private fun MiniCurveCard(forecast: BatteryForecast, onOpenSurvival: () -> Unit) {
    BatteryCastCard {
        SectionHeader(
            title = "Survival curve",
            subtitle = "Median projection with the 80% forecast interval.",
        )
        ForecastFanChart(
            points = forecast.curve,
            targetMs = forecast.target?.targetMs,
            height = 180.dp,
            showFiftyBand = false,
            showNinetyBand = false,
        )
        QuietLink(text = "Open full chart", onClick = onOpenSurvival)
    }
}

@Composable
private fun DrainCard(forecast: BatteryForecast) {
    val drain = forecast.drain
    BatteryCastCard {
        SectionHeader(title = "Current drain")
        StatRow(
            label = "Recent rate",
            supporting = drain.method?.let { "estimated from $it" },
            value = Formatters.ratePerHour(drain.currentPercentPerHour),
        )
        if (drain.baselinePercentPerHour != null) {
            StatRow(
                label = "Typical for this state",
                supporting = forecast.currentRegime.displayName,
                value = Formatters.ratePerHour(drain.baselinePercentPerHour),
            )
        }
        if (drain.milliAmpsNow != null) {
            StatRow(label = "Live current", value = Formatters.milliAmps(drain.milliAmpsNow))
        }
        if (drain.wattsNow != null) {
            StatRow(label = "Live power", value = Formatters.watts(drain.wattsNow))
        }
        if (forecast.dataQuality.unsupportedFields.isNotEmpty()) {
            Text(
                text = "Not reported by this device: ${forecast.dataQuality.unsupportedFields.joinToString()}.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PlannerPromptCard(onOpenPlanner: () -> Unit) {
    BatteryCastCard {
        SectionHeader(
            title = "Need it to last?",
            subtitle = "Work out the latest safe time to start charging for an event.",
        )
        QuietLink(text = "Open charge planner", onClick = onOpenPlanner)
    }
}

@Composable
private fun PrivacyFooter() {
    Text(
        text = "All battery observations and forecasts remain on your device.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    )
}

/**
 * The target-time strip.
 *
 * Lives directly on the canvas rather than inside a card, and runs the full width of the display,
 * so a chip at the edge is partially visible instead of being cut off at a card boundary. Each chip
 * stacks its name over its time, which is what stops "Midnight · 12:00 AM" from truncating at a
 * large display size.
 */
@Composable
private fun TargetPicker(
    quickTargets: List<TargetSelection>,
    calendarTargets: List<CalendarTarget>,
    selected: TargetSelection?,
    onSelect: (TargetSelection) -> Unit,
    onSelectEvent: (CalendarTarget) -> Unit,
    onSelectCustom: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = "TARGET TIME",
            style = MetricLabelStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Gutter),
        )

        EdgeFadedRow(gutter = Gutter) {
            items(quickTargets, key = { "q-${it.label}" }) { option ->
                SelectableChip(
                    text = option.label,
                    supporting = Formatters.clockTime(option.atMs),
                    selected = selected?.atMs == option.atMs,
                    onClick = { onSelect(option) },
                )
            }
            items(calendarTargets, key = { "c-${it.id}" }) { event ->
                SelectableChip(
                    text = event.title,
                    supporting = Formatters.clockTime(event.startMs),
                    selected = selected?.atMs == event.startMs,
                    onClick = { onSelectEvent(event) },
                )
            }
            item(key = "custom-4h") {
                SelectableChip(
                    text = "In 4 hours",
                    supporting = Formatters.clockTime(System.currentTimeMillis() + 4 * 60 * 60 * 1000L),
                    selected = false,
                    onClick = onSelectCustom,
                )
            }
        }

        if (calendarTargets.isEmpty()) {
            Text(
                text = "Grant calendar access in Settings to target a real event, such as a flight " +
                    "or the end of a meeting.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Gutter),
            )
        }
    }
}

private const val SIMULATED_PATHS_LABEL = "2,400"

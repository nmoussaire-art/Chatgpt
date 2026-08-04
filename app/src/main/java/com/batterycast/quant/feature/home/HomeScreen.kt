package com.batterycast.quant.feature.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.batterycast.quant.core.system.CalendarTarget
import com.batterycast.quant.core.ui.components.CompactRow
import com.batterycast.quant.core.ui.components.ForecastOrb
import com.batterycast.quant.core.ui.components.HeroSurface
import com.batterycast.quant.core.ui.components.InfoCard
import com.batterycast.quant.core.ui.components.InlineStatus
import com.batterycast.quant.core.ui.components.MetricLabel
import com.batterycast.quant.core.ui.components.NavigationRow
import com.batterycast.quant.core.ui.components.QuietBlock
import com.batterycast.quant.core.ui.components.SectionHeader
import com.batterycast.quant.core.ui.format.Formatters
import com.batterycast.quant.core.ui.theme.BatteryCastTheme
import com.batterycast.quant.core.ui.theme.MetricStyle
import com.batterycast.quant.core.ui.theme.RiskTone
import com.batterycast.quant.feature.shared.ForecastUiState
import com.batterycast.quant.feature.shared.TargetSelection
import com.batterycast.quant.forecasting.model.BatteryForecast
import com.batterycast.quant.forecasting.model.DataMaturity
import com.batterycast.quant.forecasting.model.RecommendedAction
import com.batterycast.quant.forecasting.model.Recommendations
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.roundToInt

/**
 * Home.
 *
 * The screen answers one question in the first two seconds — *will your phone last?* — and answers
 * it in the order a person actually needs: what you have now, what you will probably have at your
 * target, how confident that is, and what to do about it. Everything quantitative is one tap away
 * on the Forecast tab rather than competing for attention here.
 */
@Composable
fun HomeScreen(
    contentPadding: PaddingValues,
    onOpenForecast: () -> Unit,
    onOpenScenarios: () -> Unit,
    onOpenCharge: () -> Unit,
    onOpenWhatChanged: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val quickTargets by viewModel.quickTargets.collectAsStateWithLifecycle()
    val calendarTargets by viewModel.calendarTargets.collectAsStateWithLifecycle()
    val selectedTarget by viewModel.target.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.refresh()
        viewModel.loadTargets()
    }

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
        item { Greeting(onOpenSettings = onOpenSettings) }

        item {
            TargetStrip(
                quickTargets = quickTargets,
                calendarTargets = calendarTargets,
                selected = selectedTarget,
                onSelect = viewModel::selectTarget,
                onSelectEvent = viewModel::selectCalendarTarget,
            )
        }

        when (val current = state) {
            is ForecastUiState.Loading -> item { LoadingHero() }

            is ForecastUiState.Collecting -> item {
                CollectingHero(headline = current.headline, progress = current.progress)
            }

            is ForecastUiState.Ready -> {
                item {
                    ForecastHero(
                        forecast = current.forecast,
                        target = current.target,
                        onOpenCharge = onOpenCharge,
                    )
                }
                item { WhatIsAffectingCard(current.forecast, onOpenWhatChanged) }
                item {
                    NavigationRow(
                        title = "Try a different usage",
                        subtitle = "Navigation, video, gaming, power saving",
                        icon = Icons.Rounded.Settings,
                        onClick = onOpenScenarios,
                        tint = BatteryCastTheme.semanticColors.forecast,
                    )
                }
                item { LearningStatus(current.forecast, onOpenForecast) }
            }
        }
    }
}

@Composable
private fun Greeting(onOpenSettings: () -> Unit) {
    val hour = ZonedDateTime.ofInstant(Instant.now(), ZoneId.systemDefault()).hour
    val greeting = when (hour) {
        in 0..4 -> "Good evening"
        in 5..11 -> "Good morning"
        in 12..17 -> "Good afternoon"
        else -> "Good evening"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = greeting,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Will your phone last?",
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onOpenSettings) {
            Icon(
                Icons.Rounded.Settings,
                contentDescription = "Settings and privacy",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The hero.
 *
 * One ring, one probability sentence, one recommendation, two actions. Nothing else.
 */
@Composable
private fun ForecastHero(
    forecast: BatteryForecast,
    target: TargetSelection,
    onOpenCharge: () -> Unit,
) {
    val semantic = BatteryCastTheme.semanticColors
    val outcome = forecast.target
    val recommendation = forecast.recommendation
    val tone = recommendation.tone
    val toneColor = semantic.forTone(tone)

    HeroSurface(glow = toneColor) {
        ForecastOrb(
            currentPercent = forecast.currentPercent,
            predictedPercent = outcome?.median,
            lowPercent = outcome?.p10,
            highPercent = outcome?.p90,
            reservePercent = outcome?.reservePercent ?: 10.0,
            tone = tone,
            isCharging = forecast.isCharging,
        )

        if (outcome != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = "Likely ${outcome.median.roundToInt()}%",
                style = MetricStyle,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "at ${target.sentenceForm()}",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Recommendations.expectedRange(outcome)?.let { range ->
                Text(
                    text = range,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(Modifier.height(12.dp))
            val animatedProbability by animateFloatAsState(
                targetValue = outcome.survivalProbability.toFloat(),
                animationSpec = tween(700),
                label = "survival",
            )
            Text(
                text = "${(animatedProbability * 100).roundToInt()}% chance of staying above " +
                    "${outcome.reservePercent.roundToInt()}%",
                style = MaterialTheme.typography.titleLarge,
                color = toneColor,
                textAlign = TextAlign.Center,
            )
        }

        Spacer(Modifier.height(6.dp))
        Text(
            text = recommendation.headline,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        recommendation.detail?.let { detail ->
            Text(
                text = detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }

        if (recommendation.action != RecommendedAction.NONE &&
            recommendation.action != RecommendedAction.STILL_LEARNING
        ) {
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = onOpenCharge,
                colors = ButtonDefaults.buttonColors(
                    containerColor = toneColor,
                    contentColor = MaterialTheme.colorScheme.surface,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = when (recommendation.action) {
                        RecommendedAction.CHARGE_NOW -> "Plan charging now"
                        RecommendedAction.CAN_UNPLUG -> "Check the charge plan"
                        else -> "Plan charging"
                    },
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }

        Spacer(Modifier.height(6.dp))
        InlineStatus(
            text = "Measured ${Formatters.relativeAge(forecast.dataQuality.newestObservationAgeMs)}",
            color = if (forecast.dataQuality.isStale) semantic.caution else semantic.learning,
        )
    }
}

@Composable
private fun LoadingHero() {
    HeroSurface {
        Text(
            text = "Reading your battery…",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "Taking a live measurement from this device.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * The honest empty state, as a hero rather than a card.
 *
 * It occupies the same space the forecast will occupy, so the app never looks like it is missing
 * a screen — and it renders no chart and no placeholder number behind the message.
 */
@Composable
private fun CollectingHero(headline: String, progress: Float) {
    val semantic = BatteryCastTheme.semanticColors
    val animated by animateFloatAsState(progress.coerceIn(0f, 1f), tween(700), label = "collecting")

    HeroSurface(glow = semantic.forecast) {
        ForecastOrb(
            currentPercent = (animated * 100).toDouble(),
            predictedPercent = null,
            lowPercent = null,
            highPercent = null,
            reservePercent = 10.0,
            tone = RiskTone.TIGHT,
            caption = "learning",
        )
        Text(
            text = headline,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "A forecast appears once enough real change has been observed. " +
                "Nothing is estimated before then.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun TargetStrip(
    quickTargets: List<TargetSelection>,
    calendarTargets: List<CalendarTarget>,
    selected: TargetSelection?,
    onSelect: (TargetSelection) -> Unit,
    onSelectEvent: (CalendarTarget) -> Unit,
) {
    // The row bleeds past the screen's own horizontal padding and carries its own, so a partially
    // visible chip at the edge reads as "there is more" rather than as a clipping bug.
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = (-20).dp),
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(quickTargets, key = { "q-${it.label}" }) { option ->
            TargetChip(
                text = option.label,
                supporting = Formatters.clockTime(option.atMs),
                selected = selected?.atMs == option.atMs,
                onClick = { onSelect(option) },
            )
        }
        items(calendarTargets, key = { "c-${it.id}" }) { event ->
            TargetChip(
                text = event.title,
                supporting = Formatters.clockTime(event.startMs),
                selected = selected?.atMs == event.startMs,
                onClick = { onSelectEvent(event) },
            )
        }
    }
}

@Composable
private fun TargetChip(
    text: String,
    supporting: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                text = "$text · $supporting",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelLarge,
            )
        },
        shape = MaterialTheme.shapes.small,
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
            selectedLabelColor = MaterialTheme.colorScheme.primary,
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        border = null,
    )
}

/**
 * One driver, not five.
 *
 * The full list lives on What changed; the home screen shows only the strongest reason, because a
 * home screen listing every contributing factor is a diagnostics dashboard again.
 */
@Composable
private fun WhatIsAffectingCard(forecast: BatteryForecast, onOpenWhatChanged: () -> Unit) {
    val driver = forecast.drivers.firstOrNull() ?: return
    val drain = forecast.drain

    InfoCard(onClick = onOpenWhatChanged) {
        SectionHeader(title = "What's affecting your battery")
        Text(
            text = driver.headline,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (drain.currentPercentPerHour != null) {
            CompactRow(
                label = "Using now",
                supporting = drain.baselinePercentPerHour?.let {
                    "usually ${Formatters.ratePerHour(it)} in this state"
                },
                value = Formatters.ratePerHour(drain.currentPercentPerHour),
            )
        }
        Text(
            text = "See the full explanation",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/**
 * Learning status — quiet, on the background, with no card around it.
 *
 * v1 put this in an amber badge at the top of the hero, where it competed with the headline.
 */
@Composable
private fun LearningStatus(forecast: BatteryForecast, onOpenForecast: () -> Unit) {
    val semantic = BatteryCastTheme.semanticColors
    QuietBlock {
        MetricLabel(
            text = when (forecast.maturity) {
                DataMaturity.COLLECTING, DataMaturity.PRELIMINARY -> "Learning your battery"
                DataMaturity.BASIC -> "Live forecast"
                DataMaturity.PERSONALISED -> "Personalised to this phone"
                DataMaturity.MATURE -> "Fully personalised"
            },
            color = semantic.learning,
        )
        Text(
            text = when (forecast.maturity) {
                DataMaturity.COLLECTING, DataMaturity.PRELIMINARY ->
                    "This is an early estimate. Accuracy improves as BatteryCast observes more of " +
                        "your battery."
                DataMaturity.BASIC ->
                    "Based on ${forecast.dataQuality.usableObservationCount} readings from this " +
                        "phone. Day-of-week patterns unlock after a week."
                else ->
                    "Based on ${forecast.dataQuality.usableObservationCount} readings from this phone."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = onOpenForecast) {
            Text("Forecast details", style = MaterialTheme.typography.labelLarge)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "All battery observations and forecasts remain on your device.",
            style = MaterialTheme.typography.bodySmall,
            color = semantic.learning,
        )
    }
}

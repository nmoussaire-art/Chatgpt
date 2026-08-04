package com.batterycast.quant.feature.accuracy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.batterycast.quant.core.ui.components.DetailScaffold
import com.batterycast.quant.core.ui.components.InfoCard
import com.batterycast.quant.core.ui.components.ProbabilityMeter
import com.batterycast.quant.core.ui.components.SectionHeader
import com.batterycast.quant.core.ui.components.CompactRow
import com.batterycast.quant.core.ui.format.Formatters
import com.batterycast.quant.forecasting.accuracy.AccuracyEvaluator
import com.batterycast.quant.forecasting.accuracy.AccuracyReport
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.roundToInt

@HiltViewModel
class AccuracyViewModel @Inject constructor(
    private val accuracyEvaluator: AccuracyEvaluator,
) : ViewModel() {

    private val _report = MutableStateFlow<AccuracyReport?>(null)
    val report: StateFlow<AccuracyReport?> = _report.asStateFlow()

    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    fun load() {
        viewModelScope.launch {
            accuracyEvaluator.evaluatePending()
            _report.value = accuracyEvaluator.report()
            _loaded.value = true
        }
    }
}

/**
 * How well the app has actually done.
 *
 * Deliberately plain-spoken and deliberately unflattering where the data says so. There is no
 * headline accuracy percentage here, because a probabilistic forecaster does not have one: the
 * meaningful questions are how big the errors typically are, whether they lean one way, and
 * whether a stated 70 % chance happens about 70 % of the time.
 */
@Composable
fun AccuracyScreen(
    onBack: () -> Unit,
    viewModel: AccuracyViewModel = hiltViewModel(),
) {
    val report by viewModel.report.collectAsStateWithLifecycle()
    val loaded by viewModel.loaded.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.load() }

    DetailScaffold(
        title = "Model accuracy",
        subtitle = "Scored against outcomes, from forecasts made beforehand.",
        onBack = onBack,
    ) {
        val current = report
        if (current == null) {
            item {
                InfoCard {
                    Text(
                        text = if (loaded) "No forecasts scored yet" else "Loading…",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "Each forecast is filed when it is made and scored once its target " +
                            "time passes and a real reading covers it. Figures appear after " +
                            "${AccuracyReport.MIN_FORECASTS_FOR_REPORT} scored forecasts — sooner " +
                            "would be describing noise.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            return@DetailScaffold
        }

        item {
            InfoCard {
                SectionHeader(title = "Typical error")
                if (current.medianErrorByHorizon.isEmpty()) {
                    Text(
                        "Not enough scored forecasts in any horizon band yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                current.medianErrorByHorizon.entries
                    .sortedBy { it.key.ordinal }
                    .forEach { (bucket, error) ->
                        CompactRow(
                            label = bucket.label,
                            value = "${error.roundToInt()} pts",
                        )
                    }
                val threeHour = current.medianErrorByHorizon.entries
                    .firstOrNull { it.key.label.contains("1–3") }
                if (threeHour != null) {
                    Text(
                        text = "Forecasts made one to three hours ahead have typically been within " +
                            "${threeHour.value.roundToInt()} percentage points of the actual " +
                            "battery level.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                CompactRow(label = "Forecasts scored", value = "${current.evaluatedForecasts}")
            }
        }

        item {
            InfoCard {
                SectionHeader(
                    title = "Bias",
                    subtitle = "Whether the forecast leans optimistic or pessimistic.",
                )
                val bias = current.bias
                CompactRow(
                    label = "Median signed error",
                    value = Formatters.signedPercentPoints(bias),
                )
                Text(
                    text = when {
                        bias == null -> "Not enough data to say."
                        bias > 2 -> "Forecasts have been pessimistic: the battery has typically " +
                            "ended up higher than predicted."
                        bias < -2 -> "Forecasts have been optimistic: the battery has typically " +
                            "ended up lower than predicted."
                        else -> "No meaningful lean in either direction."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            InfoCard {
                SectionHeader(
                    title = "Interval coverage",
                    subtitle = "How often the real outcome fell inside each stated interval.",
                )
                current.intervalCoverage.entries
                    .sortedBy { it.key.ordinal }
                    .forEach { (band, coverage) ->
                        val gap = coverage - band.nominalCoverage
                        CompactRow(
                            label = band.label,
                            supporting = "should be about ${(band.nominalCoverage * 100).roundToInt()}%",
                            value = Formatters.probability(coverage),
                        )
                        if (abs(gap) > 0.1) {
                            Text(
                                text = if (gap < 0) {
                                    "Narrower than it should be — the app has been over-confident here."
                                } else {
                                    "Wider than it needs to be — the app has been over-cautious here."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
            }
        }

        item {
            InfoCard {
                SectionHeader(
                    title = "Probability calibration",
                    subtitle = "When this app says 70%, does it happen about 70% of the time?",
                )
                if (current.calibrationBins.isEmpty()) {
                    Text(
                        "Not enough forecasts in any probability band yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                current.calibrationBins.forEach { bin ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        CompactRow(
                            label = "Said ${(bin.predictedMean * 100).roundToInt()}%",
                            supporting = "${bin.count} forecasts",
                            value = "happened ${Formatters.probability(bin.observedFrequency)}",
                        )
                        ProbabilityMeter(probability = bin.observedFrequency)
                    }
                }
            }
        }

        item {
            Text(
                text = "BatteryCast does not publish a single accuracy percentage. A probabilistic " +
                    "forecast is judged by the size of its errors and whether its stated confidence " +
                    "matches reality, and both are shown above exactly as measured.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

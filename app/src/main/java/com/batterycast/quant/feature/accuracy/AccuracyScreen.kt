package com.batterycast.quant.feature.accuracy

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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.batterycast.quant.core.ui.components.BatteryCastCard
import com.batterycast.quant.core.ui.components.CollectingDataCard
import com.batterycast.quant.core.ui.components.ProbabilityBar
import com.batterycast.quant.core.ui.components.SectionHeader
import com.batterycast.quant.core.ui.components.StatRow
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
fun AccuracyScreen(viewModel: AccuracyViewModel = hiltViewModel()) {
    val report by viewModel.report.collectAsStateWithLifecycle()
    val loaded by viewModel.loaded.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.load() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            SectionHeader(
                title = "Model accuracy",
                subtitle = "Scored against real outcomes, from forecasts made before they happened.",
            )
        }

        val current = report
        if (current == null) {
            item {
                CollectingDataCard(
                    headline = if (loaded) "No forecasts scored yet" else "Loading…",
                    detail = "Each forecast is filed when it is made and scored once its target " +
                        "time passes and a real reading covers it. Accuracy figures appear once " +
                        "${AccuracyReport.MIN_FORECASTS_FOR_REPORT} forecasts have been scored — " +
                        "reporting sooner would be describing noise.",
                )
            }
            return@LazyColumn
        }

        item {
            BatteryCastCard {
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
                        StatRow(
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
                StatRow(label = "Forecasts scored", value = "${current.evaluatedForecasts}")
            }
        }

        item {
            BatteryCastCard {
                SectionHeader(
                    title = "Bias",
                    subtitle = "Whether the forecast leans optimistic or pessimistic.",
                )
                val bias = current.bias
                StatRow(
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
            BatteryCastCard {
                SectionHeader(
                    title = "Interval coverage",
                    subtitle = "How often the real outcome fell inside each stated interval.",
                )
                current.intervalCoverage.entries
                    .sortedBy { it.key.ordinal }
                    .forEach { (band, coverage) ->
                        val gap = coverage - band.nominalCoverage
                        StatRow(
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
            BatteryCastCard {
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
                    ProbabilityBar(
                        probability = bin.observedFrequency,
                        label = "Said ${(bin.predictedMean * 100).roundToInt()}% " +
                            "(${bin.count} forecasts)",
                        trailing = "happened ${Formatters.probability(bin.observedFrequency)}",
                    )
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

package com.ontimequant.ui.accuracy

import androidx.compose.foundation.Canvas
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.ontimequant.data.repository.TripRepository
import com.ontimequant.forecast.AccuracyReport
import com.ontimequant.forecast.CalibrationAnalyser
import com.ontimequant.forecast.CalibrationBucket
import com.ontimequant.ui.components.NoticeBanner
import com.ontimequant.ui.components.ProvenanceChip
import com.ontimequant.ui.components.SectionCard
import com.ontimequant.ui.components.StatBlock
import com.ontimequant.ui.format.formatDuration
import com.ontimequant.ui.format.formatPercent
import com.ontimequant.ui.preview.PreviewData
import com.ontimequant.ui.theme.LocalRiskColors
import com.ontimequant.ui.theme.OnTimeQuantTheme
import com.ontimequant.model.DataProvenance
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.roundToInt

data class AccuracyUiState(
    val loading: Boolean = true,
    val report: AccuracyReport? = null,
)

@HiltViewModel
class AccuracyViewModel @Inject constructor(
    private val trips: TripRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(AccuracyUiState())
    val state: StateFlow<AccuracyUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            trips.tripCount.collect {
                _state.update { current -> current.copy(report = trips.accuracyReport(), loading = false) }
            }
        }
    }
}

/**
 * Model accuracy, stated in language a person can act on.
 *
 * Deliberately absent: any single "accuracy percentage". A forecast that says 90% is
 * *supposed* to be wrong one time in ten; scoring it like a classifier would be
 * meaningless. What is shown instead is typical error, direction of bias, and whether the
 * stated probabilities have actually held up.
 */
@Composable
fun AccuracyScreen(viewModel: AccuracyViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    AccuracyContent(state.report, modifier)
}

/** Stateless body, exposed so previews and screenshot tests can render it directly. */
@Composable
fun AccuracyPreviewContent(report: AccuracyReport?, modifier: Modifier = Modifier) =
    AccuracyContent(report, modifier)

@Composable
private fun AccuracyContent(report: AccuracyReport?, modifier: Modifier = Modifier) {
    if (report == null) return

    LazyColumn(
        modifier = modifier.fillMaxSize().testTag("accuracy_screen"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (!report.hasEnoughData) {
            item {
                NoticeBanner(
                    "Only ${report.sampleSize} scored " +
                        "${if (report.sampleSize == 1) "trip" else "trips"} so far. At least " +
                        "${report.minimumSample} are needed before these figures mean anything. " +
                        "They are shown for transparency, not for confidence.",
                    DataProvenance.DEFAULT,
                )
            }
        }
        if (report.provenance == DataProvenance.DEMO) {
            item {
                NoticeBanner(
                    "These figures are scored against the built-in demo journeys, not real trips " +
                        "of yours.",
                    DataProvenance.DEMO,
                )
            }
        }

        item {
            SectionCard(
                title = "How wrong is it, typically?",
                trailing = { ProvenanceChip(report.provenance, text = "${report.sampleSize} trips") },
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    StatBlock(
                        "Typical error",
                        formatDuration(report.medianAbsoluteErrorSeconds),
                        Modifier.weight(1f),
                        supporting = "median, half of trips are closer",
                    )
                    StatBlock(
                        "Average error",
                        formatDuration(report.meanAbsoluteErrorSeconds),
                        Modifier.weight(1f),
                        supporting = "mean absolute",
                    )
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    biasSentence(report),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        item {
            SectionCard(
                title = "Do the forecast ranges hold up?",
                subtitle = "A well-behaved 80% range should contain the outcome 80% of the time",
            ) {
                CoverageRow("50% range", report.coverage50, 0.50)
                Spacer(Modifier.height(10.dp))
                CoverageRow("80% range", report.coverage80, 0.80)
                Spacer(Modifier.height(10.dp))
                CoverageRow("90% range", report.coverage90, 0.90)
                Spacer(Modifier.height(16.dp))
                Text(
                    coverageSentence(report),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (report.calibration.isNotEmpty()) {
            item {
                SectionCard(
                    title = "Are the probabilities honest?",
                    subtitle = "Predicted chance of being on time, against what actually happened",
                ) {
                    CalibrationChart(report.calibration)
                    Spacer(Modifier.height(16.dp))
                    report.calibration.forEach { bucket ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                "Forecast ${bucket.label}",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                "arrived on time ${formatPercent(bucket.observedFrequency)} " +
                                    "of ${bucket.count}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Perfect calibration means each row's two numbers match. Points on the " +
                            "dashed diagonal above are perfectly calibrated; below it the model was " +
                            "over-confident, above it under-confident.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            SectionCard(title = "Sharpness and skill") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    StatBlock(
                        "Typical range width",
                        formatDuration(report.medianIntervalWidthSeconds),
                        Modifier.weight(1.2f),
                        supporting = "10th to 90th percentile",
                    )
                    StatBlock(
                        "Brier score",
                        String.format(java.util.Locale.getDefault(), "%.3f", report.brierScore),
                        Modifier.weight(1f),
                        supporting = "0 is perfect, 0.25 is a coin flip",
                    )
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    "A narrower range is only better if coverage holds. A model can look sharp by " +
                        "being over-confident, which is why both numbers are shown together.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (report.recentMedianAbsoluteErrorSeconds != null && report.longRunMedianAbsoluteErrorSeconds != null) {
            item {
                SectionCard(title = "Recent versus long-run") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        StatBlock(
                            "Recent trips",
                            formatDuration(report.recentMedianAbsoluteErrorSeconds ?: 0.0),
                            Modifier.weight(1f),
                        )
                        StatBlock(
                            "Earlier trips",
                            formatDuration(report.longRunMedianAbsoluteErrorSeconds ?: 0.0),
                            Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        trendSentence(report),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        if (report.routePerformance.isNotEmpty()) {
            item {
                SectionCard(title = "By route") {
                    report.routePerformance.take(6).forEachIndexed { index, route ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    route.routeKey.substringAfterLast(':').take(42),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    "${route.count} trips",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                "±${formatDuration(route.medianAbsoluteErrorSeconds)}",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        if (index < report.routePerformance.take(6).lastIndex) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
        }

        item {
            SectionCard(title = "How this is measured") {
                Text(
                    "Every completed trip is re-forecast using only the information that existed " +
                        "before it started — a walk-forward backtest. The model is updated with the " +
                        "trip only after its own forecast has been scored, so none of these numbers " +
                        "can be inflated by hindsight.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun CoverageRow(label: String, observed: Double, expected: Double) {
    val risk = LocalRiskColors.current
    val good = abs(observed - expected) <= 0.08
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                "${formatPercent(observed)} (should be ${formatPercent(expected)})",
                style = MaterialTheme.typography.bodyMedium,
                color = if (good) risk.low else risk.elevated,
            )
        }
        Spacer(Modifier.height(6.dp))
        Canvas(Modifier.fillMaxWidth().height(8.dp)) {
            val w = size.width
            drawRoundRect(
                color = risk.gridline,
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f),
            )
            drawRoundRect(
                color = if (good) risk.low else risk.elevated,
                size = androidx.compose.ui.geometry.Size(w * observed.toFloat().coerceIn(0f, 1f), size.height),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f),
            )
            drawLine(
                color = risk.threshold,
                start = Offset(w * expected.toFloat(), 0f),
                end = Offset(w * expected.toFloat(), size.height),
                strokeWidth = 2.5f,
            )
        }
    }
}

/** Reliability diagram: predicted probability against observed frequency. */
@Composable
private fun CalibrationChart(buckets: List<CalibrationBucket>) {
    val risk = LocalRiskColors.current
    val description = buckets.joinToString("; ") {
        "forecast ${it.label}, observed ${(it.observedFrequency * 100).roundToInt()}% of ${it.count}"
    }
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(190.dp)
            .semantics { contentDescription = "Calibration chart. $description" },
    ) {
        val w = size.width
        val h = size.height

        listOf(0.0, 0.25, 0.5, 0.75, 1.0).forEach { g ->
            drawLine(risk.gridline, Offset(0f, h * (1 - g.toFloat())), Offset(w, h * (1 - g.toFloat())), 1f)
        }
        // Perfect-calibration diagonal.
        drawLine(
            color = risk.threshold,
            start = Offset(0f, h),
            end = Offset(w, 0f),
            strokeWidth = 2f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(9f, 7f)),
        )

        // Only buckets with a usable sample are joined by a line. A bucket holding one or
        // two trips carries almost no information, and connecting it would draw a dramatic
        // zig-zag out of pure noise. Those buckets are still plotted, as small dots.
        val joinable = buckets.filter { it.count >= MIN_BUCKET_FOR_LINE }
        if (joinable.size >= 2) {
            val path = androidx.compose.ui.graphics.Path()
            joinable.forEachIndexed { i, bucket ->
                val x = w * bucket.meanPredicted.toFloat().coerceIn(0f, 1f)
                val y = h * (1 - bucket.observedFrequency.toFloat().coerceIn(0f, 1f))
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, risk.curveLine, style = Stroke(width = 3.5f))
        }
        buckets.forEach { bucket ->
            val x = w * bucket.meanPredicted.toFloat().coerceIn(0f, 1f)
            val y = h * (1 - bucket.observedFrequency.toFloat().coerceIn(0f, 1f))
            // Marker area scales with sample size, so a bucket of two trips looks small.
            val radius = 3f + bucket.count.coerceAtMost(20) * 0.5f
            drawCircle(
                color = if (bucket.count >= MIN_BUCKET_FOR_LINE) risk.curveLine
                else risk.curveLine.copy(alpha = 0.45f),
                radius = radius,
                center = Offset(x, y),
            )
        }
    }
}

private fun biasSentence(report: AccuracyReport): String {
    val minutes = report.medianBiasSeconds / 60
    return when {
        abs(minutes) < 1 -> "There is no meaningful systematic bias: the model is about as likely " +
            "to be early as late."
        minutes > 0 -> "The model tends to underestimate journeys by about " +
            "${abs(minutes).roundToInt()} minutes. That correction is already applied to new forecasts."
        else -> "The model tends to overestimate journeys by about " +
            "${abs(minutes).roundToInt()} minutes, so recommendations are slightly cautious."
    }
}

private fun coverageSentence(report: AccuracyReport): String {
    val delta = report.coverage80 - 0.80
    return when {
        abs(delta) <= 0.08 -> "Coverage is close to what it should be, which means the forecast " +
            "ranges are neither over-confident nor padded."
        delta < 0 -> "Actual outcomes fell outside the 80% range more often than they should have. " +
            "The model widens its uncertainty automatically as this is observed."
        else -> "Outcomes landed inside the range more often than necessary, so the ranges have " +
            "been a little wider than strictly needed."
    }
}

private fun trendSentence(report: AccuracyReport): String {
    val recent = report.recentMedianAbsoluteErrorSeconds ?: return ""
    val long = report.longRunMedianAbsoluteErrorSeconds ?: return ""
    val change = recent - long
    return when {
        abs(change) < 60 -> "Forecast error has been stable."
        change > 0 -> "Recent journeys have been harder to predict than earlier ones, by about " +
            "${formatDuration(abs(change))}. Forecast ranges have widened to match."
        else -> "Recent journeys have been more predictable, by about ${formatDuration(abs(change))}."
    }
}

@Preview(name = "Accuracy", showBackground = true, heightDp = 2000)
@Composable
private fun AccuracyPreview() {
    OnTimeQuantTheme {
        AccuracyContent(PreviewData.accuracy)
    }
}

/** Below this many trips a calibration bucket is plotted but not joined to its neighbours. */
private const val MIN_BUCKET_FOR_LINE = 3

@Suppress("unused")
private val minimumSample = CalibrationAnalyser.MINIMUM_SAMPLE

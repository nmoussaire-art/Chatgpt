package com.ontimequant.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Canvas
import com.ontimequant.forecast.CandidateForecast
import com.ontimequant.forecast.DisplayCurve
import com.ontimequant.ui.format.TimeFormatter
import com.ontimequant.ui.format.formatPercent
import com.ontimequant.ui.theme.LocalReducedMotion
import com.ontimequant.ui.theme.LocalRiskColors
import java.time.Instant
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The departure probability curve.
 *
 * Drawn on a Canvas rather than with a charting library for one specific reason: the whole
 * point of this screen is that dragging across it reports the *model's own* numbers for the
 * exact minute under your finger — arrival, range, probability of being ten minutes late.
 * That needs the chart's hit-testing to map back to the underlying [CandidateForecast]
 * objects, not to interpolated plot coordinates. Vico is used elsewhere, on the accuracy
 * screen, where a conventional chart is the right tool.
 *
 * Horizontal axis: departure time. Vertical axis: probability of arriving by the deadline.
 */
@Composable
fun DepartureCurveChart(
    curve: DisplayCurve,
    confidenceTarget: Double,
    recommendedDeparture: Instant?,
    now: Instant,
    formatter: TimeFormatter,
    modifier: Modifier = Modifier,
    onScrub: ((CandidateForecast) -> Unit)? = null,
) {
    if (curve.departures.isEmpty()) return
    val risk = LocalRiskColors.current
    val reduced = LocalReducedMotion.current
    val outline = MaterialTheme.colorScheme.outlineVariant
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val onSurface = MaterialTheme.colorScheme.onSurface

    var selectedIndex by remember(curve) {
        mutableIntStateOf(recommendedDeparture?.let { curve.nearestIndex(it) } ?: 0)
    }
    val reveal by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(if (reduced) 0 else 700),
        label = "curveReveal",
    )

    val selected = curve.points.getOrNull(selectedIndex)
    val firstEpoch = curve.departures.first().epochSecond.toFloat()
    val lastEpoch = curve.departures.last().epochSecond.toFloat()
    val span = (lastEpoch - firstEpoch).coerceAtLeast(1f)

    fun xFor(epoch: Float, width: Float) = ((epoch - firstEpoch) / span) * width
    fun yFor(p: Double, height: Float) = height * (1f - p.toFloat())

    Column(modifier) {
        // Scrubbed readout, always visible so the chart is never a mystery.
        selected?.let { point ->
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            "Leave at ${formatter.clock(point.departure)}",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            "Arrive ${formatter.clock(point.percentiles[10] ?: point.expectedArrival)}" +
                                "–${formatter.clock(point.percentiles[90] ?: point.expectedArrival)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = onSurfaceVariant,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            formatPercent(point.onTimeProbability),
                            style = MaterialTheme.typography.headlineSmall,
                            color = if (point.onTimeProbability >= confidenceTarget) risk.low else risk.elevated,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "${formatPercent(point.probabilityMoreThan10LateSeconds)} over 10 min late",
                            style = MaterialTheme.typography.labelSmall,
                            color = onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        Box(
            Modifier
                .fillMaxWidth()
                .height(220.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                .semantics {
                    contentDescription = buildString {
                        append("Departure probability curve. ")
                        append("Leaving at ${formatter.clock(curve.departures.first())} gives ")
                        append("${formatPercent(curve.probabilities.first())} chance of arriving on time; ")
                        append("leaving at ${formatter.clock(curve.departures.last())} gives ")
                        append("${formatPercent(curve.probabilities.last())}.")
                        recommendedDeparture?.let {
                            append(" Recommended departure ${formatter.clock(it)}.")
                        }
                    }
                }
                .pointerInput(curve) {
                    detectDragGestures { change, _ ->
                        val fraction = (change.position.x / size.width).coerceIn(0f, 1f)
                        selectedIndex = nearestIndexForFraction(curve, fraction)
                        curve.points.getOrNull(selectedIndex)?.let { onScrub?.invoke(it) }
                    }
                }
                .pointerInput(curve) {
                    detectTapGestures { offset ->
                        val fraction = (offset.x / size.width).coerceIn(0f, 1f)
                        selectedIndex = nearestIndexForFraction(curve, fraction)
                        curve.points.getOrNull(selectedIndex)?.let { onScrub?.invoke(it) }
                    }
                },
        ) {
            Canvas(Modifier.fillMaxWidth().height(220.dp).padding(start = 4.dp, end = 4.dp, top = 14.dp, bottom = 26.dp)) {
                val w = size.width
                val h = size.height

                // Horizontal gridlines at 0/25/50/75/100%.
                listOf(0.0, 0.25, 0.5, 0.75, 1.0).forEach { p ->
                    drawLine(
                        color = outline.copy(alpha = 0.5f),
                        start = Offset(0f, yFor(p, h)),
                        end = Offset(w, yFor(p, h)),
                        strokeWidth = 1f,
                    )
                }

                // Confidence threshold.
                drawLine(
                    color = risk.threshold,
                    start = Offset(0f, yFor(confidenceTarget, h)),
                    end = Offset(w, yFor(confidenceTarget, h)),
                    strokeWidth = 2f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)),
                )

                // The curve itself, with a filled area beneath it.
                val visible = (curve.departures.size * reveal).roundToInt().coerceAtLeast(2)
                val line = Path()
                val area = Path()
                for (i in 0 until minOf(visible, curve.departures.size)) {
                    val x = xFor(curve.departures[i].epochSecond.toFloat(), w)
                    val y = yFor(curve.probabilities[i], h)
                    if (i == 0) {
                        line.moveTo(x, y)
                        area.moveTo(x, h)
                        area.lineTo(x, y)
                    } else {
                        line.lineTo(x, y)
                        area.lineTo(x, y)
                    }
                }
                val lastX = xFor(
                    curve.departures[minOf(visible, curve.departures.size) - 1].epochSecond.toFloat(), w,
                )
                area.lineTo(lastX, h)
                area.close()

                drawPath(area, color = risk.curveFill)
                drawPath(line, color = risk.curveLine, style = Stroke(width = 4.5f))

                // "Now" marker, only when it falls inside the window.
                val nowEpoch = now.epochSecond.toFloat()
                if (nowEpoch in firstEpoch..lastEpoch) {
                    val x = xFor(nowEpoch, w)
                    drawLine(
                        color = onSurfaceVariant.copy(alpha = 0.55f),
                        start = Offset(x, 0f), end = Offset(x, h),
                        strokeWidth = 1.5f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 6f)),
                    )
                }

                // Recommended departure.
                recommendedDeparture?.let { rec ->
                    val x = xFor(rec.epochSecond.toFloat(), w)
                    val idx = curve.nearestIndex(rec)
                    val y = yFor(curve.probabilities.getOrElse(idx) { 0.0 }, h)
                    drawLine(
                        color = risk.curveLine.copy(alpha = 0.5f),
                        start = Offset(x, y), end = Offset(x, h),
                        strokeWidth = 2f,
                    )
                    drawCircle(risk.curveLine, radius = 7f, center = Offset(x, y))
                    drawCircle(Color.White, radius = 3f, center = Offset(x, y))
                }

                // Scrub handle.
                selected?.let { point ->
                    val x = xFor(point.departure.epochSecond.toFloat(), w)
                    val y = yFor(curve.probabilities.getOrElse(selectedIndex) { 0.0 }, h)
                    drawLine(
                        color = onSurface,
                        start = Offset(x, 0f), end = Offset(x, h),
                        strokeWidth = 1.5f,
                    )
                    drawCircle(onSurface, radius = 6f, center = Offset(x, y))
                }
            }
        }

        // Axis labels.
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                formatter.clock(curve.departures.first()),
                style = MaterialTheme.typography.labelSmall,
                color = onSurfaceVariant,
            )
            Text(
                "Departure time →  probability of arriving on time",
                style = MaterialTheme.typography.labelSmall,
                color = onSurfaceVariant,
            )
            Text(
                formatter.clock(curve.departures.last()),
                style = MaterialTheme.typography.labelSmall,
                color = onSurfaceVariant,
            )
        }

        if (curve.smoothingApplied) {
            Spacer(Modifier.height(10.dp))
            Text(
                "The simulated curve wobbled by more than simulation noise can explain, so the line " +
                    "shown has been monotonically smoothed. The probabilities used for the recommendation " +
                    "are the raw ones.",
                style = MaterialTheme.typography.labelSmall,
                color = onSurfaceVariant,
            )
        }
    }
}

private fun nearestIndexForFraction(curve: DisplayCurve, fraction: Float): Int {
    if (curve.departures.isEmpty()) return 0
    val first = curve.departures.first().epochSecond
    val last = curve.departures.last().epochSecond
    val target = first + ((last - first) * fraction).toLong()
    var best = 0
    var bestDelta = Long.MAX_VALUE
    curve.departures.forEachIndexed { i, d ->
        val delta = abs(d.epochSecond - target)
        if (delta < bestDelta) { bestDelta = delta; best = i }
    }
    return best
}

/** Compact non-interactive sparkline for the home card. */
@Composable
fun DepartureCurveSparkline(
    curve: DisplayCurve,
    confidenceTarget: Double,
    recommendedDeparture: Instant?,
    modifier: Modifier = Modifier,
) {
    if (curve.departures.size < 2) return
    val risk = LocalRiskColors.current
    val first = curve.departures.first().epochSecond.toFloat()
    val last = curve.departures.last().epochSecond.toFloat()
    val span = (last - first).coerceAtLeast(1f)

    Canvas(modifier.fillMaxWidth().height(56.dp)) {
        val w = size.width
        val h = size.height
        val line = Path()
        val area = Path()
        curve.departures.forEachIndexed { i, d ->
            val x = ((d.epochSecond.toFloat() - first) / span) * w
            val y = h * (1f - curve.probabilities[i].toFloat())
            if (i == 0) { line.moveTo(x, y); area.moveTo(x, h); area.lineTo(x, y) }
            else { line.lineTo(x, y); area.lineTo(x, y) }
        }
        area.lineTo(w, h)
        area.close()
        drawPath(area, risk.curveFill)
        drawPath(line, risk.curveLine, style = Stroke(width = 3f))
        drawLine(
            risk.threshold.copy(alpha = 0.6f),
            Offset(0f, h * (1f - confidenceTarget.toFloat())),
            Offset(w, h * (1f - confidenceTarget.toFloat())),
            strokeWidth = 1.5f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
        )
        recommendedDeparture?.let { rec ->
            val x = ((rec.epochSecond.toFloat() - first) / span) * w
            val idx = curve.nearestIndex(rec)
            val y = h * (1f - curve.probabilities.getOrElse(idx) { 0.0 }.toFloat())
            drawCircle(risk.curveLine, radius = 5f, center = Offset(x, y))
        }
    }
}

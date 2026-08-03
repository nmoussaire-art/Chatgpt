package com.batterycast.quant.core.ui.chart

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import com.batterycast.quant.core.ui.format.Formatters
import com.batterycast.quant.core.ui.theme.BatteryCastTheme
import com.batterycast.quant.forecasting.sim.ForecastPoint
import kotlin.math.roundToInt

/** One horizontal reference line on the chart. */
data class ThresholdLine(val percent: Double, val label: String)

/**
 * The battery survival curve.
 *
 * Drawn as a fan rather than a line, because a single projected line would be a lie: the app does
 * not know what the battery will be at 10 pm, it knows a distribution. The three nested ribbons
 * are the 50 %, 80 % and 90 % prediction intervals straight from the simulated paths, and their
 * widening to the right is the honest visual statement that a forecast eight hours out is far
 * less certain than one an hour out.
 *
 * Implemented directly on Compose's Canvas rather than with a charting library. Nested quantile
 * ribbons with threshold crossings and a scrubbable target marker are not something a generic
 * chart API expresses well, and drawing it here keeps the app free of another dependency.
 */
@Composable
fun ForecastFanChart(
    points: List<ForecastPoint>,
    nowMs: Long,
    modifier: Modifier = Modifier,
    targetMs: Long? = null,
    thresholds: List<ThresholdLine> = DEFAULT_THRESHOLDS,
    height: androidx.compose.ui.unit.Dp = 240.dp,
    onScrub: ((ForecastPoint?) -> Unit)? = null,
) {
    if (points.size < 2) return

    val semantic = BatteryCastTheme.semanticColors
    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current

    var scrubFraction by remember { mutableStateOf<Float?>(null) }
    val animatedReveal by animateFloatAsState(1f, tween(700), label = "chartReveal")

    val axisStyle = TextStyle(fontSize = 10.sp, color = axisColor)

    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .semantics {
                    contentDescription = describeChart(points, targetMs)
                }
                .pointerInput(points) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            scrubFraction = null
                            onScrub?.invoke(null)
                        },
                        onDragCancel = {
                            scrubFraction = null
                            onScrub?.invoke(null)
                        },
                    ) { change, _ ->
                        val fraction = (change.position.x / size.width).coerceIn(0f, 1f)
                        scrubFraction = fraction
                        onScrub?.invoke(points[(fraction * (points.size - 1)).roundToInt()])
                    }
                }
                .pointerInput(points) {
                    detectTapGestures { offset ->
                        val fraction = (offset.x / size.width).coerceIn(0f, 1f)
                        scrubFraction = fraction
                        onScrub?.invoke(points[(fraction * (points.size - 1)).roundToInt()])
                    }
                },
        ) {
            Canvas(modifier = Modifier.fillMaxWidth().height(height)) {
                drawFanChart(
                    points = points,
                    nowMs = nowMs,
                    targetMs = targetMs,
                    thresholds = thresholds,
                    semanticColors = semantic,
                    axisColor = axisColor,
                    textMeasurer = textMeasurer,
                    axisStyle = axisStyle,
                    density = density,
                    reveal = animatedReveal,
                    scrubFraction = scrubFraction,
                )
            }
        }
    }
}

private fun DrawScope.drawFanChart(
    points: List<ForecastPoint>,
    nowMs: Long,
    targetMs: Long?,
    thresholds: List<ThresholdLine>,
    semanticColors: com.batterycast.quant.core.ui.theme.BatteryCastSemanticColors,
    axisColor: Color,
    textMeasurer: TextMeasurer,
    axisStyle: TextStyle,
    density: Density,
    reveal: Float,
    scrubFraction: Float?,
) {
    val leftPadding = with(density) { 30.dp.toPx() }
    val bottomPadding = with(density) { 20.dp.toPx() }
    val topPadding = with(density) { 8.dp.toPx() }
    val plotWidth = size.width - leftPadding
    val plotHeight = size.height - bottomPadding - topPadding
    if (plotWidth <= 0f || plotHeight <= 0f) return

    val startMs = points.first().atMs
    val endMs = points.last().atMs
    val spanMs = (endMs - startMs).coerceAtLeast(1L)

    fun xFor(atMs: Long): Float = leftPadding + plotWidth * ((atMs - startMs).toFloat() / spanMs)
    fun yFor(percent: Double): Float = topPadding + plotHeight * (1f - (percent / 100.0).toFloat())

    // --- Y grid and labels at 0/25/50/75/100. ---
    listOf(0, 25, 50, 75, 100).forEach { percent ->
        val y = yFor(percent.toDouble())
        drawLine(
            color = semanticColors.gridLine,
            start = androidx.compose.ui.geometry.Offset(leftPadding, y),
            end = androidx.compose.ui.geometry.Offset(size.width, y),
            strokeWidth = with(density) { 1.dp.toPx() },
        )
        val layout = textMeasurer.measure("$percent", axisStyle)
        drawText(
            textLayoutResult = layout,
            topLeft = androidx.compose.ui.geometry.Offset(
                x = leftPadding - layout.size.width - with(density) { 5.dp.toPx() },
                y = y - layout.size.height / 2f,
            ),
        )
    }

    val revealed = (points.size * reveal).roundToInt().coerceIn(2, points.size)
    val visible = points.subList(0, revealed)

    // --- Ribbons, widest first so the narrower ones read as denser. ---
    drawBand(visible, semanticColors.band90, ::xFor, ::yFor) { it.p05 to it.p95 }
    drawBand(visible, semanticColors.band80, ::xFor, ::yFor) { it.p10 to it.p90 }
    drawBand(visible, semanticColors.band50, ::xFor, ::yFor) { it.p25 to it.p75 }

    // --- Threshold reference lines. ---
    val dash = PathEffect.dashPathEffect(
        floatArrayOf(with(density) { 4.dp.toPx() }, with(density) { 4.dp.toPx() }),
    )
    thresholds.forEach { threshold ->
        val y = yFor(threshold.percent)
        drawLine(
            color = semanticColors.thresholdLine,
            start = androidx.compose.ui.geometry.Offset(leftPadding, y),
            end = androidx.compose.ui.geometry.Offset(size.width, y),
            strokeWidth = with(density) { 1.dp.toPx() },
            pathEffect = dash,
        )
    }

    // --- Median projection. ---
    val medianPath = Path()
    visible.forEachIndexed { index, point ->
        val x = xFor(point.atMs)
        val y = yFor(point.median)
        if (index == 0) medianPath.moveTo(x, y) else medianPath.lineTo(x, y)
    }
    drawPath(
        path = medianPath,
        color = semanticColors.medianLine,
        style = Stroke(width = with(density) { 2.5.dp.toPx() }),
    )

    // --- Target-time marker. ---
    if (targetMs != null && targetMs in startMs..endMs) {
        val x = xFor(targetMs)
        drawLine(
            color = semanticColors.medianLine.copy(alpha = 0.7f),
            start = androidx.compose.ui.geometry.Offset(x, topPadding),
            end = androidx.compose.ui.geometry.Offset(x, topPadding + plotHeight),
            strokeWidth = with(density) { 1.5.dp.toPx() },
            pathEffect = dash,
        )
        val point = points.minByOrNull { kotlin.math.abs(it.atMs - targetMs) }
        if (point != null) {
            drawCircle(
                color = semanticColors.medianLine,
                radius = with(density) { 4.dp.toPx() },
                center = androidx.compose.ui.geometry.Offset(x, yFor(point.median)),
            )
        }
    }

    // --- Scrub marker. ---
    if (scrubFraction != null) {
        val index = (scrubFraction * (points.size - 1)).roundToInt().coerceIn(0, points.lastIndex)
        val point = points[index]
        val x = xFor(point.atMs)
        drawLine(
            color = axisColor,
            start = androidx.compose.ui.geometry.Offset(x, topPadding),
            end = androidx.compose.ui.geometry.Offset(x, topPadding + plotHeight),
            strokeWidth = with(density) { 1.dp.toPx() },
        )
        drawCircle(
            color = semanticColors.medianLine,
            radius = with(density) { 5.dp.toPx() },
            center = androidx.compose.ui.geometry.Offset(x, yFor(point.median)),
        )
    }

    // --- X axis labels: start, middle, end. ---
    listOf(0f, 0.5f, 1f).forEach { fraction ->
        val atMs = startMs + (spanMs * fraction).toLong()
        val label = Formatters.clockTime(atMs)
        val layout = textMeasurer.measure(label, axisStyle)
        val rawX = xFor(atMs) - layout.size.width / 2f
        val x = rawX.coerceIn(leftPadding, size.width - layout.size.width)
        drawText(
            textLayoutResult = layout,
            topLeft = androidx.compose.ui.geometry.Offset(x, topPadding + plotHeight + with(density) { 4.dp.toPx() }),
        )
    }
}

/** Fills the area between two quantile series. */
private inline fun DrawScope.drawBand(
    points: List<ForecastPoint>,
    color: Color,
    xFor: (Long) -> Float,
    yFor: (Double) -> Float,
    bounds: (ForecastPoint) -> Pair<Double, Double>,
) {
    if (points.size < 2) return
    val path = Path()
    points.forEachIndexed { index, point ->
        val (lower, _) = bounds(point)
        val x = xFor(point.atMs)
        val y = yFor(lower)
        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    for (index in points.indices.reversed()) {
        val (_, upper) = bounds(points[index])
        path.lineTo(xFor(points[index].atMs), yFor(upper))
    }
    path.close()
    drawPath(path = path, color = color)
}

private fun describeChart(points: List<ForecastPoint>, targetMs: Long?): String {
    val first = points.first()
    val last = points.last()
    val targetPoint = targetMs?.let { target -> points.minByOrNull { kotlin.math.abs(it.atMs - target) } }
    return buildString {
        append("Battery forecast chart. ")
        append("Now ${first.median.roundToInt()} percent. ")
        if (targetPoint != null) {
            append(
                "At ${Formatters.clockTime(targetPoint.atMs)}, median " +
                    "${targetPoint.median.roundToInt()} percent, likely range " +
                    "${targetPoint.p10.roundToInt()} to ${targetPoint.p90.roundToInt()} percent. ",
            )
        }
        append(
            "By ${Formatters.clockTime(last.atMs)}, median ${last.median.roundToInt()} percent, " +
                "likely range ${last.p10.roundToInt()} to ${last.p90.roundToInt()} percent.",
        )
    }
}

val DEFAULT_THRESHOLDS = listOf(
    ThresholdLine(20.0, "20%"),
    ThresholdLine(10.0, "10%"),
    ThresholdLine(5.0, "5%"),
)

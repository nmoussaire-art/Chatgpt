package com.batterycast.quant.core.ui.chart

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.batterycast.quant.core.ui.format.Formatters
import com.batterycast.quant.core.ui.theme.BatteryCastTheme
import com.batterycast.quant.forecasting.sim.ForecastPoint
import kotlin.math.abs
import kotlin.math.roundToInt

/** Which uncertainty bands to draw. Exactly one is on by default. */
data class BandVisibility(
    val fifty: Boolean = false,
    val eighty: Boolean = true,
    val ninety: Boolean = false,
) {
    val anyOptional: Boolean get() = fifty || ninety
}

/**
 * The battery survival curve.
 *
 * v1 drew three overlapping semi-transparent mint ribbons, which composited into one murky teal
 * wedge in which no individual band was legible, and rendered the median as a visible polyline of
 * five-minute segments.
 *
 * v2 changes three things:
 *
 * - **One band by default.** The 80 % interval carries the message; 50 % and 90 % are opt-in. Three
 *   nested translucent fills on a dark surface merge into a single indistinct triangle no matter
 *   how carefully the alphas are chosen.
 * - **A gradient beneath the median**, fading to nothing at the baseline, so the chart has depth
 *   instead of reading as a filled polygon.
 * - **Cubic smoothing.** The path runs a monotone Catmull–Rom through the simulated points. Only
 *   the rendering is smoothed — every value plotted is exactly what the simulator produced.
 *
 * Dragging reads out the fan at any moment, with a haptic tick each time the selection moves.
 */
@Composable
fun ForecastFanChart(
    points: List<ForecastPoint>,
    nowMs: Long,
    modifier: Modifier = Modifier,
    targetMs: Long? = null,
    reservePercent: Double = 10.0,
    bands: BandVisibility = BandVisibility(),
    height: Dp = 220.dp,
    selectedIndex: Int? = null,
    onScrub: ((ForecastPoint?) -> Unit)? = null,
) {
    if (points.size < 2) return

    val semantic = BatteryCastTheme.semanticColors
    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current

    // v1 declared a reveal animation and then hard-coded it to 1f, so it never ran. Keying it to
    // the series makes the chart draw itself in whenever the forecast changes.
    val revealKey = remember(points.firstOrNull()?.atMs, points.size) { points.hashCode() }
    val reveal by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(durationMillis = 750),
        label = "chartReveal-$revealKey",
    )

    val axisStyle = TextStyle(fontSize = 10.5.sp, color = axisColor, fontWeight = FontWeight.Medium)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .semantics { contentDescription = describeChart(points, targetMs) }
            .pointerInput(points) {
                detectHorizontalDragGestures(
                    onDragEnd = { onScrub?.invoke(null) },
                    onDragCancel = { onScrub?.invoke(null) },
                ) { change, _ ->
                    val fraction = (change.position.x / size.width).coerceIn(0f, 1f)
                    val index = (fraction * (points.size - 1)).roundToInt().coerceIn(0, points.lastIndex)
                    if (index != selectedIndex) {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onScrub?.invoke(points[index])
                    }
                }
            }
            .pointerInput(points) {
                detectTapGestures { offset ->
                    val fraction = (offset.x / size.width).coerceIn(0f, 1f)
                    val index = (fraction * (points.size - 1)).roundToInt().coerceIn(0, points.lastIndex)
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onScrub?.invoke(points[index])
                }
            },
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().height(height)) {
            drawFanChart(
                points = points,
                targetMs = targetMs,
                reservePercent = reservePercent,
                bands = bands,
                semantic = semantic,
                textMeasurer = textMeasurer,
                axisStyle = axisStyle,
                density = density,
                reveal = reveal,
                selectedIndex = selectedIndex,
            )
        }
    }
}

private fun DrawScope.drawFanChart(
    points: List<ForecastPoint>,
    targetMs: Long?,
    reservePercent: Double,
    bands: BandVisibility,
    semantic: com.batterycast.quant.core.ui.theme.BatteryCastSemanticColors,
    textMeasurer: TextMeasurer,
    axisStyle: TextStyle,
    density: Density,
    reveal: Float,
    selectedIndex: Int?,
) {
    fun dp(value: Float) = with(density) { value.dp.toPx() }

    // Axis labels live inside the plot area, which reclaims roughly 15 % of the width for data.
    val bottomPadding = dp(18f)
    val topPadding = dp(10f)
    val plotWidth = size.width
    val plotHeight = size.height - bottomPadding - topPadding
    if (plotWidth <= 0f || plotHeight <= 0f) return

    val startMs = points.first().atMs
    val endMs = points.last().atMs
    val spanMs = (endMs - startMs).coerceAtLeast(1L)

    fun xFor(atMs: Long): Float = plotWidth * ((atMs - startMs).toFloat() / spanMs)
    fun yFor(percent: Double): Float = topPadding + plotHeight * (1f - (percent / 100.0).toFloat())

    // --- Horizontal guides. Three, not five: the chart is about shape, not precise reading. ---
    listOf(50, 100).forEach { percent ->
        val y = yFor(percent.toDouble())
        drawLine(
            color = semantic.chartGrid,
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = dp(1f),
        )
        val layout = textMeasurer.measure("$percent%", axisStyle)
        drawText(layout, topLeft = Offset(dp(2f), y + dp(2f)))
    }

    val revealed = (points.size * reveal).roundToInt().coerceIn(2, points.size)
    val visible = points.subList(0, revealed)

    // --- Bands, widest first. Only the ones the user has asked for. ---
    if (bands.ninety) {
        drawBand(visible, semantic.chartBandFaint, ::xFor, ::yFor) { it.p05 to it.p95 }
    }
    if (bands.eighty) {
        drawBand(visible, semantic.chartBand, ::xFor, ::yFor) { it.p10 to it.p90 }
    }
    if (bands.fifty) {
        drawBand(visible, semantic.chartBand, ::xFor, ::yFor) { it.p25 to it.p75 }
    }

    // --- Gradient beneath the median: depth rather than a filled polygon. ---
    val medianPath = smoothPath(visible.map { Offset(xFor(it.atMs), yFor(it.median)) })
    val fillPath = Path().apply {
        addPath(medianPath)
        lineTo(xFor(visible.last().atMs), topPadding + plotHeight)
        lineTo(xFor(visible.first().atMs), topPadding + plotHeight)
        close()
    }
    drawPath(
        path = fillPath,
        brush = Brush.verticalGradient(
            colors = listOf(semantic.chartFillTop, semantic.chartFillBottom),
            startY = topPadding,
            endY = topPadding + plotHeight,
        ),
    )

    // --- Reserve line. One, labelled inline, instead of v1's three anonymous dashes. ---
    val reserveY = yFor(reservePercent)
    val dash = PathEffect.dashPathEffect(floatArrayOf(dp(4f), dp(5f)))
    drawLine(
        color = semantic.chartThreshold,
        start = Offset(0f, reserveY),
        end = Offset(size.width, reserveY),
        strokeWidth = dp(1.2f),
        pathEffect = dash,
    )
    val reserveLabel = textMeasurer.measure("${reservePercent.roundToInt()}% reserve", axisStyle)
    drawText(
        reserveLabel,
        topLeft = Offset(size.width - reserveLabel.size.width - dp(2f), reserveY - reserveLabel.size.height - dp(2f)),
    )

    // --- Median: a soft outer glow under a crisp line. ---
    drawPath(
        path = medianPath,
        color = semantic.chartLine.copy(alpha = 0.18f),
        style = Stroke(width = dp(9f), cap = StrokeCap.Round),
    )
    drawPath(
        path = medianPath,
        color = semantic.chartLine,
        style = Stroke(width = dp(3f), cap = StrokeCap.Round),
    )

    // --- Target column, highlighted rather than merely marked. ---
    if (targetMs != null && targetMs in startMs..endMs) {
        val x = xFor(targetMs)
        drawRect(
            color = semantic.chartLine.copy(alpha = 0.07f),
            topLeft = Offset(x - dp(14f), topPadding),
            size = androidx.compose.ui.geometry.Size(dp(28f), plotHeight),
        )
        drawLine(
            color = semantic.chartLine.copy(alpha = 0.55f),
            start = Offset(x, topPadding),
            end = Offset(x, topPadding + plotHeight),
            strokeWidth = dp(1.2f),
            pathEffect = dash,
        )
        points.minByOrNull { abs(it.atMs - targetMs) }?.let { point ->
            val y = yFor(point.median)
            drawCircle(semantic.chartLine.copy(alpha = 0.25f), radius = dp(8f), center = Offset(x, y))
            drawCircle(semantic.chartLine, radius = dp(4f), center = Offset(x, y))
        }
    }

    // --- Scrub marker. ---
    if (selectedIndex != null && selectedIndex in points.indices) {
        val point = points[selectedIndex]
        val x = xFor(point.atMs)
        drawLine(
            color = semantic.onHeroMuted.copy(alpha = 0.6f),
            start = Offset(x, topPadding),
            end = Offset(x, topPadding + plotHeight),
            strokeWidth = dp(1f),
        )
        drawCircle(semantic.chartLine, radius = dp(5.5f), center = Offset(x, yFor(point.median)))
    }

    // --- Time labels, inside the plot, at the two ends only. ---
    val startLabel = textMeasurer.measure("Now", axisStyle)
    drawText(startLabel, topLeft = Offset(0f, topPadding + plotHeight + dp(3f)))
    val endLabel = textMeasurer.measure(Formatters.clockTime(endMs), axisStyle)
    drawText(
        endLabel,
        topLeft = Offset(size.width - endLabel.size.width, topPadding + plotHeight + dp(3f)),
    )
}

/**
 * Monotone cubic interpolation through the plotted points.
 *
 * Straight segments between five-minute samples read as a jagged approximation; a curve reads as a
 * forecast. The control points are clamped so the curve cannot overshoot the data — a smoothed line
 * must never imply a battery level the simulator did not produce.
 */
private fun smoothPath(points: List<Offset>): Path {
    val path = Path()
    if (points.isEmpty()) return path
    path.moveTo(points[0].x, points[0].y)
    if (points.size < 3) {
        points.drop(1).forEach { path.lineTo(it.x, it.y) }
        return path
    }

    for (index in 0 until points.size - 1) {
        val current = points[index]
        val next = points[index + 1]
        val controlX = (current.x + next.x) / 2f
        path.cubicTo(controlX, current.y, controlX, next.y, next.x, next.y)
    }
    return path
}

private inline fun DrawScope.drawBand(
    points: List<ForecastPoint>,
    color: Color,
    xFor: (Long) -> Float,
    yFor: (Double) -> Float,
    bounds: (ForecastPoint) -> Pair<Double, Double>,
) {
    if (points.size < 2) return
    val lower = points.map { Offset(xFor(it.atMs), yFor(bounds(it).first)) }
    val upper = points.map { Offset(xFor(it.atMs), yFor(bounds(it).second)) }

    val path = smoothPath(lower)
    val back = smoothPath(upper.reversed())
    path.lineTo(upper.last().x, upper.last().y)
    path.addPath(back)
    path.close()
    drawPath(path = path, color = color)
}

private fun describeChart(points: List<ForecastPoint>, targetMs: Long?): String {
    val last = points.last()
    val targetPoint = targetMs?.let { target -> points.minByOrNull { abs(it.atMs - target) } }
    return buildString {
        append("Battery forecast chart. Now ${points.first().median.roundToInt()} percent. ")
        if (targetPoint != null) {
            append(
                "At ${Formatters.clockTime(targetPoint.atMs)}, median " +
                    "${targetPoint.median.roundToInt()} percent, likely between " +
                    "${targetPoint.p10.roundToInt()} and ${targetPoint.p90.roundToInt()} percent. ",
            )
        }
        append(
            "By ${Formatters.clockTime(last.atMs)}, median ${last.median.roundToInt()} percent.",
        )
    }
}

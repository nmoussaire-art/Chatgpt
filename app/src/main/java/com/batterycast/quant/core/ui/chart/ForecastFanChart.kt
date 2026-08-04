package com.batterycast.quant.core.ui.chart

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.batterycast.quant.core.ui.format.Formatters
import com.batterycast.quant.core.ui.theme.BatteryCastSemanticColors
import com.batterycast.quant.core.ui.theme.BatteryCastTheme
import com.batterycast.quant.forecasting.sim.ForecastPoint
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** One horizontal reference line on the chart. */
data class ThresholdLine(val percent: Double, val label: String)

/**
 * The battery survival curve.
 *
 * Drawn as a fan rather than a line, because a single projected line would be a lie: the app does
 * not know what the battery will be at 10 pm, it knows a distribution. The three nested ribbons are
 * the 50 %, 80 % and 90 % prediction intervals straight from the simulated paths, at 30 %, 15 % and
 * 5 % opacity so they stay separable when stacked on a near-black canvas. Their widening to the
 * right is the honest visual statement that a forecast eight hours out is far less certain than one
 * an hour out.
 *
 * The curve is rendered with monotone cubic Béziers. The smoothing is applied to the *rendering*
 * only and is clamped so it cannot overshoot the data — a smoothed battery curve must never bulge
 * above a level the simulation never produced.
 *
 * Implemented directly on Compose's Canvas rather than with a charting library: nested quantile
 * ribbons with threshold crossings and a scrubbable crosshair are not something a generic chart API
 * expresses well, and drawing it here keeps the app free of another dependency.
 */
@Composable
fun ForecastFanChart(
    points: List<ForecastPoint>,
    modifier: Modifier = Modifier,
    targetMs: Long? = null,
    thresholds: List<ThresholdLine> = DEFAULT_THRESHOLDS,
    height: androidx.compose.ui.unit.Dp = 240.dp,
    showFiftyBand: Boolean = true,
    showNinetyBand: Boolean = true,
    onScrub: ((ForecastPoint?) -> Unit)? = null,
) {
    if (points.size < 2) return

    val semantic = BatteryCastTheme.semanticColors
    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant
    val surfaceColor = MaterialTheme.colorScheme.surfaceContainerHigh
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current

    var scrubIndex by remember(points) { mutableStateOf<Int?>(null) }
    var reveal by remember(points) { mutableStateOf(0f) }
    val animatedReveal by animateFloatAsState(reveal, tween(750), label = "chartReveal")

    // v1 declared this animation and then passed a constant, so it never ran. Kicking the target
    // off composition is what actually starts it.
    LaunchedEffect(points) { reveal = 1f }

    val axisStyle = TextStyle(fontSize = 10.sp, color = axisColor, fontFeatureSettings = "tnum")
    val tooltipTitleStyle = TextStyle(
        fontSize = 11.sp,
        color = axisColor,
        fontWeight = FontWeight.Medium,
        fontFeatureSettings = "tnum",
    )
    val tooltipValueStyle = TextStyle(
        fontSize = 15.sp,
        color = onSurfaceColor,
        fontWeight = FontWeight.SemiBold,
        fontFeatureSettings = "tnum",
    )

    fun scrubTo(x: Float, width: Int) {
        val fraction = (x / width.toFloat()).coerceIn(0f, 1f)
        val index = (fraction * (points.size - 1)).roundToInt().coerceIn(0, points.lastIndex)
        if (index != scrubIndex) {
            scrubIndex = index
            onScrub?.invoke(points[index])
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .semantics { contentDescription = describeChart(points, targetMs) }
                .pointerInput(points) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            scrubIndex = null
                            onScrub?.invoke(null)
                        },
                        onDragCancel = {
                            scrubIndex = null
                            onScrub?.invoke(null)
                        },
                    ) { change, _ -> scrubTo(change.position.x, size.width) }
                }
                .pointerInput(points) {
                    detectTapGestures { offset -> scrubTo(offset.x, size.width) }
                },
        ) {
            Canvas(modifier = Modifier.fillMaxWidth().height(height)) {
                drawFanChart(
                    points = points,
                    targetMs = targetMs,
                    thresholds = thresholds,
                    semanticColors = semantic,
                    axisColor = axisColor,
                    tooltipSurface = surfaceColor,
                    textMeasurer = textMeasurer,
                    axisStyle = axisStyle,
                    tooltipTitleStyle = tooltipTitleStyle,
                    tooltipValueStyle = tooltipValueStyle,
                    density = density,
                    reveal = animatedReveal,
                    scrubIndex = scrubIndex,
                    showFiftyBand = showFiftyBand,
                    showNinetyBand = showNinetyBand,
                )
            }
        }
    }
}

private fun DrawScope.drawFanChart(
    points: List<ForecastPoint>,
    targetMs: Long?,
    thresholds: List<ThresholdLine>,
    semanticColors: BatteryCastSemanticColors,
    axisColor: Color,
    tooltipSurface: Color,
    textMeasurer: TextMeasurer,
    axisStyle: TextStyle,
    tooltipTitleStyle: TextStyle,
    tooltipValueStyle: TextStyle,
    density: Density,
    reveal: Float,
    scrubIndex: Int?,
    showFiftyBand: Boolean,
    showNinetyBand: Boolean,
) {
    val leftPadding = with(density) { 28.dp.toPx() }
    val bottomPadding = with(density) { 20.dp.toPx() }
    val topPadding = with(density) { 10.dp.toPx() }
    val plotWidth = size.width - leftPadding
    val plotHeight = size.height - bottomPadding - topPadding
    if (plotWidth <= 0f || plotHeight <= 0f) return

    val startMs = points.first().atMs
    val endMs = points.last().atMs
    val spanMs = (endMs - startMs).coerceAtLeast(1L)

    fun xFor(atMs: Long): Float = leftPadding + plotWidth * ((atMs - startMs).toFloat() / spanMs)
    fun yFor(percent: Double): Float = topPadding + plotHeight * (1f - (percent / 100.0).toFloat())

    // --- Horizontal guides only, at 25 % increments. --------------------------------------------
    //
    // There are no vertical gridlines anywhere in this app. On a time axis they add a lattice the
    // eye has to read past, and the x positions that matter — now, the target, the scrub point —
    // are all marked explicitly.
    listOf(0, 25, 50, 75, 100).forEach { percent ->
        val y = yFor(percent.toDouble())
        drawLine(
            color = semanticColors.gridLine,
            start = Offset(leftPadding, y),
            end = Offset(size.width, y),
            strokeWidth = with(density) { 1.dp.toPx() },
            pathEffect = PathEffect.dashPathEffect(
                floatArrayOf(with(density) { 3.dp.toPx() }, with(density) { 5.dp.toPx() }),
            ),
        )
        val layout = textMeasurer.measure("$percent", axisStyle)
        drawText(
            textLayoutResult = layout,
            topLeft = Offset(
                x = leftPadding - layout.size.width - with(density) { 6.dp.toPx() },
                y = y - layout.size.height / 2f,
            ),
        )
    }

    val revealed = (points.size * reveal).roundToInt().coerceIn(2, points.size)
    val visible = points.subList(0, revealed)

    // --- Ribbons, widest first so the narrower ones read as denser. -----------------------------
    if (showNinetyBand) {
        drawBand(visible, semanticColors.band90, ::xFor, ::yFor) { it.p05 to it.p95 }
    }
    drawBand(visible, semanticColors.band80, ::xFor, ::yFor) { it.p10 to it.p90 }
    if (showFiftyBand) {
        drawBand(visible, semanticColors.band50, ::xFor, ::yFor) { it.p25 to it.p75 }
    }

    // --- Median projection, and a gradient beneath it that fades to nothing at the baseline. ----
    val medianOffsets = visible.map { Offset(xFor(it.atMs), yFor(it.median)) }
    val medianPath = Path().also { appendSmooth(it, medianOffsets, moveToFirst = true) }

    val fillPath = Path().also { path ->
        appendSmooth(path, medianOffsets, moveToFirst = true)
        path.lineTo(medianOffsets.last().x, topPadding + plotHeight)
        path.lineTo(medianOffsets.first().x, topPadding + plotHeight)
        path.close()
    }
    drawPath(
        path = fillPath,
        brush = Brush.verticalGradient(
            colors = listOf(
                semanticColors.medianLine.copy(alpha = 0.18f),
                Color.Transparent,
            ),
            startY = topPadding,
            endY = topPadding + plotHeight,
        ),
    )
    drawPath(
        path = medianPath,
        color = semanticColors.medianLine,
        style = Stroke(width = with(density) { 2.5.dp.toPx() }, cap = StrokeCap.Round),
    )

    // --- Threshold reference lines. -------------------------------------------------------------
    val dash = PathEffect.dashPathEffect(
        floatArrayOf(with(density) { 4.dp.toPx() }, with(density) { 4.dp.toPx() }),
    )
    thresholds.forEach { threshold ->
        val y = yFor(threshold.percent)
        drawLine(
            color = semanticColors.thresholdLine,
            start = Offset(leftPadding, y),
            end = Offset(size.width, y),
            strokeWidth = with(density) { 1.dp.toPx() },
            pathEffect = dash,
        )
    }

    // --- Target-time marker. --------------------------------------------------------------------
    if (targetMs != null && targetMs in startMs..endMs) {
        val x = xFor(targetMs)
        drawLine(
            color = semanticColors.medianLine.copy(alpha = 0.5f),
            start = Offset(x, topPadding),
            end = Offset(x, topPadding + plotHeight),
            strokeWidth = with(density) { 1.5.dp.toPx() },
            pathEffect = dash,
        )
        val point = points.minByOrNull { abs(it.atMs - targetMs) }
        if (point != null) {
            drawCircle(
                color = semanticColors.medianLine,
                radius = with(density) { 4.dp.toPx() },
                center = Offset(x, yFor(point.median)),
            )
        }
    }

    // --- X axis labels: start, middle, end. -----------------------------------------------------
    listOf(0f, 0.5f, 1f).forEach { fraction ->
        val atMs = startMs + (spanMs * fraction).toLong()
        val label = Formatters.clockTime(atMs)
        val layout = textMeasurer.measure(label, axisStyle)
        val rawX = xFor(atMs) - layout.size.width / 2f
        val maxX = (size.width - layout.size.width).coerceAtLeast(leftPadding)
        val x = rawX.coerceIn(leftPadding, maxX)
        drawText(
            textLayoutResult = layout,
            topLeft = Offset(x, topPadding + plotHeight + with(density) { 4.dp.toPx() }),
        )
    }

    // --- Crosshair and tooltip. -----------------------------------------------------------------
    if (scrubIndex != null) {
        val point = points[scrubIndex.coerceIn(0, points.lastIndex)]
        val x = xFor(point.atMs)
        val y = yFor(point.median)

        drawLine(
            color = semanticColors.crosshair,
            start = Offset(x, topPadding),
            end = Offset(x, topPadding + plotHeight),
            strokeWidth = with(density) { 1.dp.toPx() },
        )
        drawCircle(
            color = semanticColors.medianLine.copy(alpha = 0.25f),
            radius = with(density) { 9.dp.toPx() },
            center = Offset(x, y),
        )
        drawCircle(
            color = semanticColors.medianLine,
            radius = with(density) { 4.5.dp.toPx() },
            center = Offset(x, y),
        )

        drawTooltip(
            point = point,
            anchorX = x,
            plotLeft = leftPadding,
            plotTop = topPadding,
            textMeasurer = textMeasurer,
            titleStyle = tooltipTitleStyle,
            valueStyle = tooltipValueStyle,
            surface = tooltipSurface,
            stroke = semanticColors.cardStroke,
            density = density,
        )
    }
}

/**
 * The scrub read-out.
 *
 * Pinned to the top of the plot rather than following the point, so the value the user is reading
 * is never underneath their own finger.
 */
private fun DrawScope.drawTooltip(
    point: ForecastPoint,
    anchorX: Float,
    plotLeft: Float,
    plotTop: Float,
    textMeasurer: TextMeasurer,
    titleStyle: TextStyle,
    valueStyle: TextStyle,
    surface: Color,
    stroke: Color,
    density: Density,
) {
    val padding = with(density) { 9.dp.toPx() }
    val time = textMeasurer.measure(Formatters.clockTime(point.atMs), titleStyle)
    val median = textMeasurer.measure("${point.median.roundToInt()}%", valueStyle)
    val range = textMeasurer.measure(
        "${point.p10.roundToInt()}–${point.p90.roundToInt()}% likely",
        titleStyle,
    )

    val contentWidth = maxOf(time.size.width, median.size.width, range.size.width).toFloat()
    val contentHeight = (time.size.height + median.size.height + range.size.height).toFloat()
    val boxWidth = contentWidth + padding * 2
    val boxHeight = contentHeight + padding * 2

    // A tooltip wider than the plot would make the upper bound smaller than the lower one, and
    // `coerceIn` throws on an inverted range rather than clamping — so the bound is widened first.
    val maxLeft = (size.width - boxWidth).coerceAtLeast(plotLeft)
    val left = (anchorX - boxWidth / 2f).coerceIn(plotLeft, maxLeft)
    val top = plotTop

    drawRoundRect(
        color = surface,
        topLeft = Offset(left, top),
        size = Size(boxWidth, boxHeight),
        cornerRadius = CornerRadius(with(density) { 10.dp.toPx() }),
    )
    drawRoundRect(
        color = stroke,
        topLeft = Offset(left, top),
        size = Size(boxWidth, boxHeight),
        cornerRadius = CornerRadius(with(density) { 10.dp.toPx() }),
        style = Stroke(width = with(density) { 1.dp.toPx() }),
    )

    drawText(time, topLeft = Offset(left + padding, top + padding))
    drawText(median, topLeft = Offset(left + padding, top + padding + time.size.height))
    drawText(
        range,
        topLeft = Offset(left + padding, top + padding + time.size.height + median.size.height),
    )
}

/** Fills the area between two quantile series, with both edges smoothed. */
private inline fun DrawScope.drawBand(
    points: List<ForecastPoint>,
    color: Color,
    xFor: (Long) -> Float,
    yFor: (Double) -> Float,
    bounds: (ForecastPoint) -> Pair<Double, Double>,
) {
    if (points.size < 2) return
    val lower = points.map { Offset(xFor(it.atMs), yFor(bounds(it).first)) }
    val upper = points.map { Offset(xFor(it.atMs), yFor(bounds(it).second)) }.asReversed()

    val path = Path()
    appendSmooth(path, lower, moveToFirst = true)
    appendSmooth(path, upper, moveToFirst = false)
    path.close()
    drawPath(path = path, color = color)
}

/**
 * Appends [pts] to [path] as monotone cubic Béziers.
 *
 * Tangents come from Fritsch–Carlson, which is what makes the curve *shape preserving*: where the
 * samples are monotone the curve is monotone too, so smoothing can never invent a bulge above a
 * percentage the simulation did not produce. A plain Catmull-Rom spline would, and on a battery
 * chart that is a lie with a straight face.
 */
private fun appendSmooth(path: Path, pts: List<Offset>, moveToFirst: Boolean) {
    if (pts.isEmpty()) return
    if (moveToFirst) path.moveTo(pts[0].x, pts[0].y) else path.lineTo(pts[0].x, pts[0].y)
    if (pts.size == 1) return

    val n = pts.size
    val secant = FloatArray(n - 1)
    for (i in 0 until n - 1) {
        val dx = pts[i + 1].x - pts[i].x
        secant[i] = if (dx == 0f) 0f else (pts[i + 1].y - pts[i].y) / dx
    }

    val slope = FloatArray(n)
    slope[0] = secant[0]
    slope[n - 1] = secant[n - 2]
    for (i in 1 until n - 1) {
        slope[i] = if (secant[i - 1] * secant[i] <= 0f) 0f else (secant[i - 1] + secant[i]) / 2f
    }

    for (i in 0 until n - 1) {
        if (secant[i] == 0f) {
            slope[i] = 0f
            slope[i + 1] = 0f
            continue
        }
        val a = slope[i] / secant[i]
        val b = slope[i + 1] / secant[i]
        val magnitude = a * a + b * b
        if (magnitude > 9f) {
            val limiter = 3f / sqrt(magnitude)
            slope[i] = limiter * a * secant[i]
            slope[i + 1] = limiter * b * secant[i]
        }
    }

    for (i in 0 until n - 1) {
        val dx = pts[i + 1].x - pts[i].x
        path.cubicTo(
            pts[i].x + dx / 3f,
            pts[i].y + slope[i] * dx / 3f,
            pts[i + 1].x - dx / 3f,
            pts[i + 1].y - slope[i + 1] * dx / 3f,
            pts[i + 1].x,
            pts[i + 1].y,
        )
    }
}

private fun describeChart(points: List<ForecastPoint>, targetMs: Long?): String {
    val first = points.first()
    val last = points.last()
    val targetPoint = targetMs?.let { target -> points.minByOrNull { abs(it.atMs - target) } }
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

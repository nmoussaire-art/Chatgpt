package com.batterycast.quant.core.ui.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.MaterialTheme
import com.batterycast.quant.core.ui.format.Formatters
import com.batterycast.quant.core.ui.theme.BatteryCastTheme
import com.batterycast.quant.telemetry.model.BatteryObservation
import kotlin.math.roundToInt

/**
 * Observed battery level over time.
 *
 * This is the one chart in the app that shows no forecast at all — only what was actually
 * measured. Charging periods are shaded, screen-on periods are marked along the bottom, and gaps
 * in the record are left as gaps rather than joined by a line, because Android defers background
 * work and a straight line across a two-hour hole would imply an observation that never happened.
 */
@Composable
fun BatteryHistoryChart(
    observations: List<BatteryObservation>,
    modifier: Modifier = Modifier,
    height: Dp = 220.dp,
) {
    if (observations.size < 2) return

    val semantic = BatteryCastTheme.semanticColors
    val lineColor = MaterialTheme.colorScheme.primary
    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val axisStyle = TextStyle(fontSize = 10.sp, color = axisColor)

    val startMs = observations.first().timestampMs
    val endMs = observations.last().timestampMs
    val spanMs = (endMs - startMs).coerceAtLeast(1L)

    val description = "Battery history from ${Formatters.dateAndTime(startMs)} to " +
        "${Formatters.dateAndTime(endMs)}, ranging from " +
        "${observations.minOf { it.batteryPercent }.roundToInt()} to " +
        "${observations.maxOf { it.batteryPercent }.roundToInt()} percent."

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .semantics { contentDescription = description },
    ) {
        val leftPadding = with(density) { 30.dp.toPx() }
        val bottomPadding = with(density) { 24.dp.toPx() }
        val topPadding = with(density) { 8.dp.toPx() }
        val plotWidth = size.width - leftPadding
        val plotHeight = size.height - bottomPadding - topPadding
        if (plotWidth <= 0f || plotHeight <= 0f) return@Canvas

        fun xFor(atMs: Long) = leftPadding + plotWidth * ((atMs - startMs).toFloat() / spanMs)
        fun yFor(percent: Double) = topPadding + plotHeight * (1f - (percent / 100.0).toFloat())

        listOf(0, 50, 100).forEach { percent ->
            val y = yFor(percent.toDouble())
            drawLine(
                color = semantic.gridLine,
                start = Offset(leftPadding, y),
                end = Offset(size.width, y),
                strokeWidth = with(density) { 1.dp.toPx() },
            )
            val layout = textMeasurer.measure("$percent", axisStyle)
            drawText(
                textLayoutResult = layout,
                topLeft = Offset(
                    leftPadding - layout.size.width - with(density) { 5.dp.toPx() },
                    y - layout.size.height / 2f,
                ),
            )
        }

        // Charging periods, shaded behind the line.
        var chargeStart: Long? = null
        observations.forEachIndexed { index, observation ->
            if (observation.isCharging && chargeStart == null) {
                chargeStart = observation.timestampMs
            }
            val isLast = index == observations.lastIndex
            if ((!observation.isCharging || isLast) && chargeStart != null) {
                val from = xFor(chargeStart!!)
                val to = xFor(observation.timestampMs)
                drawRect(
                    color = semantic.chargingAccent.copy(alpha = 0.16f),
                    topLeft = Offset(from, topPadding),
                    size = androidx.compose.ui.geometry.Size((to - from).coerceAtLeast(1f), plotHeight),
                )
                chargeStart = null
            }
        }

        // Screen-on strip along the bottom.
        val stripTop = topPadding + plotHeight + with(density) { 3.dp.toPx() }
        val stripHeight = with(density) { 3.dp.toPx() }
        for (index in 0 until observations.size - 1) {
            if (!observations[index].screenInteractive) continue
            val from = xFor(observations[index].timestampMs)
            val to = xFor(observations[index + 1].timestampMs)
            drawRect(
                color = lineColor.copy(alpha = 0.55f),
                topLeft = Offset(from, stripTop),
                size = androidx.compose.ui.geometry.Size((to - from).coerceAtLeast(1f), stripHeight),
            )
        }

        // The level line, broken wherever the record has a real gap.
        val path = Path()
        var penDown = false
        observations.forEachIndexed { index, observation ->
            val x = xFor(observation.timestampMs)
            val y = yFor(observation.batteryPercent)
            val previous = observations.getOrNull(index - 1)
            val gapTooLarge = previous != null &&
                (observation.timestampMs - previous.timestampMs > GAP_BREAK_MS ||
                    observation.bootSessionId != previous.bootSessionId)

            if (!penDown || gapTooLarge) {
                path.moveTo(x, y)
                penDown = true
            } else {
                path.lineTo(x, y)
            }
        }
        drawPath(path = path, color = lineColor, style = Stroke(width = with(density) { 2.dp.toPx() }))

        listOf(0f, 0.5f, 1f).forEach { fraction ->
            val atMs = startMs + (spanMs * fraction).toLong()
            val layout = textMeasurer.measure(Formatters.clockTime(atMs), axisStyle)
            val x = (xFor(atMs) - layout.size.width / 2f)
                .coerceIn(leftPadding, size.width - layout.size.width)
            drawText(
                textLayoutResult = layout,
                topLeft = Offset(x, stripTop + stripHeight + with(density) { 3.dp.toPx() }),
            )
        }
    }
}

/** Longer than this between readings and the line is broken rather than interpolated. */
private const val GAP_BREAK_MS = 45 * 60 * 1000L

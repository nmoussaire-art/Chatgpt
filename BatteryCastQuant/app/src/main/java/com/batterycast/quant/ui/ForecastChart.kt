package com.batterycast.quant.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.batterycast.quant.model.BatteryObservation
import com.batterycast.quant.model.ForecastPoint
import kotlin.math.abs

@Composable
fun ForecastChart(
    points: List<ForecastPoint>,
    target: Long,
    modifier: Modifier = Modifier,
    onSelect: (ForecastPoint) -> Unit = {}
) {
    if (points.size < 2) return
    val primary = androidx.compose.material3.MaterialTheme.colorScheme.primary
    val band90 = androidx.compose.material3.MaterialTheme.colorScheme.primaryContainer.copy(alpha = .35f)
    val band50 = androidx.compose.material3.MaterialTheme.colorScheme.primary.copy(alpha = .18f)
    val grid = androidx.compose.material3.MaterialTheme.colorScheme.outlineVariant

    Canvas(
        modifier = modifier
            .height(280.dp)
            .fillMaxWidth()
            .pointerInput(points) {
                detectTapGestures { position ->
                    val ratio = (position.x / size.width).coerceIn(0f, 1f)
                    val timestamp = points.first().timestamp +
                        ((points.last().timestamp - points.first().timestamp) * ratio).toLong()
                    onSelect(points.minBy { abs(it.timestamp - timestamp) })
                }
            }
    ) {
        fun x(timestamp: Long) =
            ((timestamp - points.first().timestamp).toFloat() /
                (points.last().timestamp - points.first().timestamp).coerceAtLeast(1)) * size.width

        fun y(value: Double) = size.height - (value.toFloat() / 100f) * size.height

        listOf(20, 10, 5).forEach { value ->
            drawLine(
                grid,
                Offset(0f, y(value.toDouble())),
                Offset(size.width, y(value.toDouble())),
                strokeWidth = 1.dp.toPx()
            )
        }

        fun band(
            low: (ForecastPoint) -> Double,
            high: (ForecastPoint) -> Double,
            color: androidx.compose.ui.graphics.Color
        ) {
            val path = Path()
            points.forEachIndexed { index, value ->
                if (index == 0) path.moveTo(x(value.timestamp), y(high(value)))
                else path.lineTo(x(value.timestamp), y(high(value)))
            }
            points.asReversed().forEach { value -> path.lineTo(x(value.timestamp), y(low(value))) }
            path.close()
            drawPath(path, color)
        }

        band({ it.p05 }, { it.p95 }, band90)
        band({ it.p25 }, { it.p75 }, band50)

        val median = Path()
        points.forEachIndexed { index, value ->
            if (index == 0) median.moveTo(x(value.timestamp), y(value.median))
            else median.lineTo(x(value.timestamp), y(value.median))
        }
        drawPath(median, primary, style = Stroke(3.dp.toPx()))

        val targetX = x(target)
        drawLine(
            primary.copy(alpha = .65f),
            Offset(targetX, 0f),
            Offset(targetX, size.height),
            strokeWidth = 1.dp.toPx()
        )
    }
}

@Composable
fun BatteryHistoryChart(
    observations: List<BatteryObservation>,
    modifier: Modifier = Modifier
) {
    if (observations.size < 2) return
    val sorted = remember(observations) { observations.sortedBy { it.timestamp } }
    val rates = remember(sorted) {
        sorted.zipWithNext().mapNotNull { (a, b) ->
            val hours = (b.timestamp - a.timestamp) / 3_600_000.0
            if (hours <= 0.0 || a.isCharging || b.isCharging || b.batteryPercent > a.batteryPercent) null
            else (a.batteryPercent - b.batteryPercent) / hours
        }.sorted()
    }
    val typicalRate = remember(rates) {
        if (rates.isEmpty()) null
        else if (rates.size % 2 == 0) (rates[rates.size / 2 - 1] + rates[rates.size / 2]) / 2.0
        else rates[rates.size / 2]
    }

    val primary = androidx.compose.material3.MaterialTheme.colorScheme.primary
    val charging = androidx.compose.material3.MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = .45f)
    val screen = androidx.compose.material3.MaterialTheme.colorScheme.secondary
    val unusual = androidx.compose.material3.MaterialTheme.colorScheme.errorContainer.copy(alpha = .35f)
    val grid = androidx.compose.material3.MaterialTheme.colorScheme.outlineVariant

    Canvas(modifier = modifier.height(260.dp).fillMaxWidth()) {
        val firstTime = sorted.first().timestamp
        val span = (sorted.last().timestamp - firstTime).coerceAtLeast(1L)
        fun x(timestamp: Long) = ((timestamp - firstTime).toFloat() / span) * size.width
        fun y(percent: Double) = size.height - (percent.toFloat() / 100f) * size.height

        listOf(20, 10, 5).forEach { value ->
            drawLine(grid, Offset(0f, y(value.toDouble())), Offset(size.width, y(value.toDouble())))
        }

        sorted.zipWithNext().forEach { (a, b) ->
            val left = x(a.timestamp)
            val right = x(b.timestamp).coerceAtLeast(left + 1f)
            if (a.isCharging || b.isCharging) {
                drawRect(charging, topLeft = Offset(left, 0f), size = Size(right - left, size.height))
            } else {
                val hours = (b.timestamp - a.timestamp) / 3_600_000.0
                val rate = if (hours > 0.0 && b.batteryPercent <= a.batteryPercent) {
                    (a.batteryPercent - b.batteryPercent) / hours
                } else null
                if (rate != null && typicalRate != null && rate > typicalRate * 1.5 && rate > typicalRate + 1.0) {
                    drawRect(unusual, topLeft = Offset(left, 0f), size = Size(right - left, size.height))
                }
            }
            if (a.screenInteractive) {
                drawLine(screen, Offset(left, size.height - 5.dp.toPx()), Offset(right, size.height - 5.dp.toPx()), 4.dp.toPx())
            }
        }

        val path = Path()
        sorted.forEachIndexed { index, observation ->
            if (index == 0) path.moveTo(x(observation.timestamp), y(observation.batteryPercent))
            else path.lineTo(x(observation.timestamp), y(observation.batteryPercent))
        }
        drawPath(path, primary, style = Stroke(3.dp.toPx()))
    }
}

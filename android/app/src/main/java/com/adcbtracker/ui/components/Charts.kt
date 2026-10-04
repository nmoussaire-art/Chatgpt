package com.adcbtracker.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.adcbtracker.data.CategoryTotal
import com.adcbtracker.data.Cycle
import com.adcbtracker.ui.theme.Emerald
import com.adcbtracker.ui.theme.Ink2
import com.adcbtracker.ui.theme.Ink3
import com.adcbtracker.ui.theme.TextHigh
import com.adcbtracker.ui.theme.TextMid
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Cumulative spend through the cycle (this vs previous). Touch/drag to read a day;
 * [visibleDays] limits the current line to days that have happened.
 */
@Composable
fun DailyPaceChart(
    cycle: Cycle,
    current: LongArray,
    previous: LongArray,
    visibleDays: Int,
) {
    val days = maxOf(current.size, previous.size, 1)
    val maxVal = maxOf(current.take(visibleDays).maxOrNull() ?: 0L, previous.maxOrNull() ?: 0L, 1L).toFloat()
    var selected by remember(cycle) { mutableStateOf<Int?>(null) }
    val fmt = DateTimeFormatter.ofPattern("EEE d MMM", Locale.US)

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(Emerald))
            Spacer(Modifier.width(6.dp))
            Text("This cycle", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.width(16.dp))
            Box(Modifier.size(8.dp).clip(CircleShape).background(TextMid))
            Spacer(Modifier.width(6.dp))
            Text("Previous", style = MaterialTheme.typography.labelMedium)
        }
        Spacer(Modifier.height(12.dp))

        val sel = selected
        Column(Modifier.fillMaxWidth().height(46.dp)) {
            if (sel != null) {
                val date = cycle.start.plusDays(sel.toLong())
                val cur = if (sel < visibleDays) current.getOrNull(sel) else null
                Text("Day ${sel + 1} · ${date.format(fmt)}", style = MaterialTheme.typography.labelMedium, color = TextMid)
                Row {
                    Text(
                        "This: ${cur?.let { formatMoney(it) } ?: "—"}",
                        style = MaterialTheme.typography.labelLarge,
                        color = Emerald,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                    )
                    Text(
                        "Previous: ${previous.getOrNull(sel)?.let { formatMoney(it) } ?: "—"}",
                        style = MaterialTheme.typography.labelLarge,
                        color = TextHigh,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.End,
                        maxLines = 1,
                    )
                }
            } else {
                Text("Touch or drag on the chart to compare a day", style = MaterialTheme.typography.labelMedium, color = TextMid)
            }
        }

        Canvas(
            Modifier
                .fillMaxWidth()
                .height(180.dp)
                .pointerInput(days) {
                    detectTapGestures { o -> selected = ((o.x / size.width) * (days - 1)).toInt().coerceIn(0, days - 1) }
                }
                .pointerInput(days) {
                    detectDragGestures { change, _ ->
                        selected = ((change.position.x / size.width) * (days - 1)).toInt().coerceIn(0, days - 1)
                    }
                }
        ) {
            val w = size.width
            val h = size.height
            fun x(i: Int) = if (days <= 1) 0f else w * i / (days - 1)
            fun y(v: Long) = h - (v / maxVal) * h * 0.92f

            for (g in 1..3) {
                val gy = h * g / 4f
                drawLine(Ink2, Offset(0f, gy), Offset(w, gy), strokeWidth = 1f)
            }

            if (previous.isNotEmpty()) {
                val p = Path()
                previous.forEachIndexed { i, v -> if (i == 0) p.moveTo(x(i), y(v)) else p.lineTo(x(i), y(v)) }
                drawPath(p, TextMid.copy(alpha = 0.7f), style = Stroke(width = 3f, cap = StrokeCap.Round))
            }

            val n = minOf(visibleDays, current.size)
            if (n > 0) {
                val line = Path()
                val fill = Path()
                for (i in 0 until n) {
                    val px = x(i)
                    val py = y(current[i])
                    if (i == 0) {
                        line.moveTo(px, py); fill.moveTo(px, h); fill.lineTo(px, py)
                    } else {
                        line.lineTo(px, py); fill.lineTo(px, py)
                    }
                }
                fill.lineTo(x(n - 1), h)
                fill.close()
                drawPath(fill, Brush.verticalGradient(listOf(Emerald.copy(alpha = 0.25f), Emerald.copy(alpha = 0f))))
                drawPath(line, Emerald, style = Stroke(width = 5f, cap = StrokeCap.Round))
            }

            selected?.let { s ->
                drawLine(TextHigh.copy(alpha = 0.5f), Offset(x(s), 0f), Offset(x(s), h), strokeWidth = 2f)
                if (s < n) drawCircle(Emerald, 9f, Offset(x(s), y(current[s])))
                previous.getOrNull(s)?.let { drawCircle(TextMid, 7f, Offset(x(s), y(it))) }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            val dm = DateTimeFormatter.ofPattern("d MMM", Locale.US)
            Text(cycle.start.format(dm), style = MaterialTheme.typography.labelSmall, color = TextMid)
            Spacer(Modifier.weight(1f))
            Text(cycle.lastDay.format(dm), style = MaterialTheme.typography.labelSmall, color = TextMid)
        }
    }
}

/** Seven vertical bars, Monday first; the highest is highlighted. */
@Composable
fun WeekdayBars(values: LongArray) {
    val labels = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    val max = (values.maxOrNull() ?: 0L).coerceAtLeast(1L)
    val top = values.indices.maxByOrNull { values[it] }
    Row(
        Modifier.fillMaxWidth().height(160.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        values.forEachIndexed { i, v ->
            Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                Text(if (v > 0) formatCompact(v) else "", style = MaterialTheme.typography.labelSmall, color = TextMid)
                Spacer(Modifier.height(4.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.78f * (v.toFloat() / max).coerceAtLeast(0.02f))
                        .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp, bottomStart = 3.dp, bottomEnd = 3.dp))
                        .background(if (i == top && v > 0) Emerald else Ink3)
                )
                Spacer(Modifier.height(6.dp))
                Text(labels[i], style = MaterialTheme.typography.labelSmall, color = TextMid)
            }
        }
    }
}

/** Totals for recent cycles; tap a bar to jump to that cycle. */
@Composable
fun CycleHistoryBars(history: List<Pair<Cycle, Long>>, selected: Cycle, onSelect: (Int) -> Unit) {
    val max = (history.maxOfOrNull { it.second } ?: 0L).coerceAtLeast(1L)
    Row(
        Modifier.fillMaxWidth().height(170.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        history.forEachIndexed { i, (cycle, total) ->
            val isSel = cycle == selected
            Column(
                Modifier.weight(1f).fillMaxHeight().clickable { onSelect(history.size - 1 - i) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom,
            ) {
                if (isSel) Text(formatCompact(total), style = MaterialTheme.typography.labelSmall, color = Emerald, maxLines = 1)
                Spacer(Modifier.height(4.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.75f * (total.toFloat() / max).coerceAtLeast(0.02f))
                        .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                        .background(if (isSel) Emerald else Ink3)
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    cycle.start.format(DateTimeFormatter.ofPattern("MMM", Locale.US)).take(3),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isSel) TextHigh else TextMid,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Stacked share bar plus one row per category. */
@Composable
fun CategoryBreakdown(rows: List<CategoryTotal>, total: Long, onClick: (com.adcbtracker.data.Category?) -> Unit) {
    if (rows.isEmpty() || total <= 0) {
        Text("No spending recorded for this cycle.", style = MaterialTheme.typography.bodyMedium, color = TextMid)
        return
    }
    Row(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(50)).background(Ink2)) {
        rows.forEach { r ->
            val f = r.totalMinor.toFloat() / total
            if (f > 0.005f) Box(Modifier.weight(f).fillMaxHeight().background(parseColor(r.category?.colorHex, TextMid)))
        }
    }
    Spacer(Modifier.height(16.dp))
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        rows.forEach { r ->
            val f = r.totalMinor.toFloat() / total
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onClick(r.category) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CategoryBadge(r.category, size = 34)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row {
                        Text(r.category?.name ?: "Uncategorized", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                        Text(formatMoney(r.totalMinor), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(6.dp))
                    ProgressBar(f, parseColor(r.category?.colorHex, TextMid))
                    Spacer(Modifier.height(2.dp))
                    Text(
                        String.format(Locale.US, "%.0f%% · %d txn%s", f * 100, r.count, if (r.count == 1) "" else "s"),
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMid,
                    )
                }
            }
        }
    }
}

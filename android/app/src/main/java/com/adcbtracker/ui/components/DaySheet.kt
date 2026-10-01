package com.adcbtracker.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.adcbtracker.data.DaySummary
import com.adcbtracker.data.spendMinor
import com.adcbtracker.ui.theme.Emerald
import com.adcbtracker.ui.theme.Ink1
import com.adcbtracker.ui.theme.Ink2
import com.adcbtracker.ui.theme.Ink3
import com.adcbtracker.ui.theme.TextMid
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Bottom sheet listing everything spent on one day, with arrows to step through days. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DayDetailSheet(
    day: LocalDate,
    summary: DaySummary?,
    today: LocalDate,
    largeThresholdMinor: Long,
    onDismiss: () -> Unit,
    onChangeDay: (LocalDate) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Ink1,
        dragHandle = {
            Box(
                Modifier
                    .padding(top = 12.dp, bottom = 4.dp)
                    .size(width = 40.dp, height = 4.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Ink3)
            )
        },
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onChangeDay(day.minusDays(1)) }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous day")
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(relativeDayLabel(day, today).substringBefore(","), style = MaterialTheme.typography.titleLarge)
                    Text(
                        day.format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.US)),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMid,
                    )
                }
                IconButton(onClick = { onChangeDay(day.plusDays(1)) }, enabled = day.isBefore(today)) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next day")
                }
            }

            Spacer(Modifier.height(16.dp))
            val total = summary?.totalMinor ?: 0L
            val count = summary?.count ?: 0
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    formatMoney(total),
                    style = MaterialTheme.typography.headlineLarge,
                    color = if (total > 0) MaterialTheme.colorScheme.onSurface else TextMid,
                )
                Text(
                    if (count == 0) "No spending" else "$count transaction${if (count == 1) "" else "s"}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMid,
                )
            }

            val txs = summary?.transactions.orEmpty()
            val byCategory = txs.filter { it.tx.amountMinor > 0 }
                .groupBy { it.category?.id }
                .map { (_, l) -> l.first().category to l.sumOf { it.tx.spendMinor } }
                .sortedByDescending { it.second }
            if (byCategory.size > 1) {
                Spacer(Modifier.height(16.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(byCategory) { (cat, amount) ->
                        Row(
                            Modifier
                                .clip(RoundedCornerShape(50))
                                .background(parseColor(cat?.colorHex, Ink3).copy(alpha = 0.16f))
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(cat?.icon ?: "📦")
                            Spacer(Modifier.width(6.dp))
                            Text(formatMoney(amount), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            if (txs.isEmpty()) {
                EmptyState(if (day.isAfter(today)) "🗓️" else "🎉", "Nothing spent", "No card transactions were recorded on this day.")
            } else {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp)) {
                    items(txs, key = { it.tx.id }) { item ->
                        TransactionRow(item, largeThresholdMinor)
                        HorizontalDivider(color = Ink2)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/**
 * Billing-cycle calendar: one cell per day of the cycle, aligned to Monday-first weeks,
 * shaded by spend. Tapping a day calls [onDayClick].
 */
@Composable
fun SpendingCalendar(
    cycleStart: LocalDate,
    dailyMinor: LongArray,
    today: LocalDate,
    selected: LocalDate?,
    onDayClick: (LocalDate) -> Unit,
) {
    val max = (dailyMinor.maxOrNull() ?: 0L).coerceAtLeast(1L)
    val leading = cycleStart.dayOfWeek.value - 1
    val cells = leading + dailyMinor.size
    val rows = (cells + 6) / 7
    val monthFmt = DateTimeFormatter.ofPattern("MMM", Locale.US)

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth()) {
            listOf("M", "T", "W", "T", "F", "S", "S").forEach {
                Text(
                    it,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    color = TextMid,
                    textAlign = TextAlign.Center,
                )
            }
        }
        for (r in 0 until rows) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (c in 0 until 7) {
                    val idx = r * 7 + c - leading
                    Box(Modifier.weight(1f)) {
                        if (idx in dailyMinor.indices) {
                            val date = cycleStart.plusDays(idx.toLong())
                            val amount = dailyMinor[idx]
                            val future = date.isAfter(today)
                            val intensity = if (amount > 0) 0.25f + 0.75f * (amount.toFloat() / max) else 0f
                            val bg = when {
                                future -> Ink1
                                amount == 0L -> Ink2
                                else -> Emerald.copy(alpha = intensity)
                            }
                            val isSelected = date == selected
                            val isToday = date == today
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .height(54.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(bg)
                                    .then(
                                        if (isSelected || isToday) Modifier.border(
                                            if (isSelected) 2.dp else 1.dp,
                                            if (isSelected) MaterialTheme.colorScheme.onSurface else Emerald,
                                            RoundedCornerShape(12.dp),
                                        ) else Modifier
                                    )
                                    .then(if (!future) Modifier.clickable { onDayClick(date) } else Modifier),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                val dark = intensity > 0.6f
                                val textColor = when {
                                    future -> Ink3
                                    dark -> MaterialTheme.colorScheme.onPrimary
                                    else -> MaterialTheme.colorScheme.onSurface
                                }
                                if (date.dayOfMonth == 1 || idx == 0) {
                                    Text(
                                        date.format(monthFmt).uppercase(Locale.US),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = textColor.copy(alpha = 0.8f),
                                    )
                                }
                                Text("${date.dayOfMonth}", style = MaterialTheme.typography.labelLarge, color = textColor)
                                if (amount > 0) {
                                    Text(
                                        formatCompact(amount),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = textColor.copy(alpha = 0.85f),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

package com.adcbtracker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adcbtracker.data.Stats
import com.adcbtracker.data.toUaeDate
import com.adcbtracker.ui.components.AppCard
import com.adcbtracker.ui.components.CategoryBadge
import com.adcbtracker.ui.components.CategoryBreakdown
import com.adcbtracker.ui.components.CycleHistoryBars
import com.adcbtracker.ui.components.DailyPaceChart
import com.adcbtracker.ui.components.EmptyState
import com.adcbtracker.ui.components.Pill
import com.adcbtracker.ui.components.SectionTitle
import com.adcbtracker.ui.components.SpendingCalendar
import com.adcbtracker.ui.components.TransactionRow
import com.adcbtracker.ui.components.WeekdayBars
import com.adcbtracker.ui.components.formatMoney
import com.adcbtracker.ui.theme.Coral
import com.adcbtracker.ui.theme.Emerald
import com.adcbtracker.ui.theme.Ink2
import com.adcbtracker.ui.theme.TextMid
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun InsightsScreen(vm: MainViewModel) {
    val state by vm.insights.collectAsStateWithLifecycle()
    val threshold by vm.largeThreshold.collectAsStateWithLifecycle()
    val selectedDay by vm.selectedDay.collectAsStateWithLifecycle()
    val s = state

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { Text("Insights", style = MaterialTheme.typography.headlineMedium) }
        if (s == null) return@LazyColumn

        val cur = s.current
        val prev = s.previous
        val isCurrentCycle = s.cyclesAgo == 0
        val elapsedDays = if (isCurrentCycle) cur.cycle.dayIndex(s.today) + 1 else cur.cycle.lengthDays

        item {
            AppCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = vm::previousCycle) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous cycle")
                    }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            if (isCurrentCycle) "CURRENT CYCLE" else "BILLING CYCLE",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMid,
                        )
                        Text(cur.cycle.label(), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                    }
                    IconButton(onClick = vm::nextCycle, enabled = !isCurrentCycle) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next cycle")
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(formatMoney(cur.totalMinor), style = MaterialTheme.typography.headlineLarge)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (prev.totalMinor > 0) {
                        val compareTo = if (isCurrentCycle) Stats.spentThrough(prev, elapsedDays - 1) else prev.totalMinor
                        if (compareTo > 0) {
                            val pct = ((cur.totalMinor - compareTo) * 100.0 / compareTo).roundToInt()
                            Pill("${if (pct > 0) "▲" else "▼"} ${abs(pct)}%", if (pct > 0) Coral else Emerald)
                            Spacer(Modifier.width(8.dp))
                        }
                    }
                    Text(
                        "${cur.count} txns · prev ${formatMoney(prev.totalMinor)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMid,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (isCurrentCycle && cur.totalMinor > 0) {
                    val perDay = cur.totalMinor / elapsedDays.coerceAtLeast(1)
                    Spacer(Modifier.height(14.dp))
                    HorizontalDivider(color = Ink2)
                    Spacer(Modifier.height(12.dp))
                    Row {
                        Column(Modifier.weight(1f)) {
                            Text("PROJECTED AT STATEMENT", style = MaterialTheme.typography.labelSmall, color = TextMid)
                            Text("~${formatMoney(perDay * cur.cycle.lengthDays)}", style = MaterialTheme.typography.titleMedium)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("DAILY AVERAGE", style = MaterialTheme.typography.labelSmall, color = TextMid)
                            Text(formatMoney(perDay), style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
        }

        if (cur.count == 0 && prev.count == 0) {
            item { AppCard { EmptyState("📊", "No insights yet", "Insights appear here once transactions are captured.") } }
            return@LazyColumn
        }

        item {
            AppCard {
                SectionTitle("Spending calendar", "Tap a day to see what you paid")
                Spacer(Modifier.height(16.dp))
                SpendingCalendar(
                    cycleStart = cur.cycle.start,
                    dailyMinor = cur.dailyMinor,
                    today = s.today,
                    selected = selectedDay,
                    onDayClick = vm::openDay,
                )
            }
        }

        item {
            AppCard {
                SectionTitle("Daily pace", "This cycle vs the one before")
                Spacer(Modifier.height(12.dp))
                DailyPaceChart(cur.cycle, cur.cumulative(), prev.cumulative(), elapsedDays)
            }
        }

        item {
            AppCard {
                SectionTitle("By day of week", "Average spend per weekday")
                Spacer(Modifier.height(16.dp))
                WeekdayBars(cur.weekdayAvgMinor)
            }
        }

        item {
            AppCard {
                SectionTitle("By category", "Where most of your money went")
                Spacer(Modifier.height(16.dp))
                CategoryBreakdown(cur.categories, cur.totalMinor)
            }
        }

        if (cur.topMerchants.isNotEmpty()) {
            item {
                AppCard {
                    SectionTitle("Top merchants")
                    Spacer(Modifier.height(8.dp))
                    cur.topMerchants.forEachIndexed { i, m ->
                        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                            CategoryBadge(m.category, size = 34)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(m.merchant, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${m.count} txn${if (m.count == 1) "" else "s"}", style = MaterialTheme.typography.labelSmall, color = TextMid)
                            }
                            Text(formatMoney(m.totalMinor), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                        }
                        if (i < cur.topMerchants.lastIndex) HorizontalDivider(color = Ink2)
                    }
                }
            }
        }

        if (cur.biggest.isNotEmpty()) {
            item {
                AppCard {
                    SectionTitle("Biggest expenses", "Tap to open that day")
                    Spacer(Modifier.height(4.dp))
                    cur.biggest.forEachIndexed { i, t ->
                        TransactionRow(t, threshold, showDate = true) { vm.openDay(t.tx.tsEpochMillis.toUaeDate()) }
                        if (i < cur.biggest.lastIndex) HorizontalDivider(color = Ink2)
                    }
                }
            }
        }

        item {
            AppCard {
                SectionTitle("Cycle history", "Tap a bar to view that cycle")
                Spacer(Modifier.height(16.dp))
                CycleHistoryBars(s.history, cur.cycle) { ago -> vm.goToCycle(ago) }
            }
        }
    }
}

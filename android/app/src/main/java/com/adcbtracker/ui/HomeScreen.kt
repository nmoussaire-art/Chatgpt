package com.adcbtracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adcbtracker.data.DaySummary
import com.adcbtracker.data.Prefs
import com.adcbtracker.data.toUaeDate
import com.adcbtracker.ui.components.AppCard
import com.adcbtracker.ui.components.BudgetPanel
import com.adcbtracker.ui.components.ProjectionPanel
import com.adcbtracker.ui.components.CategoryBadge
import com.adcbtracker.ui.components.EmptyState
import com.adcbtracker.ui.components.Pill
import com.adcbtracker.ui.components.ProgressBar
import com.adcbtracker.ui.components.SectionTitle
import com.adcbtracker.ui.components.TransactionRow
import com.adcbtracker.ui.components.formatMoney
import com.adcbtracker.ui.components.parseColor
import com.adcbtracker.ui.components.relativeDayLabel
import com.adcbtracker.ui.theme.Amber
import com.adcbtracker.ui.theme.Coral
import com.adcbtracker.ui.theme.Emerald
import com.adcbtracker.ui.theme.Ink1
import com.adcbtracker.ui.theme.Ink2
import com.adcbtracker.ui.theme.TextMid
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun HomeScreen(vm: MainViewModel) {
    val state by vm.home.collectAsStateWithLifecycle()
    val threshold by vm.largeThreshold.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val today = state.today

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Column {
                Text("ADCB Tracker", style = MaterialTheme.typography.headlineMedium)
                Text(
                    today.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.US)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMid,
                )
            }
        }

        if (!state.hasAnyTransactions) {
            item {
                AppCard {
                    EmptyState("🧾", "No transactions yet", "They'll appear here once ADCB alerts are captured. You can also import past ones in Settings › Import from SMS.")
                }
            }
            return@LazyColumn
        }

        val lastCaptured = Prefs.getLastCapturedTx(context)
        if (lastCaptured > 0 && System.currentTimeMillis() - lastCaptured > 3L * 24 * 3600 * 1000) {
            item {
                AppCard {
                    Text("⚠️ No new transactions captured recently", style = MaterialTheme.typography.titleMedium, color = Amber)
                    Text("Check notification access and battery settings in Settings.", style = MaterialTheme.typography.bodyMedium, color = TextMid)
                }
            }
        }

        item { CycleHeroCard(state) }

        if (state.uncategorizedCount > 0 && state.cycle != null) {
            item {
                AppCard(onClick = { vm.openCategory(null, state.cycle!!.cycle) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("🏷️", fontSize = 26.sp)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "${state.uncategorizedCount} transaction${if (state.uncategorizedCount == 1) "" else "s"} need a category",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text("Tap to sort them out — it takes a few seconds", style = MaterialTheme.typography.bodySmall, color = TextMid)
                        }
                        Text("›", style = MaterialTheme.typography.headlineMedium, color = Emerald)
                    }
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("Today", state.todayTotal, state.todayCount, Modifier.weight(1f)) { vm.openDay(today) }
                StatTile("Yesterday", state.yesterdayTotal, state.yesterdayCount, Modifier.weight(1f)) { vm.openDay(today.minusDays(1)) }
                StatTile("This week", state.weekTotal, state.weekCount, Modifier.weight(1f), null)
            }
        }

        state.availableLimitMinor?.let { limit ->
            item {
                AppCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("AVAILABLE CREDIT", style = MaterialTheme.typography.labelSmall, color = TextMid)
                            Text(formatMoney(limit), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        }
                        Text(
                            "as of ${timeAgo(state.availableLimitAt)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMid,
                        )
                    }
                }
            }
        }

        item {
            AppCard {
                SectionTitle("Daily spending", "Last 14 days · tap a day for details")
                Spacer(Modifier.height(12.dp))
                DailyList(state.recentDays, today) { vm.openDay(it.date) }
            }
        }

        state.cycle?.takeIf { it.categories.isNotEmpty() }?.let { cycle ->
            item {
                AppCard {
                    SectionTitle("By category", "This billing cycle · tap to see transactions")
                    Spacer(Modifier.height(14.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        cycle.categories.take(5).forEach { c ->
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { vm.openCategory(c.category?.id, cycle.cycle) },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CategoryBadge(c.category, size = 34)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Row {
                                        Text(c.category?.name ?: "Uncategorized", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                                        Text(formatMoney(c.totalMinor), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                                    }
                                    Spacer(Modifier.height(6.dp))
                                    ProgressBar(c.totalMinor.toFloat() / cycle.totalMinor.coerceAtLeast(1), parseColor(c.category?.colorHex, TextMid))
                                }
                            }
                        }
                    }
                }
            }
        }

        if (state.recent.isNotEmpty()) {
            item {
                AppCard {
                    SectionTitle("Recent")
                    Spacer(Modifier.height(4.dp))
                    state.recent.forEachIndexed { i, t ->
                        TransactionRow(t, threshold, showDate = true) { vm.openTransaction(t.tx.id) }
                        if (i < state.recent.lastIndex) HorizontalDivider(color = Ink2)
                    }
                }
            }
        }
    }
}

@Composable
private fun CycleHeroCard(state: HomeState) {
    val cycle = state.cycle ?: return
    val dayNo = cycle.cycle.dayIndex(state.today) + 1
    val prev = state.previousCycleSameDay
    AppCard {
        Text("THIS BILLING CYCLE", style = MaterialTheme.typography.labelMedium, color = TextMid)
        Text(cycle.cycle.label(), style = MaterialTheme.typography.bodyMedium, color = TextMid)
        Spacer(Modifier.height(8.dp))
        Text(formatMoney(cycle.totalMinor), style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (prev > 0) {
                val pct = ((cycle.totalMinor - prev) * 100.0 / prev).roundToInt()
                val up = pct > 0
                Pill("${if (up) "▲" else "▼"} ${abs(pct)}%", if (up) Coral else Emerald)
                Spacer(Modifier.width(8.dp))
                Text("vs ${formatMoney(prev)} at this point last cycle", style = MaterialTheme.typography.bodySmall, color = TextMid)
            } else {
                Text("${cycle.count} transactions", style = MaterialTheme.typography.bodySmall, color = TextMid)
            }
        }
        Spacer(Modifier.height(14.dp))
        ProgressBar(dayNo.toFloat() / cycle.cycle.lengthDays, Emerald)
        Spacer(Modifier.height(6.dp))
        Row {
            Text("Day $dayNo of ${cycle.cycle.lengthDays}", style = MaterialTheme.typography.labelSmall, color = TextMid, modifier = Modifier.weight(1f))
            val left = cycle.cycle.lengthDays - dayNo
            Text(
                if (left == 0) "Statement closes today" else "$left day${if (left == 1) "" else "s"} to statement",
                style = MaterialTheme.typography.labelSmall,
                color = TextMid,
            )
        }
        state.projection?.takeIf { cycle.totalMinor > 0 }?.let { p ->
            Spacer(Modifier.height(14.dp))
            HorizontalDivider(color = Ink2)
            Spacer(Modifier.height(12.dp))
            ProjectionPanel(p)
        }
        if (state.budgetMinor > 0) {
            Spacer(Modifier.height(14.dp))
            BudgetPanel(state.budgetMinor, cycle.totalMinor, cycle.cycle.lengthDays - dayNo + 1, state.projection?.totalMinor)
        }
    }
}

@Composable
private fun StatTile(label: String, total: Long, count: Int, modifier: Modifier, onClick: (() -> Unit)?) {
    Column(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Ink1)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(14.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = TextMid)
        Spacer(Modifier.height(6.dp))
        Text(formatMoney(total).removePrefix("AED "), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1)
        Text("$count txn${if (count == 1) "" else "s"}", style = MaterialTheme.typography.labelSmall, color = TextMid)
    }
}

@Composable
fun DailyList(days: List<DaySummary>, today: java.time.LocalDate, onClick: (DaySummary) -> Unit) {
    val max = (days.maxOfOrNull { it.totalMinor } ?: 0L).coerceAtLeast(1L)
    Column {
        days.forEachIndexed { i, d ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onClick(d) }
                    .padding(vertical = 10.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.width(118.dp)) {
                    Text(
                        relativeDayLabel(d.date, today).let { if (it.contains(",")) d.date.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.US)) else it },
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(
                        if (d.count == 0) "No spending" else "${d.count} txn${if (d.count == 1) "" else "s"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMid,
                    )
                }
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    ProgressBar(d.totalMinor.toFloat() / max, if (d.date == today) Emerald else Emerald.copy(alpha = 0.6f))
                }
                Text(
                    formatMoney(d.totalMinor),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (d.totalMinor == 0L) TextMid else MaterialTheme.colorScheme.onSurface,
                )
            }
            if (i < days.lastIndex) HorizontalDivider(color = Ink2)
        }
    }
}

private fun timeAgo(epochMillis: Long): String {
    val mins = (System.currentTimeMillis() - epochMillis) / 60_000
    return when {
        mins < 1 -> "just now"
        mins < 60 -> "${mins}m ago"
        mins < 60 * 24 -> "${mins / 60}h ago"
        else -> "${mins / (60 * 24)}d ago"
    }
}

package com.adcbtracker.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.adcbtracker.data.Projection
import com.adcbtracker.ui.theme.Amber
import com.adcbtracker.ui.theme.Coral
import com.adcbtracker.ui.theme.Emerald
import com.adcbtracker.ui.theme.TextMid

/** Statement projection with a one-line explanation of how it was worked out. */
@Composable
fun ProjectionPanel(p: Projection) {
    Column(Modifier.fillMaxWidth()) {
        Text("PROJECTED AT STATEMENT", style = MaterialTheme.typography.labelSmall, color = TextMid)
        Text("≈ ${formatMoney(p.totalMinor)}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        val parts = buildString {
            append("${formatMoney(p.spentSoFarMinor)} spent")
            if (p.daysLeft > 0) append(" + usual ${formatMoney(p.usualDailyMinor)}/day × ${p.daysLeft} day${if (p.daysLeft == 1) "" else "s"} left")
            append(".")
            if (p.oneOffCount > 0) {
                append(" ${p.oneOffCount} one-off${if (p.oneOffCount == 1) "" else "s"} ≥ ${formatMoney(p.oneOffThresholdMinor)} ")
                append("(${formatMoney(p.oneOffsMinor)}) not treated as daily spending.")
            }
            if (p.basedOnCycles > 0) append(" Pace blended with your last ${p.basedOnCycles} cycle${if (p.basedOnCycles == 1) "" else "s"}.")
        }
        Text(parts, style = MaterialTheme.typography.bodySmall, color = TextMid)
    }
}

/** Budget progress with how much can still be spent per day. */
@Composable
fun BudgetPanel(budgetMinor: Long, spentMinor: Long, daysLeftIncludingToday: Int, projectedMinor: Long?) {
    val remaining = budgetMinor - spentMinor
    val fraction = spentMinor.toFloat() / budgetMinor
    val over = remaining < 0
    val color = when {
        over -> Coral
        fraction > 0.85f -> Amber
        else -> Emerald
    }
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("BUDGET", style = MaterialTheme.typography.labelSmall, color = TextMid, modifier = Modifier.weight(1f))
            Text(
                "${formatMoney(spentMinor)} of ${formatMoney(budgetMinor)}",
                style = MaterialTheme.typography.labelMedium,
                color = TextMid,
                textAlign = TextAlign.End,
            )
        }
        Spacer(Modifier.height(6.dp))
        ProgressBar(fraction, color)
        Spacer(Modifier.height(6.dp))
        val line = when {
            over -> "Over budget by ${formatMoney(-remaining)}"
            daysLeftIncludingToday > 0 ->
                "Safe to spend ${formatMoney(remaining / daysLeftIncludingToday)}/day for the next $daysLeftIncludingToday day${if (daysLeftIncludingToday == 1) "" else "s"}"
            else -> "${formatMoney(remaining)} left"
        }
        Text(line, style = MaterialTheme.typography.bodyMedium, color = color, fontWeight = FontWeight.SemiBold)
        if (!over && projectedMinor != null && projectedMinor > budgetMinor) {
            Text(
                "At your usual pace you'd go over by about ${formatMoney(projectedMinor - budgetMinor)}",
                style = MaterialTheme.typography.bodySmall,
                color = Amber,
            )
        }
    }
}

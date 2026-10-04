package com.adcbtracker.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import com.adcbtracker.ui.theme.Ink2
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

/** Statement projection; tapping the ⓘ reveals how the number was worked out. */
@Composable
fun ProjectionPanel(p: Projection) {
    var showHow by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().animateContentSize()) {
        Row(
            Modifier.clip(RoundedCornerShape(8.dp)).clickable { showHow = !showHow },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("PROJECTED AT STATEMENT", style = MaterialTheme.typography.labelSmall, color = TextMid)
            Spacer(Modifier.width(6.dp))
            Icon(
                Icons.Outlined.Info,
                contentDescription = "How is this calculated?",
                tint = if (showHow) Emerald else TextMid,
                modifier = Modifier.size(16.dp),
            )
        }
        Text(
            "≈ ${formatMoney(p.totalMinor)}",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.clickable { showHow = !showHow },
        )
        if (showHow) {
            Spacer(Modifier.height(8.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Ink2)
                    .padding(12.dp)
            ) {
                HowRow("Spent so far", formatMoney(p.spentSoFarMinor))
                if (p.daysLeft > 0) {
                    HowRow("Usual spending", "${formatMoney(p.usualDailyMinor)}/day × ${p.daysLeft}d")
                }
                if (p.expectedMoreOneOffsMinor > 0) HowRow("Big purchases still expected", formatMoney(p.expectedMoreOneOffsMinor))
                Spacer(Modifier.height(6.dp))
                val notes = buildList {
                    add("Purchases ≥ ${formatMoney(p.oneOffThresholdMinor)} count as big purchases and aren't spread across every day.")
                    if (p.oneOffCount > 0) add("This cycle: ${p.oneOffCount} big purchase${if (p.oneOffCount == 1) "" else "s"} (${formatMoney(p.oneOffsMinor)}).")
                    if (p.basedOnCycles > 0) {
                        val cycles = "your last ${p.basedOnCycles} cycle${if (p.basedOnCycles == 1) "" else "s"}"
                        add(
                            if (p.typicalOneOffsPerCycleMinor > 0) "Usual pace is blended with $cycles, which typically had ${formatMoney(p.typicalOneOffsPerCycleMinor)} of big purchases."
                            else "Usual pace is blended with $cycles."
                        )
                    }
                }
                notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = TextMid) }
            }
        }
    }
}

@Composable
private fun HowRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = TextMid, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
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

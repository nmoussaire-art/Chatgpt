package com.deadlineguardian.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deadlineguardian.data.DeadlineKind
import com.deadlineguardian.data.DeadlineWithItem
import com.deadlineguardian.data.Urgency
import com.deadlineguardian.ui.GuardianViewModel
import com.deadlineguardian.ui.Urgent
import com.deadlineguardian.ui.displayName
import com.deadlineguardian.ui.formatMoney
import com.deadlineguardian.ui.icon
import com.deadlineguardian.ui.pretty

@Composable
fun HomeScreen(
    viewModel: GuardianViewModel,
    onScan: () -> Unit,
    onOpen: (Long) -> Unit,
    onSettings: () -> Unit
) {
    val buckets by viewModel.buckets.collectAsStateWithLifecycle()
    val (moneyAtRisk, currency) = buckets.moneyAtRisk()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onScan,
                icon = { Icon(Icons.Filled.CameraAlt, contentDescription = null) },
                text = { Text("Scan") }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Deadline Guardian", style = MaterialTheme.typography.headlineLarge)
                        Text(
                            "Nothing expires without warning",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onSettings) {
                        Icon(
                            Icons.Filled.Settings,
                            contentDescription = "Settings",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
            }

            // Only worth a banner when there's actually money on the clock.
            if (moneyAtRisk > 0) {
                item {
                    MoneyBanner(moneyAtRisk, currency)
                    Spacer(Modifier.height(6.dp))
                }
            }

            if (buckets.isEmpty) {
                item { EmptyState(onScan) }
            }

            section("Act now", buckets.overdue + buckets.actNow, onOpen, viewModel)
            section("Coming up", buckets.soon, onOpen, viewModel)
            section("Later", buckets.later, onOpen, viewModel)
            section("Handled", buckets.done, onOpen, viewModel)
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.section(
    title: String,
    entries: List<DeadlineWithItem>,
    onOpen: (Long) -> Unit,
    viewModel: GuardianViewModel
) {
    if (entries.isEmpty()) return
    item(key = "header-$title") {
        Text(
            text = "$title · ${entries.size}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 14.dp, bottom = 2.dp)
        )
    }
    items(entries, key = { it.deadline.id }) { entry ->
        DeadlineCard(
            entry = entry,
            onClick = { onOpen(entry.deadline.id) },
            onToggleDone = { viewModel.markDone(entry.deadline) }
        )
    }
}

@Composable
private fun MoneyBanner(minor: Long, currency: String?) {
    val dark = isSystemInDarkTheme()
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (dark) Urgent.RedContainerDark else Urgent.RedContainerLight,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "STILL RECOVERABLE",
                style = MaterialTheme.typography.labelSmall,
                color = Urgent.Red
            )
            Spacer(Modifier.height(4.dp))
            Text(
                formatMoney(minor, currency),
                style = MaterialTheme.typography.headlineLarge,
                color = Urgent.Red,
                fontWeight = FontWeight.Bold
            )
            Text(
                "if you act before these windows close",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DeadlineCard(
    entry: DeadlineWithItem,
    onClick: () -> Unit,
    onToggleDone: () -> Unit
) {
    val dark = isSystemInDarkTheme()
    val urgency = entry.urgency()
    val accent = when (urgency) {
        Urgency.OVERDUE, Urgency.ACT_NOW -> Urgent.Red
        Urgency.SOON -> Urgent.Amber
        Urgency.LATER -> Urgent.Slate
        Urgency.DONE -> Urgent.Green
    }
    val container = when (urgency) {
        Urgency.OVERDUE, Urgency.ACT_NOW ->
            if (dark) Urgent.RedContainerDark else Urgent.RedContainerLight
        else -> MaterialTheme.colorScheme.surface
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = container,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // A coloured rail reads as urgency far faster than any text label.
            Box(
                Modifier
                    .width(3.dp)
                    .height(44.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(accent)
            )
            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        entry.item.kind.icon(),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        entry.deadline.kind.displayName().uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = accent
                    )
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    entry.item.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    buildString {
                        append(if (urgency == Urgency.DONE) "handled" else entry.countdownText())
                        append(" · ")
                        append(entry.deadline.date.pretty())
                        entry.deadline.moneyAtRiskMinor
                            ?.takeIf { entry.deadline.kind == DeadlineKind.RETURN_WINDOW }
                            ?.let { append(" · ${formatMoney(it, entry.item.currency)}") }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.width(10.dp))
            DoneToggle(done = urgency == Urgency.DONE, accent = accent, onClick = onToggleDone)
        }
    }
}

@Composable
private fun DoneToggle(done: Boolean, accent: Color, onClick: () -> Unit) {
    Surface(
        shape = CircleShape,
        color = if (done) Urgent.Green else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .size(30.dp)
            .clickable(onClick = onClick)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                Icons.Filled.Check,
                contentDescription = if (done) "Mark not handled" else "Mark handled",
                tint = if (done) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
private fun EmptyState(onScan: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            Modifier.padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Start with one receipt", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Photograph a receipt, warranty card, medicine box or ID. " +
                    "The text is read on your phone — nothing is uploaded.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(2.dp))
            listOf(
                "Return windows — the deadline shops never print",
                "Warranties — found or inferred from what you bought",
                "Expiry dates — medicine, food, documents"
            ).forEach {
                Row {
                    Text("—  ", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

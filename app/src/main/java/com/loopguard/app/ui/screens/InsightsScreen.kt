package com.loopguard.app.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.loopguard.app.data.Side
import com.loopguard.app.domain.PriorityBand
import com.loopguard.app.domain.PriorityEngine
import com.loopguard.app.ui.LoopGuardState
import com.loopguard.app.ui.components.EmptyState
import com.loopguard.app.ui.components.StatTile
import com.loopguard.app.ui.theme.LocalLoopGuardColors
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

@Composable
fun InsightsScreen(
    state: LoopGuardState,
    contentPadding: PaddingValues,
) {
    if (state.active.isEmpty() && state.completed.isEmpty()) {
        EmptyState(
            icon = Icons.Default.Insights,
            title = "Nothing to analyse yet",
            body = "Once you have a few loops, LoopGuard will show where your mental load is clustering and who tends to go quiet.",
            modifier = Modifier.padding(contentPadding),
        )
        return
    }

    val categoryCounts = state.active
        .groupingBy { it.loop.category }
        .eachCount()
        .toList()
        .sortedByDescending { it.second }

    val slowest = state.active
        .filter { it.loop.sideEnum == Side.THEM && it.loop.counterparty.isNotBlank() }
        .groupBy { it.loop.counterparty }
        .map { (person, loops) ->
            person to loops.maxOf { PriorityEngine.daysSilent(it.loop, state.today) }
        }
        .sortedByDescending { it.second }
        .take(4)

    val avgDaysToClose = state.completed
        .mapNotNull { item ->
            val closed = item.loop.closedAt ?: return@mapNotNull null
            val closedDay = Instant.ofEpochMilli(closed).atZone(ZoneId.systemDefault()).toLocalDate()
            val createdDay = Instant.ofEpochMilli(item.loop.createdAt)
                .atZone(ZoneId.systemDefault()).toLocalDate()
            ChronoUnit.DAYS.between(createdDay, closedDay).coerceAtLeast(0)
        }
        .takeIf { it.isNotEmpty() }
        ?.average()

    LazyColumn(
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 96.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Column {
                Text("Follow-through health", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Where responsibility, silence and deadlines are accumulating.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile(
                    value = state.completed.size.toString(),
                    label = "Closed",
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    value = state.criticalCount.toString(),
                    label = "Critical now",
                    modifier = Modifier.weight(1f),
                    accent = if (state.criticalCount > 0) {
                        LocalLoopGuardColors.current.critical
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                StatTile(
                    value = avgDaysToClose?.let { "${it.toInt()}d" } ?: "—",
                    label = "Avg. to close",
                    modifier = Modifier.weight(1f),
                )
            }
        }

        item {
            InsightCard(
                title = "Responsibility split",
                body = "${state.onMe} waiting on you · ${state.onThem} waiting on others",
                detail = when {
                    state.onMe > state.onThem * 2 ->
                        "Most of the backlog is yours to move. Blocking out one hour would clear a real share of it."
                    state.onThem > state.onMe * 2 ->
                        "You are mostly waiting on other people. Your best lever is a small number of well-timed, specific nudges."
                    else ->
                        "A healthy balance between what you can act on and what only needs chasing."
                },
            ) {
                SplitBar(onMe = state.onMe, onThem = state.onThem)
            }
        }

        if (categoryCounts.isNotEmpty()) {
            item {
                InsightCard(
                    title = "Where the load sits",
                    body = categoryCounts.first().let { "${it.first} is your busiest area (${it.second} open)" },
                    detail = "Clusters usually mean one unresolved situation generating several loops.",
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        val max = categoryCounts.maxOf { it.second }.coerceAtLeast(1)
                        categoryCounts.take(6).forEach { (category, count) ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    category,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.width(78.dp),
                                )
                                Bar(fraction = count.toFloat() / max, modifier = Modifier.weight(1f))
                                Spacer(Modifier.width(8.dp))
                                Text("$count", style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
            }
        }

        if (slowest.isNotEmpty()) {
            item {
                InsightCard(
                    title = "Who has gone quiet",
                    body = "Longest silence: ${slowest.first().first} (${slowest.first().second} days)",
                    detail = "Silence is not refusal. It is usually a message sitting in a queue with nobody's name on it.",
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        slowest.forEach { (person, days) ->
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(person, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "$days ${if (days == 1) "day" else "days"}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            InsightCard(
                title = "Pressure right now",
                body = "${state.pressure} / 100 across your top loops",
                detail = when (PriorityEngine.bandFor(state.pressure)) {
                    PriorityBand.CRITICAL -> "This is a genuinely heavy week. Clear the single most expensive item first rather than spreading effort."
                    PriorityBand.HIGH -> "Manageable, but two or three things need attention before they become urgent."
                    PriorityBand.MEDIUM -> "Steady. Keeping the follow-ups regular is what stops this climbing."
                    PriorityBand.LOW -> "Light load. A good moment to capture the things you have been carrying in your head."
                },
            )
        }
    }
}

@Composable
private fun InsightCard(
    title: String,
    body: String,
    detail: String,
    content: (@Composable () -> Unit)? = null,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(5.dp))
            Text(body, style = MaterialTheme.typography.bodyLarge)
            if (content != null) {
                Spacer(Modifier.height(14.dp))
                content()
            }
            Spacer(Modifier.height(11.dp))
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Bar(fraction: Float, modifier: Modifier = Modifier) {
    val animated by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(600), label = "bar")
    Box(
        modifier
            .height(9.dp)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Box(
            Modifier
                .fillMaxWidth(animated)
                .height(9.dp)
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}

@Composable
private fun SplitBar(onMe: Int, onThem: Int) {
    val total = (onMe + onThem).coerceAtLeast(1)
    Row(
        Modifier
            .fillMaxWidth()
            .height(12.dp)
            .clip(RoundedCornerShape(50)),
    ) {
        if (onMe > 0) {
            Box(
                Modifier
                    .weight(onMe.toFloat() / total)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
        if (onThem > 0) {
            Box(
                Modifier
                    .weight(onThem.toFloat() / total)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.secondary),
            )
        }
    }
}

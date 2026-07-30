package com.loopguard.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.loopguard.app.domain.PriorityEngine
import com.loopguard.app.ui.LoopGuardState
import com.loopguard.app.ui.ScoredLoop
import com.loopguard.app.ui.components.EmptyState
import com.loopguard.app.ui.components.LoopCard
import com.loopguard.app.ui.components.PriorityRing
import com.loopguard.app.ui.components.SectionHeader
import com.loopguard.app.ui.components.StatTile
import com.loopguard.app.ui.components.bandColour
import com.loopguard.app.ui.components.snoozeLabel
import com.loopguard.app.ui.theme.LocalLoopGuardColors
import java.time.LocalTime

@Composable
fun TodayScreen(
    state: LoopGuardState,
    contentPadding: PaddingValues,
    onOpenLoop: (Long) -> Unit,
    onFollowUp: (Long) -> Unit,
    onComplete: (Long) -> Unit,
    onSnooze: (Long) -> Unit,
    onUnsnooze: (Long) -> Unit,
    onSeeAll: () -> Unit,
    onAdd: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 96.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(key = "hero") { HeroCard(state) }

        item(key = "stats") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile(
                    value = state.openCount.toString(),
                    label = "Open loops",
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    value = state.overdue.toString(),
                    label = "Overdue",
                    modifier = Modifier.weight(1f),
                    accent = if (state.overdue > 0) {
                        LocalLoopGuardColors.current.critical
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                StatTile(
                    value = state.onThem.toString(),
                    label = "On them",
                    modifier = Modifier.weight(1f),
                )
            }
        }

        if (state.active.isEmpty()) {
            item(key = "empty") {
                EmptyState(
                    icon = Icons.Default.AutoAwesome,
                    title = if (state.snoozed.isEmpty()) "Nothing is hanging over you" else "All clear for now",
                    body = if (state.snoozed.isEmpty()) {
                        "No open loops. When something is left with you or with someone else, capture it here so it stops living in your head."
                    } else {
                        "Everything active is snoozed. It will come back when it needs you."
                    },
                    action = {
                        androidx.compose.material3.Button(onClick = onAdd) { Text("Capture a loop") }
                    },
                )
            }
        } else {
            item(key = "focus-header") {
                SectionHeader(
                    title = "Focus queue",
                    action = {
                        TextButton(onClick = onSeeAll) { Text("See all") }
                    },
                )
            }

            itemsIndexed(
                items = state.focus,
                key = { _, item -> "f-${item.id}" },
            ) { index, item ->
                AnimatedVisibility(
                    visible = true,
                    enter = fadeIn() + slideInVertically { it / 4 },
                ) {
                    LoopCard(
                        item = item,
                        today = state.today,
                        rank = index + 1,
                        onOpen = { onOpenLoop(item.id) },
                        onFollowUp = { onFollowUp(item.id) },
                        onComplete = { onComplete(item.id) },
                        onSnooze = { onSnooze(item.id) },
                    )
                }
            }

            if (state.active.size > state.focus.size) {
                item(key = "rest-header") {
                    Spacer(Modifier.height(2.dp))
                    SectionHeader(title = "Also open (${state.active.size - state.focus.size})")
                }
                items(
                    items = state.active.drop(state.focus.size),
                    key = { it.id },
                ) { item ->
                    LoopCard(
                        item = item,
                        today = state.today,
                        onOpen = { onOpenLoop(item.id) },
                        showActions = false,
                    )
                }
            }
        }

        if (state.snoozed.isNotEmpty()) {
            item(key = "snoozed-header") {
                Spacer(Modifier.height(2.dp))
                SectionHeader(title = "Snoozed (${state.snoozed.size})")
            }
            items(items = state.snoozed, key = { "s-${it.id}" }) { item ->
                SnoozedRow(
                    item = item,
                    label = snoozeLabel(item.loop, state.today).orEmpty(),
                    onOpen = { onOpenLoop(item.id) },
                    onWake = { onUnsnooze(item.id) },
                )
            }
        }
    }
}

@Composable
private fun HeroCard(state: LoopGuardState) {
    val name = state.settings.displayName.trim()
    val greeting = when (LocalTime.now().hour) {
        in 0..11 -> "Good morning"
        in 12..17 -> "Good afternoon"
        else -> "Good evening"
    }
    val headline = state.active.firstOrNull()?.priority?.headline
        ?: "Nothing is waiting on you right now."

    Surface(
        shape = RoundedCornerShape(26.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = if (name.isBlank()) greeting else "$greeting, $name",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = headline,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(14.dp))
                PriorityRing(
                    score = state.pressure,
                    band = PriorityEngine.bandFor(state.pressure),
                    diameter = 74,
                    label = "LOAD",
                )
            }

            if (state.overdue > 0 || state.dueToday > 0) {
                Spacer(Modifier.height(16.dp))
                val c = LocalLoopGuardColors.current
                Surface(
                    shape = RoundedCornerShape(15.dp),
                    color = if (state.overdue > 0) c.criticalContainer else c.mediumContainer,
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 13.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            modifier = Modifier.size(17.dp),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.width(9.dp))
                        Text(
                            text = buildString {
                                if (state.overdue > 0) append("${state.overdue} overdue")
                                if (state.overdue > 0 && state.dueToday > 0) append(" · ")
                                if (state.dueToday > 0) append("${state.dueToday} due today")
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SnoozedRow(
    item: ScoredLoop,
    label: String,
    onOpen: () -> Unit,
    onWake: () -> Unit,
) {
    Card(
        onClick = onOpen,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Bedtime,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    item.loop.title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                )
                Text(
                    label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onWake) { Text("Wake") }
        }
    }
}

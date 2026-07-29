package com.loopguard.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Snooze
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.loopguard.app.data.Loop
import com.loopguard.app.data.LoopEvent
import com.loopguard.app.data.LoopStatus
import com.loopguard.app.data.Side
import com.loopguard.app.domain.Priority
import com.loopguard.app.domain.PriorityEngine
import com.loopguard.app.ui.components.Pill
import com.loopguard.app.ui.components.PriorityRing
import com.loopguard.app.ui.components.TagRow
import com.loopguard.app.ui.components.bandColour
import com.loopguard.app.ui.components.impactLabel
import com.loopguard.app.ui.components.statusLine
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    loop: Loop,
    events: List<LoopEvent>,
    priority: Priority,
    today: LocalDate,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onFollowUp: () -> Unit,
    onComplete: () -> Unit,
    onReopen: () -> Unit,
    onDelete: () -> Unit,
    onSnooze: (Long) -> Unit,
    onSwapSide: (Side) -> Unit,
    onTogglePin: () -> Unit,
    onLogAction: (String, Boolean) -> Unit,
) {
    var showDelete by remember { mutableStateOf(false) }
    var showSnooze by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(false) }
    val done = loop.statusEnum == LoopStatus.DONE

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(loop.category, style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onTogglePin) {
                        Icon(
                            Icons.Default.PushPin,
                            contentDescription = if (loop.pinned) "Unpin" else "Pin",
                            tint = if (loop.pinned) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit")
                    }
                    IconButton(onClick = { showDelete = true }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = padding.calculateTopPadding() + 4.dp,
                bottom = padding.calculateBottomPadding() + 32.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Column {
                    Text(loop.title, style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        loop.whoLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (loop.reference.isNotBlank()) {
                        Spacer(Modifier.height(3.dp))
                        Text(
                            "Ref ${loop.reference}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Pill(if (loop.sideEnum == Side.ME) "Your move" else "Their move")
                        Pill(statusLine(loop, today))
                        Pill("${impactLabel(loop.impact)} impact")
                    }
                    if (loop.tagList.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        TagRow(loop.tagList)
                    }
                }
            }

            if (!done) {
                item { PriorityCard(priority) }

                item {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                "SUGGESTED NEXT STEP",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(7.dp))
                            Text(
                                PriorityEngine.nextAction(loop, today),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Spacer(Modifier.height(14.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = onFollowUp, modifier = Modifier.weight(1f)) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.Send,
                                        contentDescription = null,
                                        modifier = Modifier.size(17.dp),
                                    )
                                    Spacer(Modifier.width(7.dp))
                                    Text("Write follow-up")
                                }
                                OutlinedButton(onClick = { showLog = true }) { Text("Log") }
                            }
                        }
                    }
                }
            }

            if (loop.notes.isNotBlank()) {
                item {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLowest,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                "CONTEXT",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(7.dp))
                            Text(loop.notes, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (done) {
                        OutlinedButton(onClick = onReopen, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Undo, contentDescription = null, modifier = Modifier.size(17.dp))
                            Spacer(Modifier.width(7.dp))
                            Text("Reopen")
                        }
                    } else {
                        Button(onClick = onComplete, modifier = Modifier.weight(1f)) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                modifier = Modifier.size(17.dp),
                            )
                            Spacer(Modifier.width(7.dp))
                            Text("Mark complete")
                        }
                        OutlinedButton(onClick = { showSnooze = true }) {
                            Icon(Icons.Default.Snooze, contentDescription = "Snooze")
                        }
                    }
                }
            }

            if (!done) {
                item {
                    OutlinedButton(
                        onClick = {
                            onSwapSide(if (loop.sideEnum == Side.ME) Side.THEM else Side.ME)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.SwapHoriz, contentDescription = null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(7.dp))
                        Text(
                            if (loop.sideEnum == Side.ME) {
                                "Hand back to ${loop.counterparty.ifBlank { "them" }}"
                            } else {
                                "Take responsibility"
                            }
                        )
                    }
                }
            }

            item {
                Spacer(Modifier.height(4.dp))
                Text("History", style = MaterialTheme.typography.titleMedium)
            }

            if (events.isEmpty()) {
                item {
                    Text(
                        "Nothing logged yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(items = events, key = { it.id }) { event ->
                    TimelineRow(event)
                }
            }
        }
    }

    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("Delete this loop?") },
            text = { Text("The loop and its whole history will be removed. You can undo this straight afterwards, but not later.") },
            confirmButton = {
                TextButton(onClick = { showDelete = false; onDelete() }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDelete = false }) { Text("Cancel") }
            },
        )
    }

    if (showSnooze) {
        SnoozeDialog(
            onDismiss = { showSnooze = false },
            onPick = { days -> showSnooze = false; onSnooze(days) },
        )
    }

    if (showLog) {
        LogActionDialog(
            side = loop.sideEnum,
            counterparty = loop.counterparty,
            onDismiss = { showLog = false },
            onConfirm = { text, handOver -> showLog = false; onLogAction(text, handOver) },
        )
    }
}

@Composable
private fun PriorityCard(priority: Priority) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PriorityRing(priority.score, priority.band, diameter = 58)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "${priority.band.label} priority",
                        style = MaterialTheme.typography.titleMedium,
                        color = bandColour(priority.band),
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        priority.headline,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                "WHY IT SCORES THIS",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(9.dp))

            priority.factors.forEach { factor ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 5.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Box(
                        Modifier
                            .padding(top = 5.dp)
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(bandColour(priority.band)),
                    )
                    Spacer(Modifier.width(11.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            factor.label,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            factor.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "+${factor.points}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private val TIMELINE_FORMAT = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

@Composable
private fun TimelineRow(event: LoopEvent) {
    Row(Modifier.fillMaxWidth()) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(22.dp)) {
            Box(
                Modifier
                    .padding(top = 5.dp)
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            )
            Box(
                Modifier
                    .width(2.dp)
                    .height(30.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.padding(bottom = 6.dp)) {
            Text(event.text, style = MaterialTheme.typography.bodyMedium)
            Text(
                LocalDate.ofEpochDay(event.date).format(TIMELINE_FORMAT),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun SnoozeDialog(onDismiss: () -> Unit, onPick: (Long) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Snooze until when?") },
        text = {
            Column {
                Text(
                    "It disappears from the focus queue and comes back on its own.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                listOf(
                    1L to "Tomorrow",
                    3L to "In 3 days",
                    7L to "Next week",
                    14L to "In 2 weeks",
                    30L to "In a month",
                ).forEach { (days, label) ->
                    TextButton(
                        onClick = { onPick(days) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(label, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun LogActionDialog(
    side: Side,
    counterparty: String,
    onDismiss: () -> Unit,
    onConfirm: (String, Boolean) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    var handOver by remember { mutableStateOf(side == Side.ME) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log what happened") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("What did you do?") },
                    placeholder = { Text("e.g. Uploaded the documents") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(
                        checked = handOver,
                        onCheckedChange = { handOver = it },
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "Now waiting on ${counterparty.ifBlank { "them" }}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text.trim().ifBlank { "Action taken" }, handOver) }) {
                Text("Log it")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

package com.deadlineguardian.ui.screens

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.deadlineguardian.data.Urgency
import com.deadlineguardian.ui.GuardianViewModel
import com.deadlineguardian.ui.Urgent
import com.deadlineguardian.ui.displayName
import com.deadlineguardian.ui.formatMoney
import com.deadlineguardian.ui.pretty
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    viewModel: GuardianViewModel,
    deadlineId: Long,
    onBack: () -> Unit
) {
    val entry by remember(deadlineId) { viewModel.deadline(deadlineId) }
        .collectAsStateWithLifecycle(initialValue = null)

    var showDatePicker by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Details") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    entry?.let { e ->
                        IconButton(onClick = { viewModel.deleteItem(e.item); onBack() }) {
                            Icon(
                                Icons.Filled.DeleteOutline,
                                contentDescription = "Delete",
                                tint = Urgent.Red
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        val e = entry
        if (e == null) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "This item is no longer here.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            return@Scaffold
        }

        val urgency = e.urgency()
        val accent = when (urgency) {
            Urgency.OVERDUE, Urgency.ACT_NOW -> Urgent.Red
            Urgency.SOON -> Urgent.Amber
            Urgency.DONE -> Urgent.Green
            Urgency.LATER -> Urgent.Slate
        }

        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    e.deadline.kind.displayName().uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = accent
                )
                Spacer(Modifier.height(4.dp))
                Text(e.item.title, style = MaterialTheme.typography.headlineLarge)
                Spacer(Modifier.height(6.dp))
                Text(
                    if (urgency == Urgency.DONE) "Handled"
                    else "${e.deadline.date.pretty()} · ${e.countdownText()}",
                    style = MaterialTheme.typography.titleMedium,
                    color = accent,
                    fontWeight = FontWeight.SemiBold
                )
            }

            e.deadline.moneyAtRiskMinor?.let { money ->
                item {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text(
                                "AT STAKE",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(
                                formatMoney(money, e.item.currency),
                                style = MaterialTheme.typography.headlineSmall,
                                color = accent
                            )
                        }
                    }
                }
            }

            item {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(14.dp)) {
                        InfoRow("Type", e.item.kind.displayName())
                        e.item.purchaseDate?.let { InfoRow("Purchased", it.pretty()) }
                        e.item.merchant?.let { InfoRow("Merchant", it) }
                        e.item.amountMinor?.let {
                            InfoRow("Total", formatMoney(it, e.item.currency))
                        }
                        InfoRow("Warn me", "${e.deadline.leadDays} days before")
                    }
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { viewModel.markDone(e.deadline) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (urgency == Urgency.DONE) "Reopen" else "Mark handled")
                    }
                    OutlinedButton(
                        onClick = { showDatePicker = true },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Change date")
                    }
                }
            }

            e.item.imagePath?.takeIf { File(it).exists() }?.let { path ->
                item {
                    Text(
                        "THE SCAN",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    AsyncImage(
                        model = File(path),
                        contentDescription = "Scanned image",
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(320.dp)
                    )
                }
            }
        }

        if (showDatePicker) {
            val initialMillis = e.deadline.date
                .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            val pickerState = rememberDatePickerState(initialSelectedDateMillis = initialMillis)

            DatePickerDialog(
                onDismissRequest = { showDatePicker = false },
                confirmButton = {
                    TextButton(onClick = {
                        pickerState.selectedDateMillis?.let { millis ->
                            val newDate = Instant.ofEpochMilli(millis)
                                .atZone(ZoneOffset.UTC).toLocalDate()
                            // Reset notifiedAt so a corrected date can alert again today.
                            viewModel.updateDeadline(
                                e.deadline.copy(date = newDate, notifiedAt = null)
                            )
                        }
                        showDatePicker = false
                    }) { Text("Save") }
                },
                dismissButton = {
                    TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
                }
            ) {
                DatePicker(state = pickerState)
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.padding(vertical = 4.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(100.dp)
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

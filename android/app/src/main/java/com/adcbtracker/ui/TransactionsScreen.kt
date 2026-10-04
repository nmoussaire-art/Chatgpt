package com.adcbtracker.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adcbtracker.data.Category
import com.adcbtracker.data.TransactionWithCategory
import com.adcbtracker.data.UAE_ZONE
import com.adcbtracker.parser.AdcbAlertParser
import com.adcbtracker.ui.components.CategoryBadge
import com.adcbtracker.ui.components.EmptyState
import com.adcbtracker.ui.components.formatMoney
import com.adcbtracker.ui.components.relativeDayLabel
import com.adcbtracker.ui.theme.Amber
import com.adcbtracker.ui.theme.Coral
import com.adcbtracker.ui.theme.Emerald
import com.adcbtracker.ui.theme.Ink0
import com.adcbtracker.ui.theme.Ink1
import com.adcbtracker.ui.theme.Ink2
import com.adcbtracker.ui.theme.TextMid
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun TransactionsScreen(vm: MainViewModel) {
    val days by vm.transactionsByDay.collectAsStateWithLifecycle()
    val query by vm.searchQuery.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val threshold by vm.largeThreshold.collectAsStateWithLifecycle()
    var showAdd by rememberSaveable { mutableStateOf(false) }
    val today = LocalDate.now(UAE_ZONE)
    val total = days.sumOf { it.count }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 96.dp),
        ) {
            item {
                Text("Transactions", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = vm::updateSearchQuery,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Search merchant, location, card…") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
                    trailingIcon = {
                        if (query.isNotEmpty()) IconButton(onClick = { vm.updateSearchQuery("") }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear")
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    if (query.isBlank()) "$total transactions" else "$total results for \"$query\"",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMid,
                )
                Spacer(Modifier.height(4.dp))
            }

            if (days.isEmpty()) {
                item {
                    if (query.isBlank()) EmptyState("💳", "No transactions yet", "Transactions will appear here once captured")
                    else EmptyState("🔍", "No matching transactions", "Try a different search term")
                }
            }

            days.forEach { day ->
                item(key = "h_${day.date}") {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(Ink0)
                            .clickable { vm.openDay(day.date) }
                            .padding(top = 18.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(relativeDayLabel(day.date, today), style = MaterialTheme.typography.titleMedium)
                            Text("${day.count} txn${if (day.count == 1) "" else "s"}", style = MaterialTheme.typography.labelSmall, color = TextMid)
                        }
                        Text(formatMoney(day.totalMinor), style = MaterialTheme.typography.titleMedium, color = Emerald, fontWeight = FontWeight.Bold)
                    }
                }
                items(day.transactions, key = { it.tx.id }) { item ->
                    TransactionCard(item = item, largeThresholdMinor = threshold) { vm.openTransaction(item.tx.id) }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }

        FloatingActionButton(
            onClick = { showAdd = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = Emerald,
        ) { Icon(Icons.Default.Add, contentDescription = "Add manual transaction") }
    }

    if (showAdd) {
        AddTransactionDialog(categories, onDismiss = { showAdd = false }) { amount, currency, desc, catId, millis ->
            vm.addManualTransaction(amount, currency, desc, catId, millis)
            showAdd = false
        }
    }
}

@Composable
private fun TransactionCard(
    item: TransactionWithCategory,
    largeThresholdMinor: Long,
    onClick: () -> Unit,
) {
    val tx = item.tx
    val shape = RoundedCornerShape(20.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Ink1)
            .clickable(onClick = onClick)
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CategoryBadge(item.category)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(tx.merchant ?: "Unknown Merchant", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(
                        Instant.ofEpochMilli(tx.tsEpochMillis).atZone(UAE_ZONE).format(DateTimeFormatter.ofPattern("HH:mm", Locale.US)),
                        item.category?.name ?: "Uncategorized · tap to set",
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMid,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    formatMoney(tx.amountMinor, tx.currency),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Emerald,
                )
                if (tx.amountMinor >= largeThresholdMinor) Text("⚠️ Large expense", style = MaterialTheme.typography.labelSmall, color = Amber)
                else tx.cardId?.let { Text("Card $it", style = MaterialTheme.typography.labelSmall, color = TextMid) }
            }
        }
    }
}

@Composable
fun CategoryPickerDialog(
    title: String,
    categories: List<Category>,
    selectedId: Long?,
    onDismiss: () -> Unit,
    onPick: (Long?) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(categories, key = { it.id }) { c ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (c.id == selectedId) MaterialTheme.colorScheme.primaryContainer else Ink1)
                            .clickable { onPick(c.id) }
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CategoryBadge(c, size = 32)
                        Spacer(Modifier.width(12.dp))
                        Text(c.name, modifier = Modifier.weight(1f))
                        if (c.id == selectedId) Text("Selected", style = MaterialTheme.typography.labelSmall, color = Emerald)
                    }
                    Spacer(Modifier.height(4.dp))
                }
                if (selectedId != null) {
                    item {
                        TextButton(onClick = { onPick(null) }) { Text("🚫 Remove Category", color = Coral) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddTransactionDialog(
    categories: List<Category>,
    onDismiss: () -> Unit,
    onSave: (Long, String, String, Long?, Long) -> Unit,
) {
    var amount by rememberSaveable { mutableStateOf("") }
    var currency by rememberSaveable { mutableStateOf("AED") }
    var description by rememberSaveable { mutableStateOf("") }
    var categoryId by rememberSaveable { mutableStateOf<Long?>(null) }
    var date by rememberSaveable { mutableStateOf(LocalDate.now(UAE_ZONE)) }
    var pickDate by remember { mutableStateOf(false) }
    var pickCategory by remember { mutableStateOf(false) }
    val amountMinor = AdcbAlertParser.moneyToMinor(amount.trim())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Manual Transaction") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it.filter { ch -> ch.isDigit() || ch == '.' } },
                    label = { Text("Amount") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("AED", "USD", "EUR", "GBP").forEach { c ->
                        FilterChip(selected = currency == c, onClick = { currency = c }, label = { Text(c) })
                    }
                }
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(onClick = { pickDate = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("📅 " + date.format(DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.US)))
                }
                OutlinedButton(onClick = { pickCategory = true }, modifier = Modifier.fillMaxWidth()) {
                    val c = categories.firstOrNull { it.id == categoryId }
                    Text(c?.let { "${it.icon} ${it.name}" } ?: "Choose category (optional)")
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = amountMinor != null && amountMinor > 0,
                onClick = {
                    val time = if (date == LocalDate.now(UAE_ZONE)) LocalTime.now(UAE_ZONE) else LocalTime.NOON
                    onSave(amountMinor!!, currency, description, categoryId, date.atTime(time).atZone(UAE_ZONE).toInstant().toEpochMilli())
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )

    if (pickDate) {
        val state = rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                    pickDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text("Cancel") } },
        ) { DatePicker(state = state) }
    }

    if (pickCategory) {
        CategoryPickerDialog("Select Category", categories, categoryId, onDismiss = { pickCategory = false }) {
            categoryId = it
            pickCategory = false
        }
    }
}

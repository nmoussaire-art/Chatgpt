package com.adcbtracker.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.adcbtracker.data.Category
import com.adcbtracker.data.Cycle
import com.adcbtracker.data.TransactionWithCategory
import com.adcbtracker.data.UAE_ZONE
import com.adcbtracker.data.spendMinor
import com.adcbtracker.ui.theme.Coral
import com.adcbtracker.ui.theme.Emerald
import com.adcbtracker.ui.theme.Ink1
import com.adcbtracker.ui.theme.Ink2
import com.adcbtracker.ui.theme.Ink3
import com.adcbtracker.ui.theme.TextMid
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
private fun SheetHandle() {
    Box(
        Modifier
            .padding(top = 12.dp, bottom = 4.dp)
            .size(width = 40.dp, height = 4.dp)
            .clip(RoundedCornerShape(50))
            .background(Ink3)
    )
}

/**
 * Details of one transaction with a one-tap category picker. By default the choice is remembered
 * for the merchant (past and future transactions); the switch limits it to this transaction.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TransactionSheet(
    item: TransactionWithCategory,
    categories: List<Category>,
    onDismiss: () -> Unit,
    onSetCategory: (categoryId: Long?, applyToMerchant: Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val tx = item.tx
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var applyToMerchant by remember(tx.id) { mutableStateOf(tx.merchant != null) }
    var confirmDelete by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = Ink1, dragHandle = { SheetHandle() }) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                CategoryBadge(item.category, size = 56)
                Spacer(Modifier.height(10.dp))
                Text(
                    tx.merchant ?: "Unknown Merchant",
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(formatMoney(tx.amountMinor, tx.currency), style = MaterialTheme.typography.headlineMedium)
                Text(
                    Instant.ofEpochMilli(tx.tsEpochMillis).atZone(UAE_ZONE)
                        .format(DateTimeFormatter.ofPattern("EEEE, d MMM yyyy · HH:mm", Locale.US)),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMid,
                )
            }

            Spacer(Modifier.height(16.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Ink2)
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                tx.location?.let { DetailRow("Location", it) }
                tx.cardId?.let { DetailRow("Card", it) }
                tx.avlCreditLimitMinor?.let { DetailRow("Available limit after", formatMoney(it, tx.currency)) }
                DetailRow("Captured via", tx.source.replaceFirstChar { it.uppercase() })
            }

            Spacer(Modifier.height(18.dp))
            Text("Category", style = MaterialTheme.typography.titleMedium)
            Text(
                if (item.category == null) "Tap a category to assign it" else "Currently ${item.category.icon} ${item.category.name}",
                style = MaterialTheme.typography.bodySmall,
                color = if (item.category == null) Emerald else TextMid,
            )
            Spacer(Modifier.height(10.dp))
            CategoryChips(categories, item.category?.id) { onSetCategory(it, applyToMerchant) }

            if (tx.merchant != null) {
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Apply to all ${tx.merchant}", style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (applyToMerchant) "Past and future transactions from this merchant" else "Only this transaction",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMid,
                        )
                    }
                    Switch(
                        checked = applyToMerchant,
                        onCheckedChange = { applyToMerchant = it },
                        colors = SwitchDefaults.colors(checkedTrackColor = Emerald),
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = Ink2)
            TextButton(onClick = { confirmDelete = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("Delete transaction", color = Coral)
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete Transaction?") },
            text = { Text("Are you sure you want to delete this transaction? This cannot be undone.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Delete", color = Coral) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = TextMid, modifier = Modifier.width(140.dp))
        Text(value, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
    }
}

/** All transactions of one category (or uncategorized) in a cycle; tap one to edit it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategorySheet(
    category: Category?,
    cycle: Cycle,
    transactions: List<TransactionWithCategory>,
    largeThresholdMinor: Long,
    onDismiss: () -> Unit,
    onTransactionClick: (TransactionWithCategory) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = Ink1, dragHandle = { SheetHandle() }) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CategoryBadge(category, size = 48)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(category?.name ?: "Uncategorized", style = MaterialTheme.typography.titleLarge)
                    Text(cycle.label(), style = MaterialTheme.typography.bodySmall, color = TextMid)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(formatMoney(transactions.sumOf { it.tx.spendMinor }), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("${transactions.size} txn${if (transactions.size == 1) "" else "s"}", style = MaterialTheme.typography.labelSmall, color = TextMid)
                }
            }
            if (category == null && transactions.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text("Tap a transaction to give it a category.", style = MaterialTheme.typography.bodySmall, color = Emerald)
            }
            Spacer(Modifier.height(8.dp))
            if (transactions.isEmpty()) {
                EmptyState("🎉", "Nothing here", "No transactions in this category for this cycle.")
            } else {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
                    items(transactions, key = { it.tx.id }) { t ->
                        TransactionRow(t, largeThresholdMinor, showDate = true) { onTransactionClick(t) }
                        HorizontalDivider(color = Ink2)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** Wrapping grid of category chips; the selected one is outlined. Includes "Remove" when one is set. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CategoryChips(categories: List<Category>, selectedId: Long?, onPick: (Long?) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        categories.forEach { c ->
            val selected = c.id == selectedId
            val color = parseColor(c.colorHex)
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (selected) color.copy(alpha = 0.35f) else Ink2)
                    .then(if (selected) Modifier.border(1.5.dp, color, RoundedCornerShape(50)) else Modifier)
                    .clickable { onPick(c.id) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(c.icon)
                Spacer(Modifier.width(6.dp))
                Text(c.name, style = MaterialTheme.typography.labelLarge)
            }
        }
        if (selectedId != null) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Ink2)
                    .clickable { onPick(null) }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) { Text("🚫 Remove", style = MaterialTheme.typography.labelLarge, color = Coral) }
        }
    }
}

/** Pick a category for every transaction of a merchant. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MerchantCategorySheet(
    merchant: String,
    subtitle: String,
    categories: List<Category>,
    selectedId: Long?,
    onDismiss: () -> Unit,
    onPick: (Long?) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = Ink1, dragHandle = { SheetHandle() }) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            Text(merchant, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TextMid)
            Spacer(Modifier.height(16.dp))
            CategoryChips(categories, selectedId, onPick)
            Spacer(Modifier.height(10.dp))
            Text(
                "Applies to all past and future transactions from this merchant.",
                style = MaterialTheme.typography.bodySmall,
                color = TextMid,
            )
            Spacer(Modifier.height(20.dp))
        }
    }
}

package com.adcbtracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adcbtracker.data.Category
import com.adcbtracker.data.MerchantWithCategory
import com.adcbtracker.ui.components.CategoryBadge
import com.adcbtracker.ui.components.EmptyState
import com.adcbtracker.ui.components.MerchantCategorySheet
import com.adcbtracker.ui.components.formatMoney
import com.adcbtracker.ui.components.parseColor
import com.adcbtracker.ui.theme.Coral
import com.adcbtracker.ui.theme.Emerald
import com.adcbtracker.ui.theme.Ink0
import com.adcbtracker.ui.theme.Ink1
import com.adcbtracker.ui.theme.TextMid

private val ICONS = listOf("🍽️", "🍕", "☕", "🛒", "🚗", "🚕", "✈️", "🛍️", "🎬", "🎮", "🎵", "⚽", "💊", "💇", "💡", "📱", "📚", "🏠", "🐾", "👶", "🎁", "💼", "💰", "📦", "❓")
private val COLORS = listOf("#FF5722", "#2196F3", "#9C27B0", "#E91E63", "#4CAF50", "#00BCD4", "#FFC107", "#3F51B5", "#607D8B", "#FF9800", "#795548", "#9E9E9E")

@Composable
fun CategoriesScreen(vm: MainViewModel) {
    val categories by vm.categories.collectAsStateWithLifecycle()
    val merchants by vm.merchants.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }

    Column(Modifier.fillMaxSize()) {
        Text("Categories", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 12.dp))
        TabRow(selectedTabIndex = tab, containerColor = Ink0) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Merchants") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Manage") })
        }
        if (tab == 0) MerchantsTab(vm, merchants, categories) else ManageTab(vm, categories)
    }
}

@Composable
private fun MerchantsTab(vm: MainViewModel, merchants: List<MerchantWithCategory>, categories: List<Category>) {
    var onlyUncategorized by rememberSaveable { mutableStateOf(merchants.any { it.categoryId == null }) }
    var query by rememberSaveable { mutableStateOf("") }
    var picking by remember { mutableStateOf<MerchantWithCategory?>(null) }
    val shown = merchants
        .filter { !onlyUncategorized || it.categoryId == null }
        .filter { query.isBlank() || it.merchant.contains(query.trim(), ignoreCase = true) }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("Map merchants to categories. Future transactions will be auto-categorized.", style = MaterialTheme.typography.bodyMedium, color = TextMid)
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search merchants…") },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !onlyUncategorized, onClick = { onlyUncategorized = false }, label = { Text("All Merchants") })
                FilterChip(
                    selected = onlyUncategorized,
                    onClick = { onlyUncategorized = true },
                    label = { Text("Uncategorized (${merchants.count { it.categoryId == null }})") },
                )
            }
        }
        if (shown.isEmpty()) {
            item {
                if (merchants.isEmpty()) EmptyState("🏪", "No merchants yet", "Merchants will appear here once transactions are captured.")
                else EmptyState("🎉", "All merchants categorized!", "Great job! All your merchants have categories.")
            }
        }
        items(shown) { m ->
            val cat = categories.firstOrNull { it.id == m.categoryId }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(Ink1)
                    .clickable { picking = m }
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CategoryBadge(cat, size = 38)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(m.merchant, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${m.transactionCount} txn${if (m.transactionCount == 1) "" else "s"} · ${cat?.name ?: "Tap to categorize"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (cat == null) Emerald else TextMid,
                    )
                }
                Text(formatMoney(m.totalSpent), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            }
        }
    }

    picking?.let { m ->
        MerchantCategorySheet(
            merchant = m.merchant,
            subtitle = "${m.transactionCount} txn${if (m.transactionCount == 1) "" else "s"} · ${formatMoney(m.totalSpent)}",
            categories = categories,
            selectedId = m.categoryId,
            onDismiss = { picking = null },
        ) { id ->
            vm.mapMerchantToCategory(m.merchant, id)
            picking = null
        }
    }
}

@Composable
private fun ManageTab(vm: MainViewModel, categories: List<Category>) {
    var editing by remember { mutableStateOf<Category?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Category?>(null) }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            TextButton(onClick = { adding = true }) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Add Category")
            }
        }
        items(categories, key = { it.id }) { c ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(Ink1)
                    .clickable { editing = c }
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CategoryBadge(c, size = 38)
                Spacer(Modifier.width(12.dp))
                Text(c.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (c.isDefault) Text("Default", style = MaterialTheme.typography.labelSmall, color = TextMid)
                else TextButton(onClick = { deleting = c }) { Text("Delete", color = Coral) }
            }
        }
    }

    if (adding) CategoryFormDialog(null, onDismiss = { adding = false }) { name, icon, color ->
        vm.addCategory(name, icon, color); adding = false
    }
    editing?.let { c ->
        CategoryFormDialog(c, onDismiss = { editing = null }) { name, icon, color ->
            vm.updateCategory(c.copy(name = name, icon = icon, colorHex = color)); editing = null
        }
    }
    deleting?.let { c ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete Category?") },
            text = { Text("\"${c.name}\" will be removed. Transactions using it become uncategorized.") },
            confirmButton = { TextButton(onClick = { vm.deleteCategory(c.id); deleting = null }) { Text("Delete", color = Coral) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryFormDialog(existing: Category?, onDismiss: () -> Unit, onSave: (String, String, String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(existing?.name ?: "") }
    var icon by rememberSaveable { mutableStateOf(existing?.icon ?: ICONS.first()) }
    var color by rememberSaveable { mutableStateOf(existing?.colorHex ?: COLORS.first()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "New Category" else "Edit Category") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true)
                Text("Icon", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ICONS.forEach { i ->
                        Box(
                            Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (i == icon) MaterialTheme.colorScheme.primaryContainer else Ink1)
                                .clickable { icon = i },
                            contentAlignment = Alignment.Center,
                        ) { Text(i, fontSize = 18.sp) }
                    }
                }
                Text("Color", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    COLORS.forEach { hex ->
                        Box(
                            Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(parseColor(hex))
                                .then(if (hex == color) Modifier.border(3.dp, Color.White, CircleShape) else Modifier)
                                .clickable { color = hex }
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onSave(name.trim(), icon, color) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

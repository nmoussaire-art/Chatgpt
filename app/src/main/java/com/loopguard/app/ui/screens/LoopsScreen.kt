package com.loopguard.app.ui.screens

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.FilterListOff
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.loopguard.app.ui.Filters
import com.loopguard.app.ui.LoopGuardState
import com.loopguard.app.ui.SideFilter
import com.loopguard.app.ui.components.EmptyState
import com.loopguard.app.ui.components.LoopCard

@Composable
fun LoopsScreen(
    state: LoopGuardState,
    contentPadding: PaddingValues,
    onQueryChange: (String) -> Unit,
    onSideChange: (SideFilter) -> Unit,
    onCategoryChange: (String?) -> Unit,
    onTagChange: (String?) -> Unit,
    onShowCompleted: (Boolean) -> Unit,
    onClearFilters: () -> Unit,
    onOpenLoop: (Long) -> Unit,
    onAdd: () -> Unit,
) {
    val f = state.filters

    Column(Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(contentPadding.calculateTopPadding()))

        OutlinedTextField(
            value = f.query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            placeholder = { Text("Search title, person, notes, reference…") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (f.query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear search")
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        )

        Spacer(Modifier.height(10.dp))

        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                FilterChip(
                    selected = f.showCompleted,
                    onClick = { onShowCompleted(!f.showCompleted) },
                    label = { Text(if (f.showCompleted) "Completed" else "Open") },
                )
            }
            items(SideFilter.entries.toList()) { side ->
                FilterChip(
                    selected = f.side == side,
                    onClick = { onSideChange(side) },
                    label = { Text(side.label) },
                )
            }
            if (state.categories.isNotEmpty()) {
                items(state.categories) { category ->
                    FilterChip(
                        selected = f.category == category,
                        onClick = {
                            onCategoryChange(if (f.category == category) null else category)
                        },
                        label = { Text(category) },
                        colors = FilterChipDefaults.filterChipColors(),
                    )
                }
            }
            items(state.tags) { tag ->
                FilterChip(
                    selected = f.tag == tag,
                    onClick = { onTagChange(if (f.tag == tag) null else tag) },
                    label = { Text("#$tag") },
                )
            }
        }

        if (f.isActive) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${state.filtered.size} ${if (state.filtered.size == 1) "match" else "matches"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                androidx.compose.material3.TextButton(onClick = onClearFilters) {
                    Icon(
                        Icons.Default.FilterListOff,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(5.dp))
                    Text("Clear")
                }
            }
        }

        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 8.dp,
                bottom = contentPadding.calculateBottomPadding() + 96.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.filtered.isEmpty()) {
                item {
                    if (f.isActive) {
                        EmptyState(
                            icon = Icons.Default.Search,
                            title = "Nothing matches",
                            body = "Try a shorter search, or clear the filters to see everything again.",
                            action = {
                                androidx.compose.material3.OutlinedButton(onClick = onClearFilters) {
                                    Text("Clear filters")
                                }
                            },
                        )
                    } else if (f.showCompleted) {
                        EmptyState(
                            icon = Icons.Default.Inbox,
                            title = "No completed loops yet",
                            body = "Everything you close will be kept here with its full history, so you can prove what happened and when.",
                        )
                    } else {
                        EmptyState(
                            icon = Icons.Default.Inbox,
                            title = "No open loops",
                            body = "Capture the things other people owe you, and the things you owe them.",
                            action = {
                                androidx.compose.material3.Button(onClick = onAdd) {
                                    Text("Capture a loop")
                                }
                            },
                        )
                    }
                }
            } else {
                items(items = state.filtered, key = { it.id }) { item ->
                    LoopCard(
                        item = item,
                        today = state.today,
                        onOpen = { onOpenLoop(item.id) },
                        showActions = false,
                    )
                }
            }
        }
    }
}

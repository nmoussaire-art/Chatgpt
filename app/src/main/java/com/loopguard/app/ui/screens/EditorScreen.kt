package com.loopguard.app.ui.screens

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.loopguard.app.data.Categories
import com.loopguard.app.data.Loop
import com.loopguard.app.data.Side
import com.loopguard.app.domain.QuickCapture
import com.loopguard.app.domain.Templates
import com.loopguard.app.ui.components.impactLabel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DATE_LABEL = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH)

/** Editable form state, shared by "new loop" and "edit loop". */
private data class Draft(
    val title: String = "",
    val counterparty: String = "",
    val organisation: String = "",
    val side: Side = Side.THEM,
    val category: String = Categories.ADMIN,
    val due: LocalDate? = null,
    val impact: Int = 3,
    val notes: String = "",
    val reference: String = "",
    val tags: String = "",
)

private fun Loop.toDraft() = Draft(
    title = title,
    counterparty = counterparty,
    organisation = organisation,
    side = sideEnum,
    category = category,
    due = due,
    impact = impact,
    notes = notes,
    reference = reference,
    tags = tags,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    existing: Loop?,
    today: LocalDate,
    prefillText: String? = null,
    onBack: () -> Unit,
    onSave: (Loop) -> Unit,
) {
    val isNew = existing == null
    var draft by remember(existing?.id) {
        mutableStateOf(existing?.toDraft() ?: Draft(due = today.plusDays(3)))
    }
    var quickText by remember { mutableStateOf(prefillText.orEmpty()) }
    var understood by remember { mutableStateOf<List<String>>(emptyList()) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showTemplates by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val canSave by remember { derivedStateOf { draft.title.isNotBlank() } }

    // Anything shared into the app is parsed the moment we arrive.
    LaunchedEffect(prefillText) {
        if (!prefillText.isNullOrBlank()) {
            val parsed = QuickCapture.parse(prefillText, today)
            draft = draft.copy(
                title = parsed.title,
                counterparty = parsed.counterparty.ifBlank { draft.counterparty },
                due = parsed.dueDate ?: draft.due,
                impact = parsed.impact,
                side = parsed.side,
                category = parsed.category,
                tags = parsed.tags.joinToString(","),
                reference = parsed.reference,
            )
            understood = parsed.understood
        }
    }

    val voiceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                .orEmpty()
            if (spoken.isNotBlank()) quickText = spoken
        }
    }

    fun applyQuickCapture() {
        if (quickText.isBlank()) return
        val parsed = QuickCapture.parse(quickText, today)
        draft = draft.copy(
            title = parsed.title.ifBlank { quickText.trim() },
            counterparty = parsed.counterparty.ifBlank { draft.counterparty },
            due = parsed.dueDate ?: draft.due,
            impact = parsed.impact,
            side = parsed.side,
            category = parsed.category,
            tags = parsed.tags.joinToString(","),
            reference = parsed.reference.ifBlank { draft.reference },
        )
        understood = parsed.understood
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isNew) "New loop" else "Edit loop") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            onSave(draft.toLoop(existing, today))
                        },
                        enabled = canSave,
                    ) { Text("Save") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            if (isNew) {
                Spacer(Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "QUICK CAPTURE",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = quickText,
                            onValueChange = { quickText = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = {
                                Text("Chase @Rebecca about the school place by friday !!")
                            },
                            minLines = 2,
                            shape = RoundedCornerShape(14.dp),
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { applyQuickCapture() },
                                enabled = quickText.isNotBlank(),
                            ) {
                                Icon(
                                    Icons.Default.AutoAwesome,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(7.dp))
                                Text("Fill the form")
                            }
                            androidx.compose.material3.OutlinedButton(
                                onClick = {
                                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                        putExtra(
                                            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                                        )
                                        putExtra(RecognizerIntent.EXTRA_PROMPT, "Describe the loop")
                                    }
                                    runCatching { voiceLauncher.launch(intent) }
                                },
                            ) {
                                Icon(
                                    Icons.Default.Mic,
                                    contentDescription = "Dictate",
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                            androidx.compose.material3.OutlinedButton(
                                onClick = { showTemplates = true },
                            ) { Text("Templates") }
                        }

                        AnimatedVisibility(visible = understood.isNotEmpty()) {
                            Column {
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    "Understood: ${understood.joinToString(" · ")}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = draft.title,
                onValueChange = { draft = draft.copy(title = it) },
                label = { Text("What must happen?") },
                placeholder = { Text("e.g. Receive lease renewal confirmation") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 1,
            )

            Spacer(Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = draft.counterparty,
                    onValueChange = { draft = draft.copy(counterparty = it) },
                    label = { Text("Person") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = draft.organisation,
                    onValueChange = { draft = draft.copy(organisation = it) },
                    label = { Text("Organisation") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
            }

            Spacer(Modifier.height(18.dp))
            FieldLabel("Who owns the next move?")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                Side.entries.forEachIndexed { index, side ->
                    SegmentedButton(
                        selected = draft.side == side,
                        onClick = { draft = draft.copy(side = side) },
                        shape = SegmentedButtonDefaults.itemShape(index, Side.entries.size),
                    ) { Text(if (side == Side.ME) "Me" else "Them") }
                }
            }

            Spacer(Modifier.height(18.dp))
            FieldLabel("Area")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(Categories.ALL) { category ->
                    FilterChip(
                        selected = draft.category == category,
                        onClick = { draft = draft.copy(category = category) },
                        label = { Text(category) },
                    )
                }
            }

            Spacer(Modifier.height(18.dp))
            FieldLabel("Deadline")
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AssistChip(
                    onClick = { showDatePicker = true },
                    label = {
                        Text(draft.due?.format(DATE_LABEL) ?: "No deadline")
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Default.CalendarMonth,
                            contentDescription = null,
                            modifier = Modifier.size(17.dp),
                        )
                    },
                )
                if (draft.due != null) {
                    TextButton(onClick = { draft = draft.copy(due = null) }) { Text("Clear") }
                }
            }
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(
                    listOf(
                        0L to "Today", 1L to "Tomorrow", 3L to "3 days",
                        7L to "1 week", 14L to "2 weeks", 30L to "1 month",
                    )
                ) { (days, label) ->
                    AssistChip(
                        onClick = { draft = draft.copy(due = today.plusDays(days)) },
                        label = { Text(label) },
                    )
                }
            }

            Spacer(Modifier.height(18.dp))
            FieldLabel("Impact if this is dropped — ${impactLabel(draft.impact)}")
            androidx.compose.material3.Slider(
                value = draft.impact.toFloat(),
                onValueChange = { draft = draft.copy(impact = it.toInt().coerceIn(1, 5)) },
                valueRange = 1f..5f,
                steps = 3,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = draft.reference,
                onValueChange = { draft = draft.copy(reference = it) },
                label = { Text("Reference / case number (optional)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = draft.tags,
                onValueChange = { draft = draft.copy(tags = it) },
                label = { Text("Tags, comma separated (optional)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = draft.notes,
                onValueChange = { draft = draft.copy(notes = it) },
                label = { Text("Context") },
                placeholder = { Text("What would future-you need to know?") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
            )

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = { onSave(draft.toLoop(existing, today)) },
                enabled = canSave,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (isNew) "Add to LoopGuard" else "Save changes")
            }
            Spacer(Modifier.height(40.dp))
        }
    }

    if (showDatePicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = (draft.due ?: today)
                .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        draft = draft.copy(
                            due = Instant.ofEpochMilli(millis).atZone(ZoneId.of("UTC")).toLocalDate(),
                        )
                    }
                    showDatePicker = false
                }) { Text("Set") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
            },
        ) { DatePicker(state = pickerState) }
    }

    if (showTemplates) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showTemplates = false },
            sheetState = sheetState,
        ) {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item {
                    Text("Start from a template", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Each one fills in who owns it, a realistic deadline and the context worth writing down.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                }

                Templates.GROUPS.forEach { group ->
                    item(key = group.title) {
                        Spacer(Modifier.height(10.dp))
                        Text(group.title, style = MaterialTheme.typography.titleMedium)
                        Text(
                            group.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                    items(group.templates, key = { it.id }) { template ->
                        Surface(
                            onClick = {
                                draft = draft.copy(
                                    title = template.titleTemplate,
                                    category = template.category,
                                    side = template.side,
                                    impact = template.impact,
                                    due = today.plusDays(template.dueInDays.toLong()),
                                    notes = template.notesHint,
                                    counterparty = draft.counterparty.ifBlank { "" },
                                    organisation = draft.organisation.ifBlank { template.counterpartyHint },
                                )
                                understood = listOf("template: ${template.name}")
                                showTemplates = false
                            },
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.padding(14.dp)) {
                                Text(template.name, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "${if (template.side == Side.ME) "Your move" else "Their move"} · " +
                                        "${impactLabel(template.impact)} impact · ${template.dueInDays} days",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(40.dp)) }
            }
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

private fun Draft.toLoop(existing: Loop?, today: LocalDate): Loop {
    val cleanedTags = tags.split(',')
        .map { it.trim().removePrefix("#") }
        .filter { it.isNotEmpty() }
        .joinToString(",")

    return existing?.copy(
        title = title.trim(),
        counterparty = counterparty.trim(),
        organisation = organisation.trim(),
        side = side.storageKey,
        category = category,
        dueDate = due?.toEpochDay(),
        impact = impact,
        notes = notes.trim(),
        reference = reference.trim(),
        tags = cleanedTags,
    ) ?: Loop(
        title = title.trim(),
        counterparty = counterparty.trim(),
        organisation = organisation.trim(),
        side = side.storageKey,
        category = category,
        dueDate = due?.toEpochDay(),
        impact = impact,
        lastActivity = today.toEpochDay(),
        notes = notes.trim(),
        reference = reference.trim(),
        tags = cleanedTags,
    )
}

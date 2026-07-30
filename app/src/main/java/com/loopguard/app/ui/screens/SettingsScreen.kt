package com.loopguard.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.loopguard.app.BuildConfig
import com.loopguard.app.data.ImportMode
import com.loopguard.app.data.ThemeMode
import com.loopguard.app.ui.LoopGuardState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: LoopGuardState,
    contentPadding: PaddingValues,
    onName: (String) -> Unit,
    onTheme: (ThemeMode) -> Unit,
    onDynamicColour: (Boolean) -> Unit,
    onReminders: (Boolean) -> Unit,
    onDigestHour: (Int) -> Unit,
    onCadence: (Int) -> Unit,
    buildExport: suspend () -> String,
    onImport: (String, ImportMode) -> Unit,
    onNotify: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = state.settings

    var pasteOpen by remember { mutableStateOf(false) }
    var pendingMode by remember { mutableStateOf(ImportMode.MERGE) }
    var modeDialogFor by remember { mutableStateOf<Uri?>(null) }
    var name by remember(settings.displayName) { mutableStateOf(settings.displayName) }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val json = buildExport()
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use {
                        it.write(json.toByteArray())
                    } ?: error("no stream")
                }.isSuccess
            }
            onNotify(if (ok) "Backup saved" else "Could not write that file")
        }
    }

    val openLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) modeDialogFor = uri }

    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(
                start = 16.dp,
                end = 16.dp,
                top = contentPadding.calculateTopPadding() + 8.dp,
                bottom = contentPadding.calculateBottomPadding() + 96.dp,
            ),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)

        // ---------------------------------------------------------------
        Spacer(Modifier.height(20.dp))
        SettingsSection("You") {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it; onName(it) },
                label = { Text("Your name") },
                supportingText = { Text("Used in greetings and to sign follow-up messages.") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }

        // ---------------------------------------------------------------
        Spacer(Modifier.height(20.dp))
        SettingsSection("Appearance") {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(ThemeMode.entries.toList()) { mode ->
                    FilterChip(
                        selected = settings.themeMode == mode,
                        onClick = { onTheme(mode) },
                        label = { Text(mode.label) },
                    )
                }
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                Spacer(Modifier.height(6.dp))
                ToggleRow(
                    title = "Match my wallpaper colours",
                    subtitle = "Use the Android 12+ dynamic palette instead of the LoopGuard one.",
                    checked = settings.dynamicColour,
                    onChange = onDynamicColour,
                )
            }
        }

        // ---------------------------------------------------------------
        Spacer(Modifier.height(20.dp))
        SettingsSection("Reminders") {
            ToggleRow(
                title = "Daily digest",
                subtitle = "One summary a day, plus a nudge for anything critical. Never more than three notifications.",
                checked = settings.remindersEnabled,
                onChange = onReminders,
            )
            if (settings.remindersEnabled) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "Digest time — ${"%02d".format(settings.digestHour)}:00",
                    style = MaterialTheme.typography.bodyMedium,
                )
                androidx.compose.material3.Slider(
                    value = settings.digestHour.toFloat(),
                    onValueChange = { onDigestHour(it.toInt()) },
                    valueRange = 5f..22f,
                    steps = 16,
                )
                Text(
                    "Chase again after ${settings.followUpCadenceDays} days of silence",
                    style = MaterialTheme.typography.bodyMedium,
                )
                androidx.compose.material3.Slider(
                    value = settings.followUpCadenceDays.toFloat(),
                    onValueChange = { onCadence(it.toInt()) },
                    valueRange = 1f..21f,
                    steps = 19,
                )
            }
        }

        // ---------------------------------------------------------------
        Spacer(Modifier.height(20.dp))
        SettingsSection("Backup and restore") {
            Text(
                "Your data never leaves this phone unless you export it. Backups are plain JSON, " +
                    "and they can also be read by LoopGuard 1.0.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { saveLauncher.launch(defaultBackupName()) },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(7.dp))
                    Text("Save file")
                }
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            val json = buildExport()
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, "LoopGuard backup")
                                putExtra(Intent.EXTRA_TEXT, json)
                            }
                            runCatching {
                                context.startActivity(Intent.createChooser(intent, "Share backup"))
                            }.onFailure { onNotify("Nothing available to share to.") }
                        }
                    },
                ) {
                    Icon(Icons.Default.Share, contentDescription = "Share backup", modifier = Modifier.size(17.dp))
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        runCatching {
                            openLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                        }.onFailure { onNotify("No file picker available.") }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Upload, contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(7.dp))
                    Text("Restore file")
                }
                OutlinedButton(onClick = { pasteOpen = true }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(7.dp))
                    Text("Paste text")
                }
            }

            Spacer(Modifier.height(12.dp))
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(13.dp)) {
                    Text(
                        "Coming from LoopGuard 1.0?",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "In the old app, open Loops and tap Backup, then share the text to yourself. " +
                            "Paste it here with \"Paste text\" and every loop, date and history entry comes across.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // ---------------------------------------------------------------
        Spacer(Modifier.height(20.dp))
        SettingsSection("About") {
            Text(
                "LoopGuard ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Fully offline. The app declares no internet permission at all, so nothing can be " +
                    "sent anywhere. No account, no cloud, no subscription.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "${state.openCount} open · ${state.completed.size} completed",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(40.dp))
    }

    if (pasteOpen) {
        PasteImportDialog(
            onDismiss = { pasteOpen = false },
            onConfirm = { text, mode ->
                pasteOpen = false
                onImport(text, mode)
            },
        )
    }

    modeDialogFor?.let { uri ->
        ImportModeDialog(
            onDismiss = { modeDialogFor = null },
            onPick = { mode ->
                modeDialogFor = null
                scope.launch {
                    val text = withContext(Dispatchers.IO) {
                        runCatching {
                            context.contentResolver.openInputStream(uri)
                                ?.bufferedReader()?.use { it.readText() }
                        }.getOrNull()
                    }
                    if (text.isNullOrBlank()) onNotify("That file could not be read.")
                    else onImport(text, mode)
                }
            },
        )
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Text(
        title.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(10.dp))
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) { content() }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun PasteImportDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, ImportMode) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(ImportMode.MERGE) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Paste a backup") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Backup JSON") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 5,
                    maxLines = 8,
                )
                Spacer(Modifier.height(12.dp))
                ModePicker(mode) { mode = it }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text, mode) },
                enabled = text.isNotBlank(),
            ) { Text("Import") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ImportModeDialog(onDismiss: () -> Unit, onPick: (ImportMode) -> Unit) {
    var mode by remember { mutableStateOf(ImportMode.MERGE) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("How should this be imported?") },
        text = { ModePicker(mode) { mode = it } },
        confirmButton = { TextButton(onClick = { onPick(mode) }) { Text("Import") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ModePicker(mode: ImportMode, onChange: (ImportMode) -> Unit) {
    Column {
        listOf(
            ImportMode.MERGE to ("Add to what I have" to "Keeps your current loops and skips anything already imported."),
            ImportMode.REPLACE to ("Replace everything" to "Wipes the app first. Use this for a true restore."),
        ).forEach { (value, copy) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp),
            ) {
                androidx.compose.material3.RadioButton(
                    selected = mode == value,
                    onClick = { onChange(value) },
                )
                Spacer(Modifier.width(4.dp))
                Column {
                    Text(copy.first, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        copy.second,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun defaultBackupName(): String = "loopguard-backup-${LocalDate.now()}.json"

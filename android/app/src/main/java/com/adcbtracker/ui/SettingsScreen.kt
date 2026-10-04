package com.adcbtracker.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adcbtracker.data.Cycles
import com.adcbtracker.data.Prefs
import com.adcbtracker.data.UAE_ZONE
import com.adcbtracker.service.AdcbNotificationListener
import com.adcbtracker.ui.components.AppCard
import com.adcbtracker.ui.components.SectionTitle
import com.adcbtracker.ui.theme.Amber
import com.adcbtracker.ui.theme.Emerald
import com.adcbtracker.ui.theme.Ink2
import com.adcbtracker.ui.theme.TextMid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

const val APP_VERSION = "2.5.0"

@Composable
fun SettingsScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cycleDay by vm.cycleStartDay.collectAsStateWithLifecycle()
    val threshold by vm.largeThreshold.collectAsStateWithLifecycle()

    var listenerEnabled by remember { mutableStateOf(AdcbNotificationListener.isEnabled(context)) }
    var batteryUnrestricted by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }
    var lastCaptured by remember { mutableLongStateOf(Prefs.getLastCapturedTx(context)) }
    var importing by remember { mutableStateOf(false) }
    var importResult by remember { mutableStateOf<String?>(null) }
    var thresholdText by remember(threshold) { mutableStateOf((threshold / 100).toString()) }
    var thresholdSaved by remember { mutableStateOf(false) }
    val budget by vm.budget.collectAsStateWithLifecycle()
    var budgetText by remember(budget) { mutableStateOf(if (budget > 0) (budget / 100).toString() else "") }
    var budgetSaved by remember { mutableStateOf(false) }
    var notifyLarge by remember { mutableStateOf(Prefs.getLargeExpenseNotify(context)) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        listenerEnabled = AdcbNotificationListener.isEnabled(context)
        batteryUnrestricted = isIgnoringBatteryOptimizations(context)
        lastCaptured = Prefs.getLastCapturedTx(context)
    }

    fun runImport() {
        importing = true
        importResult = null
        scope.launch {
            val r = vm.importFromSms(context)
            importing = false
            lastCaptured = Prefs.getLastCapturedTx(context)
            importResult = "Scanned ${r.scanned} messages · found ${r.matched} ADCB transactions · added ${r.imported} new."
        }
    }

    val smsPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[Manifest.permission.READ_SMS] == true) runImport()
        else importResult = "SMS permission was declined, so the inbox can't be scanned."
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) scope.launch {
            importResult = try {
                val n = exportCsv(context, uri, vm)
                if (n == 0) "No transactions to export" else "Exported $n transactions"
            } catch (e: Exception) {
                "Export failed: ${e.message}"
            }
        }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { Text("Settings", style = MaterialTheme.typography.headlineMedium) }

        item {
            AppCard {
                SectionTitle("Notification Access")
                Spacer(Modifier.height(8.dp))
                StatusLine(
                    listenerEnabled,
                    "✓ Enabled - App is capturing ADCB alerts",
                    "⚠ Not enabled - App cannot capture transactions",
                )
                if (lastCaptured > 0) {
                    Text("Last alert captured: ${ago(lastCaptured)}", style = MaterialTheme.typography.bodySmall, color = TextMid)
                }
                if (!listenerEnabled) {
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = { context.startActivity(Intent(AdcbNotificationListener.SETTINGS_ACTION)) }) { Text("Enable Now") }
                }
            }
        }

        item {
            AppCard {
                SectionTitle("Billing cycle", "Your card statement period. Home and Insights use it instead of calendar months.")
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Cycle starts on day", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    RoundButton("−", enabled = cycleDay > 1) { vm.setCycleStartDay(cycleDay - 1) }
                    Text(
                        "$cycleDay",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.width(48.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    RoundButton("+", enabled = cycleDay < 28) { vm.setCycleStartDay(cycleDay + 1) }
                }
                Spacer(Modifier.height(8.dp))
                val current = Cycles.cycleFor(LocalDate.now(UAE_ZONE), cycleDay)
                Text("Current cycle: ${current.label()}", style = MaterialTheme.typography.bodySmall, color = Emerald)
                if (cycleDay != 1) {
                    Spacer(Modifier.height(4.dp))
                    Text("Set to 1 to use normal calendar months.", style = MaterialTheme.typography.bodySmall, color = TextMid)
                }
            }
        }

        item {
            AppCard {
                SectionTitle("Cycle budget", "How much you want to spend per billing cycle. Leave empty for no budget.")
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = budgetText,
                        onValueChange = { budgetText = it.filter(Char::isDigit); budgetSaved = false },
                        label = { Text("Budget (AED)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(10.dp))
                    Button(onClick = {
                        vm.setBudget((budgetText.toLongOrNull() ?: 0L) * 100)
                        budgetSaved = true
                        scope.launch { com.adcbtracker.service.SpendWidget.refresh(context) }
                    }) { Text(if (budgetSaved) "Saved" else "Save") }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Home and Insights then show how much is safe to spend per day until your statement.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMid,
                )
            }
        }

        item {
            AppCard {
                SectionTitle("Import from SMS")
                Spacer(Modifier.height(6.dp))
                Text(
                    "Scan your SMS inbox for ADCB transaction alerts and add any that aren't already tracked — including ones from before you installed the app. Your messages are read on your device only and never leave it. New ADCB texts are also captured automatically.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMid,
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    enabled = !importing,
                    onClick = {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED) runImport()
                        else smsPermission.launch(arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS))
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (importing) "Scanning…" else "Scan SMS inbox") }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    enabled = !importing,
                    onClick = {
                        scope.launch {
                            val removed = vm.cleanupNonExpenses()
                            importResult = if (removed > 0) "Removed $removed non-expense entr${if (removed == 1) "y" else "ies"} (salary, transfers, card payments)."
                            else "No non-expense entries found — nothing to clean up."
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Remove salary, transfers & payments") }
                importResult?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = Emerald)
                }
            }
        }

        item {
            AppCard {
                SectionTitle("Large Expense Alert")
                Spacer(Modifier.height(6.dp))
                Text(
                    "Transactions at or above this amount (AED) are flagged with a warning badge in your transaction lists.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMid,
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = thresholdText,
                        onValueChange = { thresholdText = it.filter(Char::isDigit); thresholdSaved = false },
                        label = { Text("Threshold (AED)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(10.dp))
                    Button(onClick = {
                        thresholdText.toLongOrNull()?.let { vm.setLargeThreshold(it * 100); thresholdSaved = true }
                    }) { Text(if (thresholdSaved) "Saved" else "Save") }
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Notify me", style = MaterialTheme.typography.bodyLarge)
                        Text("Phone notification when a large expense is captured", style = MaterialTheme.typography.bodySmall, color = TextMid)
                    }
                    androidx.compose.material3.Switch(
                        checked = notifyLarge,
                        onCheckedChange = {
                            notifyLarge = it
                            Prefs.setLargeExpenseNotify(context, it)
                        },
                    )
                }
            }
        }

        item {
            AppCard {
                SectionTitle("Battery Optimization")
                Spacer(Modifier.height(8.dp))
                StatusLine(batteryUnrestricted, "✓ Unrestricted - capture keeps running", "⚠ Restricted - Android may stop capture in the background")
                if (!batteryUnrestricted) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Your phone may close this app in the background to save battery, which silently stops it from catching new ADCB alerts. Allow it to run unrestricted to fix this.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMid,
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = {
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
                        runCatching { context.startActivity(intent) }
                            .onFailure { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                    }) { Text("Allow Unrestricted Battery Use") }
                }
            }
        }

        item {
            AppCard {
                SectionTitle("Export Data")
                Spacer(Modifier.height(6.dp))
                Text(
                    "Export your transaction history to CSV for analysis in Excel or other tools. Includes category information.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMid,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = {
                    val ts = java.time.LocalDateTime.now(UAE_ZONE).format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.US))
                    exportLauncher.launch("adcb_transactions_$ts.csv")
                }) { Text("Export to CSV") }
            }
        }

        item {
            AppCard {
                SectionTitle("About ADCB Tracker", "Version $APP_VERSION")
                Spacer(Modifier.height(8.dp))
                Text("New in v$APP_VERSION:", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                Text(
                    "• New app icon\n" +
                        "• Tap any transaction anywhere to change its category (one or all from that merchant)\n" +
                        "• Tap a category to see and fix its transactions; Home flags uncategorized ones\n" +
                        "• Smarter statement projection that ignores one-off big purchases\n" +
                        "• Cycle budget with safe-to-spend per day, available credit on Home\n" +
                        "• Large-expense notifications and a home-screen widget",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMid,
                )
                Spacer(Modifier.height(8.dp))
                Text("🔒 All data stays on your device. No internet required.", style = MaterialTheme.typography.bodySmall, color = TextMid)
            }
        }
    }
}

@Composable
private fun StatusLine(ok: Boolean, okText: String, badText: String) {
    Text(
        if (ok) okText else badText,
        style = MaterialTheme.typography.bodyMedium,
        color = if (ok) Emerald else Amber,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun RoundButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Ink2)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.titleLarge, color = if (enabled) MaterialTheme.colorScheme.onSurface else TextMid)
    }
}

private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

private fun ago(epochMillis: Long): String {
    val mins = (System.currentTimeMillis() - epochMillis) / 60_000
    return when {
        mins < 1 -> "just now"
        mins < 60 -> "$mins minute${if (mins == 1L) "" else "s"} ago"
        mins < 60 * 24 -> "${mins / 60} hour${if (mins / 60 == 1L) "" else "s"} ago"
        else -> "${mins / (60 * 24)} day${if (mins / (60 * 24) == 1L) "" else "s"} ago"
    }
}

private suspend fun exportCsv(context: Context, uri: Uri, vm: MainViewModel): Int = withContext(Dispatchers.IO) {
    val txs = vm.getAllForExport()
    if (txs.isEmpty()) return@withContext 0
    val cats = vm.categories.value.associateBy { it.id }
    val dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)
    val timeFmt = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US)
    fun esc(s: String?): String {
        val v = s ?: ""
        return if (v.contains(',') || v.contains('"') || v.contains('\n')) "\"" + v.replace("\"", "\"\"") + "\"" else v
    }
    context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { w ->
        w.write("Date,Time,Merchant,Category,Amount,Currency,Card,Location,Available Limit\n")
        txs.forEach { t ->
            val z = Instant.ofEpochMilli(t.tsEpochMillis).atZone(UAE_ZONE)
            w.write(
                listOf(
                    z.format(dateFmt), z.format(timeFmt), esc(t.merchant ?: "Unknown"),
                    esc(t.categoryId?.let(cats::get)?.name ?: "Uncategorized"),
                    String.format(Locale.US, "%.2f", t.amountMinor / 100.0), t.currency, esc(t.cardId), esc(t.location),
                    t.avlCreditLimitMinor?.let { String.format(Locale.US, "%.2f", it / 100.0) } ?: "",
                ).joinToString(",") + "\n"
            )
        }
    }
    txs.size
}

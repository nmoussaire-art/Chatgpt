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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deadlineguardian.ui.GuardianViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: GuardianViewModel,
    onBack: () -> Unit
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Card("Assumptions") {
                    Text(
                        "Used only when the paperwork doesn't state a policy. " +
                            "You can always override a date on the item itself.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))

                    SliderRow(
                        label = "Default return window",
                        value = settings.defaultReturnDays.toFloat(),
                        range = 7f..90f,
                        steps = 82,
                        display = "${settings.defaultReturnDays} days",
                        onChange = {
                            viewModel.saveSettings(settings.copy(defaultReturnDays = it.toInt()))
                        }
                    )

                    SliderRow(
                        label = "Electronics warranty",
                        value = (settings.electronicsWarrantyDays / 365f),
                        range = 1f..5f,
                        steps = 3,
                        display = "${settings.electronicsWarrantyDays / 365} years",
                        onChange = {
                            viewModel.saveSettings(
                                settings.copy(electronicsWarrantyDays = (it * 365).toInt())
                            )
                        }
                    )

                    SliderRow(
                        label = "Document renewal warning",
                        value = settings.documentLeadDays.toFloat(),
                        range = 30f..180f,
                        steps = 29,
                        display = "${settings.documentLeadDays} days ahead",
                        onChange = {
                            viewModel.saveSettings(settings.copy(documentLeadDays = it.toInt()))
                        }
                    )
                }
            }

            item {
                Card("Reading dates") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Day comes first", style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (settings.preferDayFirst) "03/04 means 3 April"
                                else "03/04 means 4 March",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = settings.preferDayFirst,
                            onCheckedChange = {
                                viewModel.saveSettings(settings.copy(preferDayFirst = it))
                            }
                        )
                    }
                }
            }

            item {
                Card("Daily check") {
                    SliderRow(
                        label = "Remind me at",
                        value = settings.notifyHour.toFloat(),
                        range = 6f..22f,
                        steps = 15,
                        display = "%02d:00".format(settings.notifyHour),
                        onChange = {
                            viewModel.saveSettings(settings.copy(notifyHour = it.toInt()))
                        }
                    )
                }
            }

            item {
                Card("Privacy") {
                    Text(
                        "Your photos, the text read from them and every deadline stay in this " +
                            "app's private storage. Recognition runs on-device using a model " +
                            "bundled inside the app, so nothing is uploaded and it all works in " +
                            "airplane mode. There is no account and no server.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "One caveat, stated plainly: the Google ML Kit library that does the " +
                            "text recognition adds an internet permission of its own and may " +
                            "send Google anonymous statistics about its own usage. It never " +
                            "receives your images or their contents.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun Card(title: String, content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                title.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    display: String,
    onChange: (Float) -> Unit
) {
    Column(Modifier.padding(bottom = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(
                display,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            steps = steps
        )
    }
}

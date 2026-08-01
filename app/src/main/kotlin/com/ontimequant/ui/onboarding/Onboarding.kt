package com.ontimequant.ui.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.QueryStats
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ontimequant.data.prefs.ConfidencePresets
import com.ontimequant.ui.theme.OnTimeQuantTheme

/**
 * First launch: four short steps, all of them skippable except choosing a confidence level.
 *
 * There is no account, no login, and nothing is gated behind a permission — the demo
 * journey is already loaded and will forecast without any of them.
 */
@Composable
fun OnboardingScreen(
    viewModel: OnboardingViewModel,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var step by remember { mutableIntStateOf(0) }

    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { viewModel.refreshPermissions() }

    val calendarLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> viewModel.onCalendarPermission(granted) }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { viewModel.refreshPermissions() }

    Column(
        modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 20.dp)
            .testTag("onboarding_screen"),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(4) { index ->
                Box(
                    Modifier
                        .weight(1f)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(
                            if (index <= step) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant,
                        ),
                )
            }
        }
        Spacer(Modifier.height(28.dp))

        Box(Modifier.weight(1f)) {
            AnimatedContent(targetState = step, label = "onboarding") { current ->
                when (current) {
                    0 -> ValueStep()
                    1 -> PermissionStep(
                        locationGranted = state.locationGranted,
                        calendarGranted = state.calendarGranted,
                        notificationsGranted = state.notificationsGranted,
                        onRequestLocation = {
                            locationLauncher.launch(
                                arrayOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION,
                                ),
                            )
                        },
                        onRequestCalendar = { calendarLauncher.launch(Manifest.permission.READ_CALENDAR) },
                        onRequestNotifications = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        },
                    )
                    2 -> ConfidenceStep(
                        selected = state.confidenceTarget,
                        onSelect = viewModel::setConfidence,
                    )
                    else -> ReadyStep(
                        demoForced = state.demoForced,
                        onOpenDemo = { viewModel.finish(demoMode = true, onFinished) },
                        onUseLive = { viewModel.finish(demoMode = false, onFinished) },
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            if (step > 0) {
                TextButton(onClick = { step-- }) { Text("Back") }
            } else {
                TextButton(onClick = { viewModel.finish(demoMode = true, onFinished) }) {
                    Text("Skip")
                }
            }
            if (step < 3) {
                Button(
                    onClick = { step++ },
                    modifier = Modifier.testTag("onboarding_next"),
                ) {
                    Text(if (step == 1) "Continue" else "Next")
                }
            }
        }
    }
}

@Composable
private fun ValueStep() {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Icon(
            Icons.Outlined.QueryStats,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(24.dp))
        Text(
            "A better question than “how long will it take?”",
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "Navigation apps give you one number: 25 minutes. That number is a guess with no " +
                "stated confidence, and it does not know when you should leave.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "OnTime Quant simulates thousands of versions of your journey — traffic, how long you " +
                "take to get out of the door, parking, the walk in — and tells you the latest minute " +
                "you can leave and still have the confidence you asked for.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(24.dp))
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            shape = RoundedCornerShape(20.dp),
        ) {
            Text(
                "“Leave at 7:18 to have a 90% chance of arriving before 8:00.”",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(18.dp),
            )
        }
    }
}

@Composable
private fun PermissionStep(
    locationGranted: Boolean,
    calendarGranted: Boolean,
    notificationsGranted: Boolean,
    onRequestLocation: () -> Unit,
    onRequestCalendar: () -> Unit,
    onRequestNotifications: () -> Unit,
) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Text("What would you like to switch on?", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "All of these are optional. The app works without any of them — you will just enter " +
                "a few things by hand instead.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))

        PermissionRow(
            icon = Icons.Outlined.LocationOn,
            title = "Location",
            body = "Uses your current position as a starting point, and notices when you actually " +
                "set off and arrive so the model can learn. Nothing in between is recorded.",
            granted = locationGranted,
            onRequest = onRequestLocation,
        )
        Spacer(Modifier.height(14.dp))
        PermissionRow(
            icon = Icons.Outlined.CalendarMonth,
            title = "Calendar",
            body = "Read-only, and only the calendars you pick. Titles, times and locations are read " +
                "on your device. Your calendar is never uploaded anywhere.",
            granted = calendarGranted,
            onRequest = onRequestCalendar,
        )
        Spacer(Modifier.height(14.dp))
        PermissionRow(
            icon = Icons.Outlined.Notifications,
            title = "Notifications",
            body = "One alert when it is time to leave, and one if the forecast changes materially. " +
                "Not a stream of updates.",
            granted = notificationsGranted,
            onRequest = onRequestNotifications,
        )
    }
}

@Composable
private fun PermissionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String,
    granted: Boolean,
    onRequest: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(10.dp))
            if (granted) {
                Text("On", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            } else {
                OutlinedButton(onClick = onRequest) { Text("Allow") }
            }
        }
    }
}

@Composable
private fun ConfidenceStep(selected: Double, onSelect: (Double) -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Text("How safe do you want to be?", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "This is the probability of arriving on time that OnTime Quant will aim for. " +
                "You can change it per appointment later.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(ConfidencePresets.all) { (label, value, description) ->
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (kotlin.math.abs(selected - value) < 0.005) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                    ),
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "$label · ${(value * 100).toInt()}%",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(description, style = MaterialTheme.typography.bodySmall)
                        }
                        FilterChip(
                            selected = kotlin.math.abs(selected - value) < 0.005,
                            onClick = { onSelect(value) },
                            label = { Text("Choose") },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReadyStep(
    demoForced: Boolean,
    onOpenDemo: () -> Unit,
    onUseLive: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Ready", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        Text(
            if (demoForced) {
                "No routing API key is configured in this build, so OnTime Quant will run in demo " +
                    "mode. The demo is not a mock-up: it runs the real forecasting engine over a " +
                    "built-in Abu Dhabi journey with 24 completed trips of history. Everything is " +
                    "labelled DEMO so you always know what you are looking at."
            } else {
                "A demo journey is already loaded so you can see exactly what the app does. Switch " +
                    "to live data whenever you like — the engine is identical either way."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        Button(
            onClick = onOpenDemo,
            modifier = Modifier.fillMaxWidth().height(52.dp).testTag("onboarding_finish"),
            shape = RoundedCornerShape(16.dp),
        ) {
            Text("Open the demo journey")
        }
        if (!demoForced) {
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = onUseLive,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text("Use live data")
            }
        }
    }
}

@Preview(showBackground = true, heightDp = 800)
@Composable
private fun OnboardingValuePreview() {
    OnTimeQuantTheme {
        Column(Modifier.padding(24.dp)) { ValueStep() }
    }
}

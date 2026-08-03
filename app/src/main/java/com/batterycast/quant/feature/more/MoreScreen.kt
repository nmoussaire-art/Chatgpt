package com.batterycast.quant.feature.more

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.batterycast.quant.core.ui.components.BatteryCastCard
import com.batterycast.quant.core.ui.components.SectionHeader
import com.batterycast.quant.feature.Routes

private data class MoreEntry(
    val route: String,
    val title: String,
    val description: String,
    val icon: ImageVector,
)

@Composable
fun MoreScreen(onNavigate: (String) -> Unit) {
    val entries = listOf(
        MoreEntry(
            Routes.SCENARIOS,
            "Scenario laboratory",
            "Compare navigation, video, gaming, standby and more against your baseline.",
            Icons.Outlined.Science,
        ),
        MoreEntry(
            Routes.WHAT_CHANGED,
            "What changed",
            "Why the forecast moved, with the numbers behind each reason.",
            Icons.Outlined.Timeline,
        ),
        MoreEntry(
            Routes.HISTORY,
            "Battery history",
            "Battery level over time, charging sessions and unusual drain periods.",
            Icons.Outlined.History,
        ),
        MoreEntry(
            Routes.ACCURACY,
            "Model accuracy",
            "How this app's past forecasts actually turned out.",
            Icons.AutoMirrored.Outlined.ShowChart,
        ),
        MoreEntry(
            Routes.SETTINGS,
            "Permissions and privacy",
            "Optional permissions, measurement frequency, data export and deletion.",
            Icons.Outlined.Settings,
        ),
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { SectionHeader(title = "More") }

        items(entries, key = { it.route }) { entry ->
            BatteryCastCard(
                modifier = Modifier.clickable { onNavigate(entry.route) },
                contentPadding = PaddingValues(18.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Icon(
                        imageVector = entry.icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(entry.title, style = MaterialTheme.typography.titleMedium)
                        Text(
                            entry.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        item {
            Text(
                text = "All battery observations and forecasts remain on your device.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

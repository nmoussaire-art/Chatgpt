package com.batterycast.quant.feature.whatchanged

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.TrendingDown
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.batterycast.quant.core.ui.components.BatteryCastCard
import com.batterycast.quant.core.ui.components.CollectingDataCard
import com.batterycast.quant.core.ui.components.SectionHeader
import com.batterycast.quant.core.ui.components.StatRow
import com.batterycast.quant.core.ui.components.StatusChip
import com.batterycast.quant.core.ui.format.Formatters
import com.batterycast.quant.core.ui.theme.BatteryCastTheme
import com.batterycast.quant.feature.dashboard.DashboardViewModel
import com.batterycast.quant.feature.shared.ForecastUiState
import com.batterycast.quant.forecasting.model.DriverDirection
import com.batterycast.quant.forecasting.model.ForecastDriver

/**
 * Why the forecast looks the way it does.
 *
 * Each entry is a comparison between two measured quantities, with the numbers shown. Where the
 * app cannot support a claim — most notably, attributing drain to a particular app — it says so
 * instead of making one.
 */
@Composable
fun WhatChangedScreen(viewModel: DashboardViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            SectionHeader(
                title = "What changed",
                subtitle = "Everything here is a comparison against this phone's own history.",
            )
        }

        when (val current = state) {
            is ForecastUiState.Ready -> {
                items(current.forecast.drivers) { driver -> DriverCard(driver) }

                item {
                    BatteryCastCard {
                        SectionHeader(title = "Measurement quality")
                        val quality = current.forecast.dataQuality
                        StatRow("Usable readings", "${quality.usableObservationCount}")
                        StatRow("Discarded as inconsistent", "${quality.rejectedCount}")
                        StatRow(
                            "Observed history",
                            "${String.format("%.1f", quality.observedSpanHours)} hours",
                        )
                        StatRow(
                            "Discharge observed",
                            "${quality.totalPercentObserved.toInt()} percentage points",
                        )
                        StatRow(
                            "Newest reading",
                            Formatters.relativeAge(quality.newestObservationAgeMs),
                        )
                        if (quality.unsupportedFields.isNotEmpty()) {
                            Text(
                                text = "This device does not report: " +
                                    "${quality.unsupportedFields.joinToString()}. The forecast uses " +
                                    "battery-percentage changes instead, which is coarser.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                item {
                    BatteryCastCard {
                        SectionHeader(title = "On app attribution")
                        Text(
                            text = "BatteryCast never claims that a specific app used a specific " +
                                "amount of battery. Android does not give third-party apps a " +
                                "defensible per-app energy figure, so the strongest statement made " +
                                "here is that a kind of activity coincided with a change in drain.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            is ForecastUiState.Collecting -> item {
                CollectingDataCard(
                    headline = current.headline,
                    detail = current.detail,
                    progress = current.progress,
                )
            }

            ForecastUiState.Loading -> item {
                CollectingDataCard(headline = "Reading the battery…", detail = "Taking a live measurement.")
            }
        }
    }
}

@Composable
private fun DriverCard(driver: ForecastDriver) {
    val semantic = BatteryCastTheme.semanticColors
    val (color, icon) = when (driver.direction) {
        DriverDirection.WORSE -> semantic.risk to Icons.AutoMirrored.Outlined.TrendingUp
        DriverDirection.BETTER -> semantic.positive to Icons.AutoMirrored.Outlined.TrendingDown
        DriverDirection.UNCERTAINTY -> semantic.caution to Icons.AutoMirrored.Outlined.HelpOutline
        DriverDirection.NEUTRAL -> MaterialTheme.colorScheme.secondary to Icons.Outlined.CheckCircle
    }

    BatteryCastCard {
        StatusChip(
            text = when (driver.direction) {
                DriverDirection.WORSE -> "Shortens the forecast"
                DriverDirection.BETTER -> "Extends the forecast"
                DriverDirection.UNCERTAINTY -> "Widens the interval"
                DriverDirection.NEUTRAL -> "No change"
            },
            color = color,
            icon = icon,
        )
        Text(
            text = driver.headline,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = driver.detail,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

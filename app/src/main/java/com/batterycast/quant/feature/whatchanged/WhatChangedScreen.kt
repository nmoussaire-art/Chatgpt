package com.batterycast.quant.feature.whatchanged

import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.batterycast.quant.core.ui.components.InfoCard
import com.batterycast.quant.core.ui.components.SectionHeader
import com.batterycast.quant.core.ui.components.CompactRow
import com.batterycast.quant.core.ui.components.InlineStatus
import com.batterycast.quant.core.ui.format.Formatters
import com.batterycast.quant.core.ui.theme.BatteryCastTheme
import com.batterycast.quant.core.ui.components.DetailScaffold
import com.batterycast.quant.feature.home.HomeViewModel
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
fun WhatChangedScreen(
    onBack: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    DetailScaffold(
        title = "What changed",
        subtitle = "Every line here compares two measured quantities.",
        onBack = onBack,
    ) {
        when (val current = state) {
            is ForecastUiState.Ready -> {
                items(current.forecast.drivers) { driver -> DriverCard(driver) }

                item {
                    InfoCard {
                        SectionHeader(title = "Measurement quality")
                        val quality = current.forecast.dataQuality
                        CompactRow("Usable readings", "${quality.usableObservationCount}")
                        CompactRow("Discarded as inconsistent", "${quality.rejectedCount}")
                        CompactRow(
                            "Observed history",
                            "${String.format("%.1f", quality.observedSpanHours)} hours",
                        )
                        CompactRow(
                            "Discharge observed",
                            "${quality.totalPercentObserved.toInt()} percentage points",
                        )
                        CompactRow(
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
                    InfoCard {
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
                InfoCard {
                    Text(
                        text = current.headline,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            ForecastUiState.Loading -> item {
                InfoCard {
                    Text(
                        text = "Reading your battery…",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

@Composable
private fun DriverCard(driver: ForecastDriver) {
    val semantic = BatteryCastTheme.semanticColors
    val color = when (driver.direction) {
        DriverDirection.WORSE -> semantic.risk
        DriverDirection.BETTER -> semantic.healthy
        DriverDirection.UNCERTAINTY -> semantic.caution
        DriverDirection.NEUTRAL -> semantic.learning
    }

    InfoCard {
        InlineStatus(
            text = when (driver.direction) {
                DriverDirection.WORSE -> "Shortens the forecast"
                DriverDirection.BETTER -> "Extends the forecast"
                DriverDirection.UNCERTAINTY -> "Widens the interval"
                DriverDirection.NEUTRAL -> "No change"
            },
            color = color,
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

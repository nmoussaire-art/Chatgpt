package com.ontimequant.ui.insights

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.ontimequant.data.repository.TripRepository
import com.ontimequant.forecast.Insight
import com.ontimequant.forecast.InsightCategory
import com.ontimequant.ui.components.EmptyState
import com.ontimequant.ui.components.ProvenanceChip
import com.ontimequant.ui.components.SectionCard
import com.ontimequant.ui.preview.PreviewData
import com.ontimequant.ui.theme.OnTimeQuantTheme
import com.ontimequant.model.DataProvenance
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class InsightsUiState(
    val loading: Boolean = true,
    val insights: List<Insight> = emptyList(),
    val tripCount: Int = 0,
)

@HiltViewModel
class InsightsViewModel @Inject constructor(
    private val trips: TripRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(InsightsUiState())
    val state: StateFlow<InsightsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            trips.tripCount.collect { count ->
                _state.update { it.copy(tripCount = count) }
                _state.update { it.copy(insights = trips.insights(), loading = false) }
            }
        }
    }
}

/**
 * Insights are only shown when the evidence supports them.
 *
 * There is no "insight of the day" filler. If the history is too thin, this screen says so
 * and shows nothing else — an invented pattern would undermine every real one.
 */
@Composable
fun InsightsScreen(viewModel: InsightsViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    InsightsContent(state, modifier)
}

/** Stateless body, exposed so previews and screenshot tests can render it directly. */
@Composable
fun InsightsPreviewContent(
    insights: List<Insight>,
    tripCount: Int,
    modifier: Modifier = Modifier,
) = InsightsContent(InsightsUiState(loading = false, insights = insights, tripCount = tripCount), modifier)

@Composable
private fun InsightsContent(state: InsightsUiState, modifier: Modifier = Modifier) {
    if (state.insights.isEmpty() && !state.loading) {
        EmptyState(
            icon = Icons.Outlined.Insights,
            title = "Not enough journeys yet",
            body = "OnTime Quant only reports a pattern once there is enough evidence for it. " +
                "You have ${state.tripCount} completed ${if (state.tripCount == 1) "trip" else "trips"}; " +
                "patterns start appearing at around five on the same route.",
            modifier = modifier.testTag("insights_screen"),
        )
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().testTag("insights_screen"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                "Everything below is measured from your own completed journeys, with the number of " +
                    "trips it rests on.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(state.insights, key = { it.id }) { insight ->
            SectionCard(
                trailing = {
                    ProvenanceChip(
                        DataProvenance.LEARNED,
                        text = "${insight.sampleSize} trips",
                    )
                },
            ) {
                Text(insight.headline, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    insight.detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    categoryLabel(insight.category),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

private fun categoryLabel(category: InsightCategory) = when (category) {
    InsightCategory.TIMING -> "TIMING"
    InsightCategory.ROUTE -> "ROUTE"
    InsightCategory.READINESS -> "GETTING READY"
    InsightCategory.PARKING -> "PARKING"
    InsightCategory.WEATHER -> "WEATHER"
    InsightCategory.RELIABILITY -> "FORECAST RELIABILITY"
}

@Preview(name = "Insights", showBackground = true, heightDp = 1200)
@Composable
private fun InsightsPreview() {
    OnTimeQuantTheme {
        InsightsContent(
            InsightsUiState(loading = false, insights = PreviewData.insights, tripCount = 24),
        )
    }
}

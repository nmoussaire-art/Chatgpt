package com.ontimequant.ui.journeys

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.ontimequant.data.repository.JourneyRepository
import com.ontimequant.data.repository.TripRepository
import com.ontimequant.forecast.HistoryMapper
import com.ontimequant.forecast.ModelPriors
import com.ontimequant.forecast.Stats
import com.ontimequant.forecast.TravelModelFitter
import com.ontimequant.model.CompletedTrip
import com.ontimequant.model.PersonalizationLevel
import com.ontimequant.model.SavedJourney
import com.ontimequant.model.TimeBucket
import com.ontimequant.ui.components.EmptyState
import com.ontimequant.ui.components.PersonalizationChip
import com.ontimequant.ui.components.SectionCard
import com.ontimequant.ui.components.StatBlock
import com.ontimequant.ui.format.formatDuration
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.format.TextStyle
import java.util.Locale
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

data class JourneySummary(
    val journey: SavedJourney,
    val originLabel: String,
    val destinationLabel: String,
    val tripCount: Int,
    val typicalTravelSeconds: Double?,
    val typicalPreparationSeconds: Double?,
    val typicalParkingSeconds: Double?,
    val recentBiasFraction: Double?,
    val personalization: PersonalizationLevel,
    val busiestDay: String?,
    val commonBucket: TimeBucket?,
)

data class JourneysUiState(
    val loading: Boolean = true,
    val journeys: List<JourneySummary> = emptyList(),
)

@HiltViewModel
class JourneysViewModel @Inject constructor(
    private val journeys: JourneyRepository,
    private val trips: TripRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(JourneysUiState())
    val state: StateFlow<JourneysUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            journeys.savedJourneys.collect { saved ->
                val places = journeys.savedLocations.first().associateBy { it.id }
                val allTrips = trips.completedTrips()
                _state.update {
                    it.copy(
                        loading = false,
                        journeys = saved.map { journey ->
                            summarise(journey, allTrips.filter { t -> t.journeyId == journey.id },
                                places[journey.originId]?.label ?: "Origin",
                                places[journey.destinationId]?.label ?: "Destination")
                        },
                    )
                }
            }
        }
    }

    private fun summarise(
        journey: SavedJourney,
        journeyTrips: List<CompletedTrip>,
        originLabel: String,
        destinationLabel: String,
    ): JourneySummary {
        val usable = journeyTrips.filter { !it.excludedFromLearning }
        val model = TravelModelFitter.fit(HistoryMapper.toLearningHistory(usable).travel, ModelPriors.DEFAULT)
        val group = model.groupSummary("route:${journey.id}")
        val level = when {
            usable.size >= 18 -> PersonalizationLevel.PERSONALIZED
            usable.size >= 8 -> PersonalizationLevel.PARTIALLY_PERSONALIZED
            usable.size >= 3 -> PersonalizationLevel.LEARNING
            else -> PersonalizationLevel.PRELIMINARY
        }
        return JourneySummary(
            journey = journey,
            originLabel = originLabel,
            destinationLabel = destinationLabel,
            tripCount = journeyTrips.size,
            typicalTravelSeconds = usable.takeIf { it.isNotEmpty() }
                ?.let { Stats.median(it.map { t -> t.actualTravelSeconds }) },
            typicalPreparationSeconds = usable.mapNotNull { it.preparationSeconds }
                .takeIf { it.isNotEmpty() }?.let(Stats::median),
            typicalParkingSeconds = usable.mapNotNull { it.parkingSeconds }
                .takeIf { it.isNotEmpty() }?.let(Stats::median),
            recentBiasFraction = group?.takeIf { it.count >= 3 }?.let { exp(it.meanLogRatio) - 1.0 },
            personalization = level,
            busiestDay = usable.groupBy { it.dayOfWeek }
                .maxByOrNull { entry -> Stats.median(entry.value.map { it.actualTravelSeconds }) }
                ?.takeIf { it.value.size >= 3 }
                ?.key?.getDisplayName(TextStyle.FULL, Locale.getDefault()),
            commonBucket = usable.groupingBy { it.timeBucket }.eachCount()
                .maxByOrNull { it.value }?.key,
        )
    }
}

@Composable
fun JourneysScreen(viewModel: JourneysViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    if (state.journeys.isEmpty() && !state.loading) {
        EmptyState(
            icon = Icons.Outlined.Route,
            title = "No saved journeys yet",
            body = "Journeys are created automatically when you add an appointment. Once you have " +
                "made the same trip a few times, its own travel time, preparation and parking " +
                "patterns appear here.",
            modifier = modifier.testTag("journeys_screen"),
        )
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().testTag("journeys_screen"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items(state.journeys, key = { it.journey.id }) { summary ->
            SectionCard(
                title = summary.journey.name,
                subtitle = "${summary.originLabel} → ${summary.destinationLabel}",
                trailing = { PersonalizationChip(summary.personalization) },
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    StatBlock(
                        "Typical drive",
                        summary.typicalTravelSeconds?.let(::formatDuration) ?: "—",
                        Modifier.weight(1f),
                    )
                    StatBlock(
                        "Preparation",
                        summary.typicalPreparationSeconds?.let(::formatDuration) ?: "—",
                        Modifier.weight(1f),
                    )
                    StatBlock(
                        "Parking",
                        summary.typicalParkingSeconds?.let(::formatDuration) ?: "—",
                        Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    StatBlock("Completed trips", "${summary.tripCount}", Modifier.weight(1f))
                    StatBlock(
                        "Recent forecast bias",
                        summary.recentBiasFraction?.let { bias ->
                            val pct = (bias * 100).roundToInt()
                            when {
                                abs(pct) < 1 -> "none"
                                pct > 0 -> "+$pct% slower"
                                else -> "$pct% faster"
                            }
                        } ?: "not enough data",
                        Modifier.weight(1.4f),
                        supporting = "vs the routing estimate",
                    )
                }
                if (summary.busiestDay != null || summary.commonBucket != null) {
                    Spacer(Modifier.height(14.dp))
                    Text(
                        buildString {
                            summary.commonBucket?.let {
                                append("Usually made in the ${bucketLabel(it).lowercase()}. ")
                            }
                            summary.busiestDay?.let { append("Slowest on $it.") }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun bucketLabel(bucket: TimeBucket) = when (bucket) {
    TimeBucket.EARLY_MORNING -> "Early morning"
    TimeBucket.MORNING_PEAK -> "Morning peak"
    TimeBucket.MIDDAY -> "Midday"
    TimeBucket.EVENING_PEAK -> "Evening peak"
    TimeBucket.EVENING -> "Evening"
}

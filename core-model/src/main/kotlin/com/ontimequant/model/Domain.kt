package com.ontimequant.model

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

// ---------------------------------------------------------------------------
// Places & journeys
// ---------------------------------------------------------------------------

enum class LocationKind { HOME, WORK, SCHOOL, AIRPORT, OTHER }

data class SavedLocation(
    val id: String,
    val label: String,
    val address: String,
    val point: GeoPoint,
    val kind: LocationKind = LocationKind.OTHER,
    val providerPlaceId: String? = null,
    /** Default parking assumption for this destination, in minutes; null = use global default. */
    val defaultParkingMinutes: Double? = null,
    /** Default walk-in assumption for this destination, in minutes; null = use global default. */
    val defaultWalkingMinutes: Double? = null,
)

data class PlaceSuggestion(
    val providerPlaceId: String,
    val primaryText: String,
    val secondaryText: String,
)

/**
 * A journey the user makes repeatedly. Its [id] is the key under which route-level
 * model state (bias, residuals, parking, walking) is accumulated.
 */
data class SavedJourney(
    val id: String,
    val name: String,
    val originId: String,
    val destinationId: String,
    val defaultConfidence: Double = 0.90,
    val defaultEntryBufferMinutes: Int = 5,
    val createdAt: Instant = Instant.EPOCH,
)

data class Appointment(
    val id: String,
    val title: String,
    val startTime: Instant,
    val zone: ZoneId,
    val origin: SavedLocation,
    val destination: SavedLocation,
    /** Minutes before [startTime] the user wants to be at the destination door. */
    val entryBufferMinutes: Int = 5,
    val confidenceTarget: Double = 0.90,
    val journeyId: String? = null,
    val calendarEventId: Long? = null,
    val calendarId: Long? = null,
    val remindersEnabled: Boolean = true,
) {
    /** The hard deadline the solver targets. */
    val requiredArrival: Instant get() = startTime.minusSeconds(entryBufferMinutes * 60L)

    fun localStart(): ZonedDateTime = startTime.atZone(zone)
}

// ---------------------------------------------------------------------------
// Provenance & degraded modes
// ---------------------------------------------------------------------------

/** Where a value shown to the user came from. Never label demo data as live. */
enum class DataProvenance {
    /** Fetched from a live provider during this forecast. */
    LIVE,

    /** Served from the local cache because the live call failed or was throttled. */
    CACHED,

    /** Produced by the built-in offline demo generator. */
    DEMO,

    /** Learned from the user's own stored observations. */
    LEARNED,

    /** A documented prior or a user setting, not measured. */
    DEFAULT,

    /** User typed the value in. */
    USER_DEFINED,

    /** Not obtainable — the component was excluded and uncertainty widened. */
    UNAVAILABLE,
}

data class ProviderNotice(
    val source: String,
    val provenance: DataProvenance,
    val message: String,
)

// ---------------------------------------------------------------------------
// Routing
// ---------------------------------------------------------------------------

enum class TrafficRegime { LIGHT, MODERATE, HEAVY, SEVERE, UNKNOWN;

    /**
     * Multiplier applied to residual scale. Heavier regimes are empirically more
     * volatile; these are documented priors, refined by learned residuals.
     */
    val volatilityMultiplier: Double
        get() = when (this) {
            LIGHT -> 0.85
            MODERATE -> 1.0
            HEAVY -> 1.25
            SEVERE -> 1.55
            UNKNOWN -> 1.05
        }
}

/**
 * One traffic-aware estimate returned by the routing provider for a specific
 * departure instant.
 */
data class RouteEstimate(
    val departureTime: Instant,
    val durationSeconds: Double,
    val staticDurationSeconds: Double,
    val distanceMetres: Double,
    val routeLabel: String,
    val trafficRegime: TrafficRegime = TrafficRegime.UNKNOWN,
    val polyline: List<GeoPoint> = emptyList(),
) {
    /** > 1 when traffic is slowing the route down relative to free-flow. */
    val congestionRatio: Double
        get() = if (staticDurationSeconds > 0) durationSeconds / staticDurationSeconds else 1.0
}

/** The full routing answer for one origin/destination pair over a departure window. */
data class RouteMatrix(
    val estimates: List<RouteEstimate>,
    val alternatives: List<RouteEstimate> = emptyList(),
    val provenance: DataProvenance,
    val fetchedAt: Instant,
    val notice: ProviderNotice? = null,
) {
    val isEmpty: Boolean get() = estimates.isEmpty()
}

// ---------------------------------------------------------------------------
// Weather
// ---------------------------------------------------------------------------

data class WeatherSnapshot(
    val time: Instant,
    val precipitationProbability: Double?, // 0..1
    val precipitationMm: Double?,
    val visibilityMetres: Double?,
    val windSpeedKph: Double?,
    val temperatureC: Double?,
    val severeAlert: Boolean = false,
    val provenance: DataProvenance = DataProvenance.LIVE,
) {
    /**
     * A single bounded severity index in `[0, 1]` combining the individual signals.
     * The forecasting engine only ever consumes this scalar, so a provider that can
     * supply a subset of fields still contributes.
     */
    fun severityIndex(): Double {
        val parts = mutableListOf<Double>()
        precipitationProbability?.let { parts += it.coerceIn(0.0, 1.0) * 0.6 }
        precipitationMm?.let { parts += (it / 6.0).coerceIn(0.0, 1.0) }
        visibilityMetres?.let { parts += ((6000.0 - it) / 5500.0).coerceIn(0.0, 1.0) * 0.8 }
        windSpeedKph?.let { parts += ((it - 25.0) / 45.0).coerceIn(0.0, 1.0) * 0.5 }
        temperatureC?.let { t ->
            val extreme = when {
                t >= 44 -> (t - 44.0) / 8.0
                t <= 0 -> (0.0 - t) / 10.0
                else -> 0.0
            }
            parts += extreme.coerceIn(0.0, 1.0) * 0.6
        }
        if (severeAlert) parts += 1.0
        if (parts.isEmpty()) return 0.0
        // Soft-max style combination: dominated by the worst signal but not only by it.
        val worst = parts.max()
        val mean = parts.average()
        return (0.7 * worst + 0.3 * mean).coerceIn(0.0, 1.0)
    }

    fun category(): WeatherCategory = when {
        severeAlert -> WeatherCategory.SEVERE
        severityIndex() >= 0.55 -> WeatherCategory.ADVERSE
        severityIndex() >= 0.25 -> WeatherCategory.MIXED
        else -> WeatherCategory.CLEAR
    }
}

enum class WeatherCategory { CLEAR, MIXED, ADVERSE, SEVERE, UNKNOWN }

// ---------------------------------------------------------------------------
// Nearby events
// ---------------------------------------------------------------------------

data class NearbyEvent(
    val id: String,
    val name: String,
    val venueName: String,
    val venuePoint: GeoPoint,
    val startTime: Instant,
    val endTime: Instant?,
    val venueCapacity: Int?,
    val importance: Double? = null, // 0..1 provider popularity if available
)

data class EventPressure(
    /** Bounded pressure score in `[0, 1]`. 0 means no measurable event risk. */
    val score: Double,
    val contributors: List<EventPressureContribution>,
    val provenance: DataProvenance,
) {
    companion object {
        val NONE = EventPressure(0.0, emptyList(), DataProvenance.UNAVAILABLE)
    }
}

data class EventPressureContribution(
    val eventName: String,
    val venueName: String,
    val metresFromRoute: Double,
    val metresFromDestination: Double,
    val minutesToStart: Long,
    val contribution: Double,
)

// ---------------------------------------------------------------------------
// Trips & observations
// ---------------------------------------------------------------------------

enum class TimeBucket {
    EARLY_MORNING, // 00:00–06:29
    MORNING_PEAK,  // 06:30–09:29
    MIDDAY,        // 09:30–15:29
    EVENING_PEAK,  // 15:30–19:29
    EVENING;       // 19:30–23:59

    companion object {
        fun of(time: LocalTime): TimeBucket {
            val m = time.hour * 60 + time.minute
            return when {
                m < 6 * 60 + 30 -> EARLY_MORNING
                m < 9 * 60 + 30 -> MORNING_PEAK
                m < 15 * 60 + 30 -> MIDDAY
                m < 19 * 60 + 30 -> EVENING_PEAK
                else -> EVENING
            }
        }
    }
}

enum class DayType { WEEKDAY, WEEKEND;

    companion object {
        fun of(day: DayOfWeek): DayType =
            if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) WEEKEND else WEEKDAY
    }
}

/**
 * A finished journey with everything needed to (a) learn from it and (b) replay
 * the exact forecast that was produced before it started (walk-forward backtesting).
 */
data class CompletedTrip(
    val id: String,
    val journeyId: String?,
    val originId: String,
    val destinationId: String,
    val appointmentTitle: String,
    val zone: ZoneId,
    val requiredArrival: Instant,
    val recommendedDeparture: Instant?,
    val actualDeparture: Instant,
    val actualArrival: Instant,
    /** Routing duration the API predicted for the actual departure instant, in seconds. */
    val predictedTravelSeconds: Double,
    /** Observed door-to-door road time, in seconds. */
    val actualTravelSeconds: Double,
    val predictedArrival: Instant?,
    val onTimeProbabilityAtDeparture: Double?,
    val preparationSeconds: Double?,
    val parkingSeconds: Double?,
    val walkingSeconds: Double?,
    val weatherSeverity: Double?,
    val weatherCategory: WeatherCategory = WeatherCategory.UNKNOWN,
    val eventPressure: Double?,
    val trafficRegime: TrafficRegime = TrafficRegime.UNKNOWN,
    val timeBucket: TimeBucket,
    val dayType: DayType,
    val dayOfWeek: DayOfWeek,
    val excludedFromLearning: Boolean = false,
    val unusualCircumstances: String? = null,
    val modelVersion: String = "unknown",
    val source: TripSource = TripSource.DETECTED,
) {
    val arrivedOnTime: Boolean get() = !actualArrival.isAfter(requiredArrival)
    val latenessSeconds: Double get() = (actualArrival.epochSecond - requiredArrival.epochSecond).toDouble()

    /** Observed / predicted road time. The bias model works in this log-ratio space. */
    val travelRatio: Double
        get() = if (predictedTravelSeconds > 0) actualTravelSeconds / predictedTravelSeconds else 1.0
}

enum class TripSource { DETECTED, MANUAL, DEMO, CORRECTED }

// ---------------------------------------------------------------------------
// Personalization
// ---------------------------------------------------------------------------

enum class PersonalizationLevel(val label: String) {
    PRELIMINARY("Preliminary"),
    LEARNING("Learning"),
    PARTIALLY_PERSONALIZED("Partially personalized"),
    PERSONALIZED("Personalized"),
}

enum class RiskLevel { LOW, MODERATE, ELEVATED, HIGH }

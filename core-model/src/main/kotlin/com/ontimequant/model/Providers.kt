package com.ontimequant.model

import java.time.Instant

/**
 * Result wrapper used by every external provider. Providers never throw across the
 * boundary: a failure is a value, so the forecasting engine can degrade honestly.
 */
sealed interface ProviderResult<out T> {
    data class Success<T>(val value: T, val provenance: DataProvenance) : ProviderResult<T>
    data class Failure(val notice: ProviderNotice, val cause: Throwable? = null) : ProviderResult<Nothing>

    fun valueOrNull(): T? = (this as? Success)?.value
}

/** Traffic-aware routing. Implementations: Google Routes, demo, cached. */
interface RoutesProvider {
    val name: String

    /**
     * Traffic-aware durations for departures on `[windowStart, windowEnd]` sampled every
     * [stepMinutes]. Implementations must respect provider rate limits and cache.
     */
    suspend fun routeMatrix(
        origin: GeoPoint,
        destination: GeoPoint,
        windowStart: Instant,
        windowEnd: Instant,
        stepMinutes: Int,
    ): ProviderResult<RouteMatrix>
}

/** Destination search / address validation. */
interface PlacesProvider {
    val name: String
    suspend fun autocomplete(query: String, near: GeoPoint?): ProviderResult<List<PlaceSuggestion>>
    suspend fun resolve(suggestion: PlaceSuggestion): ProviderResult<SavedLocation>
}

/** Weather is strictly optional; a failure widens uncertainty instead of blocking. */
interface WeatherProvider {
    val name: String
    suspend fun forecast(point: GeoPoint, at: Instant): ProviderResult<WeatherSnapshot>
}

/** Nearby-event risk is strictly optional. */
interface EventsProvider {
    val name: String
    suspend fun eventsNear(
        point: GeoPoint,
        radiusMetres: Double,
        from: Instant,
        to: Instant,
    ): ProviderResult<List<NearbyEvent>>
}

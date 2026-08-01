package com.ontimequant.data.remote.events

import com.ontimequant.forecast.demo.DemoScenario
import com.ontimequant.model.DataProvenance
import com.ontimequant.model.EventsProvider
import com.ontimequant.model.GeoPoint
import com.ontimequant.model.NearbyEvent
import com.ontimequant.model.ProviderNotice
import com.ontimequant.model.ProviderResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Query
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

@Serializable
data class DiscoveryResponse(
    @SerialName("_embedded") val embedded: DiscoveryEmbedded? = null,
)

@Serializable
data class DiscoveryEmbedded(val events: List<DiscoveryEvent> = emptyList())

@Serializable
data class DiscoveryEvent(
    val id: String = "",
    val name: String = "",
    val dates: DiscoveryDates? = null,
    @SerialName("_embedded") val embedded: DiscoveryEventEmbedded? = null,
)

@Serializable
data class DiscoveryEventEmbedded(val venues: List<DiscoveryVenue> = emptyList())

@Serializable
data class DiscoveryVenue(
    val name: String = "",
    val location: DiscoveryLocation? = null,
    val capacity: Int? = null,
)

@Serializable
data class DiscoveryLocation(
    val latitude: String? = null,
    val longitude: String? = null,
)

@Serializable
data class DiscoveryDates(val start: DiscoveryStart? = null)

@Serializable
data class DiscoveryStart(
    val dateTime: String? = null,
    val localDate: String? = null,
    val localTime: String? = null,
)

interface TicketmasterApi {
    @GET("discovery/v2/events.json")
    suspend fun events(
        @Query("apikey") apiKey: String,
        @Query("latlong") latLong: String,
        @Query("radius") radius: Int,
        @Query("unit") unit: String = "km",
        @Query("startDateTime") startDateTime: String,
        @Query("endDateTime") endDateTime: String,
        @Query("size") size: Int = 50,
        @Query("sort") sort: String = "date,asc",
    ): DiscoveryResponse
}

/**
 * Ticketmaster Discovery adapter.
 *
 * This is a *risk* input, never a required one. If it fails, is not configured, or returns
 * nothing, the forecast is produced without any event term and the UI says event data was
 * excluded. It is deliberately the last provider consulted and the first to be dropped.
 */
class TicketmasterEventsProvider @Inject constructor(
    private val api: TicketmasterApi,
    private val apiKey: String,
) : EventsProvider {

    override val name: String = "Ticketmaster Discovery"

    override suspend fun eventsNear(
        point: GeoPoint,
        radiusMetres: Double,
        from: Instant,
        to: Instant,
    ): ProviderResult<List<NearbyEvent>> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext ProviderResult.Failure(
                ProviderNotice(
                    name, DataProvenance.UNAVAILABLE,
                    "Event data unavailable. Event-related risk was excluded.",
                ),
            )
        }
        runCatching {
            api.events(
                apiKey = apiKey,
                latLong = "${point.latitude},${point.longitude}",
                radius = (radiusMetres / 1000).toInt().coerceAtLeast(1),
                startDateTime = ISO.format(from),
                endDateTime = ISO.format(to),
            )
        }.fold(
            onSuccess = { response ->
                val events = response.embedded?.events.orEmpty().mapNotNull { it.toDomain() }
                ProviderResult.Success(events, DataProvenance.LIVE)
            },
            onFailure = { e ->
                ProviderResult.Failure(
                    ProviderNotice(
                        name, DataProvenance.UNAVAILABLE,
                        "Event data unavailable. Event-related risk was excluded.",
                    ),
                    e,
                )
            },
        )
    }

    private fun DiscoveryEvent.toDomain(): NearbyEvent? {
        val venue = embedded?.venues?.firstOrNull() ?: return null
        val lat = venue.location?.latitude?.toDoubleOrNull() ?: return null
        val lon = venue.location.longitude?.toDoubleOrNull() ?: return null
        val start = dates?.start?.dateTime?.let { runCatching { Instant.parse(it) }.getOrNull() }
            ?: return null
        return NearbyEvent(
            id = id,
            name = name,
            venueName = venue.name,
            venuePoint = GeoPoint(lat, lon),
            startTime = start,
            endTime = null, // Discovery rarely publishes an end time; the model assumes a duration.
            venueCapacity = venue.capacity,
        )
    }

    private companion object {
        val ISO: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneId.of("UTC"))
    }
}

/** Demo events: a conference and a road race near the demo route. */
class DemoEventsProvider @Inject constructor() : EventsProvider {
    override val name: String = "Demo events"
    override suspend fun eventsNear(
        point: GeoPoint,
        radiusMetres: Double,
        from: Instant,
        to: Instant,
    ): ProviderResult<List<NearbyEvent>> {
        val day = from.atZone(DemoScenario.ZONE).toLocalDate()
        return ProviderResult.Success(DemoScenario.events(day), DataProvenance.DEMO)
    }
}

/** The honest no-op used when no event key is configured. */
class UnavailableEventsProvider @Inject constructor() : EventsProvider {
    override val name: String = "Events"
    override suspend fun eventsNear(
        point: GeoPoint,
        radiusMetres: Double,
        from: Instant,
        to: Instant,
    ): ProviderResult<List<NearbyEvent>> = ProviderResult.Failure(
        ProviderNotice(
            name, DataProvenance.UNAVAILABLE,
            "Event data unavailable. Event-related risk was excluded.",
        ),
    )
}

package com.ontimequant.data.remote.routes

import com.ontimequant.model.DataProvenance
import com.ontimequant.model.GeoPoint
import com.ontimequant.model.ProviderNotice
import com.ontimequant.model.ProviderResult
import com.ontimequant.model.RouteEstimate
import com.ontimequant.model.RouteMatrix
import com.ontimequant.model.RoutesProvider
import com.ontimequant.model.TrafficRegime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.HttpException
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import java.io.IOException
import java.time.Instant
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlin.math.pow

// ---------------------------------------------------------------------------
// Routes API v2 DTOs — only the fields the model actually consumes.
// ---------------------------------------------------------------------------

@Serializable
data class RoutesRequest(
    val origin: RoutesWaypoint,
    val destination: RoutesWaypoint,
    val travelMode: String = "DRIVE",
    val routingPreference: String = "TRAFFIC_AWARE_OPTIMAL",
    val departureTime: String,
    val computeAlternativeRoutes: Boolean = false,
    val languageCode: String = "en",
    val units: String = "METRIC",
)

@Serializable
data class RoutesWaypoint(val location: RoutesLocation)

@Serializable
data class RoutesLocation(val latLng: RoutesLatLng)

@Serializable
data class RoutesLatLng(val latitude: Double, val longitude: Double)

@Serializable
data class RoutesResponse(val routes: List<RoutesRoute> = emptyList())

@Serializable
data class RoutesRoute(
    val distanceMeters: Int? = null,
    /** Protobuf duration string, e.g. "1234s". */
    val duration: String? = null,
    val staticDuration: String? = null,
    val polyline: RoutesPolyline? = null,
    val description: String? = null,
)

@Serializable
data class RoutesPolyline(@SerialName("encodedPolyline") val encoded: String? = null)

interface RoutesApi {
    @POST("directions/v2:computeRoutes")
    suspend fun computeRoutes(
        @Header("X-Goog-Api-Key") apiKey: String,
        @Header("X-Goog-FieldMask") fieldMask: String,
        @Body body: RoutesRequest,
    ): RoutesResponse
}

/**
 * Google Routes API adapter.
 *
 * Behaviour that matters:
 *  - **Batching with a concurrency cap.** The solver wants a duration every five minutes
 *    across the window; those are independent requests, issued at most
 *    [MAX_CONCURRENT_REQUESTS] at a time so a single forecast cannot burst through a
 *    per-second quota.
 *  - **Partial success is success.** If seven of nine departure times return and two fail,
 *    the matrix is built from the seven and a notice records the gap. A forecast built on
 *    a thinner grid is far better than no forecast.
 *  - **Retries only where retrying helps.** 429 and 5xx are retried with exponential
 *    backoff; 400/401/403 are permanent and returned immediately so the app can fall back
 *    to demo mode instead of hammering a misconfigured key.
 */
class GoogleRoutesProvider @Inject constructor(
    private val api: RoutesApi,
    private val apiKey: String,
) : RoutesProvider {

    override val name: String = "Google Routes"

    private val gate = Semaphore(MAX_CONCURRENT_REQUESTS)

    override suspend fun routeMatrix(
        origin: GeoPoint,
        destination: GeoPoint,
        windowStart: Instant,
        windowEnd: Instant,
        stepMinutes: Int,
    ): ProviderResult<RouteMatrix> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext ProviderResult.Failure(
                ProviderNotice(name, DataProvenance.UNAVAILABLE, "No routing API key is configured."),
            )
        }

        val departures = buildList {
            var t = windowStart
            while (!t.isAfter(windowEnd)) {
                add(t)
                t = t.plusSeconds(stepMinutes * 60L)
            }
            if (isEmpty() || last() != windowEnd) add(windowEnd)
        }

        val results = coroutineScope {
            departures.map { departure ->
                async { departure to fetchOne(origin, destination, departure) }
            }.map { it.await() }
        }

        val estimates = results.mapNotNull { (_, outcome) -> outcome.getOrNull() }
        val firstFatal = results.firstNotNullOfOrNull { (_, outcome) ->
            (outcome.exceptionOrNull() as? PermanentRoutingError)
        }

        when {
            estimates.isNotEmpty() -> {
                val failures = departures.size - estimates.size
                ProviderResult.Success(
                    RouteMatrix(
                        estimates = estimates.sortedBy { it.departureTime },
                        provenance = DataProvenance.LIVE,
                        fetchedAt = Instant.now(),
                        notice = if (failures > 0) {
                            ProviderNotice(
                                name, DataProvenance.LIVE,
                                "$failures of ${departures.size} departure times could not be priced. " +
                                    "The forecast uses the remaining points and interpolates between them.",
                            )
                        } else null,
                    ),
                    DataProvenance.LIVE,
                )
            }
            firstFatal != null -> ProviderResult.Failure(
                ProviderNotice(name, DataProvenance.UNAVAILABLE, firstFatal.userMessage),
                firstFatal,
            )
            else -> ProviderResult.Failure(
                ProviderNotice(
                    name, DataProvenance.UNAVAILABLE,
                    "Live traffic is unavailable. The forecast falls back to the most recent cached route estimate.",
                ),
            )
        }
    }

    private suspend fun fetchOne(
        origin: GeoPoint,
        destination: GeoPoint,
        departure: Instant,
    ): Result<RouteEstimate> = gate.withPermit {
        var attempt = 0
        while (true) {
            try {
                val response = api.computeRoutes(
                    apiKey = apiKey,
                    fieldMask = FIELD_MASK,
                    body = RoutesRequest(
                        origin = RoutesWaypoint(RoutesLocation(RoutesLatLng(origin.latitude, origin.longitude))),
                        destination = RoutesWaypoint(
                            RoutesLocation(RoutesLatLng(destination.latitude, destination.longitude)),
                        ),
                        // The API rejects departure times in the past.
                        departureTime = DateTimeFormatter.ISO_INSTANT.format(
                            if (departure.isBefore(Instant.now().plusSeconds(30))) {
                                Instant.now().plusSeconds(60)
                            } else departure,
                        ),
                    ),
                )
                val route = response.routes.firstOrNull()
                    ?: return Result.failure(IOException("No route returned"))
                return Result.success(route.toEstimate(departure))
            } catch (e: HttpException) {
                if (e.code() in PERMANENT_CODES) {
                    return Result.failure(PermanentRoutingError(e.code(), messageFor(e.code()), e))
                }
                if (attempt >= MAX_RETRIES) return Result.failure(e)
            } catch (e: IOException) {
                if (attempt >= MAX_RETRIES) return Result.failure(e)
            }
            delay((BASE_BACKOFF_MS * 2.0.pow(attempt)).toLong())
            attempt++
        }
        @Suppress("UNREACHABLE_CODE")
        Result.failure(IOException("unreachable"))
    }

    private fun messageFor(code: Int): String = when (code) {
        401, 403 -> "The routing API key was rejected. Check the key and its Android application restrictions."
        400 -> "The routing request was rejected. Check that the Routes API is enabled for this key."
        else -> "Routing request failed with HTTP $code."
    }

    private companion object {
        const val MAX_CONCURRENT_REQUESTS = 4
        const val MAX_RETRIES = 2
        const val BASE_BACKOFF_MS = 400L
        val PERMANENT_CODES = setOf(400, 401, 403, 404)
        const val FIELD_MASK =
            "routes.duration,routes.staticDuration,routes.distanceMeters,routes.polyline.encodedPolyline,routes.description"
    }
}

class PermanentRoutingError(
    val code: Int,
    val userMessage: String,
    cause: Throwable?,
) : IOException(userMessage, cause)

fun RoutesRoute.toEstimate(departure: Instant): RouteEstimate {
    val duration = duration.parseProtoSeconds()
    val static = staticDuration.parseProtoSeconds().takeIf { it > 0 } ?: duration
    val ratio = if (static > 0) duration / static else 1.0
    return RouteEstimate(
        departureTime = departure,
        durationSeconds = duration,
        staticDurationSeconds = static,
        distanceMetres = (distanceMeters ?: 0).toDouble(),
        routeLabel = description?.takeIf { it.isNotBlank() } ?: "Fastest route",
        trafficRegime = when {
            ratio < 1.10 -> TrafficRegime.LIGHT
            ratio < 1.35 -> TrafficRegime.MODERATE
            ratio < 1.75 -> TrafficRegime.HEAVY
            else -> TrafficRegime.SEVERE
        },
        polyline = polyline?.encoded?.let(::decodePolyline).orEmpty(),
    )
}

/** Parses the protobuf duration form used by the Routes API, e.g. `"1234s"`. */
fun String?.parseProtoSeconds(): Double =
    this?.removeSuffix("s")?.toDoubleOrNull() ?: 0.0

/** Standard Google encoded-polyline decoder. */
fun decodePolyline(encoded: String): List<GeoPoint> {
    val points = ArrayList<GeoPoint>()
    var index = 0
    var lat = 0
    var lng = 0
    while (index < encoded.length) {
        var result = 0
        var shift = 0
        var b: Int
        do {
            b = encoded[index++].code - 63
            result = result or ((b and 0x1f) shl shift)
            shift += 5
        } while (b >= 0x20 && index < encoded.length)
        lat += if (result and 1 != 0) (result shr 1).inv() else result shr 1

        result = 0
        shift = 0
        do {
            b = encoded[index++].code - 63
            result = result or ((b and 0x1f) shl shift)
            shift += 5
        } while (b >= 0x20 && index < encoded.length)
        lng += if (result and 1 != 0) (result shr 1).inv() else result shr 1

        points += GeoPoint(lat / 1e5, lng / 1e5)
    }
    return points
}

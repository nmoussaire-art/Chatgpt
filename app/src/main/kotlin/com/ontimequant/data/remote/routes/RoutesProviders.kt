package com.ontimequant.data.remote.routes

import com.ontimequant.data.db.RouteCacheDao
import com.ontimequant.data.db.RouteCacheEntity
import com.ontimequant.forecast.demo.DemoScenario
import com.ontimequant.model.DataProvenance
import com.ontimequant.model.GeoPoint
import com.ontimequant.model.ProviderNotice
import com.ontimequant.model.ProviderResult
import com.ontimequant.model.RouteEstimate
import com.ontimequant.model.RouteMatrix
import com.ontimequant.model.RoutesProvider
import com.ontimequant.model.TrafficRegime
import com.ontimequant.model.distanceTo
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import kotlin.math.roundToLong

/**
 * The offline provider. Produces a full traffic-aware matrix from the built-in Abu Dhabi
 * duration profile, scaled to the actual straight-line distance so a demo journey between
 * arbitrary points still returns something sensible.
 *
 * It is always labelled [DataProvenance.DEMO]. The app never presents this as live.
 */
class DemoRoutesProvider @Inject constructor() : RoutesProvider {

    override val name: String = "Demo routing"

    override suspend fun routeMatrix(
        origin: GeoPoint,
        destination: GeoPoint,
        windowStart: Instant,
        windowEnd: Instant,
        stepMinutes: Int,
    ): ProviderResult<RouteMatrix> {
        val zone = ZoneId.systemDefault()
        val reference = DemoScenario.HOME.point.distanceTo(DemoScenario.OFFICE.point)
        val actual = origin.distanceTo(destination)
        // Scale gently: a journey four times as far is not four times as congested.
        val scale = if (reference <= 0 || actual <= 0) 1.0 else (actual / reference).coerceIn(0.35, 4.0)

        val base = DemoScenario.routeMatrix(windowStart, windowEnd, stepMinutes, zone, Instant.now())
        val scaled = base.estimates.map {
            it.copy(
                durationSeconds = it.durationSeconds * scale,
                staticDurationSeconds = it.staticDurationSeconds * scale,
                distanceMetres = if (actual > 0) actual * 1.25 else it.distanceMetres,
            )
        }
        return ProviderResult.Success(base.copy(estimates = scaled), DataProvenance.DEMO)
    }
}

// ---------------------------------------------------------------------------
// Caching decorator
// ---------------------------------------------------------------------------

@Serializable
private data class CachedEstimate(
    val departureEpoch: Long,
    val durationSeconds: Double,
    val staticDurationSeconds: Double,
    val distanceMetres: Double,
    val routeLabel: String,
    val trafficRegime: String,
    val polylineLat: List<Double> = emptyList(),
    val polylineLon: List<Double> = emptyList(),
)

@Serializable
private data class CachedMatrix(val estimates: List<CachedEstimate>)

/**
 * Wraps a routing provider with a short-lived local cache.
 *
 * Two jobs:
 *  1. **Do not make unnecessary calls.** A forecast recomputed within [FRESH_SECONDS]
 *     reuses the previous matrix. The home screen recalculating on resume therefore costs
 *     nothing.
 *  2. **Degrade honestly.** When the live call fails, a stale entry is served and the
 *     result is re-labelled [DataProvenance.CACHED] with its true age. The engine widens
 *     uncertainty in proportion to that age, and the UI says so on screen.
 */
class CachingRoutesProvider(
    private val delegate: RoutesProvider,
    private val cache: RouteCacheDao,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val now: () -> Instant = Instant::now,
) : RoutesProvider {

    override val name: String get() = delegate.name

    override suspend fun routeMatrix(
        origin: GeoPoint,
        destination: GeoPoint,
        windowStart: Instant,
        windowEnd: Instant,
        stepMinutes: Int,
    ): ProviderResult<RouteMatrix> {
        val key = cacheKey(origin, destination, windowStart, windowEnd, stepMinutes)
        val existing = cache.get(key)
        val currentTime = now()

        if (existing != null) {
            val age = Duration.between(Instant.ofEpochSecond(existing.fetchedAtEpoch), currentTime).seconds
            if (age <= FRESH_SECONDS) {
                decode(existing)?.let { return ProviderResult.Success(it, it.provenance) }
            }
        }

        return when (val live = delegate.routeMatrix(origin, destination, windowStart, windowEnd, stepMinutes)) {
            is ProviderResult.Success -> {
                cache.put(
                    RouteCacheEntity(
                        cacheKey = key,
                        fetchedAtEpoch = live.value.fetchedAt.epochSecond,
                        payloadJson = json.encodeToString(CachedMatrix.serializer(), live.value.toCached()),
                        provenance = live.value.provenance.name,
                    ),
                )
                cache.prune(currentTime.minusSeconds(CACHE_RETENTION_SECONDS).epochSecond)
                live
            }
            is ProviderResult.Failure -> {
                val stale = existing?.let(::decode)
                if (stale != null) {
                    val ageMinutes = Duration.between(stale.fetchedAt, currentTime).toMinutes()
                    ProviderResult.Success(
                        stale.copy(
                            provenance = DataProvenance.CACHED,
                            notice = ProviderNotice(
                                source = "Routing",
                                provenance = DataProvenance.CACHED,
                                message = "Live traffic is unavailable. This forecast uses a route estimate from " +
                                    "$ageMinutes minutes ago and widens its uncertainty to match.",
                            ),
                        ),
                        DataProvenance.CACHED,
                    )
                } else {
                    live
                }
            }
        }
    }

    private fun decode(entity: RouteCacheEntity): RouteMatrix? = runCatching {
        val cached = json.decodeFromString(CachedMatrix.serializer(), entity.payloadJson)
        RouteMatrix(
            estimates = cached.estimates.map { e ->
                RouteEstimate(
                    departureTime = Instant.ofEpochSecond(e.departureEpoch),
                    durationSeconds = e.durationSeconds,
                    staticDurationSeconds = e.staticDurationSeconds,
                    distanceMetres = e.distanceMetres,
                    routeLabel = e.routeLabel,
                    trafficRegime = runCatching { TrafficRegime.valueOf(e.trafficRegime) }
                        .getOrDefault(TrafficRegime.UNKNOWN),
                    polyline = e.polylineLat.zip(e.polylineLon) { lat, lon -> GeoPoint(lat, lon) },
                )
            },
            provenance = runCatching { DataProvenance.valueOf(entity.provenance) }
                .getOrDefault(DataProvenance.CACHED),
            fetchedAt = Instant.ofEpochSecond(entity.fetchedAtEpoch),
        )
    }.getOrNull()

    private fun RouteMatrix.toCached() = CachedMatrix(
        estimates.map { e ->
            CachedEstimate(
                departureEpoch = e.departureTime.epochSecond,
                durationSeconds = e.durationSeconds,
                staticDurationSeconds = e.staticDurationSeconds,
                distanceMetres = e.distanceMetres,
                routeLabel = e.routeLabel,
                trafficRegime = e.trafficRegime.name,
                // Only the first route's geometry is kept, and only coarsely — it is used
                // for event proximity, not for drawing turn-by-turn directions.
                polylineLat = e.polyline.filterIndexed { i, _ -> i % 4 == 0 }.map { it.latitude },
                polylineLon = e.polyline.filterIndexed { i, _ -> i % 4 == 0 }.map { it.longitude },
            )
        },
    )

    private fun cacheKey(
        origin: GeoPoint,
        destination: GeoPoint,
        windowStart: Instant,
        windowEnd: Instant,
        stepMinutes: Int,
    ): String {
        fun round(v: Double) = (v * 2000).roundToLong() // ≈50 m grid
        // The window is bucketed to 10 minutes so small clock drift does not miss the cache.
        val bucket = windowStart.epochSecond / 600
        return "${round(origin.latitude)},${round(origin.longitude)}|" +
            "${round(destination.latitude)},${round(destination.longitude)}|" +
            "$bucket|${windowEnd.epochSecond / 600}|$stepMinutes"
    }

    private companion object {
        const val FRESH_SECONDS = 240L
        const val CACHE_RETENTION_SECONDS = 24 * 3600L
    }
}

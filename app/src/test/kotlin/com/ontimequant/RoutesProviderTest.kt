package com.ontimequant

import com.google.common.truth.Truth.assertThat
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.ontimequant.data.remote.routes.CachingRoutesProvider
import com.ontimequant.data.remote.routes.GoogleRoutesProvider
import com.ontimequant.data.remote.routes.RoutesApi
import com.ontimequant.data.db.RouteCacheDao
import com.ontimequant.data.db.RouteCacheEntity
import com.ontimequant.model.DataProvenance
import com.ontimequant.model.GeoPoint
import com.ontimequant.model.ProviderNotice
import com.ontimequant.model.ProviderResult
import com.ontimequant.model.RouteEstimate
import com.ontimequant.model.RouteMatrix
import com.ontimequant.model.RoutesProvider
import com.ontimequant.model.TrafficRegime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import java.time.Instant

/** In-memory stand-in for the Room cache DAO. */
private class FakeRouteCache : RouteCacheDao {
    val store = mutableMapOf<String, RouteCacheEntity>()
    override suspend fun get(key: String): RouteCacheEntity? = store[key]
    override suspend fun put(entry: RouteCacheEntity) { store[entry.cacheKey] = entry }
    override suspend fun prune(beforeEpoch: Long) {
        store.entries.removeAll { it.value.fetchedAtEpoch < beforeEpoch }
    }
    override suspend fun clear() = store.clear()
}

/** A routing provider that can be told to succeed or fail. */
private class ScriptedRoutesProvider(
    var result: ProviderResult<RouteMatrix>,
) : RoutesProvider {
    override val name = "Scripted"
    var calls = 0
    override suspend fun routeMatrix(
        origin: GeoPoint, destination: GeoPoint,
        windowStart: Instant, windowEnd: Instant, stepMinutes: Int,
    ): ProviderResult<RouteMatrix> {
        calls++
        return result
    }
}

class GoogleRoutesProviderTest {

    private lateinit var server: MockWebServer
    private lateinit var api: RoutesApi

    private val origin = GeoPoint(24.4989, 54.4082)
    private val destination = GeoPoint(24.4874, 54.3609)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(
                Json { ignoreUnknownKeys = true }.asConverterFactory("application/json".toMediaType()),
            )
            .build()
            .create(RoutesApi::class.java)
    }

    @After
    fun tearDown() = server.shutdown()

    private fun body(durationSeconds: Int, staticSeconds: Int) = """
        {"routes":[{"distanceMeters":9400,"duration":"${durationSeconds}s",
        "staticDuration":"${staticSeconds}s","description":"Via Reem Causeway"}]}
    """.trimIndent()

    @Test
    fun `a successful response is parsed into traffic-aware estimates`() = runTest {
        repeat(4) { server.enqueue(MockResponse().setBody(body(1240, 620))) }
        val provider = GoogleRoutesProvider(api, "test-key")
        val result = provider.routeMatrix(
            origin, destination,
            Instant.now().plusSeconds(600), Instant.now().plusSeconds(1500), 5,
        )
        assertThat(result).isInstanceOf(ProviderResult.Success::class.java)
        val matrix = (result as ProviderResult.Success).value
        assertThat(matrix.provenance).isEqualTo(DataProvenance.LIVE)
        assertThat(matrix.estimates).isNotEmpty()
        val first = matrix.estimates.first()
        assertThat(first.durationSeconds).isEqualTo(1240.0)
        assertThat(first.staticDurationSeconds).isEqualTo(620.0)
        // 1240 / 620 = 2.0x free flow, which is the severe band.
        assertThat(first.trafficRegime).isEqualTo(TrafficRegime.SEVERE)
        assertThat(first.congestionRatio).isWithin(1e-9).of(2.0)
    }

    @Test
    fun `a blank key fails immediately without a network call`() = runTest {
        val provider = GoogleRoutesProvider(api, "")
        val result = provider.routeMatrix(
            origin, destination, Instant.now(), Instant.now().plusSeconds(900), 5,
        )
        assertThat(result).isInstanceOf(ProviderResult.Failure::class.java)
        assertThat(server.requestCount).isEqualTo(0)
        assertThat((result as ProviderResult.Failure).notice.message).contains("No routing API key")
    }

    @Test
    fun `a rejected key produces a specific, actionable failure`() = runTest {
        repeat(6) { server.enqueue(MockResponse().setResponseCode(403).setBody("{}")) }
        val provider = GoogleRoutesProvider(api, "bad-key")
        val result = provider.routeMatrix(
            origin, destination, Instant.now().plusSeconds(600), Instant.now().plusSeconds(900), 5,
        )
        assertThat(result).isInstanceOf(ProviderResult.Failure::class.java)
        assertThat((result as ProviderResult.Failure).notice.message).contains("rejected")
    }

    @Test
    fun `partial failure still produces a usable matrix`() = runTest {
        // Three of four departure times succeed.
        server.enqueue(MockResponse().setBody(body(900, 620)))
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(500))
        repeat(6) { server.enqueue(MockResponse().setBody(body(960, 620))) }

        val provider = GoogleRoutesProvider(api, "test-key")
        val result = provider.routeMatrix(
            origin, destination, Instant.now().plusSeconds(600), Instant.now().plusSeconds(1500), 5,
        )
        assertThat(result).isInstanceOf(ProviderResult.Success::class.java)
        assertThat((result as ProviderResult.Success).value.estimates).isNotEmpty()
    }

    @Test
    fun `a total network failure is reported rather than thrown`() = runTest {
        repeat(20) { server.enqueue(MockResponse().setResponseCode(503)) }
        val provider = GoogleRoutesProvider(api, "test-key")
        val result = provider.routeMatrix(
            origin, destination, Instant.now().plusSeconds(600), Instant.now().plusSeconds(900), 5,
        )
        assertThat(result).isInstanceOf(ProviderResult.Failure::class.java)
        assertThat((result as ProviderResult.Failure).notice.message).contains("unavailable")
    }
}

class CachingRoutesProviderTest {

    private val origin = GeoPoint(24.4989, 54.4082)
    private val destination = GeoPoint(24.4874, 54.3609)
    private val windowStart = Instant.parse("2025-11-04T02:30:00Z")
    private val windowEnd = windowStart.plusSeconds(3600)

    private fun matrix(fetchedAt: Instant, provenance: DataProvenance = DataProvenance.LIVE) = RouteMatrix(
        estimates = (0..6).map {
            RouteEstimate(
                departureTime = windowStart.plusSeconds(it * 600L),
                durationSeconds = 900.0 + it * 30,
                staticDurationSeconds = 620.0,
                distanceMetres = 9400.0,
                routeLabel = "Test route",
                trafficRegime = TrafficRegime.HEAVY,
            )
        },
        provenance = provenance,
        fetchedAt = fetchedAt,
    )

    @Test
    fun `a fresh cache hit avoids a second provider call`() = runTest {
        val cache = FakeRouteCache()
        val now = windowStart
        val delegate = ScriptedRoutesProvider(
            ProviderResult.Success(matrix(now), DataProvenance.LIVE),
        )
        val provider = CachingRoutesProvider(delegate, cache, now = { now })

        provider.routeMatrix(origin, destination, windowStart, windowEnd, 5)
        provider.routeMatrix(origin, destination, windowStart, windowEnd, 5)
        assertThat(delegate.calls).isEqualTo(1)
    }

    @Test
    fun `a failure falls back to the cached matrix and relabels it honestly`() = runTest {
        val cache = FakeRouteCache()
        var clock = windowStart
        val delegate = ScriptedRoutesProvider(
            ProviderResult.Success(matrix(clock), DataProvenance.LIVE),
        )
        val provider = CachingRoutesProvider(delegate, cache, now = { clock })

        // Populate the cache.
        provider.routeMatrix(origin, destination, windowStart, windowEnd, 5)

        // An hour later the provider is down.
        clock = windowStart.plusSeconds(3600)
        delegate.result = ProviderResult.Failure(
            ProviderNotice("Scripted", DataProvenance.UNAVAILABLE, "down"),
        )
        val result = provider.routeMatrix(origin, destination, windowStart, windowEnd, 5)

        assertThat(result).isInstanceOf(ProviderResult.Success::class.java)
        val served = (result as ProviderResult.Success).value
        assertThat(served.provenance).isEqualTo(DataProvenance.CACHED)
        assertThat(served.notice!!.message).contains("Live traffic is unavailable")
        assertThat(served.notice!!.message).contains("60 minutes ago")
        assertThat(served.estimates).hasSize(7)
    }

    @Test
    fun `a failure with an empty cache is reported as a failure`() = runTest {
        val cache = FakeRouteCache()
        val delegate = ScriptedRoutesProvider(
            ProviderResult.Failure(ProviderNotice("Scripted", DataProvenance.UNAVAILABLE, "down")),
        )
        val provider = CachingRoutesProvider(delegate, cache, now = { windowStart })
        val result = provider.routeMatrix(origin, destination, windowStart, windowEnd, 5)
        assertThat(result).isInstanceOf(ProviderResult.Failure::class.java)
    }

    @Test
    fun `a stale cache entry triggers a fresh call`() = runTest {
        val cache = FakeRouteCache()
        var clock = windowStart
        val delegate = ScriptedRoutesProvider(
            ProviderResult.Success(matrix(clock), DataProvenance.LIVE),
        )
        val provider = CachingRoutesProvider(delegate, cache, now = { clock })
        provider.routeMatrix(origin, destination, windowStart, windowEnd, 5)

        clock = windowStart.plusSeconds(600) // beyond the freshness window
        delegate.result = ProviderResult.Success(matrix(clock), DataProvenance.LIVE)
        provider.routeMatrix(origin, destination, windowStart, windowEnd, 5)
        assertThat(delegate.calls).isEqualTo(2)
    }
}

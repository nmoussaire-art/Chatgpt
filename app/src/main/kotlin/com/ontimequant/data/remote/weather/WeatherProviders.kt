package com.ontimequant.data.remote.weather

import com.ontimequant.forecast.demo.DemoScenario
import com.ontimequant.model.DataProvenance
import com.ontimequant.model.GeoPoint
import com.ontimequant.model.ProviderNotice
import com.ontimequant.model.ProviderResult
import com.ontimequant.model.WeatherProvider
import com.ontimequant.model.WeatherSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Query
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlin.math.abs

@Serializable
data class OpenMeteoResponse(
    @SerialName("utc_offset_seconds") val utcOffsetSeconds: Int = 0,
    val hourly: OpenMeteoHourly? = null,
)

@Serializable
data class OpenMeteoHourly(
    val time: List<String> = emptyList(),
    @SerialName("temperature_2m") val temperature: List<Double?> = emptyList(),
    @SerialName("precipitation_probability") val precipitationProbability: List<Double?> = emptyList(),
    val precipitation: List<Double?> = emptyList(),
    val visibility: List<Double?> = emptyList(),
    @SerialName("wind_speed_10m") val windSpeed: List<Double?> = emptyList(),
    @SerialName("weather_code") val weatherCode: List<Int?> = emptyList(),
)

interface OpenMeteoApi {
    @GET("v1/forecast")
    suspend fun forecast(
        @Query("latitude") latitude: Double,
        @Query("longitude") longitude: Double,
        @Query("hourly") hourly: String =
            "temperature_2m,precipitation_probability,precipitation,visibility,wind_speed_10m,weather_code",
        @Query("forecast_days") forecastDays: Int = 3,
        @Query("timezone") timezone: String = "UTC",
        @Query("wind_speed_unit") windUnit: String = "kmh",
    ): OpenMeteoResponse
}

/**
 * Open-Meteo adapter. No API key required, which is why it is the default weather source.
 *
 * Weather is strictly optional to the forecast. A failure here returns a
 * [ProviderResult.Failure] with a plain-language notice; the engine then applies **no**
 * weather adjustment and widens its residual scale slightly instead of guessing.
 */
class OpenMeteoWeatherProvider @Inject constructor(
    private val api: OpenMeteoApi,
) : WeatherProvider {

    override val name: String = "Open-Meteo"

    override suspend fun forecast(point: GeoPoint, at: Instant): ProviderResult<WeatherSnapshot> =
        withContext(Dispatchers.IO) {
            runCatching { api.forecast(point.latitude, point.longitude) }.fold(
                onSuccess = { response ->
                    val hourly = response.hourly
                    val index = hourly?.let { nearestIndex(it.time, at) }
                    if (hourly == null || index == null) {
                        ProviderResult.Failure(
                            ProviderNotice(
                                name, DataProvenance.UNAVAILABLE,
                                "Weather is unavailable for that time. No weather adjustment was applied.",
                            ),
                        )
                    } else {
                        val code = hourly.weatherCode.getOrNull(index)
                        ProviderResult.Success(
                            WeatherSnapshot(
                                time = at,
                                precipitationProbability = hourly.precipitationProbability.getOrNull(index)
                                    ?.let { it / 100.0 },
                                precipitationMm = hourly.precipitation.getOrNull(index),
                                visibilityMetres = hourly.visibility.getOrNull(index),
                                windSpeedKph = hourly.windSpeed.getOrNull(index),
                                temperatureC = hourly.temperature.getOrNull(index),
                                severeAlert = code != null && code in SEVERE_CODES,
                                provenance = DataProvenance.LIVE,
                            ),
                            DataProvenance.LIVE,
                        )
                    }
                },
                onFailure = { e ->
                    ProviderResult.Failure(
                        ProviderNotice(
                            name, DataProvenance.UNAVAILABLE,
                            "Weather unavailable. No weather adjustment was applied.",
                        ),
                        e,
                    )
                },
            )
        }

    private fun nearestIndex(times: List<String>, target: Instant): Int? {
        if (times.isEmpty()) return null
        var best: Int? = null
        var bestDelta = Long.MAX_VALUE
        times.forEachIndexed { i, raw ->
            val parsed = runCatching {
                LocalDateTime.parse(raw, FORMATTER).toInstant(ZoneOffset.UTC)
            }.getOrNull()
            if (parsed != null) {
                val delta = abs(parsed.epochSecond - target.epochSecond)
                if (delta < bestDelta) { bestDelta = delta; best = i }
            }
        }
        // More than three hours away is not a forecast for this journey.
        return if (bestDelta > 3 * 3600) null else best
    }

    private companion object {
        val FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")
        /** WMO codes for thunderstorm, freezing rain and heavy snow. */
        val SEVERE_CODES = setOf(65, 67, 75, 82, 86, 95, 96, 99)
    }
}

/** Offline weather for demo mode: a hot, hazy Abu Dhabi morning. */
class DemoWeatherProvider @Inject constructor() : WeatherProvider {
    override val name: String = "Demo weather"
    override suspend fun forecast(point: GeoPoint, at: Instant): ProviderResult<WeatherSnapshot> =
        ProviderResult.Success(DemoScenario.weather(at), DataProvenance.DEMO)
}

/** Used when the user has turned weather off, or nothing is reachable. */
class UnavailableWeatherProvider @Inject constructor() : WeatherProvider {
    override val name: String = "Weather"
    override suspend fun forecast(point: GeoPoint, at: Instant): ProviderResult<WeatherSnapshot> =
        ProviderResult.Failure(
            ProviderNotice(
                name, DataProvenance.UNAVAILABLE,
                "Weather unavailable. No weather adjustment was applied.",
            ),
        )
}

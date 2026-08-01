package com.ontimequant.di

import android.content.Context
import androidx.room.Room
import com.ontimequant.BuildConfig
import com.ontimequant.data.db.AppointmentDao
import com.ontimequant.data.db.CalibrationDao
import com.ontimequant.data.db.ForecastDao
import com.ontimequant.data.db.JourneyDao
import com.ontimequant.data.db.LearningDao
import com.ontimequant.data.db.LocationDao
import com.ontimequant.data.db.NotificationDao
import com.ontimequant.data.db.OnTimeQuantDatabase
import com.ontimequant.data.db.RouteCacheDao
import com.ontimequant.data.db.TripDao
import com.ontimequant.data.db.TripObservationDao
import com.ontimequant.data.nav.AndroidNavigationLauncher
import com.ontimequant.data.nav.NavigationLauncher
import com.ontimequant.data.remote.events.TicketmasterApi
import com.ontimequant.data.remote.events.TicketmasterEventsProvider
import com.ontimequant.data.remote.places.GooglePlacesProvider
import com.ontimequant.data.remote.places.PlacesApi
import com.ontimequant.data.remote.routes.GoogleRoutesProvider
import com.ontimequant.data.remote.routes.RoutesApi
import com.ontimequant.data.remote.weather.OpenMeteoApi
import com.ontimequant.data.remote.weather.OpenMeteoWeatherProvider
import com.ontimequant.forecast.ForecastEngine
import com.ontimequant.forecast.ModelPriors
import com.ontimequant.model.EventsProvider
import com.ontimequant.model.PlacesProvider
import com.ontimequant.model.RoutesProvider
import com.ontimequant.model.WeatherProvider
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): OnTimeQuantDatabase =
        Room.databaseBuilder(context, OnTimeQuantDatabase::class.java, OnTimeQuantDatabase.NAME)
            .addMigrations(*OnTimeQuantDatabase.MIGRATIONS)
            .apply {
                // Destructive fallback is a debug-only convenience while the schema settles.
                // A release build would rather fail loudly than silently delete trip history.
                if (BuildConfig.DEBUG) fallbackToDestructiveMigration()
            }
            .build()

    @Provides fun locationDao(db: OnTimeQuantDatabase): LocationDao = db.locationDao()
    @Provides fun journeyDao(db: OnTimeQuantDatabase): JourneyDao = db.journeyDao()
    @Provides fun appointmentDao(db: OnTimeQuantDatabase): AppointmentDao = db.appointmentDao()
    @Provides fun forecastDao(db: OnTimeQuantDatabase): ForecastDao = db.forecastDao()
    @Provides fun tripDao(db: OnTimeQuantDatabase): TripDao = db.tripDao()
    @Provides fun tripObservationDao(db: OnTimeQuantDatabase): TripObservationDao = db.tripObservationDao()
    @Provides fun learningDao(db: OnTimeQuantDatabase): LearningDao = db.learningDao()
    @Provides fun calibrationDao(db: OnTimeQuantDatabase): CalibrationDao = db.calibrationDao()
    @Provides fun notificationDao(db: OnTimeQuantDatabase): NotificationDao = db.notificationDao()
    @Provides fun routeCacheDao(db: OnTimeQuantDatabase): RouteCacheDao = db.routeCacheDao()
}

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun json(): Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    @Provides
    @Singleton
    fun okHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .apply {
            if (BuildConfig.DEBUG) {
                // Bodies are logged only in debug builds. API keys travel in headers and
                // query parameters, so BASIC is used rather than BODY even here.
                addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
            }
        }
        .build()

    private fun retrofit(baseUrl: String, client: OkHttpClient, json: Json): Retrofit =
        Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

    @Provides @Singleton
    fun routesApi(client: OkHttpClient, json: Json): RoutesApi =
        retrofit("https://routes.googleapis.com/", client, json).create(RoutesApi::class.java)

    @Provides @Singleton
    fun placesApi(client: OkHttpClient, json: Json): PlacesApi =
        retrofit("https://places.googleapis.com/", client, json).create(PlacesApi::class.java)

    @Provides @Singleton
    fun openMeteoApi(client: OkHttpClient, json: Json): OpenMeteoApi =
        retrofit("https://api.open-meteo.com/", client, json).create(OpenMeteoApi::class.java)

    @Provides @Singleton
    fun ticketmasterApi(client: OkHttpClient, json: Json): TicketmasterApi =
        retrofit("https://app.ticketmaster.com/", client, json).create(TicketmasterApi::class.java)
}

@Module
@InstallIn(SingletonComponent::class)
object ProviderModule {

    /**
     * The live providers. They are bound unconditionally but are only ever *resolved* by
     * [com.ontimequant.data.repository.ProviderRegistry] when a key exists and demo mode is
     * off, so constructing them is cheap and cannot fail.
     */
    @Provides @Singleton @Named("live")
    fun liveRoutes(api: RoutesApi): RoutesProvider =
        GoogleRoutesProvider(api, BuildConfig.MAPS_API_KEY)

    @Provides @Singleton @Named("live")
    fun livePlaces(api: PlacesApi): PlacesProvider =
        GooglePlacesProvider(api, BuildConfig.PLACES_API_KEY)

    @Provides @Singleton @Named("live")
    fun liveWeather(api: OpenMeteoApi): WeatherProvider = OpenMeteoWeatherProvider(api)

    @Provides @Singleton @Named("live")
    fun liveEvents(api: TicketmasterApi): EventsProvider =
        TicketmasterEventsProvider(api, BuildConfig.TICKETMASTER_API_KEY)

    @Provides @Singleton
    fun navigationLauncher(impl: AndroidNavigationLauncher): NavigationLauncher = impl

    @Provides @Singleton
    fun modelPriors(): ModelPriors = ModelPriors.DEFAULT

    @Provides @Singleton
    fun forecastEngine(priors: ModelPriors): ForecastEngine = ForecastEngine(priors)
}

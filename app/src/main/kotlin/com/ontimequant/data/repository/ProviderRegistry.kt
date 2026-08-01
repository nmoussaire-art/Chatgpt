package com.ontimequant.data.repository

import com.ontimequant.BuildConfig
import com.ontimequant.data.db.RouteCacheDao
import com.ontimequant.data.prefs.SettingsRepository
import com.ontimequant.data.remote.events.DemoEventsProvider
import com.ontimequant.data.remote.events.UnavailableEventsProvider
import com.ontimequant.data.remote.places.DemoPlacesProvider
import com.ontimequant.data.remote.routes.CachingRoutesProvider
import com.ontimequant.data.remote.routes.DemoRoutesProvider
import com.ontimequant.data.remote.weather.DemoWeatherProvider
import com.ontimequant.model.EventsProvider
import com.ontimequant.model.PlacesProvider
import com.ontimequant.model.RoutesProvider
import com.ontimequant.model.WeatherProvider
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Decides, per call, whether the app is running live or in demo mode.
 *
 * The rule is deliberately simple and checkable by the user:
 *  - Demo mode on, or no routing key configured → demo providers, everything labelled DEMO.
 *  - Otherwise → live providers, with weather and events degrading independently.
 *
 * A missing key is never a crash and never a blank screen. It is a mode.
 */
@Singleton
class ProviderRegistry @Inject constructor(
    private val settings: SettingsRepository,
    private val routeCache: RouteCacheDao,
    private val demoRoutes: DemoRoutesProvider,
    private val demoPlaces: DemoPlacesProvider,
    private val demoWeather: DemoWeatherProvider,
    private val demoEvents: DemoEventsProvider,
    private val unavailableEvents: UnavailableEventsProvider,
    @Named("live") private val liveRoutes: Provider<RoutesProvider>,
    @Named("live") private val livePlaces: Provider<PlacesProvider>,
    @Named("live") private val liveWeather: Provider<WeatherProvider>,
    @Named("live") private val liveEvents: Provider<EventsProvider>,
) {

    val hasRoutingKey: Boolean get() = BuildConfig.MAPS_API_KEY.isNotBlank()
    val hasPlacesKey: Boolean get() = BuildConfig.PLACES_API_KEY.isNotBlank()
    val hasEventsKey: Boolean get() = BuildConfig.TICKETMASTER_API_KEY.isNotBlank()

    suspend fun isDemo(): Boolean = settings.current().demoMode || !hasRoutingKey

    /** True when demo mode is forced by configuration rather than chosen by the user. */
    suspend fun demoForcedByMissingKey(): Boolean = !hasRoutingKey

    suspend fun routes(): RoutesProvider =
        if (isDemo()) demoRoutes else CachingRoutesProvider(liveRoutes.get(), routeCache)

    suspend fun places(): PlacesProvider =
        if (isDemo() || !hasPlacesKey) demoPlaces else livePlaces.get()

    /** Weather has no key requirement, so it stays live even when routing is in demo mode. */
    suspend fun weather(): WeatherProvider =
        if (settings.current().demoMode) demoWeather else liveWeather.get()

    suspend fun events(): EventsProvider = when {
        settings.current().demoMode -> demoEvents
        hasEventsKey -> liveEvents.get()
        else -> unavailableEvents
    }
}

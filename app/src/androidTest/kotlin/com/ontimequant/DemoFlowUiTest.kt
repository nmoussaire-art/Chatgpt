package com.ontimequant

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.google.common.truth.Truth.assertThat
import com.ontimequant.data.nav.NavigationLauncher
import com.ontimequant.data.prefs.NavigationApp
import com.ontimequant.data.prefs.SettingsRepository
import com.ontimequant.di.ProviderModule
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
import com.ontimequant.model.SavedLocation
import com.ontimequant.model.WeatherProvider
import javax.inject.Named
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A [NavigationLauncher] that records the launch instead of opening a maps app.
 *
 * This is exactly why the launcher is behind an interface: the test can assert that
 * tapping *Navigate* really does fire the navigation intent, on a device with no maps app
 * installed, without the emulator bouncing to another package.
 */
@Singleton
class RecordingNavigationLauncher @Inject constructor() : NavigationLauncher {
    val launched = AtomicReference<SavedLocation?>(null)
    override fun launch(destination: SavedLocation, preferred: NavigationApp): Boolean {
        launched.set(destination)
        return true
    }
    override fun availableApps(): List<NavigationApp> = listOf(NavigationApp.SYSTEM_DEFAULT)
}

/**
 * Replaces [ProviderModule] wholesale. The live providers are still bound (they are never
 * resolved in demo mode) but the navigation launcher is swapped for a recording double.
 */
@Module
@InstallIn(SingletonComponent::class)
object TestProviderModule {

    @Provides @Singleton
    fun navigationLauncher(impl: RecordingNavigationLauncher): NavigationLauncher = impl

    @Provides @Singleton @Named("live")
    fun liveRoutes(api: RoutesApi): RoutesProvider = GoogleRoutesProvider(api, "")

    @Provides @Singleton @Named("live")
    fun livePlaces(api: PlacesApi): PlacesProvider = GooglePlacesProvider(api, "")

    @Provides @Singleton @Named("live")
    fun liveWeather(api: OpenMeteoApi): WeatherProvider = OpenMeteoWeatherProvider(api)

    @Provides @Singleton @Named("live")
    fun liveEvents(api: TicketmasterApi): EventsProvider = TicketmasterEventsProvider(api, "")

    @Provides @Singleton
    fun modelPriors(): ModelPriors = ModelPriors.DEFAULT

    @Provides @Singleton
    fun forecastEngine(priors: ModelPriors): ForecastEngine = ForecastEngine(priors)
}

/**
 * The primary end-to-end demo flow, exercised through the real UI with the real
 * forecasting engine and the real database.
 *
 * Covers, in order:
 *  1. open the app in demo mode;
 *  2. see the seeded demo appointment;
 *  3. have a forecast generated for it;
 *  4. read the recommended departure time;
 *  5. open the departure curve;
 *  6. open the forecast details;
 *  7. trigger navigation through the launcher abstraction.
 */
@LargeTest
@HiltAndroidTest
@UninstallModules(ProviderModule::class)
@RunWith(AndroidJUnit4::class)
class DemoFlowUiTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Inject lateinit var launcher: NavigationLauncher
    @Inject lateinit var settings: SettingsRepository

    @Before
    fun setUp() {
        hiltRule.inject()
        // Skip onboarding and force demo mode so the test is hermetic and offline.
        runBlocking {
            settings.setOnboardingComplete(true)
            settings.setDemoMode(true)
        }
    }

    @Test
    fun demoJourneyProducesADepartureRecommendationAndDrivesTheWholeFlow() {
        // 1 & 2 — the app opens on the departure screen with the seeded demo appointment.
        composeRule.waitUntil(timeoutMillis = 60_000) {
            composeRule.onAllNodes(hasTestTag("recommended_departure"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("home_screen").assertIsDisplayed()

        // 3 & 4 — a departure time and an on-time probability were produced by the engine.
        composeRule.onNodeWithTag("recommended_departure").assertIsDisplayed()
        composeRule.onNodeWithTag("on_time_probability").assertIsDisplayed()

        val probability = composeRule.onNodeWithTag("on_time_probability")
            .fetchSemanticsNode().config
            .getOrNull(SemanticsProperties.Text)
            ?.joinToString()
            .orEmpty()
        assertThat(probability).contains("%")
        // A generated probability, not a placeholder.
        val value = probability.filter(Char::isDigit).toIntOrNull()
        assertThat(value).isNotNull()
        assertThat(value!!).isIn(1..100)

        // The demo must announce itself.
        composeRule.onNodeWithText("DEMO", substring = true).assertIsDisplayed()

        // 5 — open the departure probability curve.
        composeRule.onNodeWithTag("open_curve").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodes(hasTestTag("curve_screen")).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("curve_screen").assertIsDisplayed()
        composeRule.onNodeWithText("Percentiles of arrival time", substring = true)
            .performScrollTo().assertIsDisplayed()

        // Back to the departure screen.
        composeRule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodes(hasTestTag("home_screen")).fetchSemanticsNodes().isNotEmpty()
        }

        // 6 — open the forecast breakdown.
        composeRule.onNodeWithTag("open_details").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodes(hasTestTag("details_screen")).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("details_screen").assertIsDisplayed()
        composeRule.onNodeWithText("Journey components", substring = true)
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Free-flow driving time", substring = true)
            .performScrollTo().assertIsDisplayed()

        composeRule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodes(hasTestTag("home_screen")).fetchSemanticsNodes().isNotEmpty()
        }

        // 7 — starting navigation fires the launcher abstraction with the demo destination.
        composeRule.onNodeWithTag("navigate_button").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 15_000) {
            (launcher as RecordingNavigationLauncher).launched.get() != null
        }
        val destination = (launcher as RecordingNavigationLauncher).launched.get()
        assertThat(destination).isNotNull()
        assertThat(destination!!.label).isEqualTo("Office")
    }
}

package com.ontimequant.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "ontime_quant_settings")

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class DistanceUnits { METRIC, IMPERIAL }

enum class NavigationApp(val label: String, val packageName: String?) {
    SYSTEM_DEFAULT("System default", null),
    GOOGLE_MAPS("Google Maps", "com.google.android.apps.maps"),
    WAZE("Waze", "com.waze"),
    OSMAND("OsmAnd", "net.osmand.plus"),
}

/** The four presets plus a custom value, all clamped to a sane band. */
object ConfidencePresets {
    const val RELAXED = 0.70
    const val BALANCED = 0.85
    const val SAFE = 0.90
    const val VERY_SAFE = 0.95
    const val MIN = 0.50
    const val MAX = 0.99

    val all = listOf(
        Triple("Relaxed", RELAXED, "You will usually be on time, and occasionally not."),
        Triple("Balanced", BALANCED, "A sensible default for everyday appointments."),
        Triple("Safe", SAFE, "Recommended. Rarely late, without leaving absurdly early."),
        Triple("Very safe", VERY_SAFE, "For flights and things you cannot miss."),
    )

    fun label(value: Double): String =
        all.firstOrNull { kotlin.math.abs(it.second - value) < 0.005 }?.first ?: "Custom"
}

data class UserSettings(
    val onboardingComplete: Boolean = false,
    val demoMode: Boolean = true,
    val confidenceTarget: Double = ConfidencePresets.SAFE,
    val entryBufferMinutes: Int = 5,
    val navigationApp: NavigationApp = NavigationApp.SYSTEM_DEFAULT,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val units: DistanceUnits = DistanceUnits.METRIC,
    val use24HourClock: Boolean? = null, // null = follow the device
    val calendarEnabled: Boolean = false,
    val selectedCalendarIds: Set<Long> = emptySet(),
    val backgroundTrackingEnabled: Boolean = false,
    val notificationsEnabled: Boolean = true,
    val departureRemindersEnabled: Boolean = true,
    val forecastChangeAlertsEnabled: Boolean = true,
    val preparationLearningEnabled: Boolean = true,
    val defaultParkingMinutes: Double? = null,
    val defaultWalkingMinutes: Double? = null,
    val defaultPreparationMinutes: Double? = null,
    val simulationDraws: Int = 4000,
    val reducedMotion: Boolean = false,
) {
    val defaultParkingSeconds: Double? get() = defaultParkingMinutes?.times(60)
    val defaultWalkingSeconds: Double? get() = defaultWalkingMinutes?.times(60)
    val defaultPreparationSeconds: Double? get() = defaultPreparationMinutes?.times(60)
}

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val onboardingComplete = booleanPreferencesKey("onboarding_complete")
        val demoMode = booleanPreferencesKey("demo_mode")
        val confidence = doublePreferencesKey("confidence_target")
        val entryBuffer = intPreferencesKey("entry_buffer_minutes")
        val navApp = stringPreferencesKey("navigation_app")
        val theme = stringPreferencesKey("theme_mode")
        val units = stringPreferencesKey("units")
        val clock = stringPreferencesKey("clock_format")
        val calendarEnabled = booleanPreferencesKey("calendar_enabled")
        val calendarIds = stringSetPreferencesKey("calendar_ids")
        val backgroundTracking = booleanPreferencesKey("background_tracking")
        val notifications = booleanPreferencesKey("notifications_enabled")
        val departureReminders = booleanPreferencesKey("departure_reminders")
        val forecastAlerts = booleanPreferencesKey("forecast_alerts")
        val prepLearning = booleanPreferencesKey("preparation_learning")
        val parking = doublePreferencesKey("default_parking_minutes")
        val walking = doublePreferencesKey("default_walking_minutes")
        val preparation = doublePreferencesKey("default_preparation_minutes")
        val draws = intPreferencesKey("simulation_draws")
        val reducedMotion = booleanPreferencesKey("reduced_motion")
    }

    val settings: Flow<UserSettings> = context.settingsDataStore.data.map { prefs ->
        UserSettings(
            onboardingComplete = prefs[Keys.onboardingComplete] ?: false,
            demoMode = prefs[Keys.demoMode] ?: true,
            confidenceTarget = (prefs[Keys.confidence] ?: ConfidencePresets.SAFE)
                .coerceIn(ConfidencePresets.MIN, ConfidencePresets.MAX),
            entryBufferMinutes = prefs[Keys.entryBuffer] ?: 5,
            navigationApp = prefs[Keys.navApp]?.let { name ->
                runCatching { NavigationApp.valueOf(name) }.getOrNull()
            } ?: NavigationApp.SYSTEM_DEFAULT,
            themeMode = prefs[Keys.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                ?: ThemeMode.SYSTEM,
            units = prefs[Keys.units]?.let { runCatching { DistanceUnits.valueOf(it) }.getOrNull() }
                ?: DistanceUnits.METRIC,
            use24HourClock = when (prefs[Keys.clock]) {
                "24" -> true
                "12" -> false
                else -> null
            },
            calendarEnabled = prefs[Keys.calendarEnabled] ?: false,
            selectedCalendarIds = prefs[Keys.calendarIds]?.mapNotNull { it.toLongOrNull() }?.toSet() ?: emptySet(),
            backgroundTrackingEnabled = prefs[Keys.backgroundTracking] ?: false,
            notificationsEnabled = prefs[Keys.notifications] ?: true,
            departureRemindersEnabled = prefs[Keys.departureReminders] ?: true,
            forecastChangeAlertsEnabled = prefs[Keys.forecastAlerts] ?: true,
            preparationLearningEnabled = prefs[Keys.prepLearning] ?: true,
            defaultParkingMinutes = prefs[Keys.parking],
            defaultWalkingMinutes = prefs[Keys.walking],
            defaultPreparationMinutes = prefs[Keys.preparation],
            simulationDraws = (prefs[Keys.draws] ?: 4000).coerceIn(2000, 20_000),
            reducedMotion = prefs[Keys.reducedMotion] ?: false,
        )
    }

    suspend fun current(): UserSettings = settings.first()

    suspend fun setOnboardingComplete(value: Boolean) = edit { it[Keys.onboardingComplete] = value }
    suspend fun setDemoMode(value: Boolean) = edit { it[Keys.demoMode] = value }
    suspend fun setConfidenceTarget(value: Double) =
        edit { it[Keys.confidence] = value.coerceIn(ConfidencePresets.MIN, ConfidencePresets.MAX) }
    suspend fun setEntryBufferMinutes(value: Int) = edit { it[Keys.entryBuffer] = value.coerceIn(0, 180) }
    suspend fun setNavigationApp(value: NavigationApp) = edit { it[Keys.navApp] = value.name }
    suspend fun setThemeMode(value: ThemeMode) = edit { it[Keys.theme] = value.name }
    suspend fun setUnits(value: DistanceUnits) = edit { it[Keys.units] = value.name }
    suspend fun setClockFormat(use24: Boolean?) = edit { prefs ->
        when (use24) {
            true -> prefs[Keys.clock] = "24"
            false -> prefs[Keys.clock] = "12"
            null -> prefs.remove(Keys.clock)
        }
    }
    suspend fun setCalendarEnabled(value: Boolean) = edit { it[Keys.calendarEnabled] = value }
    suspend fun setSelectedCalendars(ids: Set<Long>) =
        edit { it[Keys.calendarIds] = ids.map(Long::toString).toSet() }
    suspend fun setBackgroundTracking(value: Boolean) = edit { it[Keys.backgroundTracking] = value }
    suspend fun setNotificationsEnabled(value: Boolean) = edit { it[Keys.notifications] = value }
    suspend fun setDepartureReminders(value: Boolean) = edit { it[Keys.departureReminders] = value }
    suspend fun setForecastAlerts(value: Boolean) = edit { it[Keys.forecastAlerts] = value }
    suspend fun setPreparationLearning(value: Boolean) = edit { it[Keys.prepLearning] = value }
    suspend fun setReducedMotion(value: Boolean) = edit { it[Keys.reducedMotion] = value }

    suspend fun setDefaultParkingMinutes(value: Double?) = edit { prefs ->
        if (value == null) prefs.remove(Keys.parking) else prefs[Keys.parking] = value.coerceIn(0.0, 120.0)
    }
    suspend fun setDefaultWalkingMinutes(value: Double?) = edit { prefs ->
        if (value == null) prefs.remove(Keys.walking) else prefs[Keys.walking] = value.coerceIn(0.0, 120.0)
    }
    suspend fun setDefaultPreparationMinutes(value: Double?) = edit { prefs ->
        if (value == null) prefs.remove(Keys.preparation) else prefs[Keys.preparation] = value.coerceIn(0.0, 180.0)
    }

    /** Used by "Reset demo" and by full data deletion. */
    suspend fun clearAll() {
        context.settingsDataStore.edit { it.clear() }
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.settingsDataStore.edit(block)
    }
}

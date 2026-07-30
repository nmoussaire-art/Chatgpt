package com.loopguard.app.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map

enum class ThemeMode(val label: String) {
    SYSTEM("Follow system"),
    LIGHT("Always light"),
    DARK("Always dark");

    companion object {
        fun from(key: String?) = entries.firstOrNull { it.name == key } ?: SYSTEM
    }
}

data class AppSettings(
    val onboardingComplete: Boolean = false,
    val displayName: String = "",
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColour: Boolean = true,
    val remindersEnabled: Boolean = true,
    val digestHour: Int = 8,
    /** Days of silence before LoopGuard suggests chasing again. */
    val followUpCadenceDays: Int = 5,
)

class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("loopguard_prefs", Context.MODE_PRIVATE)

    fun current(): AppSettings = AppSettings(
        onboardingComplete = prefs.getBoolean(KEY_ONBOARDED, false),
        displayName = prefs.getString(KEY_NAME, "").orEmpty(),
        themeMode = ThemeMode.from(prefs.getString(KEY_THEME, null)),
        dynamicColour = prefs.getBoolean(KEY_DYNAMIC, true),
        remindersEnabled = prefs.getBoolean(KEY_REMINDERS, true),
        digestHour = prefs.getInt(KEY_HOUR, 8),
        followUpCadenceDays = prefs.getInt(KEY_CADENCE, 5),
    )

    fun observe(): Flow<AppSettings> = callbackFlow {
        trySend(current())
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(current()) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.map { it }

    fun setOnboardingComplete(value: Boolean) = prefs.edit().putBoolean(KEY_ONBOARDED, value).apply()
    fun setDisplayName(value: String) = prefs.edit().putString(KEY_NAME, value.trim()).apply()
    fun setThemeMode(value: ThemeMode) = prefs.edit().putString(KEY_THEME, value.name).apply()
    fun setDynamicColour(value: Boolean) = prefs.edit().putBoolean(KEY_DYNAMIC, value).apply()
    fun setRemindersEnabled(value: Boolean) = prefs.edit().putBoolean(KEY_REMINDERS, value).apply()
    fun setDigestHour(value: Int) = prefs.edit().putInt(KEY_HOUR, value.coerceIn(0, 23)).apply()
    fun setFollowUpCadence(value: Int) = prefs.edit().putInt(KEY_CADENCE, value.coerceIn(1, 30)).apply()

    /** Guards against posting the same digest twice in one day. */
    fun lastDigestDay(): Long = prefs.getLong(KEY_LAST_DIGEST, 0L)
    fun setLastDigestDay(day: Long) = prefs.edit().putLong(KEY_LAST_DIGEST, day).apply()

    private companion object {
        const val KEY_ONBOARDED = "onboarding_complete"
        const val KEY_NAME = "display_name"
        const val KEY_THEME = "theme_mode"
        const val KEY_DYNAMIC = "dynamic_colour"
        const val KEY_REMINDERS = "reminders_enabled"
        const val KEY_HOUR = "digest_hour"
        const val KEY_CADENCE = "followup_cadence"
        const val KEY_LAST_DIGEST = "last_digest_day"
    }
}

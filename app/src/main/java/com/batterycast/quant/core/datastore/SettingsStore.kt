package com.batterycast.quant.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "batterycast_settings")

/** User preferences and model bookkeeping. Local only, like everything else in this app. */
@Singleton
class SettingsStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val store = context.dataStore

    val settings: Flow<UserSettings> = store.data.map { preferences ->
        UserSettings(
            reservePercent = preferences[RESERVE_PERCENT] ?: UserSettings.DEFAULT_RESERVE,
            bedtimeHour = preferences[BEDTIME_HOUR] ?: UserSettings.DEFAULT_BEDTIME_HOUR,
            bedtimeMinute = preferences[BEDTIME_MINUTE] ?: 0,
            targetConfidence = preferences[TARGET_CONFIDENCE] ?: UserSettings.DEFAULT_CONFIDENCE,
            notificationsEnabled = preferences[NOTIFICATIONS_ENABLED] ?: true,
            unusualDrainAlerts = preferences[UNUSUAL_DRAIN_ALERTS] ?: true,
            selectedTargetLabel = preferences[SELECTED_TARGET_LABEL],
            selectedTargetMs = preferences[SELECTED_TARGET_MS],
            desiredBatteryAtTarget = preferences[DESIRED_BATTERY_AT_TARGET]
                ?: UserSettings.DEFAULT_DESIRED_BATTERY,
        )
    }

    suspend fun current(): UserSettings = settings.first()

    suspend fun setReservePercent(value: Double) = store.editValue(RESERVE_PERCENT, value.coerceIn(0.0, 50.0))

    suspend fun setBedtime(hour: Int, minute: Int) {
        store.edit {
            it[BEDTIME_HOUR] = hour.coerceIn(0, 23)
            it[BEDTIME_MINUTE] = minute.coerceIn(0, 59)
        }
    }

    suspend fun setTargetConfidence(value: Double) =
        store.editValue(TARGET_CONFIDENCE, value.coerceIn(0.5, 0.99))

    suspend fun setNotificationsEnabled(value: Boolean) = store.editValue(NOTIFICATIONS_ENABLED, value)

    suspend fun setUnusualDrainAlerts(value: Boolean) = store.editValue(UNUSUAL_DRAIN_ALERTS, value)

    suspend fun setDesiredBatteryAtTarget(value: Double) =
        store.editValue(DESIRED_BATTERY_AT_TARGET, value.coerceIn(0.0, 100.0))

    suspend fun setSelectedTarget(label: String?, atMs: Long?) {
        store.edit { preferences ->
            if (label == null || atMs == null) {
                preferences.remove(SELECTED_TARGET_LABEL)
                preferences.remove(SELECTED_TARGET_MS)
            } else {
                preferences[SELECTED_TARGET_LABEL] = label
                preferences[SELECTED_TARGET_MS] = atMs
            }
        }
    }

    /**
     * Timestamp of the newest observation already folded into the learned model.
     *
     * Without a watermark, every model refresh would re-apply the same intervals and inflate
     * their effective weight, making the model look far more certain than the evidence supports.
     */
    suspend fun modelWatermarkMs(): Long = store.data.first()[MODEL_WATERMARK_MS] ?: 0L

    suspend fun setModelWatermarkMs(value: Long) = store.editValue(MODEL_WATERMARK_MS, value)

    suspend fun clearModelWatermark() = store.editValue(MODEL_WATERMARK_MS, 0L)

    private suspend fun <T> DataStore<Preferences>.editValue(key: Preferences.Key<T>, value: T) {
        edit { it[key] = value }
    }

    private companion object {
        val RESERVE_PERCENT = doublePreferencesKey("reserve_percent")
        val BEDTIME_HOUR = intPreferencesKey("bedtime_hour")
        val BEDTIME_MINUTE = intPreferencesKey("bedtime_minute")
        val TARGET_CONFIDENCE = doublePreferencesKey("target_confidence")
        val NOTIFICATIONS_ENABLED = booleanPreferencesKey("notifications_enabled")
        val UNUSUAL_DRAIN_ALERTS = booleanPreferencesKey("unusual_drain_alerts")
        val SELECTED_TARGET_LABEL = stringPreferencesKey("selected_target_label")
        val SELECTED_TARGET_MS = longPreferencesKey("selected_target_ms")
        val DESIRED_BATTERY_AT_TARGET = doublePreferencesKey("desired_battery_at_target")
        val MODEL_WATERMARK_MS = longPreferencesKey("model_watermark_ms")
    }
}

data class UserSettings(
    val reservePercent: Double,
    val bedtimeHour: Int,
    val bedtimeMinute: Int,
    val targetConfidence: Double,
    val notificationsEnabled: Boolean,
    val unusualDrainAlerts: Boolean,
    val selectedTargetLabel: String?,
    val selectedTargetMs: Long?,
    val desiredBatteryAtTarget: Double,
) {
    companion object {
        const val DEFAULT_RESERVE = 10.0
        const val DEFAULT_BEDTIME_HOUR = 23
        const val DEFAULT_CONFIDENCE = 0.9
        const val DEFAULT_DESIRED_BATTERY = 40.0
    }
}

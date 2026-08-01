package com.ontimequant.ui.settings

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ontimequant.data.calendar.CalendarRepository
import com.ontimequant.data.calendar.DeviceCalendar
import com.ontimequant.data.nav.NavigationLauncher
import com.ontimequant.data.prefs.DistanceUnits
import com.ontimequant.data.prefs.NavigationApp
import com.ontimequant.data.prefs.SettingsRepository
import com.ontimequant.data.prefs.ThemeMode
import com.ontimequant.data.prefs.UserSettings
import com.ontimequant.data.repository.DataExporter
import com.ontimequant.data.repository.DemoSeeder
import com.ontimequant.data.repository.ProviderRegistry
import com.ontimequant.data.repository.TripRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val settings: UserSettings = UserSettings(),
    val calendars: List<DeviceCalendar> = emptyList(),
    val availableNavigationApps: List<NavigationApp> = listOf(NavigationApp.SYSTEM_DEFAULT),
    val demoForced: Boolean = false,
    val exportMessage: String? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val calendar: CalendarRepository,
    private val providers: ProviderRegistry,
    private val seeder: DemoSeeder,
    private val trips: TripRepository,
    private val launcher: NavigationLauncher,
    private val exporter: DataExporter,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            _state.update {
                it.copy(
                    calendars = calendar.calendars(),
                    availableNavigationApps = launcher.availableApps(),
                    demoForced = providers.demoForcedByMissingKey(),
                )
            }
        }
        viewModelScope.launch {
            settings.settings.collect { userSettings ->
                _state.update { it.copy(settings = userSettings) }
            }
        }
    }

    fun setDemoMode(value: Boolean) = launch { settings.setDemoMode(value) }
    fun setConfidence(value: Double) = launch { settings.setConfidenceTarget(value) }
    fun setEntryBuffer(minutes: Int) = launch { settings.setEntryBufferMinutes(minutes) }
    fun setParking(minutes: Double?) = launch { settings.setDefaultParkingMinutes(minutes) }
    fun setWalking(minutes: Double?) = launch { settings.setDefaultWalkingMinutes(minutes) }
    fun setPreparation(minutes: Double?) = launch { settings.setDefaultPreparationMinutes(minutes) }
    fun setPreparationLearning(value: Boolean) = launch { settings.setPreparationLearning(value) }
    fun setBackgroundTracking(value: Boolean) = launch { settings.setBackgroundTracking(value) }
    fun setNotifications(value: Boolean) = launch { settings.setNotificationsEnabled(value) }
    fun setDepartureReminders(value: Boolean) = launch { settings.setDepartureReminders(value) }
    fun setForecastAlerts(value: Boolean) = launch { settings.setForecastAlerts(value) }
    fun setCalendarEnabled(value: Boolean) = launch { settings.setCalendarEnabled(value) }
    fun setNavigationApp(app: NavigationApp) = launch { settings.setNavigationApp(app) }
    fun setTheme(mode: ThemeMode) = launch { settings.setThemeMode(mode) }
    fun setUnits(units: DistanceUnits) = launch { settings.setUnits(units) }
    fun setClock(use24: Boolean?) = launch { settings.setClockFormat(use24) }
    fun setReducedMotion(value: Boolean) = launch { settings.setReducedMotion(value) }

    fun toggleCalendar(id: Long) = launch {
        val current = _state.value.settings.selectedCalendarIds
        settings.setSelectedCalendars(if (id in current) current - id else current + id)
    }

    fun resetDemo() = launch {
        seeder.reseed()
        _state.update { it.copy(exportMessage = "Demo data reloaded.") }
    }

    /**
     * Full local deletion. Clears every table and every preference, then reseeds the demo
     * so the app is usable rather than an empty husk.
     */
    fun deleteEverything() = launch {
        seeder.clear()
        settings.clearAll()
        settings.setOnboardingComplete(true)
        seeder.seedIfNeeded()
        trips.refreshDerivedState()
        _state.update { it.copy(exportMessage = "All local data deleted. The demo journey was reloaded.") }
    }

    fun exportData(context: Context) = launch {
        val file = exporter.exportToFile()
        if (file == null) {
            _state.update { it.copy(exportMessage = "Export failed.") }
            return@launch
        }
        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.getOrNull()
        if (uri != null) {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(Intent.createChooser(intent, "Export OnTime Quant data")) }
        }
        _state.update { it.copy(exportMessage = "Exported to ${file.name}.") }
    }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}

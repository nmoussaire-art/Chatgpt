package com.ontimequant.ui.onboarding

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ontimequant.data.calendar.CalendarRepository
import com.ontimequant.data.location.LocationTracker
import com.ontimequant.data.prefs.ConfidencePresets
import com.ontimequant.data.prefs.SettingsRepository
import com.ontimequant.data.repository.DemoSeeder
import com.ontimequant.data.repository.ProviderRegistry
import com.ontimequant.work.WorkScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class OnboardingState(
    val locationGranted: Boolean = false,
    val calendarGranted: Boolean = false,
    val notificationsGranted: Boolean = false,
    val confidenceTarget: Double = ConfidencePresets.SAFE,
    val demoForced: Boolean = false,
)

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val location: LocationTracker,
    private val calendar: CalendarRepository,
    private val providers: ProviderRegistry,
    private val seeder: DemoSeeder,
) : ViewModel() {

    private val _state = MutableStateFlow(OnboardingState())
    val state: StateFlow<OnboardingState> = _state.asStateFlow()

    init {
        refreshPermissions()
        viewModelScope.launch {
            _state.update {
                it.copy(
                    demoForced = providers.demoForcedByMissingKey(),
                    confidenceTarget = settings.current().confidenceTarget,
                )
            }
        }
    }

    fun refreshPermissions() {
        _state.update {
            it.copy(
                locationGranted = location.hasForeground(),
                calendarGranted = calendar.hasPermission(),
                notificationsGranted = notificationsGranted(),
            )
        }
    }

    fun onCalendarPermission(granted: Boolean) {
        viewModelScope.launch {
            settings.setCalendarEnabled(granted)
            if (granted) {
                // Default to every calendar the user has; they can narrow it in Settings.
                settings.setSelectedCalendars(calendar.calendars().map { it.id }.toSet())
            }
            refreshPermissions()
        }
    }

    fun setConfidence(value: Double) {
        _state.update { it.copy(confidenceTarget = value) }
        viewModelScope.launch { settings.setConfidenceTarget(value) }
    }

    fun finish(demoMode: Boolean, onFinished: () -> Unit) {
        viewModelScope.launch {
            settings.setDemoMode(demoMode || providers.demoForcedByMissingKey())
            settings.setNotificationsEnabled(notificationsGranted())
            settings.setOnboardingComplete(true)
            seeder.seedIfNeeded()
            WorkScheduler.schedulePeriodicRefresh(context)
            onFinished()
        }
    }

    private fun notificationsGranted(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
}

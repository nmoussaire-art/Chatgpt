package com.batterycast.quant.feature.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.batterycast.quant.core.database.dao.NotificationStateDao
import com.batterycast.quant.core.database.dao.PrecisionSessionDao
import com.batterycast.quant.core.datastore.SettingsStore
import com.batterycast.quant.core.datastore.UserSettings
import com.batterycast.quant.core.system.CalendarEventProvider
import com.batterycast.quant.core.system.UsageInsightProvider
import com.batterycast.quant.di.IoDispatcher
import com.batterycast.quant.feature.shared.ForecastCoordinator
import com.batterycast.quant.forecasting.ModelUpdater
import com.batterycast.quant.forecasting.accuracy.AccuracyEvaluator
import com.batterycast.quant.telemetry.export.ObservationExporter
import com.batterycast.quant.telemetry.model.SensorCapabilities
import com.batterycast.quant.telemetry.precision.PrecisionSessionService
import com.batterycast.quant.telemetry.repository.ObservationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class PermissionStatus(
    val usageAccessGranted: Boolean,
    val calendarGranted: Boolean,
    val notificationsGranted: Boolean,
)

data class DiagnosticsState(
    val observationCount: Int = 0,
    val earliestObservationMs: Long? = null,
    val capabilities: SensorCapabilities = SensorCapabilities(),
    val precisionSessionActive: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsStore: SettingsStore,
    private val observationRepository: ObservationRepository,
    private val usageInsightProvider: UsageInsightProvider,
    private val calendarEventProvider: CalendarEventProvider,
    private val notifier: com.batterycast.quant.notifications.BatteryCastNotifier,
    private val modelUpdater: ModelUpdater,
    private val accuracyEvaluator: AccuracyEvaluator,
    private val coordinator: ForecastCoordinator,
    private val exporter: ObservationExporter,
    private val precisionSessionDao: PrecisionSessionDao,
    private val notificationStateDao: NotificationStateDao,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    val settings: StateFlow<UserSettings?> = settingsStore.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _permissions = MutableStateFlow(PermissionStatus(false, false, false))
    val permissions: StateFlow<PermissionStatus> = _permissions.asStateFlow()

    private val _diagnostics = MutableStateFlow(DiagnosticsState())
    val diagnostics: StateFlow<DiagnosticsState> = _diagnostics.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            _permissions.value = PermissionStatus(
                usageAccessGranted = usageInsightProvider.hasUsageAccess(),
                calendarGranted = calendarEventProvider.hasPermission(),
                notificationsGranted = notifier.canPostNotifications(),
            )
            _diagnostics.value = DiagnosticsState(
                observationCount = observationRepository.count(),
                earliestObservationMs = observationRepository.earliestTimestamp(),
                capabilities = observationRepository.capabilities(),
                precisionSessionActive = precisionSessionDao.active() != null,
            )
        }
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setNotificationsEnabled(enabled) }
    }

    fun setUnusualDrainAlerts(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setUnusualDrainAlerts(enabled) }
    }

    fun setReservePercent(value: Double) {
        viewModelScope.launch {
            settingsStore.setReservePercent(value)
            coordinator.refreshSuspending(force = true)
        }
    }

    fun setBedtime(hour: Int, minute: Int) {
        viewModelScope.launch { settingsStore.setBedtime(hour, minute) }
    }

    fun startPrecisionSession(durationMinutes: Int) {
        PrecisionSessionService.start(context, durationMinutes * 60_000L)
        _message.value = "Precision session started. It will stop itself automatically."
        refresh()
    }

    fun stopPrecisionSession() {
        PrecisionSessionService.stop(context)
        _message.value = "Precision session stopped."
        refresh()
    }

    /** Writes a CSV of the raw observations to the app cache and returns a share intent target. */
    fun exportData(onReady: (android.net.Uri) -> Unit) {
        viewModelScope.launch {
            val uri = withContext(ioDispatcher) { exporter.exportToCache() }
            if (uri == null) {
                _message.value = "Nothing to export yet."
            } else {
                onReady(uri)
            }
        }
    }

    /** Deletes every observation, every learned parameter, and every scored forecast. */
    fun deleteAllData() {
        viewModelScope.launch {
            observationRepository.deleteAll()
            modelUpdater.reset()
            accuracyEvaluator.clear()
            notificationStateDao.deleteAll()
            precisionSessionDao.deleteAll()
            coordinator.invalidate()
            _message.value = "All local data deleted. Collection starts again from now."
            refresh()
        }
    }

    fun clearMessage() {
        _message.value = null
    }
}

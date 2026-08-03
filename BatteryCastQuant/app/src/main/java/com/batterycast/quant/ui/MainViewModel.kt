package com.batterycast.quant.ui

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.batterycast.quant.data.AccuracyRepository
import com.batterycast.quant.data.BatteryRepository
import com.batterycast.quant.forecast.BatteryForecastEngine
import com.batterycast.quant.forecast.ChargePlanner
import com.batterycast.quant.model.*
import com.batterycast.quant.system.CalendarTargetSource
import com.batterycast.quant.telemetry.PrecisionMeasurementService
import com.batterycast.quant.telemetry.TelemetryScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZonedDateTime
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: BatteryRepository,
    private val engine: BatteryForecastEngine,
    private val planner: ChargePlanner,
    private val scheduler: TelemetryScheduler,
    private val calendars: CalendarTargetSource,
    private val accuracyRepository: AccuracyRepository
) : ViewModel() {
    private val preferences = context.getSharedPreferences("batterycast_settings", Context.MODE_PRIVATE)
    private val _target = MutableStateFlow(
        preferences.getLong("target_time", defaultTarget()).takeIf { it > System.currentTimeMillis() } ?: defaultTarget()
    )
    val target: StateFlow<Long> = _target.asStateFlow()

    private val _reserve = MutableStateFlow(preferences.getInt("reserve", 10))
    val reserve: StateFlow<Int> = _reserve.asStateFlow()

    private val selectedScenario = MutableStateFlow<ScenarioDefinition?>(null)
    private val historyWindowDays = MutableStateFlow(14)
    private val _customDurationMinutes = MutableStateFlow(60)
    val customDurationMinutes: StateFlow<Int> = _customDurationMinutes.asStateFlow()
    private val _customActiveShare = MutableStateFlow(60)
    val customActiveShare: StateFlow<Int> = _customActiveShare.asStateFlow()
    private val _customHeavyShare = MutableStateFlow(30)
    val customHeavyShare: StateFlow<Int> = _customHeavyShare.asStateFlow()

    private val customScenario: StateFlow<ScenarioDefinition> = combine(
        _customDurationMinutes,
        _customActiveShare,
        _customHeavyShare
    ) { duration, activeShare, heavyShare ->
        val active = activeShare / 100.0
        val heavyWithinActive = heavyShare / 100.0
        ScenarioDefinition(
            id = "custom",
            title = "Custom mixed usage",
            regime = UsageRegime.NORMAL,
            durationMinutes = duration,
            mix = mapOf(
                UsageRegime.STANDBY to (1.0 - active),
                UsageRegime.NORMAL to active * (1.0 - heavyWithinActive),
                UsageRegime.MEDIA_GAMING to active * heavyWithinActive
            )
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        ScenarioDefinition(
            id = "custom",
            title = "Custom mixed usage",
            regime = UsageRegime.NORMAL,
            durationMinutes = 60,
            mix = mapOf(
                UsageRegime.STANDBY to .40,
                UsageRegime.NORMAL to .42,
                UsageRegime.MEDIA_GAMING to .18
            )
        )
    )

    val history: StateFlow<List<BatteryObservation>> = historyWindowDays
        .flatMapLatest { days -> repository.history(System.currentTimeMillis() - days * 24L * 60 * 60_000L) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val latest: StateFlow<BatteryObservation?> = repository.latest()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val forecast: StateFlow<ForecastResult?> = combine(history, _target, _reserve, selectedScenario) { observations, time, reserve, scenario ->
        ForecastRequest(observations, time, reserve, scenario)
    }.mapLatest { request ->
        if (request.observations.isEmpty()) null else withContext(Dispatchers.Default) {
            engine.forecast(request.observations, request.target, request.reserve, request.scenario)
        }
    }.onEach { result ->
        if (result != null && selectedScenario.value == null) repository.saveForecast(result)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val scenarioResults: StateFlow<List<ScenarioForecast>> = combine(
        history,
        _target,
        _reserve,
        customScenario
    ) { observations, time, reserve, custom ->
        ScenarioRequest(observations, time, reserve, custom)
    }.mapLatest { request ->
        if (request.observations.isEmpty()) emptyList() else withContext(Dispatchers.Default) {
            engine.scenarioForecasts(
                request.observations,
                request.target,
                request.reserve,
                scenarioDefinitions + request.custom
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _chargePlan = MutableStateFlow<ChargePlan?>(null)
    val chargePlan: StateFlow<ChargePlan?> = _chargePlan.asStateFlow()

    private val _accuracy = MutableStateFlow(AccuracySummary(0, null, null, null, null, null, null, null))
    val accuracy: StateFlow<AccuracySummary> = _accuracy.asStateFlow()

    private val _calendarTargets = MutableStateFlow<List<CalendarTarget>>(emptyList())
    val calendarTargets: StateFlow<List<CalendarTarget>> = _calendarTargets.asStateFlow()

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    init {
        scheduler.captureNow("ui_open")
        refreshCalendar()
        refreshAccuracy()
    }

    fun setTarget(time: Long) {
        _target.value = time
        preferences.edit().putLong("target_time", time).apply()
        scheduler.captureNow("forecast_target")
    }

    fun setReserve(value: Int) {
        _reserve.value = value.coerceIn(0, 20)
        preferences.edit().putInt("reserve", _reserve.value).apply()
    }

    fun setQuickTarget(kind: String) {
        val now = ZonedDateTime.now()
        setTarget(
            when (kind) {
                "2h" -> System.currentTimeMillis() + 2 * 60 * 60_000L
                "midnight" -> now.toLocalDate().plusDays(1).atStartOfDay(now.zone).toInstant().toEpochMilli()
                "bedtime" -> now.toLocalDate().atTime(22, 0).atZone(now.zone)
                    .let { if (it.isBefore(now)) it.plusDays(1) else it }
                    .toInstant().toEpochMilli()
                else -> _target.value
            }
        )
    }

    fun setScenario(scenario: ScenarioDefinition?) {
        selectedScenario.value = scenario
    }

    fun setCustomDuration(minutes: Int) {
        _customDurationMinutes.value = minutes.coerceIn(15, 240)
    }

    fun setCustomActiveShare(percent: Int) {
        _customActiveShare.value = percent.coerceIn(0, 100)
    }

    fun setCustomHeavyShare(percent: Int) {
        _customHeavyShare.value = percent.coerceIn(0, 100)
    }

    fun capture() {
        scheduler.captureNow("manual_refresh")
        _status.value = "A live battery reading was requested."
    }

    fun planCharge(eventTime: Long, targetPercent: Int, confidence: Double) {
        viewModelScope.launch {
            val observations = withContext(Dispatchers.IO) { repository.all() }
            val result = withContext(Dispatchers.Default) {
                planner.plan(observations, ChargePlanRequest(eventTime, targetPercent, confidence))
            }
            _chargePlan.value = result
            val safeStart = result.latestSafeStart
            val durationMinutes = result.recommendedDurationMinutes
            preferences.edit().apply {
                if (safeStart != null && durationMinutes != null) {
                    putLong("charge_plan_start", safeStart)
                    putInt("charge_plan_duration", durationMinutes)
                    putInt("charge_plan_target", targetPercent)
                    putLong("charge_plan_event", eventTime)
                    remove("charge_plan_alerted_start")
                } else {
                    remove("charge_plan_start")
                    remove("charge_plan_duration")
                    remove("charge_plan_target")
                    remove("charge_plan_event")
                    remove("charge_plan_alerted_start")
                }
            }.apply()
        }
    }

    fun refreshCalendar() {
        viewModelScope.launch(Dispatchers.IO) { _calendarTargets.value = calendars.upcoming() }
    }

    fun refreshAccuracy() {
        viewModelScope.launch(Dispatchers.IO) { _accuracy.value = accuracyRepository.summary() }
    }

    fun startPrecision(minutes: Int = 15) {
        ContextCompat.startForegroundService(
            context,
            Intent(context, PrecisionMeasurementService::class.java)
                .putExtra(PrecisionMeasurementService.EXTRA_MINUTES, minutes)
        )
        _status.value = "Precision session started for ${minutes.coerceIn(10, 20)} minutes."
    }

    fun stopPrecision() {
        context.stopService(Intent(context, PrecisionMeasurementService::class.java))
        _status.value = "Precision session stopped."
    }

    fun clearData() {
        viewModelScope.launch {
            repository.clear()
            _chargePlan.value = null
            _status.value = "All local observations and forecasts were deleted."
        }
    }

    suspend fun exportCsv(): String = withContext(Dispatchers.IO) { repository.exportCsv() }

    fun clearStatus() {
        _status.value = null
    }

    private data class ForecastRequest(
        val observations: List<BatteryObservation>,
        val target: Long,
        val reserve: Int,
        val scenario: ScenarioDefinition?
    )

    private data class ScenarioRequest(
        val observations: List<BatteryObservation>,
        val target: Long,
        val reserve: Int,
        val custom: ScenarioDefinition
    )

    companion object {
        val scenarioDefinitions = listOf(
            ScenarioDefinition("normal", "Normal use", UsageRegime.NORMAL, 60),
            ScenarioDefinition("nav", "45 min navigation", UsageRegime.NAVIGATION, 45),
            ScenarioDefinition("video", "1 hour video", UsageRegime.MEDIA_GAMING, 60),
            ScenarioDefinition("gaming", "30 min gaming", UsageRegime.HEAVY, 30),
            ScenarioDefinition("hotspot", "1 hour hotspot", UsageRegime.HOTSPOT, 60),
            ScenarioDefinition("standby", "Standby", UsageRegime.STANDBY, 60),
            ScenarioDefinition("save", "Power saving", UsageRegime.NORMAL, 60, powerSave = true)
        )

        fun defaultTarget(): Long = System.currentTimeMillis() + 4 * 60 * 60_000L
    }
}

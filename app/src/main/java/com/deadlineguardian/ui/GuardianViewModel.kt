package com.deadlineguardian.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.deadlineguardian.data.AppDatabase
import com.deadlineguardian.data.Deadline
import com.deadlineguardian.data.DeadlineWithItem
import com.deadlineguardian.data.GuardianSettings
import com.deadlineguardian.data.SettingsStore
import com.deadlineguardian.data.TrackedItem
import com.deadlineguardian.data.Urgency
import com.deadlineguardian.engine.ScanAnalyzer
import com.deadlineguardian.engine.ScanResult
import com.deadlineguardian.notify.DeadlineWorker
import com.deadlineguardian.ocr.OcrService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

/** What the scan screen is doing right now. */
sealed interface ScanState {
    data object Idle : ScanState
    data object Working : ScanState
    data class Ready(val result: ScanResult, val imagePath: String?) : ScanState
    data class Failed(val message: String) : ScanState
}

data class HomeBuckets(
    val overdue: List<DeadlineWithItem> = emptyList(),
    val actNow: List<DeadlineWithItem> = emptyList(),
    val soon: List<DeadlineWithItem> = emptyList(),
    val later: List<DeadlineWithItem> = emptyList(),
    val done: List<DeadlineWithItem> = emptyList()
) {
    val isEmpty: Boolean
        get() = overdue.isEmpty() && actNow.isEmpty() && soon.isEmpty() &&
            later.isEmpty() && done.isEmpty()

    /** Headline number: money you can still recover if you act on what's open now. */
    fun moneyAtRisk(): Pair<Long, String?> {
        val live = overdue + actNow
        val total = live.sumOf { it.deadline.moneyAtRiskMinor ?: 0L }
        val currency = live.firstOrNull { it.deadline.moneyAtRiskMinor != null }?.item?.currency
        return total to currency
    }
}

class GuardianViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = AppDatabase.get(app).dao()
    private val ocr = OcrService(app)
    private val settingsStore = SettingsStore(app)

    val settings: StateFlow<GuardianSettings> = settingsStore.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GuardianSettings())

    private val _scanState = MutableStateFlow<ScanState>(ScanState.Idle)
    val scanState: StateFlow<ScanState> = _scanState.asStateFlow()

    val buckets: StateFlow<HomeBuckets> = dao.activeDeadlines()
        .map { rows ->
            val today = LocalDate.now()
            val entries = rows.map { it.toDomain() }
            HomeBuckets(
                overdue = entries.filter { it.urgency(today) == Urgency.OVERDUE },
                actNow = entries.filter { it.urgency(today) == Urgency.ACT_NOW },
                soon = entries.filter { it.urgency(today) == Urgency.SOON },
                later = entries.filter { it.urgency(today) == Urgency.LATER },
                done = entries.filter { it.urgency(today) == Urgency.DONE }
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeBuckets())

    fun deadline(id: Long) = dao.deadlineById(id).map { it?.toDomain() }

    fun scan(uri: Uri) {
        _scanState.value = ScanState.Working
        viewModelScope.launch {
            runCatching {
                val (text, imagePath) = ocr.scan(uri)
                if (text.isBlank()) {
                    _scanState.value = ScanState.Failed(
                        "No text found. Try again with more light, and get the whole receipt in frame."
                    )
                    return@launch
                }
                val policy = settingsStore.settings.first().toPolicy()
                _scanState.value = ScanState.Ready(ScanAnalyzer.analyze(text, policy), imagePath)
            }.onFailure {
                _scanState.value = ScanState.Failed(it.message ?: "Could not read that image.")
            }
        }
    }

    fun resetScan() {
        _scanState.value = ScanState.Idle
    }

    /** Persists a reviewed scan. Deadlines the user unticked never reach here. */
    fun confirmScan(
        item: TrackedItem,
        deadlines: List<Deadline>,
        onSaved: (Long) -> Unit = {}
    ) {
        viewModelScope.launch {
            val id = dao.saveScan(item, deadlines)
            _scanState.value = ScanState.Idle
            onSaved(id)
        }
    }

    fun markDone(deadline: Deadline) = viewModelScope.launch {
        dao.updateDeadline(
            deadline.copy(doneAt = if (deadline.doneAt == null) System.currentTimeMillis() else null)
        )
    }

    fun dismiss(deadline: Deadline) = viewModelScope.launch {
        dao.updateDeadline(deadline.copy(dismissed = true, doneAt = System.currentTimeMillis()))
    }

    fun updateDeadline(deadline: Deadline) = viewModelScope.launch {
        dao.updateDeadline(deadline)
    }

    fun updateItem(item: TrackedItem) = viewModelScope.launch {
        dao.updateItem(item)
    }

    fun deleteItem(item: TrackedItem) = viewModelScope.launch {
        dao.deleteItem(item)
    }

    fun saveSettings(s: GuardianSettings) = viewModelScope.launch {
        settingsStore.update(s)
        DeadlineWorker.schedule(getApplication(), s.notifyHour)
    }
}

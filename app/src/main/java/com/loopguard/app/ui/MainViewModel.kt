package com.loopguard.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.loopguard.app.BuildConfig
import com.loopguard.app.data.AppSettings
import com.loopguard.app.data.BackupFormatException
import com.loopguard.app.data.BackupSerializer
import com.loopguard.app.data.ImportMode
import com.loopguard.app.data.Loop
import com.loopguard.app.data.LoopEvent
import com.loopguard.app.data.LoopRepository
import com.loopguard.app.data.LoopStatus
import com.loopguard.app.data.SettingsStore
import com.loopguard.app.data.Side
import com.loopguard.app.data.ThemeMode
import com.loopguard.app.domain.Priority
import com.loopguard.app.domain.PriorityBand
import com.loopguard.app.domain.PriorityEngine
import com.loopguard.app.domain.Tone
import com.loopguard.app.notify.ReminderScheduler
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

data class ScoredLoop(
    val loop: Loop,
    val priority: Priority,
) {
    val id: Long get() = loop.id
}

enum class SideFilter(val label: String) {
    ALL("Everything"),
    ME("On me"),
    THEM("On them"),
}

data class Filters(
    val query: String = "",
    val side: SideFilter = SideFilter.ALL,
    val category: String? = null,
    val tag: String? = null,
    val showCompleted: Boolean = false,
) {
    val isActive: Boolean
        get() = query.isNotBlank() || side != SideFilter.ALL || category != null || tag != null
}

data class LoopGuardState(
    val ready: Boolean = false,
    val settings: AppSettings = AppSettings(),
    val focus: List<ScoredLoop> = emptyList(),
    val active: List<ScoredLoop> = emptyList(),
    val snoozed: List<ScoredLoop> = emptyList(),
    val completed: List<ScoredLoop> = emptyList(),
    val filtered: List<ScoredLoop> = emptyList(),
    val filters: Filters = Filters(),
    val categories: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val today: LocalDate = LocalDate.now(),
) {
    val openCount: Int get() = active.size + snoozed.size
    val onMe: Int get() = active.count { it.loop.sideEnum == Side.ME }
    val onThem: Int get() = active.count { it.loop.sideEnum == Side.THEM }
    val overdue: Int
        get() = active.count { (PriorityEngine.daysUntilDue(it.loop, today) ?: 1) < 0 }
    val dueToday: Int
        get() = active.count { PriorityEngine.daysUntilDue(it.loop, today) == 0 }
    val criticalCount: Int get() = active.count { it.priority.band == PriorityBand.CRITICAL }

    /** 0-100 summary of how much unresolved pressure the user is carrying. */
    val pressure: Int
        get() = if (active.isEmpty()) 0 else {
            val top = active.take(6)
            (top.sumOf { it.priority.score } / top.size).coerceIn(0, 100)
        }
}

sealed interface UiMessage {
    data class Text(val message: String) : UiMessage
    data class Undoable(val message: String, val loopId: Long, val action: UndoAction) : UiMessage
}

enum class UndoAction { COMPLETED, SNOOZED, DELETED }

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = LoopRepository.get(app)
    private val settingsStore = SettingsStore(app)

    private val filters = MutableStateFlow(Filters())
    private val today = MutableStateFlow(LocalDate.now())

    private val messages = Channel<UiMessage>(Channel.BUFFERED)
    val messageFlow: Flow<UiMessage> = messages.receiveAsFlow()

    /** Last deleted loop, so the undo snackbar can put it back. */
    private var recycleBin: Pair<Loop, List<LoopEvent>>? = null

    val state: StateFlow<LoopGuardState> = combine(
        repository.observeAll(),
        settingsStore.observe(),
        filters,
        today,
    ) { loops, settings, activeFilters, day ->
        buildState(loops, settings, activeFilters, day)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = LoopGuardState(),
    )

    private fun buildState(
        loops: List<Loop>,
        settings: AppSettings,
        activeFilters: Filters,
        day: LocalDate,
    ): LoopGuardState {
        val scored = loops.map { ScoredLoop(it, PriorityEngine.evaluate(it, day)) }

        val open = scored.filter { it.loop.statusEnum == LoopStatus.OPEN }
        val active = open.filterNot { it.loop.isSnoozed(day) }
            .sortedWith(
                compareByDescending<ScoredLoop> { it.loop.pinned }
                    .thenByDescending { it.priority.score }
                    .thenBy { it.loop.dueDate ?: Long.MAX_VALUE }
            )
        val snoozed = open.filter { it.loop.isSnoozed(day) }
            .sortedBy { it.loop.snoozedUntil }
        val completed = scored.filter { it.loop.statusEnum == LoopStatus.DONE }
            .sortedByDescending { it.loop.closedAt ?: 0L }

        val pool = if (activeFilters.showCompleted) completed else active + snoozed
        val filtered = pool.filter { matches(it, activeFilters) }

        return LoopGuardState(
            ready = true,
            settings = settings,
            focus = active.take(3),
            active = active,
            snoozed = snoozed,
            completed = completed,
            filtered = filtered,
            filters = activeFilters,
            categories = scored.map { it.loop.category }.distinct().sorted(),
            tags = scored.flatMap { it.loop.tagList }.distinct().sorted(),
            today = day,
        )
    }

    private fun matches(item: ScoredLoop, f: Filters): Boolean {
        val loop = item.loop
        if (f.side == SideFilter.ME && loop.sideEnum != Side.ME) return false
        if (f.side == SideFilter.THEM && loop.sideEnum != Side.THEM) return false
        if (f.category != null && !loop.category.equals(f.category, ignoreCase = true)) return false
        if (f.tag != null && loop.tagList.none { it.equals(f.tag, ignoreCase = true) }) return false
        if (f.query.isBlank()) return true

        val q = f.query.trim().lowercase()
        return listOf(
            loop.title, loop.counterparty, loop.organisation,
            loop.notes, loop.reference, loop.category, loop.tags,
        ).any { it.lowercase().contains(q) }
    }

    // ------------------------------------------------------------------
    // Filters
    // ------------------------------------------------------------------

    fun setQuery(value: String) { filters.value = filters.value.copy(query = value) }
    fun setSideFilter(value: SideFilter) { filters.value = filters.value.copy(side = value) }
    fun setCategoryFilter(value: String?) { filters.value = filters.value.copy(category = value) }
    fun setTagFilter(value: String?) { filters.value = filters.value.copy(tag = value) }
    fun setShowCompleted(value: Boolean) { filters.value = filters.value.copy(showCompleted = value) }
    fun clearFilters() { filters.value = Filters(showCompleted = filters.value.showCompleted) }

    fun refreshToday() { today.value = LocalDate.now() }

    // ------------------------------------------------------------------
    // Loop lifecycle
    // ------------------------------------------------------------------

    fun observeLoop(id: Long): Flow<Loop?> = repository.observeLoop(id)
    fun observeEvents(id: Long): Flow<List<LoopEvent>> = repository.observeEvents(id)

    fun priorityFor(loop: Loop): Priority = PriorityEngine.evaluate(loop, today.value)

    fun create(loop: Loop, onCreated: (Long) -> Unit = {}) = viewModelScope.launch {
        val id = repository.create(loop, today.value)
        messages.send(UiMessage.Text("Loop captured"))
        onCreated(id)
    }

    fun update(loop: Loop, note: String = "Details updated") = viewModelScope.launch {
        repository.update(loop)
        repository.addEvent(loop.id, note, com.loopguard.app.data.EventKind.EDITED, today.value)
        messages.send(UiMessage.Text("Saved"))
    }

    fun setSide(loopId: Long, side: Side) = viewModelScope.launch {
        repository.setSide(loopId, side, today.value)
        messages.send(
            UiMessage.Text(
                if (side == Side.ME) "Moved to your side" else "Handed back to them"
            )
        )
    }

    fun logAction(loopId: Long, text: String, handOver: Boolean) = viewModelScope.launch {
        repository.logAction(loopId, text, handOver, today.value)
        messages.send(UiMessage.Text(if (handOver) "Logged and handed over" else "Action logged"))
    }

    fun recordFollowUp(loopId: Long, tone: Tone) = viewModelScope.launch {
        repository.recordFollowUp(loopId, tone.label, today.value)
    }

    fun snooze(loopId: Long, days: Long) = viewModelScope.launch {
        repository.snooze(loopId, today.value.plusDays(days), today.value)
        messages.send(
            UiMessage.Undoable(
                if (days == 1L) "Snoozed until tomorrow" else "Snoozed for $days days",
                loopId,
                UndoAction.SNOOZED,
            )
        )
    }

    fun unsnooze(loopId: Long) = viewModelScope.launch {
        repository.unsnooze(loopId, today.value)
    }

    fun complete(loopId: Long) = viewModelScope.launch {
        repository.complete(loopId, today = today.value)
        messages.send(UiMessage.Undoable("Loop closed", loopId, UndoAction.COMPLETED))
    }

    fun reopen(loopId: Long) = viewModelScope.launch {
        repository.reopen(loopId, today.value)
        messages.send(UiMessage.Text("Loop reopened"))
    }

    fun togglePin(loopId: Long) = viewModelScope.launch { repository.togglePin(loopId) }

    fun delete(loopId: Long) = viewModelScope.launch {
        val loop = repository.find(loopId) ?: return@launch
        recycleBin = loop to repository.eventsFor(loopId)
        repository.delete(loop)
        messages.send(UiMessage.Undoable("Loop deleted", loopId, UndoAction.DELETED))
    }

    fun undo(action: UndoAction, loopId: Long) = viewModelScope.launch {
        when (action) {
            UndoAction.COMPLETED -> repository.reopen(loopId, today.value)
            UndoAction.SNOOZED -> repository.unsnooze(loopId, today.value)
            UndoAction.DELETED -> recycleBin?.let { (loop, events) ->
                repository.restore(loop, events)
                recycleBin = null
            }
        }
    }

    // ------------------------------------------------------------------
    // Settings
    // ------------------------------------------------------------------

    fun completeOnboarding(name: String, seed: Boolean) = viewModelScope.launch {
        settingsStore.setDisplayName(name)
        if (seed) repository.seedStarterLoops(today.value)
        settingsStore.setOnboardingComplete(true)
        ReminderScheduler.schedule(getApplication())
    }

    fun setThemeMode(mode: ThemeMode) = settingsStore.setThemeMode(mode)
    fun setDynamicColour(value: Boolean) = settingsStore.setDynamicColour(value)
    fun setDisplayName(value: String) = settingsStore.setDisplayName(value)
    fun setFollowUpCadence(days: Int) = settingsStore.setFollowUpCadence(days)

    fun setRemindersEnabled(value: Boolean) {
        settingsStore.setRemindersEnabled(value)
        if (value) ReminderScheduler.schedule(getApplication())
        else ReminderScheduler.cancel(getApplication())
    }

    fun setDigestHour(hour: Int) {
        settingsStore.setDigestHour(hour)
        ReminderScheduler.schedule(getApplication())
    }

    // ------------------------------------------------------------------
    // Backup
    // ------------------------------------------------------------------

    suspend fun buildExport(): String = repository.exportJson(BuildConfig.VERSION_NAME)

    fun importFrom(raw: String, mode: ImportMode, onDone: (Boolean) -> Unit = {}) =
        viewModelScope.launch {
            try {
                val payload = BackupSerializer.parse(raw)
                val result = repository.import(payload, mode)
                val origin = if (result.legacy) " from LoopGuard 1.0" else ""
                val skipped = if (result.skipped > 0) ", ${result.skipped} already present" else ""
                messages.send(
                    UiMessage.Text("Imported ${result.added} loops$origin$skipped")
                )
                onDone(true)
            } catch (e: BackupFormatException) {
                messages.send(UiMessage.Text(e.message ?: "That file could not be read."))
                onDone(false)
            } catch (e: Exception) {
                messages.send(UiMessage.Text("Import failed: ${e.message ?: "unknown error"}"))
                onDone(false)
            }
        }

    fun notify(message: String) = viewModelScope.launch { messages.send(UiMessage.Text(message)) }

    companion object {
        val Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(
                modelClass: Class<T>,
                extras: androidx.lifecycle.viewmodel.CreationExtras,
            ): T {
                val app = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!
                return MainViewModel(app) as T
            }
        }
    }
}

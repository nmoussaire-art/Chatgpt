package com.adcbtracker.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.adcbtracker.App
import com.adcbtracker.data.Category
import com.adcbtracker.data.Cycle
import com.adcbtracker.data.CycleStats
import com.adcbtracker.data.Cycles
import com.adcbtracker.data.DaySummary
import com.adcbtracker.data.MerchantWithCategory
import com.adcbtracker.data.Prefs
import com.adcbtracker.data.Projection
import com.adcbtracker.data.Projections
import com.adcbtracker.data.Stats
import com.adcbtracker.data.TransactionRepository
import com.adcbtracker.data.TransactionWithCategory
import com.adcbtracker.data.UAE_ZONE
import com.adcbtracker.data.spendMinor
import com.adcbtracker.data.startMillis
import com.adcbtracker.data.toUaeDate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

data class HomeState(
    val today: LocalDate,
    val todayTotal: Long = 0,
    val todayCount: Int = 0,
    val yesterdayTotal: Long = 0,
    val yesterdayCount: Int = 0,
    val weekTotal: Long = 0,
    val weekCount: Int = 0,
    val cycle: CycleStats? = null,
    /** Previous cycle's spend up to the same day of its cycle, for a fair comparison. */
    val previousCycleSameDay: Long = 0,
    val recentDays: List<DaySummary> = emptyList(),
    val recent: List<TransactionWithCategory> = emptyList(),
    val hasAnyTransactions: Boolean = false,
    val projection: Projection? = null,
    val budgetMinor: Long = 0,
    /** Latest available credit limit reported by ADCB, with the time it was reported. */
    val availableLimitMinor: Long? = null,
    val availableLimitAt: Long = 0,
    val uncategorizedCount: Int = 0,
)

/** What the category drill-down sheet shows: one category (null = uncategorized) within a cycle. */
data class CategorySelection(val categoryId: Long?, val cycle: Cycle)

data class InsightsState(
    val today: LocalDate,
    val cyclesAgo: Int,
    val current: CycleStats,
    val previous: CycleStats,
    /** Oldest first; up to 12 cycles. */
    val history: List<Pair<Cycle, Long>>,
    /** Only for the current cycle. */
    val projection: Projection?,
    val budgetMinor: Long,
)

private data class HomeKey(val today: LocalDate, val startDay: Int, val threshold: Long, val budget: Long)
private data class InsightsKey(val today: LocalDate, val startDay: Int, val ago: Int, val threshold: Long, val budget: Long)

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(private val app: App) : ViewModel() {
    private val repo: TransactionRepository = app.repo

    private val _cycleStartDay = MutableStateFlow(Prefs.getCycleStartDay(app))
    val cycleStartDay: StateFlow<Int> = _cycleStartDay.asStateFlow()

    private val _largeThreshold = MutableStateFlow(Prefs.getLargeExpenseThresholdMinor(app))
    val largeThreshold: StateFlow<Long> = _largeThreshold.asStateFlow()

    private val _cyclesAgo = MutableStateFlow(0)
    val cyclesAgo: StateFlow<Int> = _cyclesAgo.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _budget = MutableStateFlow(Prefs.getCycleBudgetMinor(app))
    val budget: StateFlow<Long> = _budget.asStateFlow()

    private val _selectedTxId = MutableStateFlow<Long?>(null)

    private val _categorySelection = MutableStateFlow<CategorySelection?>(null)
    val categorySelection: StateFlow<CategorySelection?> = _categorySelection.asStateFlow()

    private val _selectedDay = MutableStateFlow<LocalDate?>(null)
    val selectedDay: StateFlow<LocalDate?> = _selectedDay.asStateFlow()

    /** Re-emits when the date changes so "Today"/"Yesterday" roll over at midnight. */
    private val today: Flow<LocalDate> = flow {
        while (true) {
            emit(LocalDate.now(UAE_ZONE))
            delay(60_000)
        }
    }.distinctUntilChanged()

    val categories: StateFlow<List<Category>> =
        repo.categories.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val totalCount: StateFlow<Int> =
        repo.totalCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val merchants: StateFlow<List<MerchantWithCategory>> =
        repo.merchantsWithCategories().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val home: StateFlow<HomeState> = combine(today, _cycleStartDay, _largeThreshold, _budget) { t, d, th, b -> HomeKey(t, d, th, b) }
        .flatMapLatest { (t, startDay, threshold, budget) ->
            val current = Cycles.cycleFor(t, startDay)
            val previous = Cycles.cycleAgo(t, startDay, 1)
            val past = (1..3).map { Cycles.cycleAgo(t, startDay, it) }
            val from = minOf(past.last().start, t.minusDays(31)).startMillis()
            combine(repo.since(from), repo.totalCount()) { txs, count ->
                val byDay = Stats.groupByDay(txs).associateBy { it.date }
                val weekStart = t.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                val week = txs.filter { !it.tx.tsEpochMillis.toUaeDate().isBefore(weekStart) }
                val cur = Stats.forCycle(current, txs, t)
                val prev = Stats.forCycle(previous, txs, t)
                HomeState(
                    today = t,
                    todayTotal = byDay[t]?.totalMinor ?: 0,
                    todayCount = byDay[t]?.count ?: 0,
                    yesterdayTotal = byDay[t.minusDays(1)]?.totalMinor ?: 0,
                    yesterdayCount = byDay[t.minusDays(1)]?.count ?: 0,
                    weekTotal = week.sumOf { it.tx.spendMinor },
                    weekCount = week.size,
                    cycle = cur,
                    previousCycleSameDay = Stats.spentThrough(prev, current.dayIndex(t)),
                    recentDays = Stats.recentDays(txs, t, 14),
                    recent = txs.take(6),
                    hasAnyTransactions = count > 0,
                    projection = Projections.project(current, txs, t, past, threshold),
                    budgetMinor = budget,
                    availableLimitMinor = txs.firstOrNull { it.tx.avlCreditLimitMinor != null }?.tx?.avlCreditLimitMinor,
                    availableLimitAt = txs.firstOrNull { it.tx.avlCreditLimitMinor != null }?.tx?.tsEpochMillis ?: 0,
                    uncategorizedCount = txs.count { it.category == null && current.contains(it.tx.tsEpochMillis.toUaeDate()) },
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeState(LocalDate.now(UAE_ZONE)))

    val insights: StateFlow<InsightsState?> = combine(today, _cycleStartDay, _cyclesAgo, _largeThreshold, _budget) { t, d, a, th, b ->
        InsightsKey(t, d, a, th, b)
    }
        .flatMapLatest { (t, startDay, ago, threshold, budget) ->
            val oldest = Cycles.cycleAgo(t, startDay, maxOf(11, ago + 1))
            repo.since(oldest.startMillis).map { txs ->
                val current = Cycles.cycleAgo(t, startDay, ago)
                val previous = Cycles.cycleAgo(t, startDay, ago + 1)
                val history = (11 downTo 0).map { i ->
                    val c = Cycles.cycleAgo(t, startDay, i)
                    c to txs.filter { c.contains(it.tx.tsEpochMillis.toUaeDate()) }.sumOf { it.tx.spendMinor }
                }
                InsightsState(
                    today = t,
                    cyclesAgo = ago,
                    current = Stats.forCycle(current, txs, t),
                    previous = Stats.forCycle(previous, txs, t),
                    history = history,
                    projection = if (ago == 0) {
                        Projections.project(current, txs, t, (1..3).map { Cycles.cycleAgo(t, startDay, it) }, threshold)
                    } else null,
                    budgetMinor = budget,
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** All transactions (filtered by search) grouped by day, newest first. */
    val transactionsByDay: StateFlow<List<DaySummary>> = combine(repo.all(), _searchQuery) { txs, q ->
        val query = q.trim()
        val filtered = if (query.isEmpty()) txs else txs.filter { t ->
            listOfNotNull(t.tx.merchant, t.tx.location, t.tx.cardId, t.category?.name)
                .any { it.contains(query, ignoreCase = true) }
        }
        Stats.groupByDay(filtered)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val selectedDaySummary: StateFlow<DaySummary?> = _selectedDay
        .flatMapLatest { day ->
            if (day == null) flowOf(null)
            else repo.inRange(day.startMillis(), day.plusDays(1).startMillis()).map { txs ->
                DaySummary(day, txs.sumOf { it.tx.spendMinor }, txs.size, txs)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val selectedTransaction: StateFlow<TransactionWithCategory?> = _selectedTxId
        .flatMapLatest { id -> if (id == null) flowOf(null) else repo.byId(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val categorySheetTransactions: StateFlow<List<TransactionWithCategory>> = _categorySelection
        .flatMapLatest { sel ->
            if (sel == null) flowOf(emptyList())
            else repo.inRange(sel.cycle.startMillis, sel.cycle.endMillis).map { txs -> txs.filter { it.category?.id == sel.categoryId } }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun openTransaction(id: Long) { _selectedTxId.value = id }
    fun closeTransaction() { _selectedTxId.value = null }

    fun openCategory(categoryId: Long?, cycle: Cycle) { _categorySelection.value = CategorySelection(categoryId, cycle) }
    fun closeCategory() { _categorySelection.value = null }

    fun setBudget(minor: Long) {
        Prefs.setCycleBudgetMinor(app, minor)
        _budget.value = minor
    }

    fun openDay(day: LocalDate) { _selectedDay.value = day }
    fun closeDay() { _selectedDay.value = null }

    fun previousCycle() { _cyclesAgo.value = _cyclesAgo.value + 1 }
    fun goToCycle(cyclesAgo: Int) { _cyclesAgo.value = cyclesAgo.coerceAtLeast(0) }
    fun nextCycle() { if (_cyclesAgo.value > 0) _cyclesAgo.value = _cyclesAgo.value - 1 }

    fun updateSearchQuery(q: String) { _searchQuery.value = q }

    fun setCycleStartDay(day: Int) {
        Prefs.setCycleStartDay(app, day)
        _cycleStartDay.value = Prefs.getCycleStartDay(app)
    }

    fun setLargeThreshold(minor: Long) {
        Prefs.setLargeExpenseThresholdMinor(app, minor)
        _largeThreshold.value = minor
    }

    fun deleteTransaction(id: Long) = viewModelScope.launch { repo.deleteTransaction(id) }

    fun updateTransactionCategory(id: Long, categoryId: Long?) =
        viewModelScope.launch { repo.updateTransactionCategory(id, categoryId) }

    fun mapMerchantToCategory(merchant: String, categoryId: Long?) =
        viewModelScope.launch { repo.mapMerchantToCategory(merchant, categoryId) }

    fun addManualTransaction(amountMinor: Long, currency: String, merchant: String, categoryId: Long?, epochMillis: Long) =
        viewModelScope.launch { repo.addManualTransaction(amountMinor, currency, merchant, categoryId, epochMillis) }

    fun addCategory(name: String, icon: String, colorHex: String) =
        viewModelScope.launch { repo.addCategory(name, icon, colorHex) }

    fun updateCategory(category: Category) = viewModelScope.launch { repo.updateCategory(category) }

    fun deleteCategory(id: Long) = viewModelScope.launch { repo.deleteCategory(id) }

    suspend fun importFromSms(context: Context) = repo.importFromSmsInbox(context)

    suspend fun cleanupNonExpenses(): Int = repo.deleteNonExpenseTransactions()

    suspend fun getAllForExport() = repo.getAllForExport()

    class Factory(private val app: App) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(app) as T
    }
}

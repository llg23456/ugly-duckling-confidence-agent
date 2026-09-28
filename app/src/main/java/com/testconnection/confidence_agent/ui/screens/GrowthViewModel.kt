package com.testconnection.confidence_agent.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.testconnection.confidence_agent.data.model.RecordDraft
import com.testconnection.confidence_agent.data.preferences.DeviceIdStore
import com.testconnection.confidence_agent.data.remote.GrowthEvent
import com.testconnection.confidence_agent.data.remote.ReviewApiClient
import com.testconnection.confidence_agent.data.remote.ReviewSummary
import com.testconnection.confidence_agent.data.repository.LocalRecordRepository
import com.testconnection.confidence_agent.widget.WidgetUpdater
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DailyReviewEntry(val date: LocalDate, val review: ReviewSummary?)
data class WeeklyReviewEntry(val start: LocalDate, val end: LocalDate, val review: ReviewSummary?)

data class GrowthUiState(
    val period: String = "日",
    val anchorDate: LocalDate = LocalDate.now().minusDays(1),
    val events: List<GrowthEvent> = emptyList(),
    val review: ReviewSummary? = null,
    val dailyReviews: List<DailyReviewEntry> = emptyList(),
    val weeklyReviews: List<WeeklyReviewEntry> = emptyList(),
    val recordIds: Map<Long, String> = emptyMap(),
    val loading: Boolean = false,
    val error: String? = null,
    val demoNotice: String? = null,
)

private data class PeriodData(
    val review: ReviewSummary?,
    val dailyReviews: List<DailyReviewEntry> = emptyList(),
    val weeklyReviews: List<WeeklyReviewEntry> = emptyList(),
)

class GrowthViewModel(application: Application) : AndroidViewModel(application) {
    private val deviceId = DeviceIdStore(application).get()
    private val localRecords = LocalRecordRepository(application)
    private val api = ReviewApiClient()
    private val _state = MutableStateFlow(GrowthUiState())
    val state = _state.asStateFlow()

    fun localRecordFor(serverId: Long): RecordDraft? {
        val clientId = _state.value.recordIds[serverId] ?: return null
        return localRecords.load().firstOrNull { it.id == clientId }
    }

    fun recordForEdit(serverId: Long, onLoaded: (RecordDraft?) -> Unit) {
        localRecordFor(serverId)?.let {
            onLoaded(it)
            return
        }
        viewModelScope.launch {
            onLoaded(runCatching { api.record(deviceId, serverId) }.getOrNull())
        }
    }

    fun refresh() {
        if (_state.value.loading) return
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            var partialFailure = false
            val synced = runCatching { api.syncRecords(deviceId, localRecords.load()) }
                .onFailure { partialFailure = true }.getOrDefault(emptyList())
            val eventsResult = runCatching { api.events(deviceId) }.onFailure { partialFailure = true }
            val events = eventsResult.getOrDefault(_state.value.events)
            if (eventsResult.isSuccess) runCatching { WidgetUpdater.refreshGrowthWidgets(getApplication(), events) }
            runCatching { api.pendingDaily(deviceId) }.onFailure { partialFailure = true }
            val periodData = runCatching { loadPeriod(_state.value.period, _state.value.anchorDate) }
                .onFailure { partialFailure = true }.getOrNull()
            _state.update {
                it.copy(
                    events = events,
                    review = periodData?.review,
                    dailyReviews = periodData?.dailyReviews.orEmpty(),
                    weeklyReviews = periodData?.weeklyReviews.orEmpty(),
                    recordIds = synced.associate { row -> row.serverId to row.clientId },
                    loading = false,
                    error = if (partialFailure) "部分记录或回望暂时无法同步，点重试继续。" else null,
                )
            }
        }
    }

    fun selectPeriod(period: String) {
        val today = LocalDate.now()
        val anchor = when (period) {
            "日" -> today.minusDays(1)
            "周" -> weekStart(today).minusWeeks(1)
            else -> YearMonth.from(today).atDay(1)
        }
        loadSelection(period, anchor)
    }

    fun shiftRange(amount: Long) {
        val state = _state.value
        val today = LocalDate.now()
        val next = when (state.period) {
            "日" -> state.anchorDate.plusDays(amount).coerceAtMost(today.minusDays(1))
            "周" -> weekStart(state.anchorDate).plusWeeks(amount).coerceAtMost(weekStart(today))
            else -> YearMonth.from(state.anchorDate).plusMonths(amount).coerceAtMost(YearMonth.from(today)).atDay(1)
        }
        if (next != state.anchorDate) loadSelection(state.period, next)
    }

    fun openDay(date: LocalDate) {
        if (!date.isAfter(LocalDate.now())) loadSelection("日", date)
    }

    fun openWeek(start: LocalDate) = loadSelection("周", weekStart(start))

    fun createDemoData() {
        if (_state.value.loading) return
        _state.update { it.copy(loading = true, error = null, demoNotice = null) }
        viewModelScope.launch {
            runCatching { api.createDemoData(deviceId) }
                .onSuccess { result ->
                    _state.update { it.copy(loading = false, demoNotice = "已生成 ${result.created} 天演示记录：${result.theme}") }
                    refresh()
                }
                .onFailure { error -> _state.update { it.copy(loading = false, error = "演示数据生成失败：${error.message}") } }
        }
    }

    fun clearDemoData() {
        if (_state.value.loading) return
        _state.update { it.copy(loading = true, error = null, demoNotice = null) }
        viewModelScope.launch {
            runCatching { api.clearDemoData(deviceId) }
                .onSuccess { result ->
                    _state.update { it.copy(loading = false, demoNotice = "已清除 ${result.deleted} 条演示记录，真实记录未删除。") }
                    refresh()
                }
                .onFailure { error -> _state.update { it.copy(loading = false, error = "演示数据清除失败：${error.message}") } }
        }
    }

    private fun loadSelection(period: String, anchor: LocalDate) {
        if (_state.value.loading) return
        _state.update {
            it.copy(period = period, anchorDate = anchor, review = null, dailyReviews = emptyList(),
                weeklyReviews = emptyList(), loading = true, error = null)
        }
        viewModelScope.launch {
            runCatching { loadPeriod(period, anchor) }
                .onSuccess { data -> _state.update { it.copy(
                    review = data.review, dailyReviews = data.dailyReviews,
                    weeklyReviews = data.weeklyReviews, loading = false,
                ) } }
                .onFailure { _state.update { it.copy(loading = false, error = "回望暂时无法生成，点重试继续。") } }
        }
    }

    private suspend fun loadPeriod(period: String, anchor: LocalDate): PeriodData = coroutineScope {
        val today = LocalDate.now()
        when (period) {
            "日" -> PeriodData(api.generate(deviceId, "day", anchor.toString(), anchor.toString()))
            "周" -> {
                val start = weekStart(anchor)
                val end = minOf(start.plusDays(6), today)
                val daily = (0L..6L).map { offset ->
                    val day = start.plusDays(offset)
                    async {
                        DailyReviewEntry(day, if (day.isAfter(today)) null else runCatching {
                            api.generate(deviceId, "day", day.toString(), day.toString())
                        }.getOrNull())
                    }
                }.awaitAll()
                PeriodData(api.generate(deviceId, "week", start.toString(), end.toString()), dailyReviews = daily)
            }
            else -> {
                val month = YearMonth.from(anchor)
                val start = month.atDay(1)
                val end = minOf(month.atEndOfMonth(), today)
                val ranges = mutableListOf<Pair<LocalDate, LocalDate>>()
                var cursor = start
                while (!cursor.isAfter(end)) {
                    val rangeEnd = minOf(cursor.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY)), end)
                    ranges += cursor to rangeEnd
                    cursor = rangeEnd.plusDays(1)
                }
                val weeks = ranges.map { (weekStart, weekEnd) ->
                    async {
                        WeeklyReviewEntry(weekStart, weekEnd, runCatching {
                            api.generate(deviceId, "week", weekStart.toString(), weekEnd.toString())
                        }.getOrNull())
                    }
                }.awaitAll()
                PeriodData(api.generate(deviceId, "month", start.toString(), end.toString()), weeklyReviews = weeks)
            }
        }
    }

    private fun weekStart(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
}

private fun LocalDate.coerceAtMost(maximum: LocalDate): LocalDate = if (isAfter(maximum)) maximum else this
private fun YearMonth.coerceAtMost(maximum: YearMonth): YearMonth = if (this > maximum) maximum else this

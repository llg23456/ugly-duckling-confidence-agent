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
import com.testconnection.confidence_agent.data.repository.ReviewCacheStore
import com.testconnection.confidence_agent.widget.WidgetUpdater
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters
import kotlinx.coroutines.async
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
    val refreshing: Boolean = false,
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
    private val reviewCache = ReviewCacheStore(application)
    private val _state = MutableStateFlow(GrowthUiState())
    private var requestVersion = 0
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
            val remote = runCatching { api.record(deviceId, serverId) }.getOrNull()
            val local = remote?.let { item -> localRecords.load().firstOrNull { it.id == item.id } }
            onLoaded(local ?: remote)
        }
    }

    fun refresh() {
        refreshInternal(force = false)
    }

    fun forceRefresh() {
        refreshInternal(force = true)
    }

    private fun refreshInternal(force: Boolean) {
        if (_state.value.loading || _state.value.refreshing) return
        val period = _state.value.period
        val anchor = _state.value.anchorDate
        val (start, end) = selectionRange(period, anchor)
        val cached = reviewCache.load(periodKey(period), start.toString(), end.toString())
        val cachedData = cached?.let(::overviewToPeriodData)
        val fresh = !force && cached != null && reviewCache.isFresh(
            periodKey(period), start.toString(), end.toString(),
        )
        val version = ++requestVersion
        _state.update {
            it.copy(
                review = cachedData?.review ?: it.review,
                dailyReviews = cachedData?.dailyReviews ?: it.dailyReviews,
                weeklyReviews = cachedData?.weeklyReviews ?: it.weeklyReviews,
                loading = cachedData == null,
                refreshing = cachedData != null && !fresh,
                error = null,
            )
        }
        viewModelScope.launch {
            if (fresh) {
                refreshSourceEventsAndRecordIds()
                return@launch
            }
            var syncFailed = false
            val synced = runCatching {
                api.syncRecords(deviceId, localRecords.load())
            }.onFailure { syncFailed = true }.getOrDefault(emptyList())
            val (eventsResult, periodResult) = coroutineScope {
                val eventsDeferred = async { runCatching { api.events(deviceId) } }
                val periodDeferred = async { runCatching { loadPeriod(period, anchor) } }
                eventsDeferred.await() to periodDeferred.await()
            }
            val events = eventsResult.getOrDefault(_state.value.events)
            if (eventsResult.isSuccess) com.testconnection.confidence_agent.data.preferences.ProfileJourneyStore(getApplication())
                .reconcile(events, synced.associate { it.serverId to it.clientId })
            if (eventsResult.isSuccess) runCatching { WidgetUpdater.refreshGrowthWidgets(getApplication(), events) }
            val periodData = periodResult.getOrNull() ?: cachedData
            if (syncFailed || periodResult.isFailure) reviewCache.markDirty()
            if (version != requestVersion) return@launch
            _state.update {
                it.copy(
                    events = events,
                    review = periodData?.review ?: it.review,
                    dailyReviews = periodData?.dailyReviews ?: it.dailyReviews,
                    weeklyReviews = periodData?.weeklyReviews ?: it.weeklyReviews,
                    recordIds = if (synced.isEmpty()) it.recordIds else synced.associate { row -> row.serverId to row.clientId },
                    loading = false,
                    refreshing = false,
                    error = when {
                        periodResult.isSuccess -> null
                        cachedData != null -> "正在显示上次回望，本次更新暂时失败。"
                        else -> periodError(periodResult.exceptionOrNull())
                    },
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
        if (_state.value.loading || _state.value.refreshing) return
        _state.update { it.copy(loading = true, error = null, demoNotice = null) }
        viewModelScope.launch {
            runCatching { api.createDemoData(deviceId) }
                .onSuccess { result ->
                    _state.update { it.copy(loading = false, demoNotice = "已生成 ${result.created} 天演示记录：${result.theme}") }
                    reviewCache.markDirty()
                    forceRefresh()
                }
                .onFailure { error -> _state.update { it.copy(loading = false, error = "演示数据生成失败：${error.message}") } }
        }
    }

    fun clearDemoData() {
        if (_state.value.loading || _state.value.refreshing) return
        _state.update { it.copy(loading = true, error = null, demoNotice = null) }
        viewModelScope.launch {
            runCatching { api.clearDemoData(deviceId) }
                .onSuccess { result ->
                    _state.update { it.copy(loading = false, demoNotice = "已清除 ${result.deleted} 条演示记录，真实记录未删除。") }
                    reviewCache.markDirty()
                    forceRefresh()
                }
                .onFailure { error -> _state.update { it.copy(loading = false, error = "演示数据清除失败：${error.message}") } }
        }
    }

    private fun loadSelection(period: String, anchor: LocalDate) {
        if (_state.value.loading) return
        val (start, end) = selectionRange(period, anchor)
        val cached = reviewCache.load(periodKey(period), start.toString(), end.toString())
        val cachedData = cached?.let(::overviewToPeriodData)
        val fresh = cached != null && reviewCache.isFresh(periodKey(period), start.toString(), end.toString())
        val version = ++requestVersion
        _state.update {
            it.copy(
                period = period,
                anchorDate = anchor,
                review = cachedData?.review,
                dailyReviews = cachedData?.dailyReviews.orEmpty(),
                weeklyReviews = cachedData?.weeklyReviews.orEmpty(),
                loading = cachedData == null,
                refreshing = cachedData != null && !fresh,
                error = null,
            )
        }
        if (fresh) {
            viewModelScope.launch { refreshSourceEventsAndRecordIds() }
            return
        }
        viewModelScope.launch {
            runCatching { loadPeriod(period, anchor) }
                .onSuccess { data ->
                    if (version == requestVersion) _state.update { it.copy(
                        review = data.review, dailyReviews = data.dailyReviews,
                        weeklyReviews = data.weeklyReviews, loading = false, refreshing = false,
                    ) }
                }
                .onFailure {
                    reviewCache.markDirty()
                    if (version == requestVersion) {
                        _state.update { state -> state.copy(
                            loading = false,
                            refreshing = false,
                            error = if (cachedData == null) periodError(it)
                                else "正在显示上次回望，本次更新暂时失败。",
                        ) }
                    }
                }
            refreshSourceEventsAndRecordIds()
        }
    }

    private suspend fun loadPeriod(period: String, anchor: LocalDate): PeriodData {
        val periodKey = periodKey(period)
        val (start, end) = selectionRange(period, anchor)
        val cacheGeneration = reviewCache.generation()
        val overview = api.overview(deviceId, periodKey, start.toString(), end.toString())
        reviewCache.save(periodKey, start.toString(), end.toString(), overview, cacheGeneration)
        return overviewToPeriodData(overview)
    }

    private suspend fun refreshSourceEventsAndRecordIds() {
        val syncedResult = runCatching { api.syncRecords(deviceId, localRecords.load()) }
        val eventsResult = runCatching { api.events(deviceId) }
        val events = eventsResult.getOrNull()
        if (events != null) {
            com.testconnection.confidence_agent.data.preferences.ProfileJourneyStore(getApplication())
                .reconcile(events, syncedResult.getOrNull().orEmpty().associate { it.serverId to it.clientId })
            runCatching { WidgetUpdater.refreshGrowthWidgets(getApplication(), events) }
        }
        val synced = syncedResult.getOrNull().orEmpty()
        if (events != null || synced.isNotEmpty()) {
            _state.update { current ->
                current.copy(
                    events = events ?: current.events,
                    recordIds = if (synced.isEmpty()) current.recordIds
                        else synced.associate { row -> row.serverId to row.clientId },
                )
            }
        }
    }

    private fun overviewToPeriodData(overview: com.testconnection.confidence_agent.data.remote.ReviewOverview) =
        PeriodData(
            review = overview.review,
            dailyReviews = overview.dailyReviews.map { (day, review) ->
                DailyReviewEntry(LocalDate.parse(day), review)
            },
            weeklyReviews = overview.weeklyReviews.map { (start, end, review) ->
                WeeklyReviewEntry(LocalDate.parse(start), LocalDate.parse(end), review)
            },
        )

    private fun periodKey(period: String) = when (period) {
        "日" -> "day"
        "周" -> "week"
        else -> "month"
    }

    private fun selectionRange(period: String, anchor: LocalDate): Pair<LocalDate, LocalDate> {
        val today = LocalDate.now()
        return when (period) {
            "日" -> anchor to anchor
            "周" -> {
                val start = weekStart(anchor)
                val end = minOf(start.plusDays(6), today)
                start to end
            }
            else -> {
                val month = YearMonth.from(anchor)
                val start = month.atDay(1)
                val end = minOf(month.atEndOfMonth(), today)
                start to end
            }
        }
    }

    private fun weekStart(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    private fun periodError(error: Throwable?): String {
        val detail = error?.message.orEmpty()
        return when {
            detail.contains("Failed to connect", ignoreCase = true) ||
                detail.contains("Connection refused", ignoreCase = true) ||
                detail.contains("connect", ignoreCase = true) ->
                "暂时连不上后端。请确认电脑端服务已启动，并在“我的—帮助与求助资源”检测当前地址。"
            detail.contains("404") || detail.contains("405") -> "后端还是旧版本，请重启电脑端服务后再试。"
            else -> "回望暂时无法生成，点重试继续。"
        }
    }
}

private fun LocalDate.coerceAtMost(maximum: LocalDate): LocalDate = if (isAfter(maximum)) maximum else this
private fun YearMonth.coerceAtMost(maximum: YearMonth): YearMonth = if (this > maximum) maximum else this

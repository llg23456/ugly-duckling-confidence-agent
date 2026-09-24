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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class GrowthUiState(
    val period: String = "月",
    val events: List<GrowthEvent> = emptyList(),
    val review: ReviewSummary? = null,
    val pastDaily: ReviewSummary? = null,
    val recordIds: Map<Long, String> = emptyMap(),
    val loading: Boolean = false,
    val error: String? = null,
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

    fun refresh() {
        if (_state.value.loading) return
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            var failed = false
            val synced = runCatching { api.syncRecords(deviceId, localRecords.load()) }
                .onFailure { failed = true }.getOrDefault(emptyList())
            val events = runCatching { api.events(deviceId) }
                .onFailure { failed = true }.getOrDefault(_state.value.events)
            val daily = runCatching { api.pendingDaily(deviceId) }
                .onFailure { failed = true }.getOrDefault(emptyList())
            val period = _state.value.period
            val review = runCatching { api.generate(deviceId, periodCode(period)) }
                .onFailure { failed = true }.getOrNull()
            _state.update { it.copy(
                events = events, review = review, pastDaily = daily.firstOrNull(),
                recordIds = synced.associate { row -> row.serverId to row.clientId },
                loading = false,
                error = if (failed) "部分记录或回望暂时无法同步，点重试继续。" else null,
            ) }
        }
    }

    fun selectPeriod(period: String) {
        if (period == _state.value.period) return
        _state.update { it.copy(period = period, review = null, loading = true, error = null) }
        viewModelScope.launch {
            runCatching { api.generate(deviceId, periodCode(period)) }
                .onSuccess { review -> _state.update { it.copy(review = review, loading = false) } }
                .onFailure { _state.update { it.copy(loading = false, error = "回望暂时无法生成，点重试继续。") } }
        }
    }

    private fun periodCode(label: String): String = when (label) {
        "日" -> "day"
        "周" -> "week"
        else -> "month"
    }
}

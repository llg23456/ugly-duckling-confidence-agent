package com.testconnection.confidence_agent.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.testconnection.confidence_agent.data.model.ChatMessage
import com.testconnection.confidence_agent.data.repository.ChatRepository
import com.testconnection.confidence_agent.data.repository.ReviewCacheStore
import com.testconnection.confidence_agent.data.remote.MemoryEvidence
import com.testconnection.confidence_agent.data.remote.ProactiveCheckIn
import com.testconnection.confidence_agent.data.remote.SupportApiClient
import com.testconnection.confidence_agent.data.remote.SupportSuggestion
import com.testconnection.confidence_agent.data.preferences.DeviceIdStore
import com.testconnection.confidence_agent.widget.WidgetUpdater
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeUiState(
    val messages: List<ChatMessage> = emptyList(),
    val pendingMessage: ChatMessage? = null,
    val draft: String = "",
    val sending: Boolean = false,
    val error: String? = null,
    val lastReplyWasMock: Boolean? = null,
    val lastVoiceTranscript: String? = null,
    val lastVoiceReply: String? = null,
    val voiceTurnId: Int = 0,
    val pendingImage: PendingChatImage? = null,
    val lastEvidence: List<MemoryEvidence> = emptyList(),
    val supportSuggestion: SupportSuggestion? = null,
    val supportLoading: Boolean = false,
    val supportFeedbackLoading: Boolean = false,
    val supportFeedbackOutcome: String? = null,
    val proactiveCheckIn: ProactiveCheckIn? = null,
    val checkInLoading: Boolean = false,
    val checkInAcknowledgement: String? = null,
    val checkInScheduledNotice: String? = null,
)

data class PendingChatImage(
    val fileName: String,
    val mimeType: String,
    val bytes: ByteArray,
)

class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = ChatRepository(application)
    private val supportApi = SupportApiClient()
    private val reviewCache = ReviewCacheStore(application)
    private val deviceId = DeviceIdStore(application).get()
    private val _uiState = MutableStateFlow(HomeUiState())
    private var checkInNoticeJob: Job? = null
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeHistory().collect { messages ->
                _uiState.update { it.copy(messages = messages) }
            }
        }
        viewModelScope.launch {
            runCatching { repository.syncHistory() }
                .onFailure {
                    _uiState.update { state ->
                        state.copy(error = "会话暂时无法同步，已显示本地历史。")
                    }
                }
            loadPendingCheckIn()
        }
    }

    fun updateDraft(value: String) {
        _uiState.update { it.copy(draft = value, error = null) }
    }

    fun selectImage(fileName: String, mimeType: String, imageBytes: ByteArray) {
        _uiState.update {
            it.copy(
                pendingImage = PendingChatImage(fileName, mimeType, imageBytes),
                error = null,
            )
        }
    }

    fun removeSelectedImage() {
        _uiState.update { it.copy(pendingImage = null) }
    }

    fun send() {
        val message = _uiState.value.draft.trim()
        val image = _uiState.value.pendingImage
        if ((message.isEmpty() && image == null) || _uiState.value.sending) return
        val prompt = message.ifBlank { "请看看这张图片，结合我现在的处境温柔地回应。" }

        _uiState.update {
            it.copy(
                pendingMessage = ChatMessage(
                    text = message,
                    fromUser = true,
                    imageBytes = image?.bytes,
                ),
                draft = "",
                pendingImage = null,
                sending = true,
                error = null,
                supportSuggestion = null,
                supportFeedbackOutcome = null,
            )
        }

        viewModelScope.launch {
            runCatching {
                if (image == null) repository.send(message)
                else repository.sendImage(prompt, image.fileName, image.mimeType, image.bytes)
            }
                .onSuccess { response ->
                    _uiState.update {
                        it.copy(
                            pendingMessage = null,
                            sending = false,
                            lastReplyWasMock = response.isMock,
                            lastEvidence = response.evidence,
                        )
                    }
                    handleCheckInScheduling(response.checkInScheduled)
                }
                .onFailure {
                    _uiState.update { state ->
                        state.copy(
                            pendingMessage = null,
                            draft = message,
                            pendingImage = image,
                            sending = false,
                            error = "暂时连不上小鸭服务，请确认电脑端服务已启动。",
                        )
                    }
                }
        }
    }

    fun sendAudio(file: File) {
        if (_uiState.value.sending) return
        _uiState.update { it.copy(sending = true, error = null, supportSuggestion = null, supportFeedbackOutcome = null) }
        viewModelScope.launch {
            try {
                val response = repository.sendAudio(file)
                val transcript = response.userText ?: "[语音消息]"
                _uiState.update {
                    it.copy(
                        sending = false,
                        lastReplyWasMock = response.isMock,
                        lastEvidence = response.evidence,
                        lastVoiceTranscript = transcript,
                        lastVoiceReply = response.text,
                        voiceTurnId = it.voiceTurnId + 1,
                    )
                }
                handleCheckInScheduling(response.checkInScheduled)
            } catch (error: Throwable) {
                _uiState.update {
                    it.copy(
                        sending = false,
                        error = error.message?.takeIf(String::isNotBlank)
                            ?: "语音暂时没有听清，请再试一次。",
                    )
                }
            } finally {
                file.delete()
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun dismissEvidence() {
        _uiState.update { it.copy(lastEvidence = emptyList()) }
    }

    fun dismissSupportSuggestion() {
        _uiState.update {
            it.copy(
                supportSuggestion = null,
                supportFeedbackOutcome = null,
            )
        }
    }

    fun dismissCheckInScheduledNotice() {
        _uiState.update { it.copy(checkInScheduledNotice = null) }
    }

    private fun handleCheckInScheduling(scheduledImmediately: Boolean) {
        if (scheduledImmediately) {
            _uiState.update { it.copy(checkInScheduledNotice = scheduledNoticeText()) }
            return
        }
        if (_uiState.value.proactiveCheckIn != null || checkInNoticeJob?.isActive == true) return
        checkInNoticeJob = viewModelScope.launch {
            // 成长事件在回复后异步提取；少量延迟检查不会阻塞聊天主流程。
            for (delayMs in listOf(1_500L, 6_000L, 15_000L, 25_000L)) {
                delay(delayMs)
                val notice = runCatching { repository.newCheckInNotice() }.getOrNull()
                if (notice != null) {
                    _uiState.update { it.copy(checkInScheduledNotice = scheduledNoticeText()) }
                    break
                }
            }
        }
    }

    private fun scheduledNoticeText() =
        "小鸭记住了。等你下次回来时，我会轻轻问问这件事后来怎么样；你也可以随时选择暂时不说。"

    fun refreshHistory() {
        viewModelScope.launch {
            runCatching { repository.syncHistory() }
            loadPendingCheckIn()
        }
    }

    private suspend fun loadPendingCheckIn() {
        runCatching { repository.pendingCheckIn() }
            .onSuccess { checkIn ->
                _uiState.update { it.copy(proactiveCheckIn = checkIn, checkInLoading = false) }
            }
            .onFailure {
                // 主动问候是辅助体验；加载失败不阻断正常聊天。
                _uiState.update { it.copy(checkInLoading = false) }
            }
    }

    fun respondToCheckIn(choice: String) {
        val checkIn = _uiState.value.proactiveCheckIn ?: return
        if (_uiState.value.checkInLoading || _uiState.value.sending) return
        _uiState.update { it.copy(checkInLoading = true, error = null) }
        viewModelScope.launch {
            runCatching { repository.respondToCheckIn(checkIn.id, choice) }
                .onSuccess { result ->
                    _uiState.update {
                        it.copy(
                            proactiveCheckIn = null,
                            checkInLoading = false,
                            checkInAcknowledgement = result.acknowledgement,
                        )
                    }
                    launch {
                        delay(6_000)
                        _uiState.update {
                            if (it.checkInAcknowledgement == result.acknowledgement) {
                                it.copy(checkInAcknowledgement = null)
                            } else it
                        }
                    }
                    val followUp = result.followUpMessage
                    if (choice == "talk" && !followUp.isNullOrBlank()) {
                        sendCheckInFollowUp(followUp)
                    }
                }
                .onFailure {
                    _uiState.update { state ->
                        state.copy(checkInLoading = false, error = "这次回应暂时没有保存，请重试。")
                    }
                }
        }
    }

    private suspend fun sendCheckInFollowUp(message: String) {
        _uiState.update {
            it.copy(
                pendingMessage = ChatMessage(text = message, fromUser = true),
                sending = true,
                supportSuggestion = null,
                supportFeedbackOutcome = null,
            )
        }
        runCatching { repository.send(message) }
            .onSuccess { response ->
                _uiState.update {
                    it.copy(
                        pendingMessage = null,
                        sending = false,
                        lastReplyWasMock = response.isMock,
                        lastEvidence = response.evidence,
                    )
                }
            }
            .onFailure {
                _uiState.update { state ->
                    state.copy(
                        pendingMessage = null,
                        draft = message,
                        sending = false,
                        error = "小鸭收到了你的选择，但暂时连不上对话服务。文字已放回输入框。",
                    )
                }
            }
    }

    fun requestSupport(situation: String, sourceMessageId: Long? = null) {
        if (situation.isBlank() || _uiState.value.supportLoading) return
        _uiState.update { it.copy(supportLoading = true, supportSuggestion = null, supportFeedbackOutcome = null, error = null) }
        viewModelScope.launch {
            runCatching { supportApi.suggest(deviceId, situation, sourceMessageId) }
                .onSuccess { proposal ->
                    _uiState.update { it.copy(supportSuggestion = proposal, supportLoading = false) }
                }
                .onFailure {
                    _uiState.update { it.copy(supportLoading = false, error = "暂时无法生成求助建议，请稍后重试。") }
                }
        }
    }

    fun submitSupportFeedback(outcome: String, ownEffort: String?, supportReceived: String?) {
        val suggestionId = _uiState.value.supportSuggestion?.id ?: return
        if (_uiState.value.supportFeedbackLoading) return
        _uiState.update { it.copy(supportFeedbackLoading = true, error = null) }
        viewModelScope.launch {
            runCatching { supportApi.feedback(deviceId, suggestionId, outcome, ownEffort, supportReceived) }
                .onSuccess {
                    _uiState.update { it.copy(supportFeedbackLoading = false, supportFeedbackOutcome = outcome) }
                    reviewCache.markDirty()
                    runCatching { WidgetUpdater.refreshGrowthWidgets(getApplication()) }
                    runCatching { repository.newCheckInNotice() }.getOrNull()?.let {
                        _uiState.update { state -> state.copy(checkInScheduledNotice = scheduledNoticeText()) }
                    }
                }
                .onFailure { _uiState.update { it.copy(supportFeedbackLoading = false, error = "反馈暂时没有保存，请重试。") } }
        }
    }

    suspend fun synthesizeSpeech(text: String, voice: String): ByteArray =
        repository.synthesizeSpeech(text, voice)
}

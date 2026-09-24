package com.testconnection.confidence_agent.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.testconnection.confidence_agent.data.model.ChatMessage
import com.testconnection.confidence_agent.data.repository.ChatRepository
import com.testconnection.confidence_agent.data.remote.MemoryEvidence
import com.testconnection.confidence_agent.data.remote.SupportApiClient
import com.testconnection.confidence_agent.data.remote.SupportSuggestion
import com.testconnection.confidence_agent.data.preferences.DeviceIdStore
import java.io.File
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
)

data class PendingChatImage(
    val fileName: String,
    val mimeType: String,
    val bytes: ByteArray,
)

class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = ChatRepository(application)
    private val supportApi = SupportApiClient()
    private val deviceId = DeviceIdStore(application).get()
    private val _uiState = MutableStateFlow(HomeUiState())
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
                    if (response.strategy == "seek_support") requestSupport(message, response.userMessageId)
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
                if (response.strategy == "seek_support") requestSupport(transcript, response.userMessageId)
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

    fun refreshHistory() {
        viewModelScope.launch { runCatching { repository.syncHistory() } }
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
                .onSuccess { _uiState.update { it.copy(supportFeedbackLoading = false, supportFeedbackOutcome = outcome) } }
                .onFailure { _uiState.update { it.copy(supportFeedbackLoading = false, error = "反馈暂时没有保存，请重试。") } }
        }
    }

    suspend fun synthesizeSpeech(text: String, voice: String): ByteArray =
        repository.synthesizeSpeech(text, voice)
}

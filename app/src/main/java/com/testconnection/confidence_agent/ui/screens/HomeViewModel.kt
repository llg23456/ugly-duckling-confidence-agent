package com.testconnection.confidence_agent.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.testconnection.confidence_agent.data.model.ChatMessage
import com.testconnection.confidence_agent.data.repository.ChatRepository
import com.testconnection.confidence_agent.data.repository.FakeConfidenceRepository
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeUiState(
    val messages: List<ChatMessage> = FakeConfidenceRepository.conversation,
    val draft: String = "",
    val sending: Boolean = false,
    val error: String? = null,
    val lastReplyWasMock: Boolean? = null,
    val lastVoiceTranscript: String? = null,
    val lastVoiceReply: String? = null,
    val voiceTurnId: Int = 0,
)

class HomeViewModel(
    private val repository: ChatRepository = ChatRepository(),
) : ViewModel() {
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    fun updateDraft(value: String) {
        _uiState.update { it.copy(draft = value, error = null) }
    }

    fun send() {
        val message = _uiState.value.draft.trim()
        if (message.isEmpty() || _uiState.value.sending) return

        _uiState.update {
            it.copy(
                messages = it.messages + ChatMessage(message, fromUser = true),
                draft = "",
                sending = true,
                error = null,
            )
        }

        viewModelScope.launch {
            runCatching { repository.send(message) }
                .onSuccess { response ->
                    _uiState.update {
                        it.copy(
                            messages = it.messages + ChatMessage(response.text, fromUser = false),
                            sending = false,
                            lastReplyWasMock = response.isMock,
                        )
                    }
                }
                .onFailure {
                    _uiState.update { state ->
                        state.copy(
                            sending = false,
                            error = "暂时连不上小鸭服务，请确认电脑端服务已启动。",
                        )
                    }
                }
        }
    }

    fun sendAudio(file: File) {
        if (_uiState.value.sending) return
        _uiState.update { it.copy(sending = true, error = null) }
        viewModelScope.launch {
            try {
                val response = repository.sendAudio(file)
                val transcript = response.userText ?: "[语音消息]"
                _uiState.update {
                    it.copy(
                        messages = it.messages +
                            ChatMessage(transcript, fromUser = true) +
                            ChatMessage(response.text, fromUser = false),
                        sending = false,
                        lastReplyWasMock = response.isMock,
                        lastVoiceTranscript = transcript,
                        lastVoiceReply = response.text,
                        voiceTurnId = it.voiceTurnId + 1,
                    )
                }
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

    fun sendImage(
        fileName: String,
        mimeType: String,
        imageBytes: ByteArray,
        prompt: String = "请看看这张图片，结合我现在的处境温柔地回应。",
    ) {
        if (_uiState.value.sending) return
        _uiState.update {
            it.copy(
                messages = it.messages + ChatMessage("[图片] $prompt", fromUser = true),
                sending = true,
                error = null,
            )
        }
        viewModelScope.launch {
            runCatching { repository.sendImage(prompt, fileName, mimeType, imageBytes) }
                .onSuccess { response ->
                    _uiState.update {
                        it.copy(
                            messages = it.messages + ChatMessage(response.text, fromUser = false),
                            sending = false,
                            lastReplyWasMock = response.isMock,
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            sending = false,
                            error = error.message?.takeIf(String::isNotBlank)
                                ?: "图片暂时没有看清，请换一张试试。",
                        )
                    }
                }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    suspend fun synthesizeSpeech(text: String, voice: String): ByteArray =
        repository.synthesizeSpeech(text, voice)
}

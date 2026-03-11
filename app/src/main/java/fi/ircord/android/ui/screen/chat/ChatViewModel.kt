package fi.ircord.android.ui.screen.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fi.ircord.android.data.local.entity.MessageEntity
import fi.ircord.android.data.local.preferences.UserPreferences
import fi.ircord.android.data.remote.ConnectionState
import fi.ircord.android.data.remote.IrcordSocket
import fi.ircord.android.data.repository.MessageRepository
import fi.ircord.android.data.repository.VoiceRepository
import fi.ircord.android.domain.model.LinkPreview
import fi.ircord.android.domain.model.Message
import fi.ircord.android.domain.model.SendStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ChatUiState(
    val channelId: String = "general",
    val channelName: String = "#general",
    val isEncrypted: Boolean = true,
    val isConnected: Boolean = false,
    val currentUserId: String = "",
    val messages: List<Message> = emptyList(),
    val inputText: String = "",
    val voiceActive: Boolean = false,
    val voiceChannelName: String? = null,
    val voiceParticipantCount: Int = 0,
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val messageRepository: MessageRepository,
    private val voiceRepository: VoiceRepository,
    private val userPreferences: UserPreferences,
    private val ircordSocket: IrcordSocket,
) : ViewModel() {

    private val channelId: String = savedStateHandle["channelId"] ?: "general"

    private val _uiState = MutableStateFlow(
        ChatUiState(
            channelId = channelId,
            channelName = "#$channelId",
        )
    )
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    init {
        // Load current user
        viewModelScope.launch {
            val nickname = userPreferences.nickname.first() ?: ""
            _uiState.update { it.copy(currentUserId = nickname) }
        }

        // Combine repository flows to update UI
        combine(
            messageRepository.getMessages(channelId),
            ircordSocket.connectionState,
            voiceRepository.voiceState,
        ) { entities, connectionState, voiceState ->
            
            val messages = entities.map { it.toDomainModel() }
            
            _uiState.update { state ->
                state.copy(
                    messages = messages,
                    isConnected = connectionState == ConnectionState.CONNECTED,
                    voiceActive = voiceState.isInVoice && voiceState.channelId == channelId,
                    voiceChannelName = if (voiceState.isInVoice) "#$channelId" else null,
                    voiceParticipantCount = voiceState.participants.size,
                )
            }
        }.launchIn(viewModelScope)
    }

    fun onInputChanged(text: String) = _uiState.update { it.copy(inputText = text) }

    fun sendMessage() {
        val state = _uiState.value
        val text = state.inputText.trim()
        if (text.isEmpty()) return
        
        viewModelScope.launch {
            // Create message entity
            val entity = MessageEntity(
                channelId = channelId,
                senderId = state.currentUserId.ifEmpty { "me" },
                content = text,
                timestamp = System.currentTimeMillis(),
                sendStatus = SendStatus.SENDING.name.lowercase(),
            )
            
            // Save to local database
            val id = messageRepository.insert(entity)
            
            // Clear input
            _uiState.update { it.copy(inputText = "") }
            
            // Try to send over network
            try {
                // TODO: Actually encrypt and send via IrcordSocket when protocol is ready
                // For now, mark as sent after a brief delay to simulate network
                kotlinx.coroutines.delay(500)
                messageRepository.updateSendStatus(id, SendStatus.SENT.name.lowercase())
            } catch (e: Exception) {
                messageRepository.updateSendStatus(id, SendStatus.FAILED.name.lowercase())
            }
        }
    }
    
    fun retryMessage(messageId: Long) {
        viewModelScope.launch {
            // Retry sending a failed message
            messageRepository.updateSendStatus(messageId, SendStatus.SENDING.name.lowercase())
            try {
                kotlinx.coroutines.delay(500)
                messageRepository.updateSendStatus(messageId, SendStatus.SENT.name.lowercase())
            } catch (e: Exception) {
                messageRepository.updateSendStatus(messageId, SendStatus.FAILED.name.lowercase())
            }
        }
    }
    
    fun deleteMessage(messageId: Long) {
        viewModelScope.launch {
            // TODO: Add delete method to MessageRepository if needed
        }
    }

    private fun MessageEntity.toDomainModel(): Message {
        // Extract link preview if content contains URL
        val linkPreview = extractLinkPreview(content)
        
        return Message(
            id = id,
            channelId = channelId,
            senderId = senderId,
            content = content,
            timestamp = timestamp,
            type = when (msgType) {
                "system" -> fi.ircord.android.domain.model.MessageType.SYSTEM
                "action" -> fi.ircord.android.domain.model.MessageType.ACTION
                else -> fi.ircord.android.domain.model.MessageType.CHAT
            },
            sendStatus = when (sendStatus) {
                "sending" -> SendStatus.SENDING
                "failed" -> SendStatus.FAILED
                else -> SendStatus.SENT
            },
            linkPreview = linkPreview,
        )
    }
    
    private fun extractLinkPreview(content: String): LinkPreview? {
        // Simple URL detection for link previews
        val urlRegex = "(https?://[^\\s]+)".toRegex()
        val match = urlRegex.find(content) ?: return null
        
        val url = match.value
        // For now, just create a placeholder preview
        // TODO: Implement actual OG tag fetching
        return LinkPreview(
            url = url,
            title = null,
            description = null,
        )
    }
}

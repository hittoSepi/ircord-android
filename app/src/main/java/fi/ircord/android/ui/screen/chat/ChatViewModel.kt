package fi.ircord.android.ui.screen.chat

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.onEach
import dagger.hilt.android.lifecycle.HiltViewModel
import fi.ircord.android.data.local.entity.MessageEntity
import fi.ircord.android.data.local.preferences.UserPreferences
import fi.ircord.android.data.remote.AuthState
import fi.ircord.android.data.remote.ConnectionState
import fi.ircord.android.data.remote.IrcordConnectionManager
import fi.ircord.android.data.remote.IrcordSocket
import fi.ircord.android.data.repository.FileRepository
import fi.ircord.android.data.repository.MessageRepository
import fi.ircord.android.data.repository.VoiceRepository
import fi.ircord.android.domain.model.FileTransfer
import fi.ircord.android.domain.model.LinkPreview
import fi.ircord.android.domain.model.Message
import fi.ircord.android.domain.model.SendStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
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
    val screenCaptureEnabled: Boolean = false,
    val activeFileTransfers: List<FileTransfer> = emptyList(),
    val connectionError: String? = null,
    val isConnecting: Boolean = false,
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val messageRepository: MessageRepository,
    private val voiceRepository: VoiceRepository,
    private val fileRepository: FileRepository,
    private val userPreferences: UserPreferences,
    private val ircordSocket: IrcordSocket,
    private val connectionManager: IrcordConnectionManager,
) : ViewModel() {

    private val channelId: String = savedStateHandle["channelId"] ?: "general"

    private val _uiState = MutableStateFlow(
        ChatUiState(
            channelId = channelId,
            channelName = "#$channelId",
        )
    )
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var hasJoinedChannel = false

    init {
        // Load current user and start connection + auth
        viewModelScope.launch {
            val nickname = userPreferences.nickname.first() ?: ""
            _uiState.update { it.copy(currentUserId = nickname) }
            connectionManager.start()
            
            // Auto-join the channel when connected
            kotlinx.coroutines.delay(1000) // Wait for connection
            joinChannel("#$channelId")
        }

        // Observe screen capture setting
        userPreferences.screenCapture.onEach { enabled ->
            _uiState.update { it.copy(screenCaptureEnabled = enabled) }
        }.launchIn(viewModelScope)

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
                    isConnecting = connectionState == ConnectionState.CONNECTING,
                    voiceActive = voiceState.isInVoice && voiceState.channelId == channelId,
                    voiceChannelName = if (voiceState.isInVoice) "#$channelId" else null,
                    voiceParticipantCount = voiceState.participants.size,
                )
            }
        }.launchIn(viewModelScope)
        
        // Observe auth state for errors
        connectionManager.authState.onEach { authState ->
            Timber.d("Auth state changed: $authState")
        }.launchIn(viewModelScope)
        
        // Observe file transfer updates
        viewModelScope.launch {
            fileRepository.transferUpdates.collect { transfer ->
                _uiState.update { state ->
                    val updatedList = state.activeFileTransfers.map { 
                        if (it.fileId == transfer.fileId) transfer else it 
                    }
                    state.copy(activeFileTransfers = updatedList)
                }
            }
        }
    }

    fun onInputChanged(text: String) = _uiState.update { it.copy(inputText = text) }

    /**
     * Manually trigger reconnection to the server.
     */
    fun reconnect() {
        viewModelScope.launch {
            Timber.i("Manual reconnect requested")
            _uiState.update { it.copy(connectionError = null) }
            connectionManager.start()
        }
    }
    
    /**
     * Dismiss connection error message.
     */
    fun dismissError() {
        _uiState.update { it.copy(connectionError = null) }
    }

    fun sendMessage() {
        val state = _uiState.value
        val text = state.inputText.trim()
        if (text.isEmpty()) return

        // Check if this is a command (starts with /)
        if (text.startsWith("/")) {
            handleCommand(text)
            return
        }

        // Check if we've joined the channel
        if (!hasJoinedChannel) {
            _uiState.update { it.copy(connectionError = "Not joined to channel. Use /join #${channelId} first") }
            return
        }

        val recipientId = "#$channelId" // channels prefixed with #

        viewModelScope.launch {
            // Save to local DB immediately for UI feedback
            val entity = MessageEntity(
                channelId = channelId,
                senderId = state.currentUserId.ifEmpty { "me" },
                content = text,
                timestamp = System.currentTimeMillis(),
                sendStatus = SendStatus.SENDING.name.lowercase(),
            )
            val id = messageRepository.insert(entity)
            _uiState.update { it.copy(inputText = "") }

            try {
                connectionManager.sendChat(recipientId, text)
                messageRepository.updateSendStatus(id, SendStatus.SENT.name.lowercase())
            } catch (e: Exception) {
                messageRepository.updateSendStatus(id, SendStatus.FAILED.name.lowercase())
            }
        }
    }
    
    fun joinChannel(channelName: String) {
        val name = if (channelName.startsWith("#")) channelName else "#$channelName"
        connectionManager.sendCommand("join", name)
        hasJoinedChannel = true
    }
    
    private fun handleCommand(input: String) {
        val parts = input.substring(1).split(" ")
        if (parts.isEmpty()) return
        
        val command = parts[0].lowercase()
        val args = parts.drop(1)
        
        when (command) {
            "join" -> {
                if (args.isNotEmpty()) {
                    val channelName = args[0]
                    connectionManager.sendCommand("join", channelName)
                    _uiState.update { it.copy(inputText = "") }
                }
            }
            "part", "leave" -> {
                val channelName = args.firstOrNull() ?: "#$channelId"
                connectionManager.sendCommand("part", channelName)
                _uiState.update { it.copy(inputText = "") }
            }
            "nick" -> {
                if (args.isNotEmpty()) {
                    connectionManager.sendCommand("nick", args[0])
                    _uiState.update { it.copy(inputText = "") }
                }
            }
            "me", "action" -> {
                if (args.isNotEmpty()) {
                    val actionText = args.joinToString(" ")
                    connectionManager.sendCommand("me", actionText)
                    _uiState.update { it.copy(inputText = "") }
                }
            }
            else -> {
                // Unknown command, send as chat for now
                connectionManager.sendChat("#$channelId", input)
            }
        }
    }
    
    fun retryMessage(messageId: Long) {
        viewModelScope.launch {
            messageRepository.updateSendStatus(messageId, SendStatus.SENDING.name.lowercase())
            // TODO: retrieve message content and re-send via connectionManager
            messageRepository.updateSendStatus(messageId, SendStatus.FAILED.name.lowercase())
        }
    }
    
    fun deleteMessage(messageId: Long) {
        viewModelScope.launch {
            // TODO: Add delete method to MessageRepository if needed
        }
    }
    
    // ============================================================================
    // File Transfer
    // ============================================================================
    
    fun uploadFile(uri: Uri) {
        viewModelScope.launch {
            fileRepository.uploadFile(
                uri = uri,
                channelId = channelId
            ).onSuccess { transfer ->
                _uiState.update { state ->
                    state.copy(activeFileTransfers = state.activeFileTransfers + transfer)
                }
            }.onFailure { error ->
                // Error handling - could emit an event
            }
        }
    }
    
    fun cancelFileTransfer(fileId: String) {
        fileRepository.cancelTransfer(fileId)
    }
    
    fun clearCompletedTransfers() {
        fileRepository.clearCompletedTransfers()
        _uiState.update { state ->
            state.copy(activeFileTransfers = state.activeFileTransfers.filter { 
                it.status != fi.ircord.android.domain.model.TransferStatus.COMPLETED &&
                it.status != fi.ircord.android.domain.model.TransferStatus.FAILED &&
                it.status != fi.ircord.android.domain.model.TransferStatus.CANCELLED
            })
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

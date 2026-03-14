package fi.ircord.android.ui.screen.channels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fi.ircord.android.data.local.entity.ChannelEntity
import fi.ircord.android.data.local.preferences.UserPreferences
import fi.ircord.android.data.remote.AuthState
import fi.ircord.android.data.remote.ConnectionState
import fi.ircord.android.data.remote.IrcordConnectionManager
import fi.ircord.android.data.remote.IrcordSocket
import fi.ircord.android.data.repository.ChannelRepository
import fi.ircord.android.data.repository.VoiceRepository
import fi.ircord.android.domain.model.Channel
import fi.ircord.android.domain.model.ChannelType
import fi.ircord.android.domain.model.PresenceStatus
import fi.ircord.android.domain.model.User
import fi.ircord.android.domain.model.VoiceParticipant
import fi.ircord.android.ui.screen.channels.sanitizeChannelName
import fi.ircord.android.ui.screen.channels.isValidChannelName
import fi.ircord.android.ui.screen.channels.AvailableChannel
import fi.ircord.android.ui.screen.channels.JoinDialogState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

data class ChannelListUiState(
    val currentUser: User = User("", "", PresenceStatus.OFFLINE),
    val textChannels: List<Channel> = emptyList(),
    val voiceChannels: List<VoiceChannelInfo> = emptyList(),
    val directMessages: List<User> = emptyList(),
    val isLoading: Boolean = false,
    val isConnected: Boolean = false,
    val showJoinDialog: Boolean = false,
    val joinDialogState: JoinDialogState = JoinDialogState.Idle,
    val availableChannels: List<AvailableChannel> = emptyList(),
)

data class VoiceChannelInfo(
    val channel: Channel,
    val participants: List<VoiceParticipant> = emptyList(),
)

@HiltViewModel
class ChannelListViewModel @Inject constructor(
    private val channelRepository: ChannelRepository,
    private val voiceRepository: VoiceRepository,
    private val userPreferences: UserPreferences,
    private val ircordSocket: IrcordSocket,
    private val connectionManager: IrcordConnectionManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChannelListUiState())
    val uiState: StateFlow<ChannelListUiState> = _uiState.asStateFlow()
    
    // Track pending channel joins to add them on success
    private val pendingJoins = mutableSetOf<String>()
    
    // Track the channel name being joined for error handling
    private var currentJoinChannel: String? = null

    init {
        // Set up command response callback
        connectionManager.onCommandResponse = { success, message, command ->
            if (command == "join") {
                if (success) {
                    // Extract channel name from message: "Joined #channel"
                    val channelMatch = Regex("Joined (#\\w+)").find(message)
                    channelMatch?.groupValues?.get(1)?.let { channelName ->
                        // Remove from pending joins and add the channel
                        pendingJoins.remove(channelName)
                        addJoinedChannel(channelName)
                    }
                    // Update dialog state and close dialog on success
                    _uiState.update { state ->
                        state.copy(
                            joinDialogState = JoinDialogState.Success,
                            showJoinDialog = false,
                        )
                    }
                    currentJoinChannel = null
                } else {
                    // Show error in dialog
                    _uiState.update { state ->
                        state.copy(joinDialogState = JoinDialogState.Error(message))
                    }
                    pendingJoins.remove(currentJoinChannel)
                }
            }
        }
        // Combine all relevant flows
        combine(
            channelRepository.getAllChannels(),
            voiceRepository.voiceState,
            ircordSocket.connectionState,
            userPreferences.nickname,
        ) { channels, voiceState, connectionState, nickname ->
            
            val currentUser = User(
                userId = nickname ?: "",
                nickname = nickname ?: "",
                status = if (connectionState == ConnectionState.CONNECTED) 
                    PresenceStatus.ONLINE else PresenceStatus.OFFLINE
            )
            
            // Split channels into text and voice
            val textChannels = channels
                .filter { !it.channelId.endsWith("-voice") }
                .map { it.toDomainModel() }
            
            // Create voice channels from voice state and known channels
            val voiceChannels = if (voiceState.isInVoice) {
                listOf(
                    VoiceChannelInfo(
                        channel = Channel(
                            channelId = voiceState.channelId ?: "",
                            displayName = voiceState.channelId?.let { "#$it" } ?: "",
                            type = ChannelType.VOICE,
                        ),
                        participants = voiceState.participants.map { 
                            VoiceParticipant(
                                userId = it.userId,
                                isSpeaking = it.isSpeaking,
                                isMuted = it.isMuted,
                                audioLevel = it.audioLevel
                            )
                        }
                    )
                )
            } else {
                emptyList()
            }
            
            _uiState.update { state ->
                state.copy(
                    currentUser = currentUser,
                    textChannels = textChannels,
                    voiceChannels = voiceChannels,
                    isConnected = connectionState == ConnectionState.CONNECTED,
                )
            }
        }.launchIn(viewModelScope)
        
        // Auto-join default channel when authenticated
        viewModelScope.launch {
            connectionManager.authState.collect { authState ->
                if (authState == AuthState.AUTHENTICATED) {
                    // Check if we have any channels, if not auto-join #general
                    val channels = channelRepository.getAllChannels().first()
                    if (channels.isEmpty()) {
                        Timber.d("Auto-joining #general")
                        joinChannel("#general")
                    }
                }
            }
        }
    }
    
    /**
     * Show the join channel dialog.
     */
    fun showJoinDialog() {
        _uiState.update { state ->
            state.copy(
                showJoinDialog = true,
                joinDialogState = JoinDialogState.Idle,
            )
        }
        // TODO: Fetch available channels from server if supported
        // For now, we'll show an empty list or popular channels
    }
    
    /**
     * Hide the join channel dialog.
     */
    fun hideJoinDialog() {
        _uiState.update { state ->
            state.copy(
                showJoinDialog = false,
                joinDialogState = JoinDialogState.Idle,
            )
        }
        currentJoinChannel = null
    }
    
    /**
     * Clear any error state in the join dialog.
     */
    fun clearJoinError() {
        _uiState.update { state ->
            state.copy(joinDialogState = JoinDialogState.Idle)
        }
    }
    
    /**
     * Join a channel by sending /join command to server.
     */
    fun joinChannel(channelName: String) {
        viewModelScope.launch {
            // Sanitize the channel name (auto-add # if missing)
            val sanitizedName = sanitizeChannelName(channelName)
            
            // Validate the channel name
            if (!isValidChannelName(sanitizedName)) {
                _uiState.update { state ->
                    state.copy(joinDialogState = JoinDialogState.Error("Invalid channel name"))
                }
                return@launch
            }
            
            currentJoinChannel = sanitizedName
            pendingJoins.add(sanitizedName)
            
            _uiState.update { state ->
                state.copy(joinDialogState = JoinDialogState.Joining)
            }
            
            connectionManager.sendCommand("join", sanitizedName)
            Timber.d("Sent join command for $sanitizedName")
        }
    }
    
    /**
     * Add a channel to the local database when successfully joined.
     * Called when server confirms channel join via command response.
     */
    fun addJoinedChannel(channelId: String, displayName: String? = null) {
        viewModelScope.launch {
            // Remove # prefix if present for storage
            val cleanChannelId = channelId.removePrefix("#")
            
            // Check if channel already exists using efficient lookup
            val existing = channelRepository.getChannelById(cleanChannelId)
            
            if (existing == null) {
                val entity = ChannelEntity(
                    channelId = cleanChannelId,
                    displayName = displayName ?: "#$cleanChannelId",
                    joinedAt = System.currentTimeMillis(),
                )
                channelRepository.insert(entity)
                Timber.d("Added joined channel to database: $cleanChannelId")
            } else {
                Timber.d("Channel already exists in database: $cleanChannelId")
            }
        }
    }
    
    fun createChannel(name: String) {
        viewModelScope.launch {
            val channelId = name.lowercase().replace(" ", "-")
            val entity = ChannelEntity(
                channelId = channelId,
                displayName = "#$name",
                joinedAt = System.currentTimeMillis(),
            )
            channelRepository.insert(entity)
            
            // TODO: Send join channel message to server
        }
    }
    
    fun joinVoiceChannel(channelId: String) {
        viewModelScope.launch {
            voiceRepository.joinRoom(channelId)
        }
    }
    
    fun leaveVoiceChannel() {
        viewModelScope.launch {
            voiceRepository.leaveRoom()
        }
    }
    
    fun markChannelAsRead(channelId: String) {
        viewModelScope.launch {
            channelRepository.updateLastRead(channelId, System.currentTimeMillis())
        }
    }
    
    private fun ChannelEntity.toDomainModel(): Channel {
        return Channel(
            channelId = channelId,
            displayName = displayName ?: "#$channelId",
            type = ChannelType.TEXT,
            unreadCount = 0, // TODO: Calculate from lastReadTs
        )
    }
}

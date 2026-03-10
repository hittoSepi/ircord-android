package fi.ircord.android.ui.screen.channels

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import fi.ircord.android.domain.model.Channel
import fi.ircord.android.domain.model.ChannelType
import fi.ircord.android.domain.model.PresenceStatus
import fi.ircord.android.domain.model.User
import fi.ircord.android.domain.model.VoiceParticipant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

data class ChannelListUiState(
    val currentUser: User = User("Sepi", "Sepi", PresenceStatus.ONLINE),
    val textChannels: List<Channel> = emptyList(),
    val voiceChannels: List<VoiceChannelInfo> = emptyList(),
    val directMessages: List<User> = emptyList(),
)

data class VoiceChannelInfo(
    val channel: Channel,
    val participants: List<VoiceParticipant> = emptyList(),
)

@HiltViewModel
class ChannelListViewModel @Inject constructor() : ViewModel() {

    private val _uiState = MutableStateFlow(
        ChannelListUiState(
            textChannels = listOf(
                Channel("general", "#general", ChannelType.TEXT),
                Channel("random", "#random", ChannelType.TEXT, unreadCount = 3),
                Channel("dev", "#dev", ChannelType.TEXT),
                Channel("musiikki", "#musiikki", ChannelType.TEXT),
            ),
            voiceChannels = listOf(
                VoiceChannelInfo(
                    Channel("general-voice", "general-voice", ChannelType.VOICE),
                    listOf(
                        VoiceParticipant("Matti"),
                        VoiceParticipant("Teppo"),
                        VoiceParticipant("Pekka", isMuted = true),
                    )
                ),
                VoiceChannelInfo(
                    Channel("chillaus", "chillaus", ChannelType.VOICE),
                )
            ),
            directMessages = listOf(
                User("Matti", "Matti", PresenceStatus.ONLINE),
                User("Teppo", "Teppo", PresenceStatus.AWAY),
                User("Pekka", "Pekka", PresenceStatus.OFFLINE),
            ),
        )
    )
    val uiState: StateFlow<ChannelListUiState> = _uiState.asStateFlow()
}

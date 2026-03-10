package fi.ircord.android.ui.screen.voice

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import fi.ircord.android.domain.model.VoiceParticipant
import fi.ircord.android.domain.model.VoiceState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

data class VoiceUiState(
    val channelName: String = "",
    val isEncrypted: Boolean = true,
    val isMuted: Boolean = false,
    val isDeafened: Boolean = false,
    val participants: List<VoiceParticipant> = emptyList(),
    val latencyMs: Int = 23,
    val codec: String = "48kHz Opus",
    val isPrivateCall: Boolean = false,
    val callPeerId: String? = null,
    val isIncoming: Boolean = false,
)

@HiltViewModel
class VoiceViewModel @Inject constructor() : ViewModel() {

    private val _uiState = MutableStateFlow(
        VoiceUiState(
            channelName = "#general-voice",
            participants = listOf(
                VoiceParticipant("Matti", isSpeaking = true, audioLevel = 0.7f),
                VoiceParticipant("Teppo"),
                VoiceParticipant("Pekka", isMuted = true),
            ),
        )
    )
    val uiState: StateFlow<VoiceUiState> = _uiState.asStateFlow()

    fun toggleMute() = _uiState.update { it.copy(isMuted = !it.isMuted) }
    fun toggleDeafen() = _uiState.update { it.copy(isDeafened = !it.isDeafened) }
    fun leave() { /* TODO: NativeVoice.leaveRoom() */ }
    fun acceptCall() { /* TODO */ }
    fun declineCall() { /* TODO */ }
}

package fi.ircord.android.data.repository

import fi.ircord.android.domain.model.VoiceState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VoiceRepository @Inject constructor() {

    private val _voiceState = MutableStateFlow(VoiceState())
    val voiceState: StateFlow<VoiceState> = _voiceState.asStateFlow()

    fun joinRoom(channelId: String) {
        // TODO: NativeVoice.joinRoom(channelId)
        _voiceState.value = _voiceState.value.copy(isInVoice = true, channelId = channelId)
    }

    fun leaveRoom() {
        // TODO: NativeVoice.leaveRoom()
        _voiceState.value = VoiceState()
    }

    fun toggleMute() {
        _voiceState.value = _voiceState.value.copy(isMuted = !_voiceState.value.isMuted)
    }
}

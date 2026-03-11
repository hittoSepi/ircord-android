package fi.ircord.android.data.repository

import fi.ircord.android.domain.model.VoiceParticipant
import fi.ircord.android.domain.model.VoiceState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VoiceRepository @Inject constructor() {

    private val _voiceState = MutableStateFlow(VoiceState())
    val voiceState: StateFlow<VoiceState> = _voiceState.asStateFlow()

    fun joinRoom(channelId: String) {
        // TODO: NativeVoice.joinRoom(channelId) when native lib is ready
        _voiceState.update { state ->
            state.copy(
                isInVoice = true,
                channelId = channelId,
                isPrivateCall = false,
                callPeerId = null,
            )
        }
    }

    fun leaveRoom() {
        // TODO: NativeVoice.leaveRoom() when native lib is ready
        _voiceState.value = VoiceState()
    }

    fun toggleMute() {
        val newMuted = !_voiceState.value.isMuted
        // TODO: NativeVoice.setMuted(newMuted) when native lib is ready
        _voiceState.update { it.copy(isMuted = newMuted) }
    }
    
    fun toggleDeafen() {
        val newDeafened = !_voiceState.value.isDeafened
        // TODO: NativeVoice.setDeafened(newDeafened) when native lib is ready
        _voiceState.update { it.copy(isDeafened = newDeafened) }
    }
    
    fun call(peerId: String) {
        // TODO: NativeVoice.call(peerId) when native lib is ready
        _voiceState.update { state ->
            state.copy(
                isPrivateCall = true,
                callPeerId = peerId,
            )
        }
    }
    
    fun acceptCall() {
        // TODO: NativeVoice.acceptCall() when native lib is ready
        _voiceState.update { it.copy(isPrivateCall = true) }
    }
    
    fun declineCall() {
        // TODO: NativeVoice.declineCall() when native lib is ready
        _voiceState.update { 
            it.copy(
                isPrivateCall = false,
                callPeerId = null,
            )
        }
    }
    
    fun hangup() {
        // TODO: NativeVoice.hangup() when native lib is ready
        _voiceState.update {
            it.copy(
                isPrivateCall = false,
                callPeerId = null,
            )
        }
    }
    
    /**
     * Called when a peer joins the voice channel.
     * In real implementation, this would be triggered by native callbacks.
     */
    fun onPeerJoined(peerId: String) {
        _voiceState.update { state ->
            val newParticipants = state.participants + VoiceParticipant(userId = peerId)
            state.copy(participants = newParticipants)
        }
    }
    
    /**
     * Called when a peer leaves the voice channel.
     */
    fun onPeerLeft(peerId: String) {
        _voiceState.update { state ->
            val newParticipants = state.participants.filter { it.userId != peerId }
            state.copy(participants = newParticipants)
        }
    }
    
    /**
     * Updates audio level for a participant.
     */
    fun updateAudioLevel(peerId: String, level: Float) {
        _voiceState.update { state ->
            val newParticipants = state.participants.map { 
                if (it.userId == peerId) it.copy(audioLevel = level, isSpeaking = level > 0.1f) 
                else it 
            }
            state.copy(participants = newParticipants)
        }
    }
}

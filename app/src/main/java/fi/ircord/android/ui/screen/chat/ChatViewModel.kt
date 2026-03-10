package fi.ircord.android.ui.screen.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import fi.ircord.android.domain.model.Channel
import fi.ircord.android.domain.model.ChannelType
import fi.ircord.android.domain.model.LinkPreview
import fi.ircord.android.domain.model.Message
import fi.ircord.android.domain.model.SendStatus
import fi.ircord.android.domain.model.VoiceParticipant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

data class ChatUiState(
    val channelId: String = "general",
    val channelName: String = "#general",
    val isEncrypted: Boolean = true,
    val isConnected: Boolean = true,
    val messages: List<Message> = emptyList(),
    val inputText: String = "",
    val voiceActive: Boolean = false,
    val voiceChannelName: String? = null,
    val voiceParticipantCount: Int = 0,
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val channelId: String = savedStateHandle["channelId"] ?: "general"

    private val _uiState = MutableStateFlow(
        ChatUiState(
            channelId = channelId,
            channelName = "#$channelId",
            messages = mockMessages(),
            voiceActive = true,
            voiceChannelName = "#general",
            voiceParticipantCount = 3,
        )
    )
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    fun onInputChanged(text: String) = _uiState.update { it.copy(inputText = text) }

    fun sendMessage() {
        val text = _uiState.value.inputText.trim()
        if (text.isEmpty()) return
        val msg = Message(
            id = System.currentTimeMillis(),
            channelId = channelId,
            senderId = "Sepi",
            content = text,
            timestamp = System.currentTimeMillis(),
            sendStatus = SendStatus.SENDING,
        )
        _uiState.update { it.copy(messages = it.messages + msg, inputText = "") }
    }

    private fun mockMessages(): List<Message> {
        val base = System.currentTimeMillis()
        return listOf(
            Message(1, channelId, "Matti", "Kattokaas taa:\nhttps://example.com/cool", base - 300000,
                linkPreview = LinkPreview("https://example.com/cool", "Example Site", "Cool article about things")),
            Message(2, channelId, "Teppo", "nice", base - 240000),
            Message(3, channelId, "Teppo", "mut hei, onks kellaan sita uutta rust-kirjaa?", base - 180000),
            Message(4, channelId, "Sepi", "jep, E2E FTW", base - 120000),
            Message(5, channelId, "Pekka", "mulla on pdf, laitan kohta", base - 60000),
        )
    }
}

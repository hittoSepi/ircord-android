package fi.ircord.android.ui.screen.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import fi.ircord.android.ui.components.VoicePill
import fi.ircord.android.ui.screen.chat.components.MessageBubble
import fi.ircord.android.ui.screen.chat.components.MessageInput
import fi.ircord.android.ui.theme.IrcordSpacing
import fi.ircord.android.ui.theme.IrcordTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    channelId: String,
    onOpenDrawer: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToVoice: (String) -> Unit,
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.channelName, style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(Icons.Default.Menu, contentDescription = "Channels")
                    }
                },
                actions = {
                    if (state.isEncrypted) {
                        Icon(
                            Icons.Default.Lock,
                            contentDescription = "Encrypted",
                            tint = IrcordTheme.semanticColors.encryptionOk,
                        )
                    }
                    IconButton(onClick = { /* TODO: search */ }) {
                        Icon(Icons.Default.Search, contentDescription = "Search")
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        bottomBar = {
            Column {
                if (state.voiceActive && state.voiceChannelName != null) {
                    VoicePill(
                        channelName = state.voiceChannelName!!,
                        participantCount = state.voiceParticipantCount,
                        onJoin = { onNavigateToVoice(channelId) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                MessageInput(
                    text = state.inputText,
                    onTextChanged = viewModel::onInputChanged,
                    onSend = viewModel::sendMessage,
                    enabled = state.isConnected,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            reverseLayout = false,
        ) {
            items(state.messages, key = { it.id }) { message ->
                MessageBubble(
                    message = message,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = IrcordSpacing.messagePaddingHorizontal,
                            vertical = IrcordSpacing.messagePaddingVertical,
                        ),
                )
            }
        }
    }
}

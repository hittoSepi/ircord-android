package fi.ircord.android.ui.screen.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import fi.ircord.android.ui.components.VoicePill
import fi.ircord.android.ui.screen.chat.components.MessageBubble
import fi.ircord.android.ui.screen.chat.components.MessageInput
import fi.ircord.android.ui.security.SecureScreenEffect
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
    
    // Apply screen security when screen capture is disabled
    SecureScreenEffect(enabled = !state.screenCaptureEnabled)

    // Scroll to bottom when keyboard opens or new messages arrive
    val currentMessages by rememberUpdatedState(state.messages)
    LaunchedEffect(currentMessages.size) {
        if (currentMessages.isNotEmpty()) {
            listState.animateScrollToItem(currentMessages.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(state.channelName, style = MaterialTheme.typography.titleMedium)
                        val topic = state.topic
                        if (!topic.isNullOrBlank()) {
                            Text(
                                text = topic,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(Icons.Default.Menu, contentDescription = "Channels")
                    }
                },
                actions = {
                    // Connection status indicator
                    val connectionColor = when {
                        state.isConnected -> IrcordTheme.semanticColors.statusOnline
                        else -> IrcordTheme.semanticColors.statusOffline
                    }
                    Icon(
                        Icons.Default.Circle,
                        contentDescription = if (state.isConnected) "Connected" else "Disconnected",
                        tint = connectionColor,
                        modifier = Modifier.padding(end = IrcordSpacing.sm)
                    )
                    if (state.isEncrypted) {
                        Icon(
                            Icons.Default.Lock,
                            contentDescription = "Encrypted",
                            tint = IrcordTheme.semanticColors.encryptionOk,
                        )
                    }
                    IconButton(onClick = { viewModel.toggleSearch() }) {
                        Icon(
                            if (state.isSearchActive) Icons.Default.Close else Icons.Default.Search,
                            contentDescription = if (state.isSearchActive) "Close search" else "Search",
                        )
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding() // Add padding for keyboard
                .navigationBarsPadding(), // Handle navigation bar
        ) {
            // Connection status banner
            if (!state.isConnected && !state.isConnecting) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { viewModel.reconnect() }
                ) {
                    Text(
                        text = "⚠ Disconnected - Tap to reconnect",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(IrcordSpacing.md)
                    )
                }
            } else if (state.isConnecting) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "⏳ Connecting...",
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(IrcordSpacing.md)
                    )
                }
            }
            
            // Search bar
            if (state.isSearchActive) {
                OutlinedTextField(
                    value = state.searchQuery,
                    onValueChange = viewModel::onSearchQueryChanged,
                    placeholder = { Text("Search messages...") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = IrcordSpacing.md, vertical = IrcordSpacing.xs),
                )
            }

            // Messages list - takes all available space
            val displayMessages = if (state.isSearchActive && state.searchQuery.length >= 2)
                state.searchResults
            else
                state.messages

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f), // Use weight to fill space
                reverseLayout = false,
            ) {
                items(displayMessages, key = { it.id }) { message ->
                    MessageBubble(
                        message = message,
                        onRetry = { id -> viewModel.retryMessage(id) },
                        onDelete = { id -> viewModel.deleteMessage(id) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                horizontal = IrcordSpacing.messagePaddingHorizontal,
                                vertical = IrcordSpacing.messagePaddingVertical,
                            ),
                    )
                }
            }
            
            // Input area at bottom (moves with keyboard)
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
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
                    onAttachFile = viewModel::uploadFile,
                    enabled = state.isConnected,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

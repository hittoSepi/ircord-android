package fi.ircord.android.ui.screen.channels

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import fi.ircord.android.ui.components.StatusBadge
import fi.ircord.android.ui.components.UserAvatar
import fi.ircord.android.ui.theme.IrcordSpacing
import fi.ircord.android.ui.theme.IrcordTheme

@Composable
fun ChannelListScreen(
    onChannelSelected: (String) -> Unit,
    onNavigateToSettings: () -> Unit,
    viewModel: ChannelListViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val semantic = IrcordTheme.semanticColors

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .width(IrcordSpacing.drawerWidth)
                .verticalScroll(rememberScrollState())
                .padding(vertical = IrcordSpacing.lg),
        ) {
            // Current user
            Row(
                modifier = Modifier.padding(horizontal = IrcordSpacing.channelItemPadding),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusBadge(status = state.currentUser.status)
                Spacer(Modifier.width(IrcordSpacing.sm))
                Text(state.currentUser.nickname, style = MaterialTheme.typography.titleMedium)
            }

            Spacer(Modifier.height(IrcordSpacing.lg))

            // Text Channels
            SectionHeader("TEXT CHANNELS")
            state.textChannels.forEach { channel ->
                ChannelItem(
                    icon = { Icon(Icons.Default.Tag, null, Modifier.size(IrcordSpacing.channelIconSize)) },
                    name = channel.displayName,
                    unreadCount = channel.unreadCount,
                    onClick = { onChannelSelected(channel.channelId) },
                )
            }

            Spacer(Modifier.height(IrcordSpacing.lg))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(IrcordSpacing.sm))

            // Voice Channels
            SectionHeader("VOICE CHANNELS")
            state.voiceChannels.forEach { vc ->
                ChannelItem(
                    icon = { Icon(Icons.Default.VolumeUp, null, Modifier.size(IrcordSpacing.channelIconSize)) },
                    name = vc.channel.displayName,
                    onClick = { onChannelSelected(vc.channel.channelId) },
                )
                vc.participants.forEach { p ->
                    Row(
                        modifier = Modifier
                            .padding(start = IrcordSpacing.xxl, end = IrcordSpacing.channelItemPadding)
                            .height(28.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("\u251C ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(p.userId, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.width(IrcordSpacing.xs))
                        Icon(
                            if (p.isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                            null,
                            modifier = Modifier.size(14.dp),
                            tint = if (p.isMuted) semantic.voiceMuted else semantic.voiceActive,
                        )
                    }
                }
                if (vc.participants.isEmpty()) {
                    Text(
                        "(empty)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = IrcordSpacing.xxl),
                    )
                }
            }

            Spacer(Modifier.height(IrcordSpacing.lg))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(IrcordSpacing.sm))

            // Direct Messages
            SectionHeader("DIRECT MESSAGES")
            state.directMessages.forEach { user ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IrcordSpacing.channelItemHeight)
                        .clickable { onChannelSelected(user.userId) }
                        .padding(horizontal = IrcordSpacing.channelItemPadding),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusBadge(status = user.status)
                    Spacer(Modifier.width(IrcordSpacing.sm))
                    Text(user.nickname, style = MaterialTheme.typography.bodyMedium)
                }
            }

            Spacer(Modifier.weight(1f))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            TextButton(
                onClick = onNavigateToSettings,
                modifier = Modifier.padding(horizontal = IrcordSpacing.channelItemPadding),
            ) {
                Icon(Icons.Default.Settings, null)
                Spacer(Modifier.width(IrcordSpacing.sm))
                Text("Settings")
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(
            horizontal = IrcordSpacing.channelItemPadding,
            vertical = IrcordSpacing.xs,
        ),
    )
}

@Composable
private fun ChannelItem(
    icon: @Composable () -> Unit,
    name: String,
    unreadCount: Int = 0,
    onClick: () -> Unit,
) {
    val semantic = IrcordTheme.semanticColors

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IrcordSpacing.channelItemHeight)
            .clickable(onClick = onClick)
            .padding(horizontal = IrcordSpacing.channelItemPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon()
        Spacer(Modifier.width(IrcordSpacing.sm))
        Text(name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (unreadCount > 0) {
            Surface(
                color = semantic.unreadBadge,
                shape = MaterialTheme.shapes.extraSmall,
            ) {
                Text(
                    text = unreadCount.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onError,
                    modifier = Modifier.padding(horizontal = IrcordSpacing.xs, vertical = IrcordSpacing.xxs),
                )
            }
        }
    }
}

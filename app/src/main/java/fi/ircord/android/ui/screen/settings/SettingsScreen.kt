package fi.ircord.android.ui.screen.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import fi.ircord.android.ui.theme.IrcordSpacing
import fi.ircord.android.ui.theme.IrcordTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val semantic = IrcordTheme.semanticColors

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            SectionTitle("ACCOUNT")
            SettingsRow("Nickname", state.nickname)
            SettingsRow("Identity key", state.identityFingerprint)
            SettingsRow("Export key backup", "")

            SectionTitle("SERVER")
            SettingsRow("Address", state.serverAddress)
            SettingsRow("Port", state.port)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = IrcordSpacing.lg, vertical = IrcordSpacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Status", modifier = Modifier.weight(1f))
                Text(
                    if (state.isConnected) "Connected" else "Disconnected",
                    color = if (state.isConnected) semantic.statusOnline else semantic.statusOffline,
                )
            }

            SectionTitle("APPEARANCE")
            SettingsRow("Theme", state.themeMode)
            SettingsRow("Message style", state.messageStyle)
            SettingsRow("Timestamp", state.timestampFormat)
            SettingsToggle("Compact mode", state.compactMode, viewModel::setCompactMode)

            SectionTitle("NOTIFICATIONS")
            SettingsToggle("Mentions", state.notifyMentions, viewModel::setNotifyMentions)
            SettingsToggle("DMs", state.notifyDMs, viewModel::setNotifyDMs)
            SettingsToggle("Sound", state.notifySound, viewModel::setNotifySound)

            SectionTitle("VOICE")
            SettingsToggle("Push-to-talk", state.pushToTalk, viewModel::setPushToTalk)
            SettingsToggle("Noise suppression", state.noiseSuppression, viewModel::setNoiseSuppression)
            SettingsRow("Bitrate", state.voiceBitrate)

            SectionTitle("SECURITY")
            SettingsToggle("Screen capture", state.screenCapture, viewModel::setScreenCapture)
            SettingsRow("Verified peers", "")
            SettingsRow("Blocked users", "")

            SectionTitle("ABOUT")
            SettingsRow("Version", state.version)
            SettingsRow("Protocol", state.protocolVersion)
            SettingsRow("Licenses", "")

            Spacer(Modifier.height(IrcordSpacing.xxl))
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(
            start = IrcordSpacing.lg,
            top = IrcordSpacing.lg,
            bottom = IrcordSpacing.xs,
        ),
    )
}

@Composable
private fun SettingsRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = IrcordSpacing.lg, vertical = IrcordSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        if (value.isNotEmpty()) {
            Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.width(IrcordSpacing.xs))
            Text(">", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text(">", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SettingsToggle(label: String, checked: Boolean, onChanged: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = IrcordSpacing.lg, vertical = IrcordSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChanged)
    }
}

package fi.ircord.android.ui.screenshot

import android.content.Intent
import android.os.Bundle
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SettingsVoice
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import fi.ircord.android.BuildConfig
import fi.ircord.android.domain.model.LinkPreview
import fi.ircord.android.domain.model.Message
import fi.ircord.android.ui.components.UserAvatar
import fi.ircord.android.ui.components.VoicePill
import fi.ircord.android.ui.screen.chat.components.MessageBubble
import fi.ircord.android.ui.screen.chat.components.MessageInput
import fi.ircord.android.ui.theme.IrcordSpacing
import fi.ircord.android.ui.theme.IrcordTheme

private const val SCREENSHOT_SCENE_KEY = "ircord_screenshot_scene"
private const val SCREENSHOT_DARK_THEME_KEY = "ircord_screenshot_dark_theme"
private const val SCREENSHOT_FONT_SCALE_KEY = "ircord_screenshot_font_scale"

internal data class ScreenshotConfig(
    val scene: ScreenshotScene,
    val darkTheme: Boolean = true,
    val fontScale: Float = 1.0f,
)

internal enum class ScreenshotScene(val value: String) {
    SETUP("setup"),
    CHAT("chat"),
    SETTINGS("settings"),
    VOICE("voice");

    companion object {
        fun fromValue(rawValue: String?): ScreenshotScene? {
            return entries.firstOrNull { it.value == rawValue }
        }
    }
}

internal fun screenshotConfigFromIntent(intent: Intent?): ScreenshotConfig? {
    if (!BuildConfig.DEBUG || intent == null) {
        return null
    }

    val extras = intent.extras ?: return null
    val scene = ScreenshotScene.fromValue(extras.readStringCompat(SCREENSHOT_SCENE_KEY)) ?: return null
    return ScreenshotConfig(
        scene = scene,
        darkTheme = extras.readBooleanCompat(SCREENSHOT_DARK_THEME_KEY, defaultValue = true),
        fontScale = extras.readFloatCompat(SCREENSHOT_FONT_SCALE_KEY, defaultValue = 1.0f),
    )
}

@Composable
internal fun ScreenshotSceneScreen(
    config: ScreenshotConfig,
) {
    when (config.scene) {
        ScreenshotScene.SETUP -> SetupScreenshotScene()
        ScreenshotScene.CHAT -> ChatScreenshotScene()
        ScreenshotScene.SETTINGS -> SettingsScreenshotScene()
        ScreenshotScene.VOICE -> VoiceScreenshotScene()
    }
}

@Composable
private fun SetupScreenshotScene() {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = IrcordSpacing.xl, vertical = IrcordSpacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(36.dp),
                )
            }

            Spacer(Modifier.height(IrcordSpacing.lg))

            Text(
                text = "Welcome to IrssiCord",
                style = MaterialTheme.typography.headlineMedium,
            )

            Spacer(Modifier.height(IrcordSpacing.sm))

            Text(
                text = "End-to-end encrypted chat\nfor your friend group.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(IrcordSpacing.xxl))

            OutlinedTextField(
                value = "chat.ircord.dev",
                onValueChange = {},
                label = { Text("Server address") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                readOnly = true,
            )

            Spacer(Modifier.height(IrcordSpacing.md))

            OutlinedTextField(
                value = "6697",
                onValueChange = {},
                label = { Text("Port") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                readOnly = true,
            )

            Spacer(Modifier.height(IrcordSpacing.md))

            OutlinedTextField(
                value = "sepi",
                onValueChange = {},
                label = { Text("Your nickname") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                readOnly = true,
            )

            Spacer(Modifier.height(IrcordSpacing.md))

            OutlinedTextField(
                value = "F4J-9Q2-KL7",
                onValueChange = {},
                label = { Text("Invite code (if required)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                readOnly = true,
            )

            Spacer(Modifier.height(IrcordSpacing.md))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = true,
                    onCheckedChange = {},
                )
                Text(
                    text = "Remember me",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Spacer(Modifier.height(IrcordSpacing.xl))

            Button(
                onClick = {},
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Generate Keys & Join")
            }

            Spacer(Modifier.height(IrcordSpacing.md))

            Text(
                text = "Generating your identity key pair.\nThis may take a moment.",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun ChatScreenshotScene() {
    val messages = listOf(
        Message(
            id = 1,
            channelId = "general",
            senderId = "mira",
            content = "Landing docs are ready for review.",
            timestamp = 1_710_000_000_000,
        ),
        Message(
            id = 2,
            channelId = "general",
            senderId = "sepi",
            content = "Nice. The desktop client build is green again.",
            timestamp = 1_710_000_060_000,
        ),
        Message(
            id = 3,
            channelId = "general",
            senderId = "otto",
            content = "Shipping the Android screenshots next.",
            timestamp = 1_710_000_120_000,
            linkPreview = LinkPreview(
                url = "https://ircord.dev/download",
                title = "IRCord Downloads",
                description = "Desktop TUI, Android builds, and quick-start docs in one place.",
            ),
        ),
        Message(
            id = 4,
            channelId = "general",
            senderId = "mira",
            content = "Voice room is live if anyone wants a quick test.",
            timestamp = 1_710_000_180_000,
        ),
    )

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        topBar = {
            TopAppBar(
                title = { Text("#general", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = {}) {
                        Icon(Icons.Default.Menu, contentDescription = "Channels")
                    }
                },
                actions = {
                    Icon(
                        Icons.Default.Circle,
                        contentDescription = "Connected",
                        tint = IrcordTheme.semanticColors.statusOnline,
                        modifier = Modifier.padding(end = IrcordSpacing.sm),
                    )
                    Icon(
                        Icons.Default.Lock,
                        contentDescription = "Encrypted",
                        tint = IrcordTheme.semanticColors.encryptionOk,
                        modifier = Modifier.padding(end = IrcordSpacing.sm),
                    )
                    IconButton(onClick = {}) {
                        Icon(Icons.Default.Search, contentDescription = "Search")
                    }
                    IconButton(onClick = {}) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .navigationBarsPadding(),
        ) {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(
                    horizontal = IrcordSpacing.messagePaddingHorizontal,
                    vertical = IrcordSpacing.lg,
                ),
                verticalArrangement = Arrangement.spacedBy(IrcordSpacing.xs),
            ) {
                items(messages, key = { it.id }) { message ->
                    MessageBubble(
                        message = message,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            VoicePill(
                channelName = "#general",
                participantCount = 3,
                onJoin = {},
                modifier = Modifier.fillMaxWidth(),
            )

            MessageInput(
                text = "Team sync notes are in the docs.",
                onTextChanged = {},
                onSend = {},
                onAttachFile = {},
                enabled = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun SettingsScreenshotScene() {
    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = {}) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState()),
        ) {
            ScreenshotSectionTitle("ACCOUNT")
            ScreenshotSettingsRow("Nickname", "sepi")
            ScreenshotSettingsRow("Identity key", "D4A1-11B7-90C3")
            ScreenshotSettingsRow("Export key backup", "")

            ScreenshotSectionTitle("SERVER")
            ScreenshotSettingsRow("Address", "chat.ircord.dev")
            ScreenshotSettingsRow("Port", "6697")
            ScreenshotSettingsRow("Status", "Connected", valueColor = IrcordTheme.semanticColors.statusOnline)

            ScreenshotSectionTitle("APPEARANCE")
            ScreenshotSettingsRow("Theme", "Dark", trailingChevron = true)
            ScreenshotSettingsRow("Font size", "Normal", trailingChevron = true)
            ScreenshotSettingsRow("Timestamp", "24h")
            ScreenshotSettingsToggle("Compact mode", true)

            ScreenshotSectionTitle("NOTIFICATIONS")
            ScreenshotSettingsToggle("Mentions", true)
            ScreenshotSettingsToggle("DMs", true)
            ScreenshotSettingsToggle("Sound", false)

            ScreenshotSectionTitle("VOICE")
            ScreenshotSettingsToggle("Push-to-talk", true)
            ScreenshotSettingsToggle("Noise suppression", true)
            ScreenshotSettingsRow("Bitrate", "64 kbps")

            ScreenshotSectionTitle("SECURITY")
            ScreenshotSettingsToggle("Screen capture", false)
            ScreenshotSettingsRow("Biometric auth", "", trailingChevron = true)
            ScreenshotSettingsRow("Certificate pinning", "", trailingChevron = true)
            ScreenshotSettingsRow("Verified peers", "12")

            ScreenshotSectionTitle("ABOUT")
            ScreenshotSettingsRow("Version", "0.1.0")
            ScreenshotSettingsRow("Protocol", "v1")

            Spacer(Modifier.height(IrcordSpacing.xxl))
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun VoiceScreenshotScene() {
    val semantic = IrcordTheme.semanticColors
    val participants = listOf("mira", "otto", "sepi")

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(IrcordSpacing.xl),
        ) {
            Text(
                text = "Voice Room",
                style = MaterialTheme.typography.headlineMedium,
            )

            Spacer(Modifier.height(IrcordSpacing.xs))

            Text(
                text = "#general",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(IrcordSpacing.lg))

            Surface(
                color = semantic.voiceActive.copy(alpha = 0.15f),
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(IrcordSpacing.lg),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = semantic.encryptionOk,
                    )
                    Spacer(Modifier.width(IrcordSpacing.sm))
                    Text(
                        text = "Encrypted voice session active",
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
            }

            Spacer(Modifier.height(IrcordSpacing.xxl))

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(IrcordSpacing.lg),
                verticalArrangement = Arrangement.spacedBy(IrcordSpacing.lg),
            ) {
                participants.forEach { userId ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        shape = MaterialTheme.shapes.large,
                    ) {
                        Column(
                            modifier = Modifier
                                .padding(horizontal = IrcordSpacing.lg, vertical = IrcordSpacing.md),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            UserAvatar(userId = userId, size = 64.dp)
                            Spacer(Modifier.height(IrcordSpacing.sm))
                            Text(
                                text = userId,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.height(IrcordSpacing.xs))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Mic,
                                    contentDescription = null,
                                    tint = semantic.voiceActive,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(IrcordSpacing.xs))
                                Text(
                                    text = if (userId == "sepi") "Speaking" else "Listening",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            Text(
                text = "3 participants live",
                style = MaterialTheme.typography.titleMedium,
            )

            Spacer(Modifier.height(IrcordSpacing.sm))

            Text(
                text = "Low-latency voice with end-to-end encrypted transport.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(IrcordSpacing.xl))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(IrcordSpacing.md),
            ) {
                Button(
                    onClick = {},
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.SettingsVoice, contentDescription = null)
                    Spacer(Modifier.width(IrcordSpacing.sm))
                    Text("Mute")
                }

                Button(
                    onClick = {},
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null)
                    Spacer(Modifier.width(IrcordSpacing.sm))
                    Text("Speaker")
                }

                Button(
                    onClick = {},
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Icon(Icons.Default.Check, contentDescription = null)
                    Spacer(Modifier.width(IrcordSpacing.sm))
                    Text("Leave")
                }
            }
        }
    }
}

@Composable
private fun ScreenshotSectionTitle(
    title: String,
) {
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
private fun ScreenshotSettingsRow(
    label: String,
    value: String,
    valueColor: Color? = null,
    trailingChevron: Boolean = false,
) {
    val resolvedValueColor = valueColor ?: MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = IrcordSpacing.lg, vertical = IrcordSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        if (value.isNotEmpty()) {
            Text(
                text = value,
                color = resolvedValueColor,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (trailingChevron) {
            Spacer(Modifier.width(IrcordSpacing.xs))
            Text(
                text = ">",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ScreenshotSettingsToggle(
    label: String,
    checked: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = IrcordSpacing.lg, vertical = IrcordSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = {},
        )
    }
}

private fun Bundle.readStringCompat(key: String): String? {
    return getString(key)
}

private fun Bundle.readBooleanCompat(
    key: String,
    defaultValue: Boolean,
): Boolean {
    val value = getString(key) ?: return defaultValue
    return when {
        value.equals("true", ignoreCase = true) -> true
        value.equals("false", ignoreCase = true) -> false
        value == "1" -> true
        value == "0" -> false
        else -> defaultValue
    }
}

private fun Bundle.readFloatCompat(
    key: String,
    defaultValue: Float,
): Float {
    return getString(key)?.toFloatOrNull() ?: defaultValue
}

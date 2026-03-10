package fi.ircord.android.ui.screen.settings

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

data class SettingsUiState(
    val nickname: String = "Sepi",
    val identityFingerprint: String = "7A:A2:F7:9E:CE:6A:BB:9A:F7:73:DA:CA",
    val serverAddress: String = "ircord.example.com",
    val port: String = "6667",
    val isConnected: Boolean = true,
    val themeMode: String = "Dark",
    val messageStyle: String = "IRC",
    val timestampFormat: String = "HH:MM",
    val compactMode: Boolean = false,
    val notifyMentions: Boolean = true,
    val notifyDMs: Boolean = true,
    val notifySound: Boolean = true,
    val pushToTalk: Boolean = false,
    val noiseSuppression: Boolean = true,
    val voiceBitrate: String = "64 kbps",
    val screenCapture: Boolean = false,
    val version: String = "0.1.0",
    val protocolVersion: String = "v1",
)

@HiltViewModel
class SettingsViewModel @Inject constructor() : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    fun setCompactMode(v: Boolean) = _uiState.update { it.copy(compactMode = v) }
    fun setNotifyMentions(v: Boolean) = _uiState.update { it.copy(notifyMentions = v) }
    fun setNotifyDMs(v: Boolean) = _uiState.update { it.copy(notifyDMs = v) }
    fun setNotifySound(v: Boolean) = _uiState.update { it.copy(notifySound = v) }
    fun setPushToTalk(v: Boolean) = _uiState.update { it.copy(pushToTalk = v) }
    fun setNoiseSuppression(v: Boolean) = _uiState.update { it.copy(noiseSuppression = v) }
    fun setScreenCapture(v: Boolean) = _uiState.update { it.copy(screenCapture = v) }
}

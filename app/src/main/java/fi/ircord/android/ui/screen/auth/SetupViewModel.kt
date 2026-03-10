package fi.ircord.android.ui.screen.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SetupUiState(
    val serverAddress: String = "",
    val port: String = "6667",
    val nickname: String = "",
    val inviteCode: String = "",
    val isGenerating: Boolean = false,
    val progress: Float = 0f,
    val currentStep: String = "",
    val steps: List<SetupStep> = listOf(
        SetupStep("Identity key pair", StepStatus.PENDING),
        SetupStep("Signed pre-key", StepStatus.PENDING),
        SetupStep("One-time pre-keys (0/100)", StepStatus.PENDING),
        SetupStep("Uploading to server", StepStatus.PENDING),
        SetupStep("Joining channels", StepStatus.PENDING),
    ),
    val fingerprint: String? = null,
    val error: String? = null,
    val isComplete: Boolean = false,
)

data class SetupStep(val label: String, val status: StepStatus)
enum class StepStatus { PENDING, IN_PROGRESS, DONE }

@HiltViewModel
class SetupViewModel @Inject constructor() : ViewModel() {

    private val _uiState = MutableStateFlow(SetupUiState())
    val uiState: StateFlow<SetupUiState> = _uiState.asStateFlow()

    fun onServerAddressChanged(value: String) = _uiState.update { it.copy(serverAddress = value) }
    fun onPortChanged(value: String) = _uiState.update { it.copy(port = value) }
    fun onNicknameChanged(value: String) = _uiState.update { it.copy(nickname = value) }
    fun onInviteCodeChanged(value: String) = _uiState.update { it.copy(inviteCode = value) }

    fun generateKeysAndJoin() {
        viewModelScope.launch {
            _uiState.update { it.copy(isGenerating = true, error = null) }

            // Simulate key generation steps
            val stepLabels = listOf(
                "Identity key pair",
                "Signed pre-key",
                "One-time pre-keys (100/100)",
                "Uploading to server",
                "Joining channels",
            )
            for (i in stepLabels.indices) {
                _uiState.update { state ->
                    val updated = state.steps.toMutableList()
                    if (i > 0) updated[i - 1] = updated[i - 1].copy(status = StepStatus.DONE)
                    updated[i] = updated[i].copy(status = StepStatus.IN_PROGRESS)
                    state.copy(
                        steps = updated,
                        progress = (i + 1f) / stepLabels.size,
                        currentStep = stepLabels[i],
                    )
                }
                delay(600)
            }

            _uiState.update { state ->
                val updated = state.steps.toMutableList()
                updated[updated.lastIndex] = updated.last().copy(status = StepStatus.DONE)
                state.copy(
                    steps = updated,
                    progress = 1f,
                    isGenerating = false,
                    isComplete = true,
                    fingerprint = "7A:A2:F7:9E:CE:6A:BB:9A:F7:73:DA:CA",
                )
            }
        }
    }
}

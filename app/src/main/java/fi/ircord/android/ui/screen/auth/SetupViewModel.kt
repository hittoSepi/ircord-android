package fi.ircord.android.ui.screen.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fi.ircord.android.data.local.preferences.UserPreferences
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
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
class SetupViewModel @Inject constructor(
    private val userPreferences: UserPreferences,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SetupUiState())
    val uiState: StateFlow<SetupUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            // Pre-fill with saved values if any
            val savedAddress = userPreferences.serverAddress.first()
            val savedPort = userPreferences.port.first()
            val savedNickname = userPreferences.nickname.first()
            
            _uiState.update { state ->
                state.copy(
                    serverAddress = savedAddress ?: "",
                    port = savedPort.toString(),
                    nickname = savedNickname ?: "",
                )
            }
        }
    }

    fun onServerAddressChanged(value: String) = _uiState.update { it.copy(serverAddress = value) }
    fun onPortChanged(value: String) = _uiState.update { it.copy(port = value) }
    fun onNicknameChanged(value: String) = _uiState.update { it.copy(nickname = value) }
    fun onInviteCodeChanged(value: String) = _uiState.update { it.copy(inviteCode = value) }

    fun generateKeysAndJoin() {
        viewModelScope.launch {
            val state = _uiState.value
            _uiState.update { it.copy(isGenerating = true, error = null) }

            try {
                // Step 1: Generate identity key pair
                updateStep(0, StepStatus.IN_PROGRESS)
                val (identityKeyPair, fingerprint) = generateIdentityKeyPair()
                delay(200) // Small delay for UI feedback
                updateStep(0, StepStatus.DONE)

                // Step 2: Generate signed pre-key
                updateStep(1, StepStatus.IN_PROGRESS)
                generateSignedPreKey(identityKeyPair)
                delay(200)
                updateStep(1, StepStatus.DONE)

                // Step 3: Generate one-time pre-keys
                updateStep(2, StepStatus.IN_PROGRESS)
                generateOneTimePreKeys(identityKeyPair, 100)
                delay(200)
                updateStep(2, StepStatus.DONE)
                
                // Update step label to show completion
                _uiState.update { s ->
                    val updated = s.steps.toMutableList()
                    updated[2] = updated[2].copy(label = "One-time pre-keys (100/100)")
                    s.copy(steps = updated)
                }

                // Step 4: Save to preferences (server connection would happen here)
                updateStep(3, StepStatus.IN_PROGRESS)
                userPreferences.saveServerSettings(state.serverAddress, state.port.toIntOrNull() ?: 6667)
                
                // Serialize the key pair for storage
                val keyPairBytes = serializeKeyPair(identityKeyPair)
                userPreferences.saveIdentity(state.nickname, fingerprint, keyPairBytes)
                delay(300)
                updateStep(3, StepStatus.DONE)

                // Step 5: Mark as complete
                updateStep(4, StepStatus.IN_PROGRESS)
                userPreferences.setRegistered(true)
                delay(200)
                updateStep(4, StepStatus.DONE)

                _uiState.update { s ->
                    s.copy(
                        progress = 1f,
                        isGenerating = false,
                        isComplete = true,
                        fingerprint = fingerprint,
                    )
                }
            } catch (e: Exception) {
                _uiState.update { 
                    it.copy(
                        isGenerating = false,
                        error = e.message ?: "Unknown error occurred"
                    )
                }
            }
        }
    }

    private fun updateStep(index: Int, status: StepStatus) {
        _uiState.update { state ->
            val updated = state.steps.toMutableList()
            updated[index] = updated[index].copy(status = status)
            state.copy(
                steps = updated,
                progress = (index + 1f) / updated.size
            )
        }
    }

    /**
     * Generates an Ed25519-like identity key pair and fingerprint.
     * When native lib is ready, this will call NativeCrypto.generateIdentity()
     */
    private fun generateIdentityKeyPair(): Pair<KeyPair, String> {
        // Using Java's Ed25519 support (Java 15+) or fallback
        val keyPairGenerator = KeyPairGenerator.getInstance("Ed25519")
        val keyPair = keyPairGenerator.generateKeyPair()
        
        // Generate fingerprint: SHA-256 of public key, formatted as colon-separated hex
        val publicKeyBytes = keyPair.public.encoded
        val hash = MessageDigest.getInstance("SHA-256").digest(publicKeyBytes)
        val fingerprint = hash.take(18).joinToString(":") { "%02X".format(it) }
        
        return keyPair to fingerprint
    }

    private fun generateSignedPreKey(identityKeyPair: KeyPair) {
        // Generate a signed pre-key (X3DH protocol)
        val keyPairGenerator = KeyPairGenerator.getInstance("Ed25519")
        val signedPreKey = keyPairGenerator.generateKeyPair()
        
        // Sign the pre-key public key with identity key
        val signature = Signature.getInstance("Ed25519")
        signature.initSign(identityKeyPair.private)
        signature.update(signedPreKey.public.encoded)
        val signedData = signature.sign()
        
        // TODO: Store signed pre-key in database when KeyRepository supports it
    }

    private fun generateOneTimePreKeys(identityKeyPair: KeyPair, count: Int) {
        val keyPairGenerator = KeyPairGenerator.getInstance("Ed25519")
        val secureRandom = SecureRandom()
        
        // Generate batch of one-time pre-keys
        val preKeys = List(count) {
            keyPairGenerator.generateKeyPair()
        }
        
        // TODO: Store pre-keys in database when KeyRepository supports it
        // For now, we just simulate the generation time
    }
    
    /**
     * Serializes a KeyPair to bytes for storage.
     * Format: [public key length (2 bytes)][public key][private key length (2 bytes)][private key]
     */
    private fun serializeKeyPair(keyPair: KeyPair): ByteArray {
        val pubKey = keyPair.public.encoded
        val privKey = keyPair.private.encoded
        
        return ByteArray(2 + pubKey.size + 2 + privKey.size).apply {
            // Public key length (big endian)
            this[0] = (pubKey.size shr 8).toByte()
            this[1] = pubKey.size.toByte()
            // Public key
            System.arraycopy(pubKey, 0, this, 2, pubKey.size)
            // Private key length
            this[2 + pubKey.size] = (privKey.size shr 8).toByte()
            this[2 + pubKey.size + 1] = privKey.size.toByte()
            // Private key
            System.arraycopy(privKey, 0, this, 2 + pubKey.size + 2, privKey.size)
        }
    }
}

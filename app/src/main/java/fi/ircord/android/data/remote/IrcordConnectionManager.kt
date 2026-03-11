package fi.ircord.android.data.remote

import com.google.protobuf.ByteString
import fi.ircord.android.data.local.entity.MessageEntity
import fi.ircord.android.data.local.preferences.UserPreferences
import fi.ircord.android.data.repository.MessageRepository
import fi.ircord.android.crypto.NativeCrypto
import fi.ircord.android.crypto.NativeStore
import ircord.Ircord.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

enum class AuthState { NONE, HELLO_SENT, CHALLENGE_RECEIVED, AUTH_SENT, AUTHENTICATED }

/**
 * Manages the full connection lifecycle:
 *   connect → HELLO → AUTH_CHALLENGE → AUTH_RESPONSE → AUTH_OK → KEY_UPLOAD
 *   then: send/receive encrypted chat messages, handle key exchange, ping/pong.
 */
@Singleton
class IrcordConnectionManager @Inject constructor(
    private val socket: IrcordSocket,
    private val userPreferences: UserPreferences,
    private val messageRepository: MessageRepository,
    private val nativeStore: NativeStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val seq = AtomicLong(1)

    private val _authState = MutableStateFlow(AuthState.NONE)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private var userId: String = ""
    private var cryptoInitialized = false

    // Pending DMs waiting for key bundles
    private val pendingSends = ConcurrentHashMap<String, String>()

    /**
     * Connect to the server, authenticate, and start the message loop.
     * Call once from ChatViewModel or a Service.
     */
    fun start() {
        scope.launch {
            val address = userPreferences.serverAddress.first() ?: return@launch
            val port = userPreferences.port.first()
            userId = userPreferences.nickname.first() ?: return@launch

            // Initialize native crypto engine
            if (!cryptoInitialized) {
                val ok = withContext(Dispatchers.Default) {
                    NativeCrypto.nativeInit(nativeStore, userId, "")
                }
                if (!ok) {
                    Timber.e("Failed to initialize NativeCrypto")
                    return@launch
                }
                cryptoInitialized = true
            }

            // Start listening for incoming frames
            launch { collectFrames() }

            // Connect socket (triggers TLS handshake)
            socket.connect(address, port)

            // Wait for connection, then send HELLO
            socket.connectionState.collect { state ->
                if (state == ConnectionState.CONNECTED && _authState.value == AuthState.NONE) {
                    sendHello()
                }
            }
        }
    }

    // ========================================================================
    // Outgoing: Auth flow
    // ========================================================================

    private fun sendHello() {
        val hello = Hello.newBuilder()
            .setProtocolVersion(1)
            .setClientVersion("ircord-android/0.15.0")
            .build()
        sendEnvelope(MessageType.MT_HELLO, hello.toByteArray())
        _authState.value = AuthState.HELLO_SENT
        Timber.i("Sent HELLO")
    }

    private fun handleAuthChallenge(payload: ByteArray) {
        _authState.value = AuthState.CHALLENGE_RECEIVED
        val challenge = AuthChallenge.parseFrom(payload)
        val nonce = challenge.nonce.toByteArray()
        Timber.i("Received AUTH_CHALLENGE (${nonce.size} bytes)")

        // Sign: nonce || user_id
        val signature = NativeCrypto.signChallenge(nonce)
        val identityPub = NativeCrypto.identityPub()
        val spk = NativeCrypto.currentSpk()

        if (signature == null || identityPub == null) {
            Timber.e("Failed to sign challenge")
            return
        }

        val authResp = AuthResponse.newBuilder()
            .setUserId(userId)
            .setIdentityPub(ByteString.copyFrom(identityPub))
            .setSignature(ByteString.copyFrom(signature))

        if (spk != null) {
            authResp.setSignedPrekey(ByteString.copyFrom(spk.pub))
            authResp.setSpkSig(ByteString.copyFrom(spk.sig))
        }

        sendEnvelope(MessageType.MT_AUTH_RESPONSE, authResp.build().toByteArray())
        _authState.value = AuthState.AUTH_SENT
        Timber.i("Sent AUTH_RESPONSE for '$userId'")
    }

    private fun handleAuthOk() {
        _authState.value = AuthState.AUTHENTICATED
        Timber.i("Authenticated as '$userId'")

        // Upload pre-keys
        scope.launch(Dispatchers.Default) {
            val uploadBytes = NativeCrypto.prepareRegistration(100) ?: return@launch
            val upload = parseKeyUploadBytes(uploadBytes)
            sendEnvelope(MessageType.MT_KEY_UPLOAD, upload.toByteArray())
            Timber.i("Sent KEY_UPLOAD (100 OPKs)")
        }
    }

    private fun handleAuthFail(payload: ByteArray) {
        val err = Error.parseFrom(payload)
        Timber.e("AUTH_FAIL: [${err.code}] ${err.message}")
        _authState.value = AuthState.NONE
    }

    // ========================================================================
    // Outgoing: Chat
    // ========================================================================

    /**
     * Send a chat message (encrypted) to a recipient or channel.
     * Called from ChatViewModel.
     */
    fun sendChat(recipientId: String, plaintext: String) {
        if (_authState.value != AuthState.AUTHENTICATED) {
            Timber.w("Cannot send: not authenticated")
            return
        }

        scope.launch(Dispatchers.Default) {
            val isChannel = recipientId.startsWith("#")

            if (isChannel) {
                sendChannelMessage(recipientId, plaintext)
            } else {
                sendDmMessage(recipientId, plaintext)
            }
        }
    }

    private fun sendDmMessage(recipientId: String, plaintext: String) {
        val ciphertextBytes = NativeCrypto.encrypt(recipientId, plaintext.toByteArray(Charsets.UTF_8))

        if (ciphertextBytes == null) {
            // No session yet — request key bundle, queue plaintext
            Timber.i("No session for '$recipientId', requesting key bundle")
            pendingSends[recipientId] = plaintext
            val kr = KeyRequest.newBuilder().setUserId(recipientId).build()
            sendEnvelope(MessageType.MT_KEY_REQUEST, kr.toByteArray())
            return
        }

        // Parse the native ChatEnvelope bytes
        val chat = ChatEnvelope.parseFrom(ciphertextBytes)
        sendEnvelope(MessageType.MT_CHAT_ENVELOPE, chat.toByteArray())
    }

    private fun sendChannelMessage(channelId: String, plaintext: String) {
        val ciphertextBytes = NativeCrypto.encryptGroup(channelId, plaintext.toByteArray(Charsets.UTF_8))

        if (ciphertextBytes == null) {
            Timber.e("Failed to encrypt group message for $channelId")
            return
        }

        // Native returns serialized ChatEnvelope
        val chat = ChatEnvelope.parseFrom(ciphertextBytes)
        sendEnvelope(MessageType.MT_CHAT_ENVELOPE, chat.toByteArray())
    }

    // ========================================================================
    // Incoming: Frame dispatch
    // ========================================================================

    private suspend fun collectFrames() {
        socket.incomingFrames.collect { payload ->
            try {
                val envelope = Envelope.parseFrom(payload)
                dispatch(envelope)
            } catch (e: Exception) {
                Timber.e(e, "Failed to parse incoming envelope")
            }
        }
    }

    private fun dispatch(env: Envelope) {
        val payload = env.payload.toByteArray()
        when (env.type) {
            MessageType.MT_AUTH_CHALLENGE -> handleAuthChallenge(payload)
            MessageType.MT_AUTH_OK        -> handleAuthOk()
            MessageType.MT_AUTH_FAIL      -> handleAuthFail(payload)
            MessageType.MT_CHAT_ENVELOPE  -> handleIncomingChat(env)
            MessageType.MT_KEY_BUNDLE     -> handleKeyBundle(payload)
            MessageType.MT_PRESENCE       -> handlePresence(payload)
            MessageType.MT_PING           -> handlePing(env)
            MessageType.MT_ERROR          -> handleError(payload)
            else -> Timber.d("Unhandled message type: ${env.type}")
        }
    }

    // ========================================================================
    // Incoming: Chat
    // ========================================================================

    private fun handleIncomingChat(env: Envelope) {
        scope.launch(Dispatchers.Default) {
            try {
                val chat = ChatEnvelope.parseFrom(env.payload)
                val senderId = chat.senderId
                val recipientId = chat.recipientId
                val ciphertext = chat.ciphertext.toByteArray()
                val type = chat.ciphertextType

                // Process SKDM if present (group first-message)
                if (chat.skdm.size() > 0 && recipientId.startsWith("#")) {
                    NativeCrypto.processSenderKeyDistribution(
                        senderId, recipientId, chat.skdm.toByteArray()
                    )
                }

                // Decrypt
                val plaintext: ByteArray? = if (recipientId.startsWith("#")) {
                    NativeCrypto.decryptGroup(
                        senderId, recipientId, ciphertext,
                        if (chat.skdm.size() > 0) chat.skdm.toByteArray() else null
                    )
                } else {
                    NativeCrypto.decrypt(senderId, recipientId, ciphertext, type.toInt())
                }

                if (plaintext == null) {
                    Timber.w("Failed to decrypt message from $senderId")
                    return@launch
                }

                val text = String(plaintext, Charsets.UTF_8)

                // Determine channel: for DMs use the sender's ID, for channels use recipient
                val channelId = if (recipientId.startsWith("#")) {
                    recipientId.removePrefix("#")
                } else {
                    senderId
                }

                // Persist to local DB
                val entity = MessageEntity(
                    channelId = channelId,
                    senderId = senderId,
                    content = text,
                    timestamp = if (env.timestampMs > 0) env.timestampMs else System.currentTimeMillis(),
                )
                messageRepository.insert(entity)

            } catch (e: Exception) {
                Timber.e(e, "Error handling incoming chat")
            }
        }
    }

    // ========================================================================
    // Incoming: Key exchange
    // ========================================================================

    private fun handleKeyBundle(payload: ByteArray) {
        scope.launch(Dispatchers.Default) {
            try {
                val bundle = KeyBundle.parseFrom(payload)
                val recipientId = bundle.recipientFor
                if (recipientId.isNullOrEmpty()) {
                    Timber.w("KeyBundle missing recipient_for")
                    return@launch
                }

                // Establish X3DH session
                NativeCrypto.onKeyBundle(recipientId, bundle.toNativeBytes())
                Timber.i("Established session with '$recipientId'")

                // Flush pending plaintext
                val pending = pendingSends.remove(recipientId) ?: return@launch
                sendDmMessage(recipientId, pending)
                Timber.i("Flushed pending DM to '$recipientId'")
            } catch (e: Exception) {
                Timber.e(e, "Error handling key bundle")
            }
        }
    }

    // ========================================================================
    // Incoming: Misc
    // ========================================================================

    private fun handlePresence(payload: ByteArray) {
        try {
            val update = PresenceUpdate.parseFrom(payload)
            Timber.d("Presence: ${update.userId} -> ${update.status}")
        } catch (e: Exception) {
            Timber.e(e, "Error parsing presence")
        }
    }

    private fun handlePing(env: Envelope) {
        sendEnvelope(MessageType.MT_PONG, env.payload.toByteArray())
    }

    private fun handleError(payload: ByteArray) {
        try {
            val err = Error.parseFrom(payload)
            Timber.e("Server error: [${err.code}] ${err.message}")
        } catch (e: Exception) {
            Timber.e(e, "Error parsing error message")
        }
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private fun sendEnvelope(type: MessageType, payload: ByteArray) {
        val env = Envelope.newBuilder()
            .setSeq(seq.getAndIncrement())
            .setTimestampMs(System.currentTimeMillis())
            .setType(type)
            .setPayload(ByteString.copyFrom(payload))
            .build()
        socket.send(env.toByteArray())
    }
}

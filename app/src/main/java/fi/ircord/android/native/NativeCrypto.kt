package fi.ircord.android.native

/**
 * JNI bridge to the C++ CryptoEngine (libsignal-protocol-c + libsodium).
 * All methods delegate to native implementations in jni_bridge.cpp.
 */
object NativeCrypto {

    init {
        System.loadLibrary("ircord_crypto")
    }

    data class SpkInfo(
        val pub: ByteArray,
        val sig: ByteArray,
        val id: Int,
    )

    /**
     * Initialize the crypto engine with a passphrase-protected identity store.
     * Loads or generates Ed25519 identity key pair.
     * @param store  Room-backed NativeStore for key persistence
     * @param userId local user's ID
     * @param passphrase passphrase for identity key encryption at rest
     * @return true if initialization succeeded
     */
    @JvmStatic
    external fun nativeInit(store: Any, userId: String, passphrase: String): Boolean

    /**
     * Prepare a KeyUpload payload (SPK + OPKs) for the server.
     * @param numOpks number of one-time pre-keys to generate
     * @return serialized KeyUpload bytes (native format), or null on error
     */
    @JvmStatic
    external fun prepareRegistration(numOpks: Int): ByteArray?

    /**
     * Encrypt plaintext for a 1:1 recipient.
     * @return serialized ChatEnvelope bytes, or null if no session (need key bundle)
     */
    @JvmStatic
    external fun encrypt(recipientId: String, plaintext: ByteArray): ByteArray?

    /**
     * Decrypt a 1:1 ciphertext.
     * @param type ciphertext type: 1=WHISPER, 3=PRE_KEY, 4=SENDER_KEY
     * @return plaintext bytes, or null on error
     */
    @JvmStatic
    external fun decrypt(senderId: String, recipientId: String, ciphertext: ByteArray, type: Int): ByteArray?

    /**
     * Process an incoming KeyBundle and establish an X3DH session.
     * @param bundleData native-format key bundle bytes (see ProtobufExt.toNativeBytes())
     */
    @JvmStatic
    external fun onKeyBundle(recipientId: String, bundleData: ByteArray)

    /** Check if we have an established session with this recipient. */
    @JvmStatic
    external fun hasSession(recipientId: String): Boolean

    /** Initialize a group session for a channel. */
    @JvmStatic
    external fun initGroupSession(channelId: String, members: Array<String>)

    /** Encrypt for a group channel. Returns ciphertext bytes (may include SKDM on first send). */
    @JvmStatic
    external fun encryptGroup(channelId: String, plaintext: ByteArray): ByteArray?

    /** Decrypt a group message. */
    @JvmStatic
    external fun decryptGroup(senderId: String, channelId: String, ciphertext: ByteArray, skdm: ByteArray?): ByteArray?

    /** Process an incoming SenderKeyDistributionMessage. */
    @JvmStatic
    external fun processSenderKeyDistribution(senderId: String, channelId: String, skdm: ByteArray)

    /**
     * Sign an auth challenge nonce.
     * Signs: nonce || user_id with Ed25519 identity key.
     * @return 64-byte Ed25519 signature
     */
    @JvmStatic
    external fun signChallenge(nonce: ByteArray): ByteArray?

    /** Get the Ed25519 identity public key (32 bytes). */
    @JvmStatic
    external fun identityPub(): ByteArray?

    /** Get the current signed pre-key info. */
    @JvmStatic
    external fun currentSpk(): SpkInfo?

    /** Compute safety number for a peer. */
    @JvmStatic
    external fun safetyNumber(peerId: String): String
}

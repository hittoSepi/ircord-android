package fi.ircord.android.crypto

/**
 * JNI bridge to the C++ CryptoEngine (libsignal-protocol-c + libsodium).
 * All methods delegate to native implementations in jni_bridge.cpp.
 */
object NativeCrypto {

    init {
        System.loadLibrary("ircord-native")
    }

    data class SpkInfo(
        val pub: ByteArray,
        val sig: ByteArray,
        val id: Int,
    )

    @JvmStatic
    external fun nativeInit(store: Any, userId: String, passphrase: String): Boolean

    @JvmStatic
    external fun prepareRegistration(numOpks: Int): ByteArray?

    @JvmStatic
    external fun encrypt(recipientId: String, plaintext: ByteArray): ByteArray?

    @JvmStatic
    external fun decrypt(senderId: String, recipientId: String, ciphertext: ByteArray, type: Int): ByteArray?

    @JvmStatic
    external fun onKeyBundle(recipientId: String, bundleData: ByteArray)

    @JvmStatic
    external fun hasSession(recipientId: String): Boolean

    @JvmStatic
    external fun initGroupSession(channelId: String, members: Array<String>)

    @JvmStatic
    external fun encryptGroup(channelId: String, plaintext: ByteArray): ByteArray?

    @JvmStatic
    external fun decryptGroup(senderId: String, channelId: String, ciphertext: ByteArray, skdm: ByteArray?): ByteArray?

    @JvmStatic
    external fun processSenderKeyDistribution(senderId: String, channelId: String, skdm: ByteArray)

    @JvmStatic
    external fun signChallenge(nonce: ByteArray): ByteArray?

    @JvmStatic
    external fun identityPub(): ByteArray?

    @JvmStatic
    external fun currentSpk(): SpkInfo?

    @JvmStatic
    external fun safetyNumber(peerId: String): String
}

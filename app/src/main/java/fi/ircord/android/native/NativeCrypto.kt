package fi.ircord.android.native

/**
 * JNI bridge to the shared C++ crypto engine (libsignal-protocol-c + libsodium).
 * Native library will be loaded when NDK build is ready.
 */
object NativeCrypto {
    // TODO: uncomment when native .so is built
    // init { System.loadLibrary("ircord-native") }

    // Identity
    external fun generateIdentity(): ByteArray
    external fun prepareRegistration(numOpks: Int): ByteArray

    // Encrypt/decrypt
    external fun encrypt(recipientId: String, plaintext: ByteArray): ByteArray
    external fun decrypt(senderId: String, ciphertext: ByteArray, type: Int): ByteArray

    // Group (Sender Keys)
    external fun initGroupSession(channelId: String, members: Array<String>)
    external fun encryptGroup(channelId: String, plaintext: ByteArray): ByteArray
    external fun decryptGroup(senderId: String, channelId: String, ciphertext: ByteArray): ByteArray

    // Safety Number
    external fun safetyNumber(peerId: String): String

    // Auth
    external fun signChallenge(nonce: ByteArray): ByteArray
}

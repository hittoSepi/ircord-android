package fi.ircord.android.crypto

import android.content.Context
import java.io.File

/**
 * Java-side key store called by JNI (JNIStore in jni_bridge.cpp).
 * Uses files in app-private storage for synchronous read/write
 * (Room DAOs are suspend-only, but JNI callbacks must be synchronous).
 */
class NativeStore(context: Context) {

    private val root = File(context.filesDir, "crypto_store").also { it.mkdirs() }

    private fun dir(name: String) = File(root, name).also { it.mkdirs() }

    fun saveIdentity(userId: String, pubKey: ByteArray, privKeyEncrypted: ByteArray, salt: ByteArray) {
        val pubLen = pubKey.size
        val saltLen = salt.size
        val buf = ByteArray(8 + saltLen + pubLen + privKeyEncrypted.size)
        buf[0] = (pubLen and 0xFF).toByte()
        buf[1] = ((pubLen shr 8) and 0xFF).toByte()
        buf[2] = ((pubLen shr 16) and 0xFF).toByte()
        buf[3] = ((pubLen shr 24) and 0xFF).toByte()
        buf[4] = (saltLen and 0xFF).toByte()
        buf[5] = ((saltLen shr 8) and 0xFF).toByte()
        buf[6] = ((saltLen shr 16) and 0xFF).toByte()
        buf[7] = ((saltLen shr 24) and 0xFF).toByte()
        System.arraycopy(salt, 0, buf, 8, saltLen)
        System.arraycopy(pubKey, 0, buf, 8 + saltLen, pubLen)
        System.arraycopy(privKeyEncrypted, 0, buf, 8 + saltLen + pubLen, privKeyEncrypted.size)
        File(dir("identity"), userId).writeBytes(buf)
    }

    fun loadIdentity(userId: String): ByteArray? {
        val f = File(dir("identity"), userId)
        return if (f.exists()) f.readBytes() else null
    }

    fun saveSession(address: String, record: ByteArray) {
        File(dir("sessions"), address.sanitize()).writeBytes(record)
    }

    fun loadSession(address: String): ByteArray? {
        val f = File(dir("sessions"), address.sanitize())
        return if (f.exists()) f.readBytes() else null
    }

    fun savePreKey(id: Int, record: ByteArray) {
        File(dir("prekeys"), id.toString()).writeBytes(record)
    }

    fun loadPreKey(id: Int): ByteArray? {
        val f = File(dir("prekeys"), id.toString())
        return if (f.exists()) f.readBytes() else null
    }

    fun removePreKey(id: Int) {
        File(dir("prekeys"), id.toString()).delete()
    }

    fun saveSignedPreKey(id: Int, record: ByteArray) {
        File(dir("signed_prekeys"), id.toString()).writeBytes(record)
    }

    fun loadSignedPreKey(id: Int): ByteArray? {
        val f = File(dir("signed_prekeys"), id.toString())
        return if (f.exists()) f.readBytes() else null
    }

    fun savePeerIdentity(userId: String, pubKey: ByteArray) {
        File(dir("peers"), userId.sanitize()).writeBytes(pubKey)
    }

    fun loadPeerIdentity(userId: String): ByteArray? {
        val f = File(dir("peers"), userId.sanitize())
        return if (f.exists()) f.readBytes() else null
    }

    fun saveSenderKey(senderKeyId: String, record: ByteArray) {
        File(dir("sender_keys"), senderKeyId.sanitize()).writeBytes(record)
    }

    fun loadSenderKey(senderKeyId: String): ByteArray? {
        val f = File(dir("sender_keys"), senderKeyId.sanitize())
        return if (f.exists()) f.readBytes() else null
    }

    private fun String.sanitize(): String =
        replace(Regex("[^a-zA-Z0-9._#@:-]"), "_")
}

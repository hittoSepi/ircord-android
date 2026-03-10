package fi.ircord.android.native

/**
 * JNI bridge to the shared C++ voice engine (libdatachannel + Opus + Oboe).
 * Native library will be loaded when NDK build is ready.
 */
object NativeVoice {
    // TODO: uncomment when native .so is built
    // init { System.loadLibrary("ircord-native") }

    external fun init(sampleRate: Int, framesPerBuffer: Int)
    external fun joinRoom(channelId: String)
    external fun leaveRoom()
    external fun call(peerId: String)
    external fun hangup()
    external fun setMuted(muted: Boolean)
    external fun setDeafened(deafened: Boolean)
    external fun onVoiceSignal(fromUser: String, signalType: Int, data: ByteArray)
    external fun destroy()

    interface VoiceCallback {
        fun onIceCandidate(peerId: String, candidate: ByteArray)
        fun onPeerJoined(peerId: String)
        fun onPeerLeft(peerId: String)
        fun onAudioLevel(peerId: String, level: Float)
    }
}

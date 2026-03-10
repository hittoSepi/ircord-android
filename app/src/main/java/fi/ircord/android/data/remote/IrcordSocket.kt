package fi.ircord.android.data.remote

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED }

/**
 * TLS TCP socket with length-prefixed framing.
 * TODO: implement actual socket I/O with OkHttp or raw SSLSocket.
 */
class IrcordSocket @Inject constructor(
    private val frameCodec: FrameCodec,
    private val reconnectPolicy: ReconnectPolicy,
) {
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _incomingFrames = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    val incomingFrames: SharedFlow<ByteArray> = _incomingFrames.asSharedFlow()

    suspend fun connect(host: String, port: Int) {
        _connectionState.value = ConnectionState.CONNECTING
        // TODO: establish TLS connection, start read loop
        // On success: _connectionState.value = ConnectionState.CONNECTED
    }

    suspend fun send(payload: ByteArray) {
        // TODO: encode frame and write to socket
        val frame = frameCodec.encode(payload)
        // socket.write(frame)
    }

    fun disconnect() {
        _connectionState.value = ConnectionState.DISCONNECTED
        reconnectPolicy.reset()
        // TODO: close socket
    }
}

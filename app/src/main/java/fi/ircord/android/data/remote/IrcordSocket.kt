package fi.ircord.android.data.remote

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.inject.Inject
import javax.inject.Singleton

enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED }

/**
 * Raw TLS/TCP socket with 4-byte big-endian length-prefixed framing.
 * Matches the server's Boost.Asio TLS 1.3 + length-prefix wire protocol.
 */
@Singleton
class IrcordSocket @Inject constructor(
    private val frameCodec: FrameCodec,
    private val reconnectPolicy: ReconnectPolicy,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _incomingFrames = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    val incomingFrames: SharedFlow<ByteArray> = _incomingFrames.asSharedFlow()

    private var socket: SSLSocket? = null
    private var output: DataOutputStream? = null
    private val writeLock = Any()

    private var currentHost: String? = null
    private var currentPort: Int = 0
    private var shouldReconnect = true

    suspend fun connect(host: String, port: Int) {
        if (_connectionState.value != ConnectionState.DISCONNECTED) return

        currentHost = host
        currentPort = port
        shouldReconnect = true

        _connectionState.value = ConnectionState.CONNECTING

        withContext(Dispatchers.IO) {
            try {
                val sslContext = SSLContext.getInstance("TLSv1.3")
                sslContext.init(null, null, null)

                val sslSocket = sslContext.socketFactory.createSocket() as SSLSocket
                sslSocket.enabledProtocols = arrayOf("TLSv1.3")
                sslSocket.soTimeout = 0 // no read timeout — we block on read loop
                sslSocket.connect(InetSocketAddress(host, port), 30_000)
                sslSocket.startHandshake()

                socket = sslSocket
                output = DataOutputStream(sslSocket.outputStream)

                _connectionState.value = ConnectionState.CONNECTED
                reconnectPolicy.reset()
                Timber.i("Connected to $host:$port (TLS 1.3)")

                // Start read loop
                scope.launch { readLoop(sslSocket) }

            } catch (e: Exception) {
                Timber.e(e, "Failed to connect to $host:$port")
                closeSocket()
                _connectionState.value = ConnectionState.DISCONNECTED
                scheduleReconnect()
            }
        }
    }

    fun send(payload: ByteArray): Boolean {
        if (_connectionState.value != ConnectionState.CONNECTED) {
            Timber.w("Cannot send: not connected")
            return false
        }

        return try {
            val frame = frameCodec.encode(payload)
            synchronized(writeLock) {
                output?.write(frame)
                output?.flush()
            }
            true
        } catch (e: IOException) {
            Timber.e(e, "Error sending frame")
            handleDisconnect("Write failed: ${e.message}")
            false
        }
    }

    fun disconnect() {
        shouldReconnect = false
        reconnectPolicy.reset()
        closeSocket()
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    private fun readLoop(sslSocket: SSLSocket) {
        val input = DataInputStream(sslSocket.inputStream)
        try {
            while (_connectionState.value == ConnectionState.CONNECTED) {
                // Read 4-byte length header (big-endian)
                val length = input.readInt()
                if (length < 0 || length > FrameCodec.MAX_FRAME_SIZE) {
                    Timber.w("Invalid frame length: $length")
                    break
                }

                // Read payload
                val payload = ByteArray(length)
                input.readFully(payload)

                _incomingFrames.tryEmit(payload)
            }
        } catch (e: IOException) {
            if (_connectionState.value == ConnectionState.CONNECTED) {
                Timber.e(e, "Read loop error")
            }
        } finally {
            handleDisconnect("Read loop ended")
        }
    }

    private fun handleDisconnect(reason: String) {
        if (_connectionState.value == ConnectionState.DISCONNECTED) return
        Timber.i("Disconnected: $reason")
        closeSocket()
        _connectionState.value = ConnectionState.DISCONNECTED
        if (shouldReconnect) {
            scheduleReconnect()
        }
    }

    private fun closeSocket() {
        try {
            output?.close()
        } catch (_: Exception) {}
        try {
            socket?.close()
        } catch (_: Exception) {}
        output = null
        socket = null
    }

    private fun scheduleReconnect() {
        if (!shouldReconnect || !reconnectPolicy.hasAttemptsLeft()) return

        scope.launch {
            val delayMs = reconnectPolicy.nextDelayMs()
            Timber.i("Reconnecting in ${delayMs}ms...")
            delay(delayMs)

            if (isActive && shouldReconnect && _connectionState.value == ConnectionState.DISCONNECTED) {
                currentHost?.let { host ->
                    connect(host, currentPort)
                }
            }
        }
    }
}

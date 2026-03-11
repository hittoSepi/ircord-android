package fi.ircord.android.data.remote

import fi.ircord.android.security.pinning.CertificatePinEventListener
import fi.ircord.android.security.pinning.CertificatePinner
import fi.ircord.android.security.pinning.PinRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import timber.log.Timber
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED }

/**
 * TLS TCP socket with length-prefixed framing and certificate pinning.
 * Uses OkHttp for TLS support and handles reconnection via ReconnectPolicy.
 */
@Singleton
class IrcordSocket @Inject constructor(
    private val frameCodec: FrameCodec,
    private val reconnectPolicy: ReconnectPolicy,
    private val pinRepository: PinRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _incomingFrames = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    val incomingFrames: SharedFlow<ByteArray> = _incomingFrames.asSharedFlow()
    
    private var webSocket: WebSocket? = null
    private var currentHost: String? = null
    private var currentPort: Int = 0
    private var shouldReconnect = true
    
    // Certificate pinning failure callback
    var onCertificatePinFailure: ((hostname: String, peerPins: List<String>) -> Unit)? = null
    
    suspend fun connect(host: String, port: Int) {
        if (_connectionState.value == ConnectionState.CONNECTED || 
            _connectionState.value == ConnectionState.CONNECTING) {
            return
        }
        
        currentHost = host
        currentPort = port
        shouldReconnect = true
        
        _connectionState.value = ConnectionState.CONNECTING
        
        try {
            // Determine WebSocket vs raw TLS based on port
            val protocol = if (port == 443 || port == 8443) "wss" else "ws"
            val url = "$protocol://$host:$port/ircord"
            
            // Create client with certificate pinning
            val client = createOkHttpClient(host)
            
            val request = Request.Builder()
                .url(url)
                .build()
            
            webSocket = client.newWebSocket(request, createWebSocketListener())
            
        } catch (e: Exception) {
            Timber.e(e, "Failed to connect to $host:$port")
            _connectionState.value = ConnectionState.DISCONNECTED
            scheduleReconnect()
        }
    }

    suspend fun send(payload: ByteArray): Boolean {
        if (_connectionState.value != ConnectionState.CONNECTED) {
            Timber.w("Cannot send: not connected")
            return false
        }
        
        return try {
            val frame = frameCodec.encode(payload)
            val sent = webSocket?.send(ByteString.of(*frame)) ?: false
            if (!sent) {
                Timber.w("Failed to send frame")
            }
            sent
        } catch (e: Exception) {
            Timber.e(e, "Error sending frame")
            false
        }
    }

    fun disconnect() {
        shouldReconnect = false
        reconnectPolicy.reset()
        webSocket?.close(1000, "User disconnected")
        webSocket = null
        _connectionState.value = ConnectionState.DISCONNECTED
        scope.cancel()
    }
    
    private suspend fun createOkHttpClient(hostname: String): OkHttpClient {
        val pinningConfig = pinRepository.pinningConfig.first()
        
        val builder = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS) // No timeout for WebSocket
            .writeTimeout(30, TimeUnit.SECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
        
        // Add certificate pinning if configured for this hostname
        if (pinningConfig.findPinsForHostname(hostname).isNotEmpty()) {
            Timber.d("Certificate pinning enabled for $hostname")
            
            val pinner = CertificatePinner(
                config = pinningConfig,
                onPinFailure = { host, peerPins ->
                    Timber.w("Certificate pinning failed for $host")
                    onCertificatePinFailure?.invoke(host, peerPins)
                }
            )
            
            val eventListener = CertificatePinEventListener(
                config = pinningConfig,
                onPinValidated = { host, pin ->
                    Timber.d("Certificate validated for $host with pin: ${pin.take(16)}...")
                },
                onPinFailure = { host, peerPins ->
                    onCertificatePinFailure?.invoke(host, peerPins)
                }
            )
            
            builder.addInterceptor(pinner)
            builder.eventListener(eventListener)
        } else {
            Timber.d("No certificate pins configured for $hostname")
        }
        
        return builder.build()
    }
    
    private fun createWebSocketListener(): WebSocketListener {
        return object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Timber.i("WebSocket connected")
                _connectionState.value = ConnectionState.CONNECTED
                reconnectPolicy.reset()
            }
            
            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                try {
                    val frame = bytes.toByteArray()
                    val payload = frameCodec.decode(frame)
                    if (payload != null) {
                        _incomingFrames.tryEmit(payload)
                    } else {
                        Timber.w("Failed to decode frame")
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Error processing message")
                }
            }
            
            override fun onMessage(ws: WebSocket, text: String) {
                // Server shouldn't send text frames, but handle just in case
                Timber.d("Received text message: $text")
            }
            
            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                Timber.i("WebSocket closing: $code - $reason")
                _connectionState.value = ConnectionState.DISCONNECTED
            }
            
            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Timber.i("WebSocket closed: $code - $reason")
                _connectionState.value = ConnectionState.DISCONNECTED
                if (shouldReconnect) {
                    scheduleReconnect()
                }
            }
            
            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Timber.e(t, "WebSocket failure")
                _connectionState.value = ConnectionState.DISCONNECTED
                if (shouldReconnect) {
                    scheduleReconnect()
                }
            }
        }
    }
    
    private fun scheduleReconnect() {
        if (!shouldReconnect) return
        
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

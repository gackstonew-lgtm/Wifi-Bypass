package com.example.wifibridgebypass.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Network
import android.net.wifi.WifiManager
import android.os.IBinder
import android.util.Log
import com.example.wifibridgebypass.MainActivity
import com.example.wifibridgebypass.utils.NetworkManager
import com.example.wifibridgebypass.utils.UpstreamWifiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * ProxyBridgeService implements a high-performance, concurrent RFC 1928 SOCKS5 Proxy Server.
 *
 * It bridges downstream devices (such as a laptop connected via local Hotspot or USB Tethering)
 * to the phone's authenticated Wi-Fi connection.
 *
 * HOW IT CIRCUMVENTS ISP RADIUS / TETHERING RESTRICTIONS:
 * 1. Traditional tethering relies on Linux kernel IP packet routing and NAT (Network Address Translation).
 *    This decrements the IP TTL (Hop Limit) header by 1 and produces OS TCP window signatures that the
 *    upstream Radius firewall or Deep Packet Inspection (DPI) flags as unauthorized tethering.
 * 2. In this architecture, all incoming TCP connections from the laptop terminate at this SOCKS5 service.
 * 3. The service then instantiates a native outbound Java Socket and explicitly binds it to the
 *    authenticated Wi-Fi Network handle using `Network.bindSocket()`.
 * 4. As a result, all outbound packets originate natively from the Android OS user space with the
 *    device's valid authenticated IP, legitimate MAC, standard Android TTL (64), and genuine TCP stack
 *    characteristics. The ISP Radius billing system processes these packets as standard single-device usage.
 */
class ProxyBridgeService : Service() {

    enum class BridgeStatus {
        STOPPED,
        WAITING_FOR_WIFI,
        RUNNING,
        ERROR
    }

    /**
     * Bounded, thread-safe byte buffer pool to eliminate GC pressure during sustained transfers.
     */
    private object BufferPool {
        const val BUFFER_SIZE = 32 * 1024 // 32KB high throughput buffer
        private const val MAX_POOL_SIZE = 64
        private val pool = ConcurrentLinkedQueue<ByteArray>()
        private val pooledCount = AtomicInteger(0)

        fun acquire(): ByteArray {
            val buffer = pool.poll()
            if (buffer != null) {
                pooledCount.decrementAndGet()
                return buffer
            }
            return ByteArray(BUFFER_SIZE)
        }

        fun release(buffer: ByteArray) {
            if (buffer.size == BUFFER_SIZE && pooledCount.get() < MAX_POOL_SIZE) {
                pool.offer(buffer)
                pooledCount.incrementAndGet()
            }
        }
    }

    companion object {
        const val PROXY_PORT = 1080
        const val CHANNEL_ID = "ProxyBridgeNotificationChannel"
        const val NOTIFICATION_ID = 1001

        private const val TAG = "ProxyBridgeService"

        // RFC 1928 SOCKS Protocol Constants
        private const val SOCKS_VERSION = 0x05
        private const val AUTH_NO_AUTH = 0x00
        private const val AUTH_NO_ACCEPTABLE = 0xFF

        private const val CMD_CONNECT = 0x01
        private const val CMD_BIND = 0x02
        private const val CMD_UDP_ASSOCIATE = 0x03

        private const val ATYP_IPV4 = 0x01
        private const val ATYP_DOMAIN = 0x03
        private const val ATYP_IPV6 = 0x04

        // SOCKS5 Response Codes (RFC 1928 Section 6)
        private const val REP_SUCCEEDED = 0x00
        private const val REP_GENERAL_FAILURE = 0x01
        private const val REP_CONN_NOT_ALLOWED = 0x02
        private const val REP_NETWORK_UNREACHABLE = 0x03
        private const val REP_HOST_UNREACHABLE = 0x04
        private const val REP_CONN_REFUSED = 0x05
        private const val REP_TTL_EXPIRED = 0x06
        private const val REP_COMMAND_NOT_SUPPORTED = 0x07
        private const val REP_ATYP_NOT_SUPPORTED = 0x08

        private const val HANDSHAKE_TIMEOUT_MS = 10_000
        private const val CONNECT_TIMEOUT_MS = 12_000

        // Observables for UI & ViewModel
        private val _status = MutableStateFlow(BridgeStatus.STOPPED)
        val status: StateFlow<BridgeStatus> = _status.asStateFlow()

        private val _activeConnections = MutableStateFlow(0)
        val activeConnections: StateFlow<Int> = _activeConnections.asStateFlow()

        private val _bytesTransferredTx = MutableStateFlow(0L)
        val bytesTransferredTx: StateFlow<Long> = _bytesTransferredTx.asStateFlow()

        private val _bytesTransferredRx = MutableStateFlow(0L)
        val bytesTransferredRx: StateFlow<Long> = _bytesTransferredRx.asStateFlow()

        private val _lastError = MutableStateFlow<String?>(null)
        val lastError: StateFlow<String?> = _lastError.asStateFlow()

        private val _boundWifiIp = MutableStateFlow<String?>(null)
        val boundWifiIp: StateFlow<String?> = _boundWifiIp.asStateFlow()
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var networkManager: NetworkManager

    private var serverJob: Job? = null
    private var networkWatcherJob: Job? = null
    private var metricsJob: Job? = null
    private var serverSocket: ServerSocket? = null
    private var wifiLock: WifiManager.WifiLock? = null

    // Set of active sockets to ensure clean teardown if upstream Wi-Fi drops
    private val activeSockets = ConcurrentHashMap.newKeySet<Socket>()
    private val connectionCounter = AtomicLong(0)
    private val totalTx = AtomicLong(0)
    private val totalRx = AtomicLong(0)

    override fun onCreate() {
        super.onCreate()
        networkManager = NetworkManager.getInstance(applicationContext)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Initializing Legitimate SOCKS5 Bridge..."))
        startNetworkWatcher()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "ProxyBridgeService onStartCommand received")
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "ProxyBridgeService destroying")
        shutdownServer("Service destroyed")
        networkWatcherJob?.cancel()
        serviceScope.cancel()
        _status.value = BridgeStatus.STOPPED
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Applies optimized socket options (TCP_NODELAY, SO_KEEPALIVE, tuned buffers)
     * for high-throughput and low latency.
     */
    private fun configureSocket(socket: Socket) {
        try {
            socket.tcpNoDelay = true
            socket.keepAlive = true
            socket.receiveBufferSize = 64 * 1024
            socket.sendBufferSize = 64 * 1024
        } catch (e: Exception) {
            Log.w(TAG, "Socket options could not be fully applied: ${e.message}")
        }
    }

    /**
     * Prevents Android Wi-Fi power-save sleep from dropping throughput during background streaming.
     */
    @Suppress("DEPRECATION")
    private fun acquireWifiLock() {
        try {
            if (wifiLock == null) {
                val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                wifiLock = wifiManager?.createWifiLock(
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                    "WiFiBridge:HighPerfLock"
                )?.apply {
                    setReferenceCounted(false)
                }
            }
            wifiLock?.let {
                if (!it.isHeld) it.acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to acquire high-performance Wi-Fi lock: ${e.message}")
        }
    }

    private fun releaseWifiLock() {
        try {
            wifiLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing Wi-Fi lock: ${e.message}")
        }
        wifiLock = null
    }

    /**
     * Starts a periodic metrics updater (4 Hz) to prevent UI thread / Looper starvation.
     */
    private fun startMetricsReporter() {
        metricsJob?.cancel()
        metricsJob = serviceScope.launch {
            var lastTx = -1L
            var lastRx = -1L
            while (isActive) {
                delay(250) // Sample at 4 Hz
                val currentTx = totalTx.get()
                val currentRx = totalRx.get()
                if (currentTx != lastTx) {
                    _bytesTransferredTx.value = currentTx
                    lastTx = currentTx
                }
                if (currentRx != lastRx) {
                    _bytesTransferredRx.value = currentRx
                    lastRx = currentRx
                }
            }
        }
    }

    private fun stopMetricsReporter() {
        metricsJob?.cancel()
        metricsJob = null
        _bytesTransferredTx.value = totalTx.get()
        _bytesTransferredRx.value = totalRx.get()
    }

    /**
     * Observes upstream Wi-Fi connectivity. If Wi-Fi becomes authenticated, the proxy is launched.
     * If Wi-Fi drops or a captive portal prompt returns, active connections are immediately severed.
     */
    private fun startNetworkWatcher() {
        networkWatcherJob = serviceScope.launch {
            networkManager.observeUpstreamWifi().collect { wifiState ->
                when (wifiState) {
                    is UpstreamWifiState.Authenticated -> {
                        _boundWifiIp.value = wifiState.ipAddress
                        if (serverJob == null) {
                            Log.i(TAG, "Authenticated Wi-Fi available (${wifiState.ipAddress}). Starting SOCKS5 server...")
                            startServer(wifiState.network)
                        }
                    }
                    is UpstreamWifiState.CaptivePortalDetected -> {
                        Log.w(TAG, "Captive portal detected! Stopping proxy server until authentication is completed.")
                        shutdownServer("Captive portal sign-in required")
                        _status.value = BridgeStatus.WAITING_FOR_WIFI
                        updateNotification("Waiting for captive portal authentication...")
                    }
                    is UpstreamWifiState.ConnectedNoInternet -> {
                        Log.w(TAG, "Wi-Fi connected but no internet access verified.")
                        shutdownServer("Wi-Fi has no internet")
                        _status.value = BridgeStatus.WAITING_FOR_WIFI
                        updateNotification("Wi-Fi connected — verifying upstream internet...")
                    }
                    is UpstreamWifiState.Disconnected -> {
                        Log.w(TAG, "Wi-Fi disconnected. Halting proxy bridge.")
                        shutdownServer("Wi-Fi disconnected")
                        _status.value = BridgeStatus.WAITING_FOR_WIFI
                        updateNotification("Waiting for Wi-Fi connection...")
                    }
                }
            }
        }
    }

    /**
     * Spawns the SOCKS5 ServerSocket listening on PROXY_PORT.
     */
    private fun startServer(network: Network) {
        acquireWifiLock()
        startMetricsReporter()

        serverJob = serviceScope.launch {
            try {
                // Binding to 0.0.0.0:1080 allows accepting clients from Hotspot (192.168.43.1),
                // USB Tethering (192.168.42.129), or local loopback.
                val socket = ServerSocket()
                socket.reuseAddress = true
                socket.bind(InetSocketAddress("0.0.0.0", PROXY_PORT))
                serverSocket = socket

                _status.value = BridgeStatus.RUNNING
                _lastError.value = null
                updateNotification("SOCKS5 Bridge Active on port $PROXY_PORT (Relaying via Wi-Fi)")
                Log.i(TAG, "SOCKS5 Proxy server successfully listening on 0.0.0.0:$PROXY_PORT")

                while (isActive) {
                    val clientSocket = try {
                        socket.accept()
                    } catch (e: IOException) {
                        if (isActive) Log.e(TAG, "ServerSocket.accept() exception: ${e.message}")
                        break
                    }

                    // Apply socket optimizations immediately upon accept
                    configureSocket(clientSocket)

                    // Security: Verify that the incoming peer is on a local/private subnet
                    if (!isPrivateOrLocalAddress(clientSocket.inetAddress)) {
                        Log.w(TAG, "Unauthorized non-private connection attempt from ${clientSocket.inetAddress}")
                        clientSocket.closeQuietly()
                        continue
                    }

                    // Verify upstream Wi-Fi network is still valid
                    val currentNetwork = networkManager.getAuthenticatedWifiNetwork() ?: network

                    val connId = connectionCounter.incrementAndGet()
                    activeSockets.add(clientSocket)
                    _activeConnections.value = activeSockets.size

                    launch {
                        try {
                            handleSocksClient(connId, clientSocket, currentNetwork)
                        } finally {
                            activeSockets.remove(clientSocket)
                            _activeConnections.value = activeSockets.size
                        }
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "Fatal error starting SOCKS5 server on port $PROXY_PORT", e)
                _lastError.value = "Failed to bind SOCKS5 server: ${e.message}"
                _status.value = BridgeStatus.ERROR
                updateNotification("Bridge Error: ${e.message}")
            }
        }
    }

    /**
     * Handles the complete RFC 1928 SOCKS5 handshake and connection tunneling for a client.
     */
    private suspend fun handleSocksClient(connId: Long, clientSocket: Socket, upstreamNetwork: Network) {
        clientSocket.use { client ->
            try {
                client.soTimeout = HANDSHAKE_TIMEOUT_MS
                val clientIn = client.getInputStream()
                val clientOut = client.getOutputStream()

                // Step 1: Authentication Negotiation Handshake
                if (!negotiateAuthentication(connId, clientIn, clientOut)) {
                    return
                }

                // Step 2: Read Client Connection Request
                val request = readSocksRequest(connId, clientIn, clientOut) ?: return

                Log.i(TAG, "[$connId] SOCKS5 Request: CONNECT to ${request.host}:${request.port}")

                // Step 3: Establish Outbound Upstream Socket with Explicit Wi-Fi Binding
                val outboundSocket = try {
                    connectOutboundViaWifi(upstreamNetwork, request.host, request.port)
                } catch (e: SocketTimeoutException) {
                    Log.w(TAG, "[$connId] Outbound connection to ${request.host}:${request.port} timed out")
                    sendSocksReply(clientOut, REP_HOST_UNREACHABLE)
                    return
                } catch (e: IOException) {
                    Log.w(TAG, "[$connId] Outbound connection failed: ${e.message}")
                    sendSocksReply(clientOut, REP_NETWORK_UNREACHABLE)
                    return
                }

                outboundSocket.use { target ->
                    activeSockets.add(target)
                    try {
                        // Inform client that tunnel is established
                        sendSocksReply(clientOut, REP_SUCCEEDED, target.localAddress, target.localPort)
                        client.soTimeout = 0 // Remove timeout for active streaming
                        target.soTimeout = 0

                        Log.i(TAG, "[$connId] SOCKS5 tunnel established successfully to ${request.host}:${request.port}")

                        // Step 4: High-Throughput Bidirectional Data Pipe
                        relayStreams(connId, client, target)
                    } finally {
                        activeSockets.remove(target)
                    }
                }
            } catch (e: SocketTimeoutException) {
                Log.w(TAG, "[$connId] SOCKS5 Handshake timed out")
            } catch (e: IOException) {
                Log.w(TAG, "[$connId] Client connection reset/closed: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "[$connId] Unexpected error during client relay", e)
            } finally {
                Log.i(TAG, "[$connId] Connection completed and closed")
            }
        }
    }

    /**
     * Performs SOCKS5 authentication method negotiation (RFC 1928 Section 3).
     */
    private fun negotiateAuthentication(connId: Long, input: InputStream, output: OutputStream): Boolean {
        val version = input.read()
        if (version != SOCKS_VERSION) {
            Log.w(TAG, "[$connId] Unsupported SOCKS version: $version (expected 5)")
            return false
        }

        val numMethods = input.read()
        if (numMethods <= 0) return false

        val methods = ByteArray(numMethods)
        readExact(input, methods)

        // Check if "No Authentication" (0x00) is supported by the client
        val supportsNoAuth = methods.any { it.toInt() == AUTH_NO_AUTH }
        if (!supportsNoAuth) {
            Log.w(TAG, "[$connId] Client did not offer NO_AUTH method")
            output.write(byteArrayOf(SOCKS_VERSION.toByte(), AUTH_NO_ACCEPTABLE.toByte()))
            output.flush()
            return false
        }

        // Accept NO_AUTH
        output.write(byteArrayOf(SOCKS_VERSION.toByte(), AUTH_NO_AUTH.toByte()))
        output.flush()
        return true
    }

    private data class SocksRequest(val host: String, val port: Int)

    /**
     * Parses the SOCKS5 Command Request (RFC 1928 Section 4).
     */
    private fun readSocksRequest(connId: Long, input: InputStream, output: OutputStream): SocksRequest? {
        val version = input.read()
        val cmd = input.read()
        input.read() // RSV (Reserved, must be 0x00)
        val atyp = input.read()

        if (version != SOCKS_VERSION) {
            Log.w(TAG, "[$connId] Invalid request version: $version")
            return null
        }

        if (cmd != CMD_CONNECT) {
            Log.w(TAG, "[$connId] Unsupported command: $cmd (only CONNECT 0x01 is supported)")
            sendSocksReply(output, REP_COMMAND_NOT_SUPPORTED)
            return null
        }

        val host: String = when (atyp) {
            ATYP_IPV4 -> {
                val ipBytes = ByteArray(4)
                readExact(input, ipBytes)
                InetAddress.getByAddress(ipBytes).hostAddress ?: return null
            }
            ATYP_DOMAIN -> {
                val length = input.read()
                if (length <= 0) return null
                val domainBytes = ByteArray(length)
                readExact(input, domainBytes)
                String(domainBytes, Charsets.US_ASCII)
            }
            ATYP_IPV6 -> {
                val ipBytes = ByteArray(16)
                readExact(input, ipBytes)
                InetAddress.getByAddress(ipBytes).hostAddress ?: return null
            }
            else -> {
                Log.w(TAG, "[$connId] Unsupported address type: $atyp")
                sendSocksReply(output, REP_ATYP_NOT_SUPPORTED)
                return null
            }
        }

        val portHi = input.read()
        val portLo = input.read()
        if (portHi < 0 || portLo < 0) return null
        val port = (portHi shl 8) or portLo

        return SocksRequest(host, port)
    }

    /**
     * Resolves target host and binds the outbound socket strictly to the authenticated Wi-Fi Network.
     */
    private suspend fun connectOutboundViaWifi(network: Network, host: String, port: Int): Socket =
        withContext(Dispatchers.IO) {
            // Step 1: Upstream DNS Resolution (Cached and bound)
            val resolvedAddress = networkManager.resolveHostOnUpstream(host, network)
                ?: throw IOException("Upstream DNS resolution failed for '$host'")

            // Step 2: Create raw socket, tune TCP options and bind explicitly to Wi-Fi Network
            val outboundSocket = Socket()
            configureSocket(outboundSocket)
            val bound = networkManager.bindSocketToUpstream(outboundSocket, network)
            if (!bound) {
                outboundSocket.closeQuietly()
                throw IOException("Failed to bind socket to authenticated Wi-Fi Network")
            }

            // Step 3: Connect to destination
            outboundSocket.connect(InetSocketAddress(resolvedAddress, port), CONNECT_TIMEOUT_MS)
            outboundSocket
        }

    /**
     * Transmits SOCKS5 Response packet back to client.
     */
    private fun sendSocksReply(
        output: OutputStream,
        replyCode: Int,
        boundAddr: InetAddress? = null,
        boundPort: Int = 0
    ) {
        try {
            val addrBytes = boundAddr?.address ?: byteArrayOf(0, 0, 0, 0)
            val atyp = if (addrBytes.size == 16) ATYP_IPV6.toByte() else ATYP_IPV4.toByte()
            val portHi = (boundPort shr 8).toByte()
            val portLo = (boundPort and 0xFF).toByte()

            val response = ByteArray(4 + addrBytes.size + 2)
            response[0] = SOCKS_VERSION.toByte()
            response[1] = replyCode.toByte()
            response[2] = 0x00 // RSV
            response[3] = atyp
            System.arraycopy(addrBytes, 0, response, 4, addrBytes.size)
            response[response.size - 2] = portHi
            response[response.size - 1] = portLo

            output.write(response)
            output.flush()
        } catch (e: IOException) {
            Log.w(TAG, "Failed to send SOCKS reply: ${e.message}")
        }
    }

    /**
     * Concurrently pipes data bidirectionally between client socket and outbound target socket
     * using structured concurrency and pooled memory buffers.
     */
    private suspend fun relayStreams(connId: Long, client: Socket, target: Socket) = coroutineScope {
        val clientIn = client.getInputStream()
        val clientOut = client.getOutputStream()
        val targetIn = target.getInputStream()
        val targetOut = target.getOutputStream()

        val clientTx = AtomicLong(0)
        val clientRx = AtomicLong(0)

        val uploadJob = launch(Dispatchers.IO) {
            pumpData(clientIn, targetOut) { bytes ->
                clientTx.addAndGet(bytes.toLong())
                totalTx.addAndGet(bytes.toLong())
            }
            try { target.shutdownOutput() } catch (_: Exception) {}
            try { client.shutdownInput() } catch (_: Exception) {}
        }

        val downloadJob = launch(Dispatchers.IO) {
            pumpData(targetIn, clientOut) { bytes ->
                clientRx.addAndGet(bytes.toLong())
                totalRx.addAndGet(bytes.toLong())
            }
            try { client.shutdownOutput() } catch (_: Exception) {}
            try { target.shutdownInput() } catch (_: Exception) {}
        }

        uploadJob.join()
        downloadJob.join()

        Log.i(TAG, "[$connId] Tunnel finished. Tx: ${clientTx.get()} bytes, Rx: ${clientRx.get()} bytes")
    }

    /**
     * Streams data from input to output using the high-performance buffer pool.
     */
    private fun pumpData(input: InputStream, output: OutputStream, onBytes: (Int) -> Unit) {
        val buffer = BufferPool.acquire()
        try {
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                output.write(buffer, 0, read)
                onBytes(read)
            }
        } catch (_: IOException) {
            // Normal when either side closes the connection
        } finally {
            BufferPool.release(buffer)
        }
    }

    private fun readExact(input: InputStream, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read == -1) throw IOException("Premature EOF during SOCKS handshake")
            offset += read
        }
    }

    private fun isPrivateOrLocalAddress(address: InetAddress?): Boolean {
        if (address == null) return false
        if (address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress) {
            return true
        }
        val bytes = address.address
        if (bytes.size != 4) return false
        val a = bytes[0].toInt() and 0xFF
        val b = bytes[1].toInt() and 0xFF
        return (a == 10) || (a == 172 && b in 16..31) || (a == 192 && b == 168)
    }

    private fun Socket.closeQuietly() {
        try {
            close()
        } catch (_: Exception) {}
    }

    private fun shutdownServer(reason: String) {
        Log.i(TAG, "Stopping SOCKS5 Server: $reason")
        stopMetricsReporter()
        releaseWifiLock()

        serverJob?.cancel()
        serverJob = null
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing server socket: ${e.message}")
        }
        serverSocket = null

        // Force close all open connections
        activeSockets.forEach { it.closeQuietly() }
        activeSockets.clear()
        _activeConnections.value = 0
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Wi-Fi Proxy Bridge Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Maintains the authenticated SOCKS5 proxy network bridge."
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Authenticated Wi-Fi Bridge")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }
}

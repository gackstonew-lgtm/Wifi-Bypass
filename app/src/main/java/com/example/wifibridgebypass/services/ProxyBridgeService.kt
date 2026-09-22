package com.example.wifibridgebypass.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.Network
import android.os.IBinder
import android.util.Log
import com.example.wifibridgebypass.MainActivity
import com.example.wifibridgebypass.utils.WifiState
import com.example.wifibridgebypass.utils.WifiUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Local SOCKS5 relay. Devices that connect to this phone's hotspot / USB
 * tether can point their SOCKS5 client at this device's LAN IP on
 * [PROXY_PORT] and have their TCP traffic relayed out over this device's
 * already-connected Wi-Fi network — the same thing native Wi-Fi tethering
 * does, implemented in the app layer for devices where native Wi-Fi
 * tethering isn't available.
 *
 * Every outbound connection is explicitly bound to the active Wi-Fi
 * [Network] via [Network.bindSocket] so traffic can never silently fall
 * back to cellular data. If Wi-Fi becomes unvalidated, loses internet, or a
 * captive portal reappears, the service tears down every open connection
 * and stops itself.
 */
class ProxyBridgeService : Service() {

    enum class Status { STOPPED, WAITING_FOR_WIFI, RUNNING, ERROR }

    companion object {
        const val PROXY_PORT = 1080
        const val CHANNEL_ID = "ProxyBridgeChannel"
        const val NOTIFICATION_ID = 1

        private const val TAG = "ProxyBridge"
        private const val SOCKS_VERSION = 0x05
        private const val CMD_CONNECT = 0x01
        private const val ATYP_IPV4 = 0x01
        private const val ATYP_DOMAIN = 0x03
        private const val ATYP_IPV6 = 0x04
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val HANDSHAKE_TIMEOUT_MS = 8_000

        // Reply codes (RFC 1928 section 6)
        private const val REP_SUCCEEDED = 0x00
        private const val REP_GENERAL_FAILURE = 0x01
        private const val REP_NETWORK_UNREACHABLE = 0x03
        private const val REP_HOST_UNREACHABLE = 0x04
        private const val REP_CONN_REFUSED = 0x05
        private const val REP_COMMAND_NOT_SUPPORTED = 0x07
        private const val REP_ATYP_NOT_SUPPORTED = 0x08

        private val _status = MutableStateFlow(Status.STOPPED)
        val status: StateFlow<Status> = _status.asStateFlow()

        private val _activeConnections = MutableStateFlow(0)
        val activeConnections: StateFlow<Int> = _activeConnections.asStateFlow()

        private val _lastError = MutableStateFlow<String?>(null)
        val lastError: StateFlow<String?> = _lastError.asStateFlow()
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var serverJob: Job? = null
    private var wifiWatcherJob: Job? = null
    private var serverSocket: ServerSocket? = null

    // Tracks live client sockets so we can force-close everything if Wi-Fi drops.
    private val activeSockets = ConcurrentHashMap.newKeySet<Socket>()
    private val connectionCounter = AtomicLong(0)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Starting…"))
        watchWifiState()
    }

    override fun onDestroy() {
        super.onDestroy()
        shutdownProxy("Service destroyed")
        wifiWatcherJob?.cancel()
        serviceScope.cancel()
        _status.value = Status.STOPPED
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ------------------------------------------------------------------
    // Wi-Fi lifecycle
    // ------------------------------------------------------------------

    private fun watchWifiState() {
        wifiWatcherJob = serviceScope.launch {
            WifiUtils.observeWifiState(applicationContext).collect { state ->
                when (state) {
                    is WifiState.Ready -> {
                        if (serverJob == null) {
                            startProxyServer(state.network)
                        }
                    }
                    WifiState.Disconnected, WifiState.NoInternet, WifiState.CaptivePortal -> {
                        if (serverJob != null) {
                            Log.i(TAG, "Wi-Fi no longer valid (${state::class.simpleName}); stopping proxy")
                            shutdownProxy("Wi-Fi disconnected or invalid")
                        }
                        _status.value = Status.WAITING_FOR_WIFI
                        updateNotification(
                            when (state) {
                                WifiState.CaptivePortal -> "Waiting for Wi-Fi sign-in to complete"
                                WifiState.NoInternet -> "Wi-Fi connected, no internet yet"
                                else -> "Waiting for Wi-Fi connection"
                            }
                        )
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Server accept loop
    // ------------------------------------------------------------------

    private fun startProxyServer(network: Network) {
        serverJob = serviceScope.launch {
            try {
                val socket = ServerSocket(PROXY_PORT)
                serverSocket = socket
                _status.value = Status.RUNNING
                _lastError.value = null
                updateNotification("Bridge active — relaying via Wi-Fi on port $PROXY_PORT")
                Log.i(TAG, "SOCKS5 proxy listening on port $PROXY_PORT")

                while (isActive) {
                    val client = try {
                        socket.accept()
                    } catch (e: IOException) {
                        if (isActive) Log.e(TAG, "accept() failed: ${e.message}")
                        break
                    }

                    if (!isLocalNetworkPeer(client)) {
                        Log.w(TAG, "Rejecting connection from non-local peer ${client.inetAddress}")
                        client.closeQuietly()
                        continue
                    }

                    // Re-check Wi-Fi is still ready for every new connection; the
                    // network handle can change (e.g. Wi-Fi reconnects) between
                    // accepts.
                    val currentNetwork = WifiUtils.getReadyWifiNetwork(applicationContext)
                    if (currentNetwork == null) {
                        Log.w(TAG, "No validated Wi-Fi network available; refusing new client")
                        client.closeQuietly()
                        continue
                    }

                    val id = connectionCounter.incrementAndGet()
                    activeSockets.add(client)
                    _activeConnections.value = activeSockets.size
                    launch {
                        try {
                            handleClient(id, client, currentNetwork)
                        } finally {
                            activeSockets.remove(client)
                            _activeConnections.value = activeSockets.size
                        }
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "Failed to bind proxy server on port $PROXY_PORT", e)
                _lastError.value = "Could not start proxy: ${e.message}"
                _status.value = Status.ERROR
                updateNotification("Bridge failed to start: ${e.message}")
            }
        }
    }

    /**
     * Only local/LAN peers may use the proxy (loopback + the private ranges
     * used by Android hotspot / USB tethering / Wi-Fi Direct). This keeps
     * the relay from being reachable by anyone outside the phone's own
     * local network.
     */
    private fun isLocalNetworkPeer(socket: Socket): Boolean {
        val addr = socket.inetAddress ?: return false
        if (addr.isLoopbackAddress || addr.isLinkLocalAddress) return true
        val bytes = addr.address
        if (bytes.size != 4) return false // only allow IPv4 LAN peers
        val a = bytes[0].toInt() and 0xFF
        val b = bytes[1].toInt() and 0xFF
        return when (a) {
            10 -> true
            172 -> b in 16..31
            192 -> b == 168
            else -> false
        }
    }

    // ------------------------------------------------------------------
    // SOCKS5 per-connection handling
    // ------------------------------------------------------------------

    private suspend fun handleClient(id: Long, client: Socket, network: Network) {
        client.use { socket ->
            try {
                socket.soTimeout = HANDSHAKE_TIMEOUT_MS
                val input = socket.getInputStream()
                val output = socket.getOutputStream()

                if (!performGreeting(id, input, output)) return
                val target = readConnectRequest(id, input, output) ?: return

                Log.i(TAG, "[$id] CONNECT request for ${target.host}:${target.port}")

                val outbound = try {
                    connectViaWifi(network, target.host, target.port)
                } catch (e: SocketTimeoutException) {
                    Log.e(TAG, "[$id] Connect timed out: ${target.host}:${target.port}")
                    sendReply(output, REP_HOST_UNREACHABLE)
                    return
                } catch (e: IOException) {
                    Log.e(TAG, "[$id] Connect failed: ${e.message}")
                    sendReply(output, REP_NETWORK_UNREACHABLE)
                    return
                }

                outbound.use { targetSocket ->
                    activeSockets.add(targetSocket)
                    try {
                        sendReply(output, REP_SUCCEEDED)
                        socket.soTimeout = 0 // no timeout once relaying
                        Log.i(TAG, "[$id] Tunnel established, relaying")
                        pipeBidirectionally(id, socket, targetSocket)
                    } finally {
                        activeSockets.remove(targetSocket)
                    }
                }
            } catch (e: SocketTimeoutException) {
                Log.w(TAG, "[$id] Handshake timed out")
            } catch (e: IOException) {
                Log.w(TAG, "[$id] Client connection error: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "[$id] Unexpected error handling client", e)
            } finally {
                Log.i(TAG, "[$id] Connection closed")
            }
        }
    }

    private fun performGreeting(id: Long, input: InputStream, output: OutputStream): Boolean {
        val ver = input.read()
        if (ver != SOCKS_VERSION) {
            Log.w(TAG, "[$id] Unsupported SOCKS version: $ver")
            return false
        }
        val nMethods = input.read()
        if (nMethods < 0) return false
        val methods = ByteArray(nMethods)
        readFully(input, methods)
        // We only offer "no authentication required" (0x00). A production
        // deployment that needs per-user access control should advertise
        // and implement username/password auth (0x02) here instead.
        output.write(byteArrayOf(SOCKS_VERSION.toByte(), 0x00))
        output.flush()
        return true
    }

    private data class Target(val host: String, val port: Int)

    private fun readConnectRequest(id: Long, input: InputStream, output: OutputStream): Target? {
        val ver = input.read()
        val cmd = input.read()
        input.read() // RSV, always 0x00
        val atyp = input.read()

        if (ver != SOCKS_VERSION) {
            Log.w(TAG, "[$id] Bad request version: $ver")
            return null
        }
        if (cmd != CMD_CONNECT) {
            Log.w(TAG, "[$id] Unsupported SOCKS command: $cmd")
            sendReply(output, REP_COMMAND_NOT_SUPPORTED)
            return null
        }

        val host: String = when (atyp) {
            ATYP_IPV4 -> {
                val addr = ByteArray(4)
                readFully(input, addr)
                InetAddress.getByAddress(addr).hostAddress
            }
            ATYP_DOMAIN -> {
                val len = input.read()
                val nameBytes = ByteArray(len)
                readFully(input, nameBytes)
                String(nameBytes, Charsets.US_ASCII)
            }
            ATYP_IPV6 -> {
                val addr = ByteArray(16)
                readFully(input, addr)
                InetAddress.getByAddress(addr).hostAddress
            }
            else -> {
                Log.w(TAG, "[$id] Unsupported address type: $atyp")
                sendReply(output, REP_ATYP_NOT_SUPPORTED)
                return null
            }
        }

        val portHi = input.read()
        val portLo = input.read()
        val port = (portHi shl 8) or portLo

        return Target(host, port)
    }

    /** Resolves + connects a fresh outbound socket, bound to the Wi-Fi network. */
    private suspend fun connectViaWifi(network: Network, host: String, port: Int): Socket =
        withContext(Dispatchers.IO) {
            val resolved: InetAddress = withTimeoutOrNull(5_000) {
                // Resolve using the Wi-Fi network specifically so DNS also
                // goes over Wi-Fi, not whatever the default network is.
                network.getAllByName(host).firstOrNull { it is Inet4Address }
                    ?: network.getAllByName(host).firstOrNull()
            } ?: throw IOException("DNS resolution failed or timed out for $host")

            val outSocket = Socket()
            network.bindSocket(outSocket) // <-- forces this socket onto Wi-Fi
            outSocket.connect(InetSocketAddress(resolved, port), CONNECT_TIMEOUT_MS)
            outSocket
        }

    private fun sendReply(output: OutputStream, rep: Int) {
        try {
            output.write(
                byteArrayOf(
                    SOCKS_VERSION.toByte(), rep.toByte(), 0x00,
                    ATYP_IPV4.toByte(), 0, 0, 0, 0, 0, 0
                )
            )
            output.flush()
        } catch (e: IOException) {
            Log.w(TAG, "Failed to send SOCKS reply: ${e.message}")
        }
    }

    private suspend fun pipeBidirectionally(id: Long, a: Socket, b: Socket) {
        val bytesAtoB = AtomicLong(0)
        val bytesBtoA = AtomicLong(0)

        // Either direction ending (client or target closes) tears down both
        // sockets so the other copy loop unblocks from its read() call.
        val clientToTarget = serviceScope.launch {
            copyStream(a.getInputStream(), b.getOutputStream(), bytesAtoB)
            a.closeQuietly()
            b.closeQuietly()
        }
        val targetToClient = serviceScope.launch {
            copyStream(b.getInputStream(), a.getOutputStream(), bytesBtoA)
            a.closeQuietly()
            b.closeQuietly()
        }

        clientToTarget.join()
        targetToClient.join()
        // Metadata-only logging — never log payload contents, only sizes.
        Log.i(TAG, "[$id] Relay finished: ${bytesAtoB.get()}B up / ${bytesBtoA.get()}B down")
    }

    private fun copyStream(input: InputStream, output: OutputStream, counter: AtomicLong) {
        val buffer = ByteArray(8 * 1024)
        try {
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                output.write(buffer, 0, read)
                output.flush()
                counter.addAndGet(read.toLong())
            }
        } catch (e: IOException) {
            // Expected once either socket is closed from the other direction.
        }
    }

    private fun Socket.closeQuietly() {
        try {
            close()
        } catch (_: IOException) { }
    }

    private fun readFully(input: InputStream, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read == -1) throw IOException("Unexpected end of stream")
            offset += read
        }
    }

    // ------------------------------------------------------------------
    // Shutdown
    // ------------------------------------------------------------------

    private fun shutdownProxy(reason: String) {
        Log.i(TAG, "Shutting down proxy: $reason")
        serverJob?.cancel()
        serverJob = null
        try {
            serverSocket?.close()
        } catch (e: IOException) {
            Log.w(TAG, "Error closing server socket: ${e.message}")
        }
        serverSocket = null

        // Force-close every in-flight connection so nothing lingers on a
        // stale network.
        activeSockets.forEach { it.closeQuietly() }
        activeSockets.clear()
        _activeConnections.value = 0
    }

    // ------------------------------------------------------------------
    // Notification
    // ------------------------------------------------------------------

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Wi-Fi Bridge",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Wi-Fi Bridge")
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

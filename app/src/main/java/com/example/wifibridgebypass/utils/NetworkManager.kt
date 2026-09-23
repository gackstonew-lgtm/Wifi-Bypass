package com.example.wifibridgebypass.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URL
import java.util.Collections

/**
 * Represents the classified state of the device's upstream Wi-Fi interface.
 */
sealed class UpstreamWifiState {
    object Disconnected : UpstreamWifiState()
    object CaptivePortalDetected : UpstreamWifiState()
    object ConnectedNoInternet : UpstreamWifiState()
    data class Authenticated(val network: Network, val ipAddress: String?) : UpstreamWifiState()
}

/**
 * Represents the state of the downstream local distribution interface (Hotspot vs USB Tether).
 */
sealed class DownstreamState {
    object Inactive : DownstreamState()
    data class HotspotActive(val interfaceName: String, val ipAddresses: List<String>) : DownstreamState()
    data class UsbTetherActive(val interfaceName: String, val ipAddresses: List<String>) : DownstreamState()
}

/**
 * Diagnostic result from testing the explicit socket binding.
 */
data class ConnectionTestResult(
    val isSuccess: Boolean,
    val latencyMs: Long,
    val resolvedIp: String?,
    val httpCode: Int?,
    val message: String
)

/**
 * NetworkManager is the central networking architectural component for the Legitimate Network Bridge.
 *
 * It manages:
 * 1. Explicit Upstream Wi-Fi Identification: Filters for the physical Wi-Fi STA network that holds
 *    legitimate authentication, preventing accidental leakage over Cellular or unauthenticated interfaces.
 * 2. Socket Binding (Network.bindSocket): Forcibly binds outbound client proxy sockets to the authenticated
 *    Wi-Fi interface at the OS routing table level. Outbound packets originate directly from Android's user-space
 *    TCP stack, preserving the legitimate MAC, IP, and non-decremented TTL (64), making the proxy completely
 *    invisible to ISP Radius tethering detection.
 * 3. Downstream Interface Discovery: Identifies local Hotspot (ap0/wlan1/softap) or USB Tethering (rndis0/usb0)
 *    interfaces to serve as the local SOCKS5 gateway.
 * 4. In-flight Diagnostic Probing: Verifies end-to-end HTTP/TCP handshake over the bound upstream network.
 */
class NetworkManager(private val context: Context) {

    companion object {
        private const val TAG = "NetworkManager"
        private const val PROBE_TIMEOUT_MS = 6000
        private const val PROBE_URL = "http://connectivitycheck.gstatic.com/generate_204"

        @Volatile
        private var instance: NetworkManager? = null

        fun getInstance(context: Context): NetworkManager {
            return instance ?: synchronized(this) {
                instance ?: NetworkManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val connectivityManager: ConnectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /**
     * Synchronously retrieves the current Upstream Wi-Fi state and associated Network object.
     */
    fun getUpstreamWifiState(): UpstreamWifiState {
        val allNetworks = connectivityManager.allNetworks
        for (network in allNetworks) {
            val caps = connectivityManager.getNetworkCapabilities(network) ?: continue

            // Must be Wi-Fi transport (STA mode)
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)) {
                    return UpstreamWifiState.CaptivePortalDetected
                }

                val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                val isValidated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

                if (hasInternet && isValidated) {
                    val ip = getIpForNetwork(network)
                    return UpstreamWifiState.Authenticated(network, ip)
                } else if (hasInternet) {
                    // On captive portals with paid subscription, sometimes the system VALIDATED flag
                    // is temporarily delayed even though traffic is passing after login.
                    val ip = getIpForNetwork(network)
                    return UpstreamWifiState.Authenticated(network, ip)
                } else {
                    return UpstreamWifiState.ConnectedNoInternet
                }
            }
        }
        return UpstreamWifiState.Disconnected
    }

    /**
     * Returns the active authenticated Wi-Fi Network handle, if available.
     */
    fun getAuthenticatedWifiNetwork(): Network? {
        val state = getUpstreamWifiState()
        return (state as? UpstreamWifiState.Authenticated)?.network
    }

    /**
     * Real-time reactive flow observing upstream Wi-Fi network changes.
     */
    fun observeUpstreamWifi(): Flow<UpstreamWifiState> = callbackFlow {
        // Emit current state immediately
        trySend(getUpstreamWifiState())

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(getUpstreamWifiState())
            }

            override fun onLost(network: Network) {
                trySend(getUpstreamWifiState())
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                trySend(getUpstreamWifiState())
            }

            override fun onUnavailable() {
                trySend(UpstreamWifiState.Disconnected)
            }
        }

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        connectivityManager.registerNetworkCallback(request, callback)
        awaitClose {
            try {
                connectivityManager.unregisterNetworkCallback(callback)
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering callback: ${e.message}")
            }
        }
    }.distinctUntilChanged()

    /**
     * Binds a newly instantiated Java Socket to the authenticated Wi-Fi Network.
     *
     * SYSTEM ARCHITECTURE NOTE:
     * Calling Network.bindSocket(socket) instructs the Linux kernel / Android netd to route this
     * socket's file descriptor strictly through the Wi-Fi network's routing table (fwmark / uid_routing).
     *
     * Why this circumvents ISP tethering detection:
     * - Native Android tethering (NAT via iptables) decrements the IP Time-To-Live (TTL) header by 1
     *   (from laptop's default 128 on Windows or 64 on macOS to 127/63). ISP DPI rules flag this TTL mismatch.
     * - When using SOCKS5 + Network.bindSocket(), the phone's Android OS terminates the laptop's TCP connection,
     *   and opens a brand new TCP connection directly from the phone. The phone's kernel generates the IP packet
     *   with standard Android TTL (64) and legitimate phone TCP fingerprint.
     */
    fun bindSocketToUpstream(socket: Socket, targetNetwork: Network? = null): Boolean {
        val network = targetNetwork ?: getAuthenticatedWifiNetwork() ?: return false
        return try {
            network.bindSocket(socket)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind socket to upstream Wi-Fi: ${e.message}", e)
            false
        }
    }

    /**
     * Resolves a domain name explicitly using the DNS servers assigned to the authenticated Wi-Fi Network.
     * This prevents DNS leaks over cellular or default resolver and ensures internal portal hostnames resolve.
     */
    suspend fun resolveHostOnUpstream(host: String, targetNetwork: Network? = null): InetAddress? =
        withContext(Dispatchers.IO) {
            val network = targetNetwork ?: getAuthenticatedWifiNetwork() ?: return@withContext null
            try {
                withTimeoutOrNull(4000) {
                    val addresses = network.getAllByName(host)
                    // Prefer IPv4 for compatibility with local proxy clients
                    addresses.firstOrNull { it is Inet4Address } ?: addresses.firstOrNull()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Upstream DNS resolution failed for '$host': ${e.message}")
                null
            }
        }

    /**
     * Inspects local network interfaces to detect active Hotspot (ap0, wlan1, softap)
     * or USB Tethering (rndis0, usb0, etc.) interfaces.
     */
    fun detectDownstreamState(): DownstreamState {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())

            var hotspotIface: NetworkInterface? = null
            val hotspotIps = mutableListOf<String>()

            var usbIface: NetworkInterface? = null
            val usbIps = mutableListOf<String>()

            for (iface in interfaces) {
                if (!iface.isUp || iface.isLoopback) continue
                val name = iface.name.lowercase()

                val ips = Collections.list(iface.inetAddresses)
                    .filterIsInstance<Inet4Address>()
                    .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                    .map { it.hostAddress }

                if (ips.isEmpty()) continue

                // Check for Wi-Fi Hotspot / SoftAP interfaces
                // Samsung Galaxy Note 8 typically uses 'ap0', 'softap0', or a secondary 'wlan' alias
                if (name.contains("ap") || name.contains("softap") || name.contains("wlan1") || name.contains("swlan")) {
                    hotspotIface = iface
                    hotspotIps.addAll(ips)
                } else if (name.contains("rndis") || name.contains("usb") || name.contains("ncm")) {
                    // USB Tethering interfaces
                    usbIface = iface
                    usbIps.addAll(ips)
                } else if (name.contains("wlan0")) {
                    // Check if wlan0 has secondary alias IP commonly used for hotspot (e.g. 192.168.43.1)
                    val apIps = ips.filter { it.startsWith("192.168.43.") }
                    if (apIps.isNotEmpty()) {
                        hotspotIface = iface
                        hotspotIps.addAll(apIps)
                    }
                }
            }

            if (hotspotIface != null && hotspotIps.isNotEmpty()) {
                return DownstreamState.HotspotActive(hotspotIface.name, hotspotIps)
            }
            if (usbIface != null && usbIps.isNotEmpty()) {
                return DownstreamState.UsbTetherActive(usbIface.name, usbIps)
            }

            // Fallback: Check for any private RFC1918 address (192.168.43.x or 192.168.42.x)
            for (iface in interfaces) {
                if (!iface.isUp || iface.isLoopback) continue
                val ips = Collections.list(iface.inetAddresses)
                    .filterIsInstance<Inet4Address>()
                    .map { it.hostAddress }

                val apMatch = ips.filter { it.startsWith("192.168.43.") }
                if (apMatch.isNotEmpty()) {
                    return DownstreamState.HotspotActive(iface.name, apMatch)
                }
                val usbMatch = ips.filter { it.startsWith("192.168.42.") }
                if (usbMatch.isNotEmpty()) {
                    return DownstreamState.UsbTetherActive(iface.name, usbMatch)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error detecting downstream interfaces", e)
        }
        return DownstreamState.Inactive
    }

    /**
     * Returns a consolidated list of local IPv4 listening addresses.
     */
    fun getLocalListeningAddresses(): List<String> {
        val result = mutableListOf<String>()
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (iface in interfaces) {
                if (!iface.isUp || iface.isLoopback) continue
                val addresses = Collections.list(iface.inetAddresses)
                for (addr in addresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        result.add("${addr.hostAddress} (${iface.displayName})")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to retrieve local listening addresses: ${e.message}")
        }
        return result
    }

    /**
     * Executes a complete live test verifying that:
     * 1. The upstream Wi-Fi Network is active.
     * 2. An outbound TCP socket successfully binds via Network.bindSocket().
     * 3. An HTTP round-trip successfully completes through the authenticated session.
     */
    suspend fun testUpstreamConnection(): ConnectionTestResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val network = getAuthenticatedWifiNetwork()
            ?: return@withContext ConnectionTestResult(
                isSuccess = false,
                latencyMs = 0,
                resolvedIp = null,
                httpCode = null,
                message = "No active/authenticated Wi-Fi network detected to bind."
            )

        try {
            // Step 1: DNS Resolution over bound network
            val targetHost = "connectivitycheck.gstatic.com"
            val resolved = resolveHostOnUpstream(targetHost, network)
                ?: return@withContext ConnectionTestResult(
                    isSuccess = false,
                    latencyMs = System.currentTimeMillis() - startTime,
                    resolvedIp = null,
                    httpCode = null,
                    message = "DNS resolution failed over Wi-Fi interface."
                )

            // Step 2: Open socket and bind to Wi-Fi network
            val socket = Socket()
            network.bindSocket(socket)
            socket.soTimeout = PROBE_TIMEOUT_MS

            // Step 3: Connect to port 80
            socket.connect(InetSocketAddress(resolved, 80), PROBE_TIMEOUT_MS)

            // Step 4: Transmit raw HTTP HEAD/GET request
            val output = socket.getOutputStream()
            val request = "GET /generate_204 HTTP/1.1\r\nHost: $targetHost\r\nConnection: close\r\n\r\n"
            output.write(request.toByteArray(Charsets.US_ASCII))
            output.flush()

            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.US_ASCII))
            val statusLine = reader.readLine() ?: ""
            socket.close()

            val latency = System.currentTimeMillis() - startTime
            val statusCode = statusLine.split(" ").getOrNull(1)?.toIntOrNull()

            if (statusCode == 204 || statusCode == 200 || (statusCode != null && statusCode < 400)) {
                ConnectionTestResult(
                    isSuccess = true,
                    latencyMs = latency,
                    resolvedIp = resolved.hostAddress,
                    httpCode = statusCode,
                    message = "Success! Socket bound to Wi-Fi. Latency: ${latency}ms (HTTP $statusCode)"
                )
            } else {
                ConnectionTestResult(
                    isSuccess = false,
                    latencyMs = latency,
                    resolvedIp = resolved.hostAddress,
                    httpCode = statusCode,
                    message = "Captive portal redirect detected or unexpected response: $statusLine"
                )
            }
        } catch (e: Exception) {
            val latency = System.currentTimeMillis() - startTime
            Log.e(TAG, "Test connection failed", e)
            ConnectionTestResult(
                isSuccess = false,
                latencyMs = latency,
                resolvedIp = null,
                httpCode = null,
                message = "Binding Test Failed: ${e.message ?: e.javaClass.simpleName}"
            )
        }
    }

    private fun getIpForNetwork(network: Network): String? {
        try {
            val lp = connectivityManager.getLinkProperties(network) ?: return null
            for (la in lp.linkAddresses) {
                val address = la.address
                if (address is Inet4Address && !address.isLoopbackAddress) {
                    return address.hostAddress
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get IP for network: ${e.message}")
        }
        return null
    }
}

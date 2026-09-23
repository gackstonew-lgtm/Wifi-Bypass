package com.example.wifibridgebypass.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.RouteInfo
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
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

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
    val socketBound: Boolean,
    val message: String
)

/**
 * Internal model representing an evaluated Wi-Fi network candidate.
 */
private data class NetworkCandidate(
    val network: Network,
    val capabilities: NetworkCapabilities,
    val linkProperties: LinkProperties?,
    val interfaceName: String,
    val ipAddresses: List<String>,
    val hasDefaultRoute: Boolean,
    val isSoftApInterface: Boolean,
    val score: Int,
    val statusSummary: String
)

/**
 * NetworkManager is the central networking architectural component for the Legitimate Network Bridge.
 *
 * It manages:
 * 1. Deterministic Upstream Wi-Fi STA Selection: Filters and ranks all active Network objects to
 *    reliably distinguish the true Wi-Fi STA connection from Samsung downstream SoftAP/Wi-Fi Sharing
 *    interfaces (such as swlan0, ap0, softap0).
 * 2. Real Usability Verification: Treats Android's VALIDATED capability as metadata, validating real
 *    reachability through socket probes so unvalidated or delayed captive sessions are not discarded.
 * 3. Socket Binding (Network.bindSocket): Forcibly binds outbound client proxy sockets to the authenticated
 *    Wi-Fi interface at the OS routing table level. Outbound packets originate directly from Android's user-space
 *    TCP stack, preserving the legitimate MAC, IP, and non-decremented TTL (64), avoiding tethering detection.
 * 4. Downstream Interface Discovery: Identifies local Hotspot (swlan0/ap0/wlan1) or USB Tethering (rndis0/usb0)
 *    interfaces to serve as the local SOCKS5 gateway.
 * 5. Strict Cellular Isolation: Guarantees that proxy traffic never leaks onto mobile data.
 */
class NetworkManager(private val context: Context) {

    companion object {
        private const val TAG = "NetworkManager"
        private const val PROBE_TIMEOUT_MS = 6000
        private const val PROBE_URL_HOST = "connectivitycheck.gstatic.com"

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

    // Bounded thread-safe DNS resolution cache with TTL to eliminate repetitive upstream DNS queries
    private val dnsCache = ConcurrentHashMap<String, DnsCacheEntry>()
    private val dnsCacheTtlMs = 5 * 60 * 1000L // 5 minutes TTL
    private val maxDnsCacheSize = 256

    private data class DnsCacheEntry(val address: InetAddress, val timestamp: Long)

    fun clearDnsCache() {
        dnsCache.clear()
    }

    /**
     * Deterministically finds and ranks candidate Wi-Fi networks to identify the true upstream STA interface.
     *
     * Samsung devices (such as the Galaxy Note 8) create virtual or secondary Wi-Fi interfaces
     * (e.g., swlan0, ap0) when Mobile Hotspot / Wi-Fi Sharing is enabled.
     * This function iterates through all networks, inspects their capabilities and LinkProperties,
     * penalizes downstream SoftAP interfaces, and returns the highest-ranking upstream STA candidate.
     */
    @Suppress("DEPRECATION")
    private fun findBestUpstreamWifiNetwork(): NetworkCandidate? {
        val allNetworks = connectivityManager.allNetworks
        val activeNet = connectivityManager.activeNetwork
        val candidates = mutableListOf<NetworkCandidate>()

        Log.d(TAG, "Evaluating network candidates (total active networks: ${allNetworks.size})")

        for (network in allNetworks) {
            val caps = connectivityManager.getNetworkCapabilities(network) ?: continue
            val lp = connectivityManager.getLinkProperties(network)

            // Strictly filter out cellular and bluetooth networks
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                Log.d(TAG, "  - Network $network rejected: TRANSPORT_CELLULAR")
                continue
            }
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) {
                Log.d(TAG, "  - Network $network rejected: TRANSPORT_BLUETOOTH")
                continue
            }

            // Must have Wi-Fi transport
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                Log.d(TAG, "  - Network $network rejected: missing TRANSPORT_WIFI")
                continue
            }

            val ifaceName = lp?.interfaceName?.lowercase() ?: ""
            val ips: List<String> = lp?.linkAddresses
                ?.mapNotNull { it.address }
                ?.filterIsInstance<Inet4Address>()
                ?.filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                ?.mapNotNull { it.hostAddress } ?: emptyList()

            val hasDefaultRoute = lp?.routes?.any { route ->
                route.isDefaultRoute || (route.destination.address.isAnyLocalAddress)
            } ?: false

            // Identify downstream SoftAP interfaces on Samsung / Android
            // Common SoftAP interface names: swlan0, ap0, softap0, p2p0, or subnets 192.168.43.x / 192.168.42.x
            val isSoftApName = ifaceName.contains("swlan") ||
                    ifaceName.contains("softap") ||
                    ifaceName.contains("ap0") ||
                    ifaceName.contains("p2p")
            val isHotspotSubnet = ips.any { it.startsWith("192.168.43.") || it.startsWith("192.168.42.") }
            val isSoftAp = isSoftApName || isHotspotSubnet

            // Score calculation
            var score = 0
            val statusParts = mutableListOf<String>()

            if (isSoftAp) {
                score -= 10000 // Disqualify downstream SoftAP interface as upstream STA
                statusParts.add("SoftAP/Hotspot interface (Disqualified as upstream)")
            } else {
                score += 1000 // Base score for non-SoftAP Wi-Fi
                statusParts.add("Wi-Fi STA")
            }

            if (ips.isNotEmpty()) {
                score += 500
                statusParts.add("IPv4: ${ips.joinToString()}")
            } else {
                score -= 300
                statusParts.add("No IPv4")
            }

            if (hasDefaultRoute) {
                score += 400
                statusParts.add("DefaultRoute: Yes")
            }

            if (ifaceName.contains("wlan0") || ifaceName.contains("wlan")) {
                score += 200
                statusParts.add("Interface: $ifaceName")
            }

            if (network == activeNet) {
                score += 400
                statusParts.add("ActiveNetwork: Yes")
            }

            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                score += 300
                statusParts.add("INTERNET: Yes")
            }

            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                score += 200
                statusParts.add("VALIDATED: Yes")
            }

            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)) {
                score += 150
                statusParts.add("CAPTIVE_PORTAL: Yes")
            }

            val candidate = NetworkCandidate(
                network = network,
                capabilities = caps,
                linkProperties = lp,
                interfaceName = ifaceName,
                ipAddresses = ips,
                hasDefaultRoute = hasDefaultRoute,
                isSoftApInterface = isSoftAp,
                score = score,
                statusSummary = statusParts.joinToString(" | ")
            )

            Log.d(TAG, "  - Candidate Network $network ($ifaceName): score=$score [${candidate.statusSummary}]")
            candidates.add(candidate)
        }

        val bestCandidate = candidates.filter { it.score > 0 }.maxByOrNull { it.score }
        if (bestCandidate != null) {
            Log.i(TAG, "Selected best upstream Wi-Fi Network: ${bestCandidate.network} (${bestCandidate.interfaceName}, IPs: ${bestCandidate.ipAddresses}, Score: ${bestCandidate.score})")
        } else {
            Log.w(TAG, "No valid upstream Wi-Fi STA network found among ${candidates.size} candidates")
        }
        return bestCandidate
    }

    /**
     * Synchronously retrieves the current Upstream Wi-Fi state and associated Network object.
     * Uses deterministic ranking to avoid selecting downstream SoftAP interfaces.
     */
    fun getUpstreamWifiState(): UpstreamWifiState {
        val best = findBestUpstreamWifiNetwork() ?: return UpstreamWifiState.Disconnected
        val caps = best.capabilities
        val primaryIp = best.ipAddresses.firstOrNull()

        if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)) {
            return UpstreamWifiState.CaptivePortalDetected
        }

        val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val isValidated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

        // If validated, or if it has INTERNET capability and a valid assigned IPv4 address,
        // it is ready for explicit socket binding.
        return if (isValidated || (hasInternet && best.ipAddresses.isNotEmpty())) {
            UpstreamWifiState.Authenticated(best.network, primaryIp)
        } else if (best.ipAddresses.isNotEmpty() && best.hasDefaultRoute) {
            // Android VALIDATED might be temporarily false / delayed on captive portal networks
            // even after authentication. Treat as Authenticated so SOCKS5 can bind.
            UpstreamWifiState.Authenticated(best.network, primaryIp)
        } else if (best.ipAddresses.isNotEmpty()) {
            UpstreamWifiState.ConnectedNoInternet
        } else {
            UpstreamWifiState.Disconnected
        }
    }

    /**
     * Returns the active authenticated Wi-Fi Network handle, if available.
     */
    fun getAuthenticatedWifiNetwork(): Network? {
        val best = findBestUpstreamWifiNetwork() ?: return null
        return if (best.score > 0 && best.ipAddresses.isNotEmpty()) {
            best.network
        } else {
            null
        }
    }

    /**
     * Real-time reactive flow observing upstream Wi-Fi network changes.
     */
    fun observeUpstreamWifi(): Flow<UpstreamWifiState> = callbackFlow {
        // Emit current state immediately
        trySend(getUpstreamWifiState())

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "NetworkCallback.onAvailable: $network")
                clearDnsCache()
                trySend(getUpstreamWifiState())
            }

            override fun onLost(network: Network) {
                Log.d(TAG, "NetworkCallback.onLost: $network")
                clearDnsCache()
                trySend(getUpstreamWifiState())
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                Log.d(TAG, "NetworkCallback.onCapabilitiesChanged: $network")
                trySend(getUpstreamWifiState())
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                Log.d(TAG, "NetworkCallback.onLinkPropertiesChanged: $network")
                clearDnsCache()
                trySend(getUpstreamWifiState())
            }

            override fun onUnavailable() {
                Log.d(TAG, "NetworkCallback.onUnavailable")
                clearDnsCache()
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
     * Utilizes a thread-safe bounded LRU cache with TTL to eliminate repetitive upstream DNS latency.
     */
    suspend fun resolveHostOnUpstream(host: String, targetNetwork: Network? = null): InetAddress? =
        withContext(Dispatchers.IO) {
            val network = targetNetwork ?: getAuthenticatedWifiNetwork() ?: return@withContext null

            // Fast path 1: Check if host is already an IP address literal
            try {
                if (host.matches(Regex("^[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+$"))) {
                    return@withContext InetAddress.getByName(host)
                }
            } catch (_: Exception) {}

            // Fast path 2: Check bounded DNS cache
            val now = System.currentTimeMillis()
            val cached = dnsCache[host]
            if (cached != null && (now - cached.timestamp) < dnsCacheTtlMs) {
                return@withContext cached.address
            }

            try {
                withTimeoutOrNull(4000) {
                    val addresses = network.getAllByName(host)
                    // Prefer IPv4 for compatibility with local proxy clients
                    val resolved = addresses.firstOrNull { it is Inet4Address } ?: addresses.firstOrNull()
                    if (resolved != null) {
                        if (dnsCache.size >= maxDnsCacheSize) {
                            val oldestKey = dnsCache.minByOrNull { it.value.timestamp }?.key
                            if (oldestKey != null) dnsCache.remove(oldestKey)
                        }
                        dnsCache[host] = DnsCacheEntry(resolved, now)
                    }
                    resolved
                }
            } catch (e: Exception) {
                Log.w(TAG, "Upstream DNS resolution failed for '$host': ${e.message}")
                null
            }
        }

    /**
     * Inspects local network interfaces to detect active Hotspot (swlan0, ap0, softap)
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

                val ips: List<String> = Collections.list(iface.inetAddresses)
                    .filterIsInstance<Inet4Address>()
                    .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                    .mapNotNull { it.hostAddress }

                if (ips.isEmpty()) continue

                // Check for Wi-Fi Hotspot / SoftAP interfaces
                // Samsung Galaxy Note 8 uses 'swlan0', 'ap0', 'softap0', or secondary 'wlan' alias
                if (name.contains("swlan") || name.contains("softap") || name.contains("ap") || name.contains("wlan1")) {
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
                val ips: List<String> = Collections.list(iface.inetAddresses)
                    .filterIsInstance<Inet4Address>()
                    .mapNotNull { it.hostAddress }

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
     * 1. The upstream Wi-Fi Network is active and ranked.
     * 2. An IPv4 address is assigned.
     * 3. DNS resolution succeeds over the Wi-Fi Network.
     * 4. An outbound TCP socket successfully binds via Network.bindSocket().
     * 5. An HTTP round-trip successfully completes through the authenticated session.
     */
    suspend fun testUpstreamConnection(): ConnectionTestResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val candidate = findBestUpstreamWifiNetwork()
            ?: return@withContext ConnectionTestResult(
                isSuccess = false,
                latencyMs = 0,
                resolvedIp = null,
                httpCode = null,
                socketBound = false,
                message = "No upstream Wi-Fi STA network detected (checked all active network interfaces)."
            )

        val network = candidate.network
        val ifaceName = candidate.interfaceName.ifEmpty { "wlan" }
        val ip = candidate.ipAddresses.firstOrNull()

        if (ip == null) {
            return@withContext ConnectionTestResult(
                isSuccess = false,
                latencyMs = 0,
                resolvedIp = null,
                httpCode = null,
                socketBound = false,
                message = "Upstream Wi-Fi network found ($ifaceName), but no valid IPv4 address is assigned."
            )
        }

        // Step 1: DNS Resolution over bound network
        val targetHost = PROBE_URL_HOST
        val resolved = resolveHostOnUpstream(targetHost, network)
        if (resolved == null) {
            val latency = System.currentTimeMillis() - startTime
            return@withContext ConnectionTestResult(
                isSuccess = false,
                latencyMs = latency,
                resolvedIp = null,
                httpCode = null,
                socketBound = false,
                message = "DNS resolution failed via upstream Wi-Fi ($ifaceName) for '$targetHost'."
            )
        }

        // Step 2: Open socket and bind explicitly to Wi-Fi network
        val socket = Socket()
        try {
            network.bindSocket(socket)
        } catch (e: Exception) {
            socket.closeQuietly()
            val latency = System.currentTimeMillis() - startTime
            return@withContext ConnectionTestResult(
                isSuccess = false,
                latencyMs = latency,
                resolvedIp = resolved.hostAddress,
                httpCode = null,
                socketBound = false,
                message = "Network.bindSocket() failed on interface $ifaceName: ${e.message}"
            )
        }

        // Step 3: Connect to port 80
        socket.soTimeout = PROBE_TIMEOUT_MS
        try {
            socket.connect(InetSocketAddress(resolved, 80), PROBE_TIMEOUT_MS)
        } catch (e: SocketTimeoutException) {
            socket.closeQuietly()
            val latency = System.currentTimeMillis() - startTime
            return@withContext ConnectionTestResult(
                isSuccess = false,
                latencyMs = latency,
                resolvedIp = resolved.hostAddress,
                httpCode = null,
                socketBound = true,
                message = "Socket bound successfully, but TCP connect to ${resolved.hostAddress}:80 timed out."
            )
        } catch (e: Exception) {
            socket.closeQuietly()
            val latency = System.currentTimeMillis() - startTime
            return@withContext ConnectionTestResult(
                isSuccess = false,
                latencyMs = latency,
                resolvedIp = resolved.hostAddress,
                httpCode = null,
                socketBound = true,
                message = "Socket bound successfully, but TCP connect to ${resolved.hostAddress}:80 failed: ${e.message}"
            )
        }

        // Step 4: Transmit raw HTTP GET /generate_204 request
        try {
            val output = socket.getOutputStream()
            val request = "GET /generate_204 HTTP/1.1\r\nHost: $targetHost\r\nConnection: close\r\n\r\n"
            output.write(request.toByteArray(Charsets.US_ASCII))
            output.flush()

            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.US_ASCII))
            val statusLine = reader.readLine() ?: ""
            socket.closeQuietly()

            val latency = System.currentTimeMillis() - startTime
            val statusCode = statusLine.split(" ").getOrNull(1)?.toIntOrNull()

            if (statusCode == 204 || statusCode == 200) {
                ConnectionTestResult(
                    isSuccess = true,
                    latencyMs = latency,
                    resolvedIp = resolved.hostAddress,
                    httpCode = statusCode,
                    socketBound = true,
                    message = "Success! Socket bound to Wi-Fi ($ifaceName - $ip). Latency: ${latency}ms (HTTP $statusCode)"
                )
            } else if (statusCode != null && statusCode in 300..399) {
                ConnectionTestResult(
                    isSuccess = false,
                    latencyMs = latency,
                    resolvedIp = resolved.hostAddress,
                    httpCode = statusCode,
                    socketBound = true,
                    message = "Captive portal redirect detected (HTTP $statusCode). Please sign in via the portal button."
                )
            } else {
                ConnectionTestResult(
                    isSuccess = false,
                    latencyMs = latency,
                    resolvedIp = resolved.hostAddress,
                    httpCode = statusCode,
                    socketBound = true,
                    message = "Unexpected HTTP response from probe: ${statusLine.ifEmpty { "Empty response" }}"
                )
            }
        } catch (e: Exception) {
            socket.closeQuietly()
            val latency = System.currentTimeMillis() - startTime
            ConnectionTestResult(
                isSuccess = false,
                latencyMs = latency,
                resolvedIp = resolved.hostAddress,
                httpCode = null,
                socketBound = true,
                message = "HTTP probe request failed: ${e.message ?: e.javaClass.simpleName}"
            )
        }
    }

    private fun Socket.closeQuietly() {
        try {
            close()
        } catch (_: Exception) {}
    }
}

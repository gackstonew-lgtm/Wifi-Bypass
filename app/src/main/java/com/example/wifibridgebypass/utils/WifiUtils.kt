package com.example.wifibridgebypass.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Represents the current state of the device's Wi-Fi connection as far as
 * the proxy bridge is concerned.
 */
sealed class WifiState {
    object Disconnected : WifiState()
    object CaptivePortal : WifiState()
    object NoInternet : WifiState()
    data class Ready(val network: Network) : WifiState()
}

object WifiUtils {

    private fun connectivityManager(context: Context): ConnectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /**
     * Synchronously inspects the currently active network and classifies it.
     * Only networks with the WIFI transport are considered "connected" here;
     * cellular is deliberately ignored so the bridge never routes traffic
     * over mobile data.
     */
    fun getWifiState(context: Context): WifiState {
        val cm = connectivityManager(context)
        val network = cm.activeNetwork ?: return WifiState.Disconnected
        val caps = cm.getNetworkCapabilities(network) ?: return WifiState.Disconnected

        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return WifiState.Disconnected
        }
        if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)) {
            return WifiState.CaptivePortal
        }
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
            !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        ) {
            return WifiState.NoInternet
        }
        return WifiState.Ready(network)
    }

    fun isWifiConnected(context: Context): Boolean = getWifiState(context) !is WifiState.Disconnected

    fun hasInternetConnection(context: Context): Boolean = getWifiState(context) is WifiState.Ready

    fun isCaptivePortal(context: Context): Boolean = getWifiState(context) is WifiState.CaptivePortal

    /** The active Wi-Fi Network handle, or null if Wi-Fi isn't validated/ready. */
    fun getReadyWifiNetwork(context: Context): Network? =
        (getWifiState(context) as? WifiState.Ready)?.network

    /**
     * Emits a fresh [WifiState] every time Wi-Fi connectivity, validation, or
     * captive-portal status changes. Callers (the service and the UI) use
     * this to react immediately if Wi-Fi drops or a portal reappears.
     */
    fun observeWifiState(context: Context): Flow<WifiState> = callbackFlow {
        val cm = connectivityManager(context)

        // Emit current state immediately so subscribers don't wait for a change.
        trySend(getWifiState(context))

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                trySend(WifiState.Disconnected)
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                trySend(getWifiState(context))
            }

            override fun onUnavailable() {
                trySend(WifiState.Disconnected)
            }
        }

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        cm.registerNetworkCallback(request, callback)
        awaitClose { cm.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()

    /**
     * Lists this device's own local IPv4 addresses (excluding loopback),
     * so the UI can tell the user which address to point their second
     * device's proxy settings at once a hotspot / USB tether interface
     * (e.g. "ap0", "rndis0") comes up.
     */
    fun getLocalIpAddresses(): List<String> {
        val addresses = mutableListOf<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (!iface.isUp || iface.isLoopback) continue
                val ifaceAddresses = iface.inetAddresses
                while (ifaceAddresses.hasMoreElements()) {
                    val addr = ifaceAddresses.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        addresses.add("${addr.hostAddress} (${iface.displayName})")
                    }
                }
            }
        } catch (e: Exception) {
            // Best-effort; leave list empty/partial on failure.
        }
        return addresses
    }
}

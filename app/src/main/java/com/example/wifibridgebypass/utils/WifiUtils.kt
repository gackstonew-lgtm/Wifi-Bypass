package com.example.wifibridgebypass.utils

import android.content.Context
import android.net.Network
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Backward-compatible wrapper exposing standard Wi-Fi utilities backed by [NetworkManager].
 */
sealed class WifiState {
    object Disconnected : WifiState()
    object CaptivePortal : WifiState()
    object NoInternet : WifiState()
    data class Ready(val network: Network, val ipAddress: String? = null) : WifiState()
}

object WifiUtils {

    fun getWifiState(context: Context): WifiState {
        return when (val state = NetworkManager.getInstance(context).getUpstreamWifiState()) {
            is UpstreamWifiState.Disconnected -> WifiState.Disconnected
            is UpstreamWifiState.CaptivePortalDetected -> WifiState.CaptivePortal
            is UpstreamWifiState.ConnectedNoInternet -> WifiState.NoInternet
            is UpstreamWifiState.Authenticated -> WifiState.Ready(state.network, state.ipAddress)
        }
    }

    fun observeWifiState(context: Context): Flow<WifiState> {
        return NetworkManager.getInstance(context).observeUpstreamWifi().map { state ->
            when (state) {
                is UpstreamWifiState.Disconnected -> WifiState.Disconnected
                is UpstreamWifiState.CaptivePortalDetected -> WifiState.CaptivePortal
                is UpstreamWifiState.ConnectedNoInternet -> WifiState.NoInternet
                is UpstreamWifiState.Authenticated -> WifiState.Ready(state.network, state.ipAddress)
            }
        }
    }

    fun getReadyWifiNetwork(context: Context): Network? {
        return NetworkManager.getInstance(context).getAuthenticatedWifiNetwork()
    }

    fun getLocalIpAddresses(context: Context): List<String> {
        return NetworkManager.getInstance(context).getLocalListeningAddresses()
    }
}

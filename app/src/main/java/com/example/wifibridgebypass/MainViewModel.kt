package com.example.wifibridgebypass

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.wifibridgebypass.services.ProxyBridgeService
import com.example.wifibridgebypass.utils.ConnectionTestResult
import com.example.wifibridgebypass.utils.DownstreamState
import com.example.wifibridgebypass.utils.NetworkManager
import com.example.wifibridgebypass.utils.UpstreamWifiState
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val networkManager = NetworkManager.getInstance(application)

    // Upstream Wi-Fi State
    private val _upstreamState = MutableLiveData<UpstreamWifiState>(UpstreamWifiState.Disconnected)
    val upstreamState: LiveData<UpstreamWifiState> = _upstreamState

    // Downstream Local State (Hotspot vs USB)
    private val _downstreamState = MutableLiveData<DownstreamState>(DownstreamState.Inactive)
    val downstreamState: LiveData<DownstreamState> = _downstreamState

    // Service State
    private val _bridgeStatus = MutableLiveData(ProxyBridgeService.BridgeStatus.STOPPED)
    val bridgeStatus: LiveData<ProxyBridgeService.BridgeStatus> = _bridgeStatus

    private val _activeConnections = MutableLiveData(0)
    val activeConnections: LiveData<Int> = _activeConnections

    private val _bytesTransferredTx = MutableLiveData(0L)
    val bytesTransferredTx: LiveData<Long> = _bytesTransferredTx

    private val _bytesTransferredRx = MutableLiveData(0L)
    val bytesTransferredRx: LiveData<Long> = _bytesTransferredRx

    private val _localAddresses = MutableLiveData<List<String>>(emptyList())
    val localAddresses: LiveData<List<String>> = _localAddresses

    // Diagnostic Test State
    private val _isTestingConnection = MutableLiveData(false)
    val isTestingConnection: LiveData<Boolean> = _isTestingConnection

    private val _testResult = MutableLiveData<ConnectionTestResult?>(null)
    val testResult: LiveData<ConnectionTestResult?> = _testResult

    private val _lastError = MutableLiveData<String?>(null)
    val lastError: LiveData<String?> = _lastError

    init {
        // Collect Service StateFlows
        viewModelScope.launch {
            ProxyBridgeService.status.collect { status ->
                _bridgeStatus.value = status
                refreshDownstreamAndAddresses()
            }
        }

        viewModelScope.launch {
            ProxyBridgeService.activeConnections.collect { count ->
                _activeConnections.value = count
            }
        }

        viewModelScope.launch {
            ProxyBridgeService.bytesTransferredTx.collect { tx ->
                _bytesTransferredTx.value = tx
            }
        }

        viewModelScope.launch {
            ProxyBridgeService.bytesTransferredRx.collect { rx ->
                _bytesTransferredRx.value = rx
            }
        }

        viewModelScope.launch {
            ProxyBridgeService.lastError.collect { error ->
                _lastError.value = error
            }
        }

        // Collect Real-Time Upstream Network Changes
        viewModelScope.launch {
            networkManager.observeUpstreamWifi().collect { state ->
                _upstreamState.value = state
                refreshDownstreamAndAddresses()
            }
        }
    }

    fun refreshNetworkStatus() {
        _upstreamState.value = networkManager.getUpstreamWifiState()
        refreshDownstreamAndAddresses()
    }

    private fun refreshDownstreamAndAddresses() {
        val downstream = networkManager.detectDownstreamState()
        _downstreamState.value = downstream
        _localAddresses.value = networkManager.getLocalListeningAddresses()
    }

    /**
     * Executes the explicit upstream socket binding diagnostic.
     */
    fun runConnectionTest() {
        if (_isTestingConnection.value == true) return
        _isTestingConnection.value = true
        _testResult.value = null

        viewModelScope.launch {
            try {
                val result = networkManager.testUpstreamConnection()
                _testResult.value = result
            } finally {
                _isTestingConnection.value = false
            }
        }
    }

    fun openCaptivePortalLogin(context: Context) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("http://neverssl.com")).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            _lastError.value = "Failed to launch browser: ${e.message}"
        }
    }

    fun openHotspotSettings(context: Context) {
        try {
            val intent = Intent("android.settings.WIFI_TETHER_SETTINGS").apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            try {
                val intent = Intent(Settings.ACTION_WIRELESS_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            } catch (e2: Exception) {
                _lastError.value = "Unable to open Hotspot settings."
            }
        }
    }

    fun openUsbTetheringSettings(context: Context) {
        try {
            val intent = Intent("android.settings.TETHER_SETTINGS").apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            try {
                val intent = Intent(Settings.ACTION_WIRELESS_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            } catch (e2: Exception) {
                _lastError.value = "Unable to open USB Tethering settings."
            }
        }
    }

    fun startBridge(context: Context) {
        val intent = Intent(context, ProxyBridgeService::class.java)
        context.startForegroundService(intent)
    }

    fun stopBridge(context: Context) {
        val intent = Intent(context, ProxyBridgeService::class.java)
        context.stopService(intent)
    }
}

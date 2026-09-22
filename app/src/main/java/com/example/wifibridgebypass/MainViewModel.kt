package com.example.wifibridgebypass

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.wifibridgebypass.services.ProxyBridgeService
import com.example.wifibridgebypass.utils.WifiState
import com.example.wifibridgebypass.utils.WifiUtils
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val _statusText = MutableLiveData("Checking Wi-Fi status...")
    val statusText: LiveData<String> = _statusText

    private val _captivePortalDetected = MutableLiveData(false)
    val captivePortalDetected: LiveData<Boolean> = _captivePortalDetected

    private val _bridgeRunning = MutableLiveData(false)
    val bridgeRunning: LiveData<Boolean> = _bridgeRunning

    private val _activeConnections = MutableLiveData(0)
    val activeConnections: LiveData<Int> = _activeConnections

    private val _localAddresses = MutableLiveData<List<String>>(emptyList())
    val localAddresses: LiveData<List<String>> = _localAddresses

    private val _lastError = MutableLiveData<String?>(null)
    val lastError: LiveData<String?> = _lastError

    init {
        // Reflect the service's own state (it's the source of truth once running,
        // since it reacts to Wi-Fi loss independently of the UI being open).
        viewModelScope.launch {
            ProxyBridgeService.status.collect { status ->
                _bridgeRunning.value = status == ProxyBridgeService.Status.RUNNING
                if (status == ProxyBridgeService.Status.RUNNING) {
                    _localAddresses.value = WifiUtils.getLocalIpAddresses()
                }
            }
        }
        viewModelScope.launch {
            ProxyBridgeService.activeConnections.collect { _activeConnections.value = it }
        }
        viewModelScope.launch {
            ProxyBridgeService.lastError.collect { _lastError.value = it }
        }
    }

    fun refreshWifiStatus() {
        val context = getApplication<Application>().applicationContext
        when (val state = WifiUtils.getWifiState(context)) {
            WifiState.Disconnected -> {
                _statusText.value = "Not connected to Wi-Fi"
                _captivePortalDetected.value = false
            }
            WifiState.NoInternet -> {
                _statusText.value = "Wi-Fi connected — waiting for internet"
                _captivePortalDetected.value = false
            }
            WifiState.CaptivePortal -> {
                _statusText.value = "Wi-Fi connected — sign-in required"
                _captivePortalDetected.value = true
            }
            is WifiState.Ready -> {
                _statusText.value = "Wi-Fi connected and validated"
                _captivePortalDetected.value = false
            }
        }
    }

    fun openCaptivePortalLogin(context: Context) {
        // Opens a neutral URL that the OS/router redirects to the network's
        // own sign-in page, so the user can authenticate manually. This does
        // not attempt to skip or automate the sign-in step itself.
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("http://neverssl.com"))
        context.startActivity(intent)
    }

    fun startBridge(context: Context) {
        context.startForegroundService(Intent(context, ProxyBridgeService::class.java))
    }

    fun stopBridge(context: Context) {
        context.stopService(Intent(context, ProxyBridgeService::class.java))
    }
}

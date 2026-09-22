package com.example.wifibridgebypass

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.wifibridgebypass.utils.WifiUtils
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val _wifiStatus = MutableLiveData("Checking Wi-Fi status...")
    val wifiStatus: LiveData<String> = _wifiStatus

    private val _captivePortalDetected = MutableLiveData(false)
    val captivePortalDetected: LiveData<Boolean> = _captivePortalDetected

    private val _canStartBridge = MutableLiveData(false)
    val canStartBridge: LiveData<Boolean> = _canStartBridge

    private val _bridgeActive = MutableLiveData(false)
    val bridgeActive: LiveData<Boolean> = _bridgeActive

    var isBridgeActive: Boolean = false
        private set

    fun checkWifiStatus() {
        val context = getApplication<Application>().applicationContext
        viewModelScope.launch {
            val connected = WifiUtils.isWifiConnected(context)
            val hasInternet = WifiUtils.hasInternetConnection(context)
            val captive = WifiUtils.isCaptivePortal(context)

            _captivePortalDetected.value = captive

            _wifiStatus.value = when {
                !connected -> "Not connected to Wi-Fi"
                captive -> "Wi-Fi connected — sign-in required"
                hasInternet -> "Wi-Fi connected"
                else -> "Wi-Fi connected — no internet"
            }

            _canStartBridge.value = connected && hasInternet && !captive
        }
    }

    fun openCaptivePortalLogin(context: Context) {
        // Opens the network's own sign-in page in the browser so the user can
        // authenticate manually. This does not bypass any authentication step.
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("http://neverssl.com"))
        context.startActivity(intent)
    }

    fun setBridgeActive(active: Boolean) {
        isBridgeActive = active
        _bridgeActive.value = active
    }
}

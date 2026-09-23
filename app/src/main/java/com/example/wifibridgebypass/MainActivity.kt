package com.example.wifibridgebypass

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import com.example.wifibridgebypass.databinding.ActivityMainBinding
import com.example.wifibridgebypass.services.ProxyBridgeService
import com.example.wifibridgebypass.utils.DownstreamState
import com.example.wifibridgebypass.utils.UpstreamWifiState
import java.text.DecimalFormat

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: MainViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel = ViewModelProvider(
            this,
            ViewModelProvider.AndroidViewModelFactory.getInstance(application)
        )[MainViewModel::class.java]

        setupObservers()
        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        // Refresh network state when returning from system settings or captive portal browser
        viewModel.refreshNetworkStatus()
    }

    private fun setupObservers() {
        // Upstream Wi-Fi Observer
        viewModel.upstreamState.observe(this) { state ->
            when (state) {
                is UpstreamWifiState.Authenticated -> {
                    binding.upstreamStatusBadge.text = getString(R.string.status_wifi_authenticated)
                    binding.upstreamStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.status_green))
                    binding.upstreamStatusBadge.setBackgroundResource(R.drawable.badge_background_green)
                    binding.upstreamDetailsText.text = "IP: ${state.ipAddress ?: "Assigned"} | STA Interface bound (Internet Validated)"
                    binding.loginPortalButton.visibility = View.GONE
                }
                is UpstreamWifiState.CaptivePortalDetected -> {
                    binding.upstreamStatusBadge.text = getString(R.string.status_wifi_portal)
                    binding.upstreamStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.status_orange))
                    binding.upstreamStatusBadge.setBackgroundResource(R.drawable.badge_background_orange)
                    binding.upstreamDetailsText.text = "Captive portal redirect detected. Complete authentication to unlock bridge."
                    binding.loginPortalButton.visibility = View.VISIBLE
                }
                is UpstreamWifiState.ConnectedNoInternet -> {
                    binding.upstreamStatusBadge.text = getString(R.string.status_wifi_no_internet)
                    binding.upstreamStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.status_red))
                    binding.upstreamStatusBadge.setBackgroundResource(R.drawable.badge_background_red)
                    binding.upstreamDetailsText.text = "Connected to Wi-Fi AP, but upstream internet is not yet validated."
                    binding.loginPortalButton.visibility = View.GONE
                }
                is UpstreamWifiState.Disconnected -> {
                    binding.upstreamStatusBadge.text = getString(R.string.status_wifi_disconnected)
                    binding.upstreamStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.status_red))
                    binding.upstreamStatusBadge.setBackgroundResource(R.drawable.badge_background_red)
                    binding.upstreamDetailsText.text = "Please connect this phone to the venue/ISP Wi-Fi network."
                    binding.loginPortalButton.visibility = View.GONE
                }
            }
        }

        // Downstream Interface Observer
        viewModel.downstreamState.observe(this) { state ->
            when (state) {
                is DownstreamState.HotspotActive -> {
                    binding.downstreamStatusBadge.text = getString(R.string.status_hotspot_active)
                    binding.downstreamStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.status_green))
                    binding.downstreamStatusBadge.setBackgroundResource(R.drawable.badge_background_green)
                    binding.downstreamDetailsText.text = "Interface: ${state.interfaceName} | Gateway IP: ${state.ipAddresses.joinToString(", ")}"
                }
                is DownstreamState.UsbTetherActive -> {
                    binding.downstreamStatusBadge.text = getString(R.string.status_usb_active)
                    binding.downstreamStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.status_green))
                    binding.downstreamStatusBadge.setBackgroundResource(R.drawable.badge_background_green)
                    binding.downstreamDetailsText.text = "USB Interface: ${state.interfaceName} | Gateway IP: ${state.ipAddresses.joinToString(", ")}"
                }
                is DownstreamState.Inactive -> {
                    binding.downstreamStatusBadge.text = getString(R.string.status_downstream_inactive)
                    binding.downstreamStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.status_red))
                    binding.downstreamStatusBadge.setBackgroundResource(R.drawable.badge_background_red)
                    binding.downstreamDetailsText.text = "Turn on Mobile Hotspot (or USB Tethering) to connect your laptop."
                }
            }
        }

        // Proxy Bridge Service Observer
        viewModel.bridgeStatus.observe(this) { status ->
            when (status) {
                ProxyBridgeService.BridgeStatus.RUNNING -> {
                    binding.proxyStatusBadge.text = getString(R.string.proxy_status_running)
                    binding.proxyStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.status_green))
                    binding.proxyStatusBadge.setBackgroundResource(R.drawable.badge_background_green)
                    binding.btnToggleBridge.text = getString(R.string.stop_bridge)
                    binding.btnToggleBridge.backgroundTintList = ContextCompat.getColorStateList(this, R.color.status_red)
                }
                ProxyBridgeService.BridgeStatus.WAITING_FOR_WIFI -> {
                    binding.proxyStatusBadge.text = getString(R.string.proxy_status_waiting)
                    binding.proxyStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.status_orange))
                    binding.proxyStatusBadge.setBackgroundResource(R.drawable.badge_background_orange)
                    binding.btnToggleBridge.text = getString(R.string.stop_bridge)
                    binding.btnToggleBridge.backgroundTintList = ContextCompat.getColorStateList(this, R.color.status_red)
                }
                ProxyBridgeService.BridgeStatus.ERROR -> {
                    binding.proxyStatusBadge.text = getString(R.string.proxy_status_error)
                    binding.proxyStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.status_red))
                    binding.proxyStatusBadge.setBackgroundResource(R.drawable.badge_background_red)
                    binding.btnToggleBridge.text = getString(R.string.start_bridge)
                    binding.btnToggleBridge.backgroundTintList = ContextCompat.getColorStateList(this, R.color.status_green)
                }
                ProxyBridgeService.BridgeStatus.STOPPED, null -> {
                    binding.proxyStatusBadge.text = getString(R.string.proxy_status_stopped)
                    binding.proxyStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.status_red))
                    binding.proxyStatusBadge.setBackgroundResource(R.drawable.badge_background_red)
                    binding.btnToggleBridge.text = getString(R.string.start_bridge)
                    binding.btnToggleBridge.backgroundTintList = ContextCompat.getColorStateList(this, R.color.status_green)
                }
            }
        }

        viewModel.activeConnections.observe(this) { count ->
            binding.proxyActiveConnectionsText.text = getString(R.string.active_connections_format, count)
        }

        viewModel.localAddresses.observe(this) { addresses ->
            val isRunning = viewModel.bridgeStatus.value == ProxyBridgeService.BridgeStatus.RUNNING
            if (!isRunning || addresses.isEmpty()) {
                binding.proxyListeningText.text = "Listening on: 0.0.0.0:${ProxyBridgeService.PROXY_PORT} (All LAN Interfaces)"
            } else {
                binding.proxyListeningText.text = "Listening on:\n" + addresses.joinToString("\n") { "• $it:${ProxyBridgeService.PROXY_PORT}" }
            }
        }

        // Live Traffic Metrics
        viewModel.bytesTransferredTx.observe(this) { tx ->
            val rx = viewModel.bytesTransferredRx.value ?: 0L
            binding.proxyTrafficText.text = getString(
                R.string.traffic_metrics_format,
                formatBytes(tx),
                formatBytes(rx)
            )
        }

        viewModel.bytesTransferredRx.observe(this) { rx ->
            val tx = viewModel.bytesTransferredTx.value ?: 0L
            binding.proxyTrafficText.text = getString(
                R.string.traffic_metrics_format,
                formatBytes(tx),
                formatBytes(rx)
            )
        }

        // Diagnostic Connection Test Observer
        viewModel.isTestingConnection.observe(this) { isTesting ->
            binding.btnTestConnection.isEnabled = !isTesting
            if (isTesting) {
                binding.diagnosticResultText.text = getString(R.string.testing_connection)
            }
        }

        viewModel.testResult.observe(this) { result ->
            if (result != null) {
                val prefix = if (result.isSuccess) "✓ PASS: " else "✗ FAIL: "
                val details = buildString {
                    append(prefix)
                    append(result.message)
                    if (result.resolvedIp != null) {
                        append("\n• Resolved Upstream IP: ").append(result.resolvedIp)
                    }
                    if (result.latencyMs > 0) {
                        append("\n• Upstream Round-Trip: ").append(result.latencyMs).append(" ms")
                    }
                    append("\n• Socket Binding: Verified via Network.bindSocket()")
                }
                binding.diagnosticResultText.text = details
            }
        }

        viewModel.lastError.observe(this) { error ->
            if (!error.isNullOrBlank()) {
                Toast.makeText(this, error, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun setupListeners() {
        binding.loginPortalButton.setOnClickListener {
            viewModel.openCaptivePortalLogin(this)
        }

        binding.btnHotspotSettings.setOnClickListener {
            viewModel.openHotspotSettings(this)
        }

        binding.btnUsbSettings.setOnClickListener {
            viewModel.openUsbTetheringSettings(this)
        }

        binding.btnToggleBridge.setOnClickListener {
            if (viewModel.bridgeStatus.value == ProxyBridgeService.BridgeStatus.RUNNING ||
                viewModel.bridgeStatus.value == ProxyBridgeService.BridgeStatus.WAITING_FOR_WIFI
            ) {
                viewModel.stopBridge(this)
                Toast.makeText(this, "Bridge stopped.", Toast.LENGTH_SHORT).show()
            } else {
                viewModel.startBridge(this)
                Toast.makeText(this, "Starting SOCKS5 Bridge...", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnTestConnection.setOnClickListener {
            viewModel.runConnectionTest()
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        val index = digitGroups.coerceIn(0, units.size - 1)
        val value = bytes / Math.pow(1024.0, index.toDouble())
        return DecimalFormat("#,##0.#").format(value) + " " + units[index]
    }
}

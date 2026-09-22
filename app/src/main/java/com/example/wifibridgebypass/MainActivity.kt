package com.example.wifibridgebypass

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import com.example.wifibridgebypass.databinding.ActivityMainBinding

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
        // Re-check every time the user comes back — e.g. after finishing a
        // captive-portal sign-in in the browser, or after manually turning
        // on their hotspot in system settings.
        viewModel.refreshWifiStatus()
    }

    private fun setupObservers() {
        viewModel.statusText.observe(this) { status ->
            binding.statusText.text = status
        }

        viewModel.captivePortalDetected.observe(this) { isCaptive ->
            binding.loginButton.visibility = if (isCaptive) View.VISIBLE else View.GONE
        }

        viewModel.bridgeRunning.observe(this) { running ->
            if (running) {
                binding.startBridgeButton.text = getString(R.string.stop_bridge)
                binding.startBridgeButton.setBackgroundColor(getColor(android.R.color.holo_red_dark))
            } else {
                binding.startBridgeButton.text = getString(R.string.start_bridge)
                binding.startBridgeButton.setBackgroundColor(getColor(android.R.color.holo_green_dark))
            }
        }

        viewModel.activeConnections.observe(this) { count ->
            binding.connectionsText.text = getString(R.string.active_connections, count)
        }

        viewModel.localAddresses.observe(this) { addresses ->
            binding.addressText.text = if (addresses.isEmpty()) {
                getString(R.string.address_placeholder)
            } else {
                getString(R.string.address_prefix) + "\n" + addresses.joinToString("\n")
            }
        }

        viewModel.lastError.observe(this) { error ->
            if (!error.isNullOrBlank()) {
                Toast.makeText(this, error, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun setupListeners() {
        binding.loginButton.setOnClickListener {
            viewModel.openCaptivePortalLogin(this)
        }

        binding.hotspotSettingsButton.setOnClickListener {
            // Android 10+ doesn't let apps start the hotspot programmatically,
            // so we deep-link into the system settings screen instead and the
            // user flips it on manually.
            try {
                startActivity(Intent(Settings.ACTION_WIFI_TETHER_SETTING_ACTION))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
            }
        }

        binding.startBridgeButton.setOnClickListener {
            if (viewModel.bridgeRunning.value == true) {
                viewModel.stopBridge(this)
            } else {
                viewModel.startBridge(this)
                Toast.makeText(this, R.string.bridge_starting, Toast.LENGTH_SHORT).show()
            }
        }
    }
}

package com.example.wifibridgebypass

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import com.example.wifibridgebypass.databinding.ActivityMainBinding
import com.example.wifibridgebypass.services.ProxyBridgeService

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

        // Initial check
        viewModel.checkWifiStatus()
    }

    override fun onResume() {
        super.onResume()
        viewModel.checkWifiStatus()
    }

    private fun setupObservers() {
        viewModel.wifiStatus.observe(this) { status ->
            binding.statusText.text = status
        }

        viewModel.captivePortalDetected.observe(this) { isCaptive ->
            binding.loginButton.visibility = if (isCaptive) View.VISIBLE else View.GONE
        }

        viewModel.canStartBridge.observe(this) { canStart ->
            binding.startBridgeButton.isEnabled = canStart
            binding.startBridgeButton.alpha = if (canStart) 1.0f else 0.5f
        }

        viewModel.bridgeActive.observe(this) { isActive ->
            if (isActive) {
                binding.startBridgeButton.text = getString(R.string.stop_bridge)
                binding.startBridgeButton.setBackgroundColor(getColor(android.R.color.holo_red_dark))
            } else {
                binding.startBridgeButton.text = getString(R.string.start_bridge)
                binding.startBridgeButton.setBackgroundColor(getColor(android.R.color.holo_green_dark))
            }
        }
    }

    private fun setupListeners() {
        binding.loginButton.setOnClickListener {
            viewModel.openCaptivePortalLogin(this)
        }

        binding.startBridgeButton.setOnClickListener {
            if (viewModel.isBridgeActive) {
                stopBridgeService()
            } else {
                startBridgeService()
            }
        }
    }

    private fun startBridgeService() {
        val intent = Intent(this, ProxyBridgeService::class.java)
        startForegroundService(intent)
        viewModel.setBridgeActive(true)
        Toast.makeText(this, R.string.bridge_started, Toast.LENGTH_SHORT).show()
    }

    private fun stopBridgeService() {
        val intent = Intent(this, ProxyBridgeService::class.java)
        stopService(intent)
        viewModel.setBridgeActive(false)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (viewModel.isBridgeActive) {
            stopBridgeService()
        }
    }
}

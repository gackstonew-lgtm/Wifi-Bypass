package com.example.wifibridgebypass.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.ServerSocket
import java.net.Socket

/**
 * Runs a local, plain TCP forwarding proxy that other devices on the same
 * Wi-Fi network can point at (e.g. by setting it as their HTTP proxy) to
 * share this device's active network connection. This does not implement
 * TLS interception; HTTPS traffic is simply not decrypted.
 */
class ProxyBridgeService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var serverJob: Job? = null
    private var serverSocket: ServerSocket? = null

    companion object {
        const val PROXY_PORT = 8080
        const val CHANNEL_ID = "ProxyBridgeChannel"
        const val NOTIFICATION_ID = 1
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        startProxyServer()
    }

    override fun onDestroy() {
        super.onDestroy()
        serverJob?.cancel()
        serviceScope.cancel()
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Log.e("ProxyBridge", "Error closing server socket", e)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Proxy Bridge Service",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Wi-Fi Bridge Active")
            .setContentText("Sharing Wi-Fi connection via proxy on port $PROXY_PORT")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
    }

    private fun startProxyServer() {
        serverJob = serviceScope.launch {
            try {
                Log.i("ProxyBridge", "Starting local proxy on port $PROXY_PORT")
                val socket = ServerSocket(PROXY_PORT)
                serverSocket = socket

                while (isActive) {
                    try {
                        val clientSocket = socket.accept()
                        launch { handleClient(clientSocket) }
                    } catch (e: Exception) {
                        if (isActive) Log.e("ProxyBridge", "Error accepting client", e)
                    }
                }
            } catch (e: Exception) {
                Log.e("ProxyBridge", "Server failed", e)
            }
        }
    }

    // NOTE: this is a skeleton. A production implementation needs to:
    //  1. Parse the incoming HTTP request line to get the target host/port.
    //  2. Open an outbound socket to that target (optionally bound to the
    //     Wi-Fi network via ConnectivityManager.bindProcessToNetwork).
    //  3. Pipe bytes both directions between clientSocket and the target
    //     socket until either side closes.
    private suspend fun handleClient(clientSocket: Socket) {
        clientSocket.use { socket ->
            Log.i("ProxyBridge", "Client connected: ${socket.remoteSocketAddress}")
            // Placeholder: real forwarding logic goes here.
            socket.close()
        }
    }
}

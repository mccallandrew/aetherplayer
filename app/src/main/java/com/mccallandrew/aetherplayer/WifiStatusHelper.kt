package com.mccallandrew.aetherplayer

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat

/*
 * Tracks the current Wi-Fi connection for the kiosk home tile
 * subtitle.
 */
class WifiStatusHelper(
    context: Context,
    private val listener: Listener
) {

    interface Listener {
        fun onWifiStatusChanged(summary: String)
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    private val wifiManager =
        appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager?

    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE)
            as ConnectivityManager

    private var started = false
    private var networkCallbackRegistered = false

    private val broadcastReceiver = object : BroadcastReceiver() {

        override fun onReceive(
            context: Context,
            intent: Intent
        ) {
            publish()
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {

        override fun onAvailable(network: Network) {
            publish()
        }

        override fun onLost(network: Network) {
            publish()
        }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities
        ) {
            publish()
        }
    }

    fun start() {
        if (started) {
            return
        }

        started = true

        val filter = IntentFilter().apply {
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
        }

        ContextCompat.registerReceiver(
            appContext,
            broadcastReceiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED
        )

        try {
            connectivityManager.registerDefaultNetworkCallback(
                networkCallback,
                mainHandler
            )
            networkCallbackRegistered = true
        } catch (exception: RuntimeException) {
            Log.w(TAG, "Unable to register Wi-Fi status callback.", exception)
        }

        publish()
    }

    fun stop() {
        if (!started) {
            return
        }

        started = false

        try {
            appContext.unregisterReceiver(broadcastReceiver)
        } catch (_: IllegalArgumentException) {
            // Already unregistered.
        }

        if (networkCallbackRegistered) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback)
            } catch (_: IllegalArgumentException) {
                // Already unregistered.
            }
            networkCallbackRegistered = false
        }
    }

    fun refresh() {
        publish()
    }

    private fun publish() {
        mainHandler.post {
            if (!started) {
                return@post
            }

            listener.onWifiStatusChanged(buildSummary())
        }
    }

    private fun buildSummary(): String {
        val manager = wifiManager
            ?: return appContext.getString(R.string.wifi_not_connected)

        try {
            if (!manager.isWifiEnabled) {
                return appContext.getString(R.string.wifi_off)
            }
        } catch (exception: SecurityException) {
            Log.w(TAG, "Unable to read Wi-Fi adapter state.", exception)
            return appContext.getString(R.string.wifi_not_connected)
        }

        val ssid = currentSsid()

        if (ssid.isNullOrBlank()) {
            return appContext.getString(R.string.wifi_not_connected)
        }

        return ssid
    }

    private fun currentSsid(): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val network = connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(
                network
            )

            if (
                capabilities?.hasTransport(
                    NetworkCapabilities.TRANSPORT_WIFI
                ) == true
            ) {
                val wifiInfo = capabilities.transportInfo as? WifiInfo
                sanitizeSsid(wifiInfo?.ssid)?.let { return it }
            }
        }

        if (!hasSsidPermission()) {
            return null
        }

        @Suppress("DEPRECATION")
        val info = wifiManager?.connectionInfo ?: return null

        if (info.networkId == INVALID_NETWORK_ID) {
            return null
        }

        return sanitizeSsid(info.ssid)
    }

    private fun sanitizeSsid(ssid: String?): String? {
        if (ssid.isNullOrBlank()) {
            return null
        }

        val trimmed = ssid.trim().removeSurrounding("\"")

        if (trimmed.isEmpty() || trimmed == UNKNOWN_SSID) {
            return null
        }

        return trimmed
    }

    private fun hasSsidPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val nearbyGranted = ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.NEARBY_WIFI_DEVICES
            ) == PackageManager.PERMISSION_GRANTED

            if (nearbyGranted) {
                return true
            }
        }

        return ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    companion object {

        private const val TAG = "AetherPlayer"

        private const val UNKNOWN_SSID = "<unknown ssid>"
        private const val INVALID_NETWORK_ID = -1
    }
}

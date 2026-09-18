package com.mccallandrew.aetherplayer

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat

/*
 * Tracks currently connected Bluetooth audio devices (A2DP and
 * Headset) for the kiosk home tile subtitle.
 */
class BluetoothDevicesHelper(
    context: Context,
    private val listener: Listener
) {

    interface Listener {
        fun onConnectedDevicesChanged(summary: String)
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    private val bluetoothManager =
        appContext.getSystemService(Context.BLUETOOTH_SERVICE)
            as BluetoothManager?

    private val adapter: BluetoothAdapter? =
        bluetoothManager?.adapter

    private var a2dpProxy: BluetoothProfile? = null
    private var headsetProxy: BluetoothProfile? = null
    private var started = false

    private val broadcastReceiver = object : BroadcastReceiver() {

        override fun onReceive(
            context: Context,
            intent: Intent
        ) {
            publish()
        }
    }

    private val profileListener = object : BluetoothProfile.ServiceListener {

        override fun onServiceConnected(
            profile: Int,
            proxy: BluetoothProfile
        ) {
            when (profile) {
                BluetoothProfile.A2DP -> a2dpProxy = proxy
                BluetoothProfile.HEADSET -> headsetProxy = proxy
            }

            publish()
        }

        override fun onServiceDisconnected(profile: Int) {
            when (profile) {
                BluetoothProfile.A2DP -> a2dpProxy = null
                BluetoothProfile.HEADSET -> headsetProxy = null
            }

            publish()
        }
    }

    fun start() {
        if (started) {
            return
        }

        started = true

        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(ACTION_A2DP_CONNECTION_STATE_CHANGED)
            addAction(ACTION_HEADSET_CONNECTION_STATE_CHANGED)
        }

        ContextCompat.registerReceiver(
            appContext,
            broadcastReceiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED
        )

        connectProfiles()
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

        val bluetoothAdapter = adapter

        if (bluetoothAdapter != null) {
            a2dpProxy?.let {
                bluetoothAdapter.closeProfileProxy(BluetoothProfile.A2DP, it)
            }
            headsetProxy?.let {
                bluetoothAdapter.closeProfileProxy(
                    BluetoothProfile.HEADSET,
                    it
                )
            }
        }

        a2dpProxy = null
        headsetProxy = null
    }

    fun refresh() {
        publish()
    }

    private fun connectProfiles() {
        val bluetoothAdapter = adapter ?: return

        if (!hasConnectPermission()) {
            return
        }

        try {
            bluetoothAdapter.getProfileProxy(
                appContext,
                profileListener,
                BluetoothProfile.A2DP
            )
            bluetoothAdapter.getProfileProxy(
                appContext,
                profileListener,
                BluetoothProfile.HEADSET
            )
        } catch (exception: SecurityException) {
            Log.w(TAG, "Unable to open Bluetooth profile proxies.", exception)
        }
    }

    private fun publish() {
        mainHandler.post {
            if (!started) {
                return@post
            }

            listener.onConnectedDevicesChanged(buildSummary())
        }
    }

    private fun buildSummary(): String {
        if (!hasConnectPermission()) {
            return appContext.getString(R.string.bluetooth_no_devices)
        }

        val bluetoothAdapter = adapter
            ?: return appContext.getString(R.string.bluetooth_no_devices)

        try {
            if (!bluetoothAdapter.isEnabled) {
                return appContext.getString(R.string.bluetooth_off)
            }
        } catch (exception: SecurityException) {
            Log.w(TAG, "Unable to read Bluetooth adapter state.", exception)
            return appContext.getString(R.string.bluetooth_no_devices)
        }

        val devices = linkedMapOf<String, String>()

        collectConnected(a2dpProxy, devices)
        collectConnected(headsetProxy, devices)

        if (devices.isEmpty()) {
            return appContext.getString(R.string.bluetooth_no_devices)
        }

        return devices.values.joinToString(", ")
    }

    private fun collectConnected(
        proxy: BluetoothProfile?,
        devices: MutableMap<String, String>
    ) {
        if (proxy == null) {
            return
        }

        val connected = try {
            proxy.connectedDevices
        } catch (exception: SecurityException) {
            Log.w(TAG, "Unable to list connected Bluetooth devices.", exception)
            return
        }

        for (device in connected) {
            val address = try {
                device.address
            } catch (_: SecurityException) {
                continue
            }

            val name = try {
                device.name?.takeIf { it.isNotBlank() }
            } catch (_: SecurityException) {
                null
            } ?: address

            devices.putIfAbsent(address, name)
        }
    }

    private fun hasConnectPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true
        }

        return ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED
    }

    companion object {

        private const val TAG = "AetherPlayer"

        /*
         * Profile connection broadcasts are not on BluetoothDevice
         * as public constants for every OEM API level we target, so
         * the action strings are kept here.
         */
        private const val ACTION_A2DP_CONNECTION_STATE_CHANGED =
            "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED"

        private const val ACTION_HEADSET_CONNECTION_STATE_CHANGED =
            "android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED"
    }
}

package com.mccallandrew.aetherplayer

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

/*
 * In-app Wi-Fi connection screen used from the kiosk home tile.
 * While open, lock-task temporarily allowlists Settings /
 * captive-portal packages so system Wi-Fi UI is not blocked as
 * "Missing app". The allowlist is restored on exit.
 */
class WifiActivity :
    Activity(),
    WifiController.Listener {

    private lateinit var buttonBack: ImageButton
    private lateinit var wifiSwitch: Switch
    private lateinit var wifiStatus: TextView
    private lateinit var savedNetworkList: LinearLayout
    private lateinit var savedEmpty: TextView
    private lateinit var availableNetworkList: LinearLayout
    private lateinit var availableEmpty: TextView
    private lateinit var buttonScan: TextView

    private var controller: WifiController? = null
    private var updatingSwitch = false
    private var passwordDialog: AlertDialog? = null
    private var connectingSsid: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_wifi)

        buttonBack = findViewById(R.id.button_back)
        wifiSwitch = findViewById(R.id.wifi_switch)
        wifiStatus = findViewById(R.id.wifi_status)
        savedNetworkList = findViewById(R.id.saved_network_list)
        savedEmpty = findViewById(R.id.saved_empty)
        availableNetworkList = findViewById(R.id.available_network_list)
        availableEmpty = findViewById(R.id.available_empty)
        buttonScan = findViewById(R.id.button_scan)

        buttonBack.setOnClickListener {
            finish()
        }

        wifiSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (updatingSwitch) {
                return@setOnCheckedChangeListener
            }

            controller?.setAdapterEnabled(isChecked)
        }

        buttonScan.setOnClickListener {
            val active = controller ?: return@setOnClickListener

            if (buttonScan.tag == TAG_STOP_SCAN) {
                active.stopScan()
            } else {
                active.startScan()
            }
        }
    }

    override fun onResume() {
        super.onResume()

        KioskCommandReceiver.applyLockTaskPackages(
            this,
            KioskCommandReceiver.wifiLockTaskPackages(this)
        )

        if (controller == null) {
            val active = WifiController(this, this)
            controller = active
            active.start()
        } else {
            controller?.refresh()
        }
    }

    override fun onPause() {
        controller?.stopScan()
        super.onPause()
    }

    override fun onDestroy() {
        passwordDialog?.dismiss()
        passwordDialog = null

        controller?.stop()
        controller = null

        KioskCommandReceiver.applyLockTaskPackages(
            this,
            KioskCommandReceiver.defaultLockTaskPackages(this)
        )

        super.onDestroy()
    }

    override fun onWifiStateChanged(
        state: WifiController.State
    ) {
        connectingSsid = state.connectingSsid

        updatingSwitch = true
        wifiSwitch.isChecked = state.adapterEnabled
        wifiSwitch.isEnabled = true
        updatingSwitch = false

        if (state.statusMessage.isNullOrBlank()) {
            wifiStatus.visibility = View.GONE
            wifiStatus.text = ""
        } else {
            wifiStatus.visibility = View.VISIBLE
            wifiStatus.text = state.statusMessage
        }

        buttonScan.isEnabled = state.adapterEnabled

        if (state.scanning) {
            buttonScan.text = getString(R.string.wifi_stop_scan)
            buttonScan.tag = TAG_STOP_SCAN
        } else {
            buttonScan.text = getString(R.string.wifi_scan)
            buttonScan.tag = TAG_START_SCAN
        }

        renderSavedNetworks(state)
        renderAvailableNetworks(state)
    }

    override fun onPasswordRequired(
        ssid: String,
        submitPassword: (String?) -> Unit
    ) {
        passwordDialog?.dismiss()

        val input = EditText(this).apply {
            inputType =
                InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = getString(R.string.wifi_password_hint)
            setPadding(48, 32, 48, 16)
        }

        var submitted = false

        passwordDialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.wifi_password_title, ssid))
            .setView(input)
            .setPositiveButton(R.string.wifi_password_connect) { _, _ ->
                submitted = true
                submitPassword(input.text?.toString())
            }
            .setNegativeButton(R.string.wifi_password_cancel) { _, _ ->
                submitted = true
                submitPassword(null)
            }
            .setOnDismissListener {
                if (!submitted) {
                    submitPassword(null)
                }
                passwordDialog = null
            }
            .show()
    }

    private fun renderSavedNetworks(
        state: WifiController.State
    ) {
        savedNetworkList.removeAllViews()

        if (!state.adapterEnabled || state.savedNetworks.isEmpty()) {
            savedEmpty.visibility = View.VISIBLE
            savedEmpty.setText(
                if (state.adapterEnabled) {
                    R.string.wifi_no_saved_networks
                } else {
                    R.string.wifi_off
                }
            )
            return
        }

        savedEmpty.visibility = View.GONE

        val inflater = LayoutInflater.from(this)

        for (network in state.savedNetworks) {
            val row = inflater.inflate(
                R.layout.item_bluetooth_device,
                savedNetworkList,
                false
            )

            bindSavedRow(row, network)
            savedNetworkList.addView(row)
        }
    }

    private fun renderAvailableNetworks(
        state: WifiController.State
    ) {
        availableNetworkList.removeAllViews()

        if (!state.adapterEnabled) {
            availableEmpty.visibility = View.VISIBLE
            availableEmpty.setText(R.string.wifi_off)
            return
        }

        if (state.availableNetworks.isEmpty()) {
            availableEmpty.visibility = View.VISIBLE
            availableEmpty.setText(
                if (state.scanning) {
                    R.string.wifi_scanning
                } else {
                    R.string.wifi_no_available_networks
                }
            )
            return
        }

        availableEmpty.visibility = View.GONE

        val inflater = LayoutInflater.from(this)

        for (network in state.availableNetworks) {
            val row = inflater.inflate(
                R.layout.item_bluetooth_device,
                availableNetworkList,
                false
            )

            bindAvailableRow(row, network)
            availableNetworkList.addView(row)
        }
    }

    private fun bindSavedRow(
        row: View,
        network: WifiController.NetworkItem
    ) {
        val nameView = row.findViewById<TextView>(R.id.device_name)
        val statusView = row.findViewById<TextView>(R.id.device_status)
        val primaryAction =
            row.findViewById<TextView>(R.id.device_primary_action)
        val secondaryAction =
            row.findViewById<TextView>(R.id.device_secondary_action)

        nameView.text = network.ssid

        if (network.removable) {
            secondaryAction.visibility = View.VISIBLE
            secondaryAction.setText(R.string.wifi_forget)
            secondaryAction.setOnClickListener {
                controller?.forgetNetwork(network.ssid)
            }
        } else {
            secondaryAction.visibility = View.GONE
            secondaryAction.setOnClickListener(null)
        }

        val connecting = network.ssid == connectingSsid

        when {
            network.connected -> {
                statusView.setText(R.string.wifi_status_connected)
                primaryAction.isEnabled = true
                primaryAction.setText(R.string.wifi_disconnect)
                primaryAction.setOnClickListener {
                    controller?.disconnectNetwork(network.ssid)
                }
            }

            connecting -> {
                statusView.setText(R.string.wifi_status_connecting)
                primaryAction.isEnabled = false
                primaryAction.setText(R.string.wifi_connect)
                primaryAction.setOnClickListener(null)
            }

            !network.removable -> {
                statusView.setText(R.string.wifi_status_carrier)
                primaryAction.isEnabled = false
                primaryAction.setText(R.string.wifi_connect)
                primaryAction.setOnClickListener(null)
            }

            else -> {
                statusView.setText(R.string.wifi_status_saved)
                primaryAction.isEnabled = true
                primaryAction.setText(R.string.wifi_connect)
                primaryAction.setOnClickListener {
                    controller?.connectNetwork(network.ssid)
                }
            }
        }
    }

    private fun bindAvailableRow(
        row: View,
        network: WifiController.NetworkItem
    ) {
        val nameView = row.findViewById<TextView>(R.id.device_name)
        val statusView = row.findViewById<TextView>(R.id.device_status)
        val primaryAction =
            row.findViewById<TextView>(R.id.device_primary_action)
        val secondaryAction =
            row.findViewById<TextView>(R.id.device_secondary_action)

        nameView.text = network.ssid
        secondaryAction.visibility = View.GONE

        val connecting = network.ssid == connectingSsid

        if (connecting) {
            statusView.setText(R.string.wifi_status_connecting)
            primaryAction.isEnabled = false
            primaryAction.setText(R.string.wifi_connect)
            primaryAction.setOnClickListener(null)
        } else {
            statusView.setText(
                if (network.secured) {
                    R.string.wifi_status_secured
                } else {
                    R.string.wifi_status_open
                }
            )
            primaryAction.isEnabled = true
            primaryAction.setText(R.string.wifi_connect)
            primaryAction.setOnClickListener {
                controller?.connectNetwork(network.ssid)
            }
        }
    }

    companion object {
        private const val TAG_START_SCAN = "start"
        private const val TAG_STOP_SCAN = "stop"
    }
}

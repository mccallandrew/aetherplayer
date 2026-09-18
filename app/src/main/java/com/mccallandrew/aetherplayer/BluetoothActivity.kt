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
 * In-app Bluetooth pairing screen used from the kiosk home tile.
 * While open, lock-task temporarily allowlists Settings / Fast
 * Pair packages so system pairing UI is not blocked as
 * "Missing app". The allowlist is restored on exit.
 */
class BluetoothActivity :
    Activity(),
    BluetoothController.Listener {

    private lateinit var buttonBack: ImageButton
    private lateinit var bluetoothSwitch: Switch
    private lateinit var bluetoothStatus: TextView
    private lateinit var bondedDeviceList: LinearLayout
    private lateinit var bondedEmpty: TextView
    private lateinit var availableDeviceList: LinearLayout
    private lateinit var availableEmpty: TextView
    private lateinit var buttonScan: TextView

    private var controller: BluetoothController? = null
    private var updatingSwitch = false
    private var pinDialog: AlertDialog? = null
    private var connectingAddress: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_bluetooth)

        buttonBack = findViewById(R.id.button_back)
        bluetoothSwitch = findViewById(R.id.bluetooth_switch)
        bluetoothStatus = findViewById(R.id.bluetooth_status)
        bondedDeviceList = findViewById(R.id.bonded_device_list)
        bondedEmpty = findViewById(R.id.bonded_empty)
        availableDeviceList = findViewById(R.id.available_device_list)
        availableEmpty = findViewById(R.id.available_empty)
        buttonScan = findViewById(R.id.button_scan)

        buttonBack.setOnClickListener {
            finish()
        }

        bluetoothSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (updatingSwitch) {
                return@setOnCheckedChangeListener
            }

            controller?.setAdapterEnabled(isChecked)
        }

        buttonScan.setOnClickListener {
            val active = controller ?: return@setOnClickListener

            if (buttonScan.tag == TAG_STOP_SCAN) {
                active.stopDiscovery()
            } else {
                active.startDiscovery()
            }
        }
    }

    override fun onResume() {
        super.onResume()

        KioskCommandReceiver.applyLockTaskPackages(
            this,
            KioskCommandReceiver.bluetoothLockTaskPackages(this)
        )

        if (controller == null) {
            val active = BluetoothController(this, this)
            controller = active
            active.start()
        } else {
            controller?.refresh()
        }
    }

    override fun onPause() {
        controller?.stopDiscovery()
        super.onPause()
    }

    override fun onDestroy() {
        pinDialog?.dismiss()
        pinDialog = null

        controller?.stop()
        controller = null

        KioskCommandReceiver.applyLockTaskPackages(
            this,
            KioskCommandReceiver.defaultLockTaskPackages(this)
        )

        super.onDestroy()
    }

    override fun onBluetoothStateChanged(
        state: BluetoothController.State
    ) {
        connectingAddress = state.connectingAddress

        updatingSwitch = true
        bluetoothSwitch.isChecked = state.adapterEnabled
        bluetoothSwitch.isEnabled = true
        updatingSwitch = false

        if (state.statusMessage.isNullOrBlank()) {
            bluetoothStatus.visibility = View.GONE
            bluetoothStatus.text = ""
        } else {
            bluetoothStatus.visibility = View.VISIBLE
            bluetoothStatus.text = state.statusMessage
        }

        buttonScan.isEnabled = state.adapterEnabled

        if (state.discovering) {
            buttonScan.text = getString(R.string.bluetooth_stop_scan)
            buttonScan.tag = TAG_STOP_SCAN
        } else {
            buttonScan.text = getString(R.string.bluetooth_scan)
            buttonScan.tag = TAG_START_SCAN
        }

        renderBondedDevices(state)
        renderAvailableDevices(state)
    }

    override fun onPairingPinRequired(
        deviceName: String,
        submitPin: (String?) -> Unit
    ) {
        pinDialog?.dismiss()

        val input = EditText(this).apply {
            inputType =
                InputType.TYPE_CLASS_NUMBER or
                    InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = getString(R.string.bluetooth_pin_hint)
            setPadding(48, 32, 48, 16)
        }

        var submitted = false

        pinDialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.bluetooth_pin_title, deviceName))
            .setView(input)
            .setPositiveButton(R.string.bluetooth_pin_pair) { _, _ ->
                submitted = true
                submitPin(input.text?.toString())
            }
            .setNegativeButton(R.string.bluetooth_pin_cancel) { _, _ ->
                submitted = true
                submitPin(null)
            }
            .setOnDismissListener {
                if (!submitted) {
                    submitPin(null)
                }
                pinDialog = null
            }
            .show()
    }

    private fun renderBondedDevices(
        state: BluetoothController.State
    ) {
        bondedDeviceList.removeAllViews()

        if (!state.adapterEnabled || state.bondedDevices.isEmpty()) {
            bondedEmpty.visibility = View.VISIBLE
            bondedEmpty.setText(
                if (state.adapterEnabled) {
                    R.string.bluetooth_no_paired_devices
                } else {
                    R.string.bluetooth_off
                }
            )
            return
        }

        bondedEmpty.visibility = View.GONE

        val inflater = LayoutInflater.from(this)

        for (device in state.bondedDevices) {
            val row = inflater.inflate(
                R.layout.item_bluetooth_device,
                bondedDeviceList,
                false
            )

            bindBondedRow(row, device)
            bondedDeviceList.addView(row)
        }
    }

    private fun renderAvailableDevices(
        state: BluetoothController.State
    ) {
        availableDeviceList.removeAllViews()

        if (!state.adapterEnabled) {
            availableEmpty.visibility = View.VISIBLE
            availableEmpty.setText(R.string.bluetooth_off)
            return
        }

        if (state.availableDevices.isEmpty()) {
            availableEmpty.visibility = View.VISIBLE
            availableEmpty.setText(
                if (state.discovering) {
                    R.string.bluetooth_scanning
                } else {
                    R.string.bluetooth_no_available_devices
                }
            )
            return
        }

        availableEmpty.visibility = View.GONE

        val inflater = LayoutInflater.from(this)

        for (device in state.availableDevices) {
            val row = inflater.inflate(
                R.layout.item_bluetooth_device,
                availableDeviceList,
                false
            )

            bindAvailableRow(row, device)
            availableDeviceList.addView(row)
        }
    }

    private fun bindBondedRow(
        row: View,
        device: BluetoothController.DeviceItem
    ) {
        val nameView = row.findViewById<TextView>(R.id.device_name)
        val statusView = row.findViewById<TextView>(R.id.device_status)
        val primaryAction =
            row.findViewById<TextView>(R.id.device_primary_action)
        val secondaryAction =
            row.findViewById<TextView>(R.id.device_secondary_action)

        nameView.text = device.name
        secondaryAction.visibility = View.VISIBLE
        secondaryAction.setOnClickListener {
            controller?.forgetDevice(device.address)
        }

        val connecting = device.address == connectingAddress

        when {
            device.connected -> {
                statusView.setText(R.string.bluetooth_status_connected)
                primaryAction.isEnabled = true
                primaryAction.setText(R.string.bluetooth_disconnect)
                primaryAction.setOnClickListener {
                    controller?.disconnectDevice(device.address)
                }
            }

            connecting -> {
                statusView.setText(R.string.bluetooth_status_connecting)
                primaryAction.isEnabled = false
                primaryAction.setText(R.string.bluetooth_connect)
                primaryAction.setOnClickListener(null)
            }

            else -> {
                statusView.setText(R.string.bluetooth_status_paired)
                primaryAction.isEnabled = true
                primaryAction.setText(R.string.bluetooth_connect)
                primaryAction.setOnClickListener {
                    controller?.connectDevice(device.address)
                }
            }
        }
    }

    private fun bindAvailableRow(
        row: View,
        device: BluetoothController.DeviceItem
    ) {
        val nameView = row.findViewById<TextView>(R.id.device_name)
        val statusView = row.findViewById<TextView>(R.id.device_status)
        val primaryAction =
            row.findViewById<TextView>(R.id.device_primary_action)
        val secondaryAction =
            row.findViewById<TextView>(R.id.device_secondary_action)

        nameView.text = device.name
        secondaryAction.visibility = View.GONE

        val connecting = device.address == connectingAddress

        if (connecting) {
            statusView.setText(R.string.bluetooth_status_connecting)
            primaryAction.isEnabled = false
            primaryAction.setText(R.string.bluetooth_pair)
            primaryAction.setOnClickListener(null)
        } else {
            statusView.setText(R.string.bluetooth_status_available)
            primaryAction.isEnabled = true
            primaryAction.setText(R.string.bluetooth_pair)
            primaryAction.setOnClickListener {
                controller?.pairDevice(device.address)
            }
        }
    }

    companion object {
        private const val TAG_START_SCAN = "start"
        private const val TAG_STOP_SCAN = "stop"
    }
}

package com.mccallandrew.aetherplayer

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
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
import java.util.UUID

/*
 * Owns Bluetooth adapter state, discovery, bonding, and A2DP /
 * Headset connect-disconnect for the in-app pairing screen.
 *
 * Profile connect/disconnect and removeBond are hidden APIs, so
 * they are invoked through reflection. Pairing confirmations are
 * handled here so system Settings UI is not required under lock
 * task.
 */
class BluetoothController(
    context: Context,
    private val listener: Listener
) {

    interface Listener {
        fun onBluetoothStateChanged(state: State)

        fun onPairingPinRequired(
            deviceName: String,
            submitPin: (String?) -> Unit
        )
    }

    data class DeviceItem(
        val address: String,
        val name: String,
        val bonded: Boolean,
        val connected: Boolean
    )

    data class State(
        val adapterEnabled: Boolean,
        val discovering: Boolean,
        val bondedDevices: List<DeviceItem>,
        val availableDevices: List<DeviceItem>,
        val connectingAddress: String? = null,
        val statusMessage: String? = null
    )

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
    private var discovering = false
    private var pendingConnectAddress: String? = null
    private var connectAttemptToken = 0
    private var aclKickStarted = false
    private var statusMessage: String? = null

    private val availableByAddress =
        linkedMapOf<String, DeviceItem>()

    private val connectTimeoutRunnable = Runnable {
        failPendingConnect()
    }

    private val broadcastReceiver = object : BroadcastReceiver() {

        override fun onReceive(
            context: Context,
            intent: Intent
        ) {
            when (intent.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(
                        BluetoothAdapter.EXTRA_STATE,
                        BluetoothAdapter.ERROR
                    )

                    if (state == BluetoothAdapter.STATE_ON) {
                        connectProfiles()
                    }

                    if (
                        state == BluetoothAdapter.STATE_OFF ||
                        state == BluetoothAdapter.STATE_TURNING_OFF
                    ) {
                        discovering = false
                        availableByAddress.clear()
                        clearPendingConnect()
                    }

                    publish()
                }

                BluetoothAdapter.ACTION_DISCOVERY_STARTED -> {
                    discovering = true
                    publish()
                }

                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    discovering = false
                    publish()
                }

                BluetoothDevice.ACTION_FOUND -> {
                    onDeviceFound(intent)
                }

                BluetoothDevice.ACTION_NAME_CHANGED -> {
                    onDeviceNameChanged(intent)
                }

                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    onBondStateChanged(intent)
                }

                BluetoothDevice.ACTION_ACL_CONNECTED,
                BluetoothDevice.ACTION_ACL_DISCONNECTED,
                ACTION_A2DP_CONNECTION_STATE_CHANGED,
                ACTION_HEADSET_CONNECTION_STATE_CHANGED -> {
                    onConnectionStatePossiblyChanged()
                }

                BluetoothDevice.ACTION_PAIRING_REQUEST -> {
                    handlePairingRequest(this, intent)
                }
            }
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

            connectPendingIfNeeded()
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
        exemptHiddenApis()

        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothDevice.ACTION_NAME_CHANGED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(ACTION_A2DP_CONNECTION_STATE_CHANGED)
            addAction(ACTION_HEADSET_CONNECTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)
            priority = IntentFilter.SYSTEM_HIGH_PRIORITY
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
        clearPendingConnect()

        stopDiscoveryInternal()

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
        availableByAddress.clear()
    }

    fun refresh() {
        publish()
    }

    fun setAdapterEnabled(enabled: Boolean): Boolean {
        val bluetoothAdapter = adapter ?: return false

        if (!hasConnectPermission()) {
            statusMessage = appContext.getString(
                R.string.bluetooth_permission_missing
            )
            publish()
            return false
        }

        return try {
            val success = if (enabled) {
                @Suppress("DEPRECATION")
                bluetoothAdapter.enable()
            } else {
                stopDiscoveryInternal()
                @Suppress("DEPRECATION")
                bluetoothAdapter.disable()
            }

            if (!success) {
                statusMessage = appContext.getString(
                    R.string.bluetooth_toggle_failed
                )
                publish()
            }

            success
        } catch (exception: SecurityException) {
            Log.w(TAG, "Unable to toggle Bluetooth.", exception)
            statusMessage = appContext.getString(
                R.string.bluetooth_toggle_failed
            )
            publish()
            false
        }
    }

    fun startDiscovery(): Boolean {
        val bluetoothAdapter = adapter ?: return false

        if (!isAdapterEnabled()) {
            statusMessage = appContext.getString(R.string.bluetooth_off)
            publish()
            return false
        }

        if (!hasScanPermission()) {
            statusMessage = appContext.getString(
                R.string.bluetooth_permission_missing
            )
            publish()
            return false
        }

        availableByAddress.clear()
        statusMessage = null

        return try {
            if (bluetoothAdapter.isDiscovering) {
                bluetoothAdapter.cancelDiscovery()
            }

            val startedDiscovery = bluetoothAdapter.startDiscovery()

            if (!startedDiscovery) {
                statusMessage = appContext.getString(
                    R.string.bluetooth_scan_failed
                )
                publish()
            } else {
                discovering = true
                publish()
            }

            startedDiscovery
        } catch (exception: SecurityException) {
            Log.w(TAG, "Unable to start discovery.", exception)
            statusMessage = appContext.getString(
                R.string.bluetooth_scan_failed
            )
            publish()
            false
        }
    }

    fun stopDiscovery() {
        stopDiscoveryInternal()
        publish()
    }

    fun pairDevice(address: String): Boolean {
        val device = remoteDevice(address) ?: return false

        if (!hasConnectPermission()) {
            statusMessage = appContext.getString(
                R.string.bluetooth_permission_missing
            )
            publish()
            return false
        }

        stopDiscoveryInternal()
        cancelConnectAttempts()
        pendingConnectAddress = address
        statusMessage = appContext.getString(
            R.string.bluetooth_connect_pending
        )

        return try {
            if (device.bondState == BluetoothDevice.BOND_BONDED) {
                connectDevice(address)
            } else {
                val startedBond = createBondForAudio(device)

                if (!startedBond) {
                    clearPendingConnect()
                    statusMessage = appContext.getString(
                        R.string.bluetooth_pair_failed
                    )
                    publish()
                } else {
                    publish()
                }

                startedBond
            }
        } catch (exception: SecurityException) {
            Log.w(TAG, "Unable to pair device.", exception)
            clearPendingConnect()
            statusMessage = appContext.getString(
                R.string.bluetooth_pair_failed
            )
            publish()
            false
        }
    }

    fun connectDevice(address: String): Boolean {
        val device = remoteDevice(address) ?: return false

        if (!hasConnectPermission()) {
            statusMessage = appContext.getString(
                R.string.bluetooth_permission_missing
            )
            publish()
            return false
        }

        if (isLeOnlyDevice(device)) {
            statusMessage = appContext.getString(
                R.string.bluetooth_connect_le_only
            )
            publish()
            return false
        }

        stopDiscoveryInternal()
        beginPendingConnect(address)

        connectProfiles()
        scheduleConnectAttempts()
        publish()
        return true
    }

    fun disconnectDevice(address: String): Boolean {
        val device = remoteDevice(address) ?: return false

        if (!hasConnectPermission()) {
            return false
        }

        if (pendingConnectAddress == address) {
            clearPendingConnect()
        }

        val disconnectedA2dp = disconnectProfile(a2dpProxy, device)
        val disconnectedHeadset = disconnectProfile(headsetProxy, device)
        val disconnected = disconnectedA2dp || disconnectedHeadset

        if (!disconnected) {
            statusMessage = appContext.getString(
                R.string.bluetooth_disconnect_failed
            )
        } else {
            statusMessage = null
        }

        publish()
        return disconnected
    }

    fun forgetDevice(address: String): Boolean {
        val device = remoteDevice(address) ?: return false

        if (!hasConnectPermission()) {
            return false
        }

        if (isAddressConnected(address)) {
            disconnectDevice(address)
        }

        if (pendingConnectAddress == address) {
            clearPendingConnect()
        }

        val removed = removeBond(device)

        if (!removed) {
            statusMessage = appContext.getString(
                R.string.bluetooth_forget_failed
            )
        } else {
            statusMessage = null
        }

        publish()
        return removed
    }

    private fun onDeviceFound(intent: Intent) {
        val device = deviceExtra(intent) ?: return
        val address = safeAddress(device) ?: return

        if (isBondedAddress(address)) {
            return
        }

        /*
         * System Bluetooth settings hides unnamed inquiry
         * results. Prefer EXTRA_NAME from the found broadcast;
         * device.name is often still empty on the first hit.
         */
        val name = intent.getStringExtra(BluetoothDevice.EXTRA_NAME)
            ?.takeIf { isUsableDeviceName(it) }
            ?: safeName(device)?.takeIf { isUsableDeviceName(it) }

        if (name == null) {
            return
        }

        if (!isAudioCandidate(device)) {
            return
        }

        availableByAddress[address] = DeviceItem(
            address = address,
            name = name,
            bonded = false,
            connected = false
        )

        publish()
    }

    private fun onDeviceNameChanged(intent: Intent) {
        val device = deviceExtra(intent) ?: return
        val address = safeAddress(device) ?: return

        if (isBondedAddress(address)) {
            return
        }

        val name = intent.getStringExtra(BluetoothDevice.EXTRA_NAME)
            ?.takeIf { isUsableDeviceName(it) }
            ?: safeName(device)?.takeIf { isUsableDeviceName(it) }
            ?: return

        if (!isAudioCandidate(device)) {
            return
        }

        availableByAddress[address] = DeviceItem(
            address = address,
            name = name,
            bonded = false,
            connected = false
        )

        publish()
    }

    private fun onBondStateChanged(intent: Intent) {
        val device = deviceExtra(intent) ?: return
        val bondState = intent.getIntExtra(
            BluetoothDevice.EXTRA_BOND_STATE,
            BluetoothDevice.BOND_NONE
        )
        val address = safeAddress(device)

        if (bondState == BluetoothDevice.BOND_BONDED) {
            if (address != null) {
                availableByAddress.remove(address)
            }

            val connectAddress = address ?: pendingConnectAddress

            if (connectAddress != null) {
                beginPendingConnect(connectAddress)
                scheduleConnectAttempts()
            }
        }

        if (
            bondState == BluetoothDevice.BOND_NONE &&
            address != null &&
            pendingConnectAddress == address
        ) {
            clearPendingConnect()
            statusMessage = appContext.getString(
                R.string.bluetooth_pair_failed
            )
        }

        publish()
    }

    private fun handlePairingRequest(
        receiver: BroadcastReceiver,
        intent: Intent
    ) {
        val device = deviceExtra(intent) ?: return

        val variant = intent.getIntExtra(
            BluetoothDevice.EXTRA_PAIRING_VARIANT,
            BluetoothDevice.ERROR
        )

        try {
            when (variant) {
                BluetoothDevice.PAIRING_VARIANT_PASSKEY_CONFIRMATION,
                PAIRING_VARIANT_CONSENT,
                PAIRING_VARIANT_DISPLAY_PASSKEY,
                PAIRING_VARIANT_DISPLAY_PIN,
                PAIRING_VARIANT_OOB_CONSENT -> {
                    device.setPairingConfirmation(true)
                    abortOrderedBroadcast(receiver)
                }

                BluetoothDevice.PAIRING_VARIANT_PIN,
                PAIRING_VARIANT_PIN_16_DIGITS -> {
                    abortOrderedBroadcast(receiver)

                    val deviceName =
                        safeName(device) ?: safeAddress(device) ?: "device"

                    mainHandler.post {
                        if (!started) {
                            return@post
                        }

                        listener.onPairingPinRequired(deviceName) { pin ->
                            try {
                                if (pin.isNullOrBlank()) {
                                    device.setPairingConfirmation(false)
                                } else {
                                    device.setPin(
                                        pin.toByteArray(Charsets.UTF_8)
                                    )
                                    device.setPairingConfirmation(true)
                                }
                            } catch (exception: SecurityException) {
                                Log.w(
                                    TAG,
                                    "Unable to submit pairing PIN.",
                                    exception
                                )
                            }
                        }
                    }
                }

                else -> {
                    /*
                     * Unknown OEM / Fast Pair variants still need
                     * an in-app confirm so Settings is not required
                     * under lock task.
                     */
                    Log.i(TAG, "Confirming pairing variant: $variant")
                    device.setPairingConfirmation(true)
                    abortOrderedBroadcast(receiver)
                }
            }
        } catch (exception: SecurityException) {
            Log.w(TAG, "Unable to confirm pairing.", exception)
        }
    }

    private fun abortOrderedBroadcast(receiver: BroadcastReceiver) {
        try {
            receiver.abortBroadcast()
        } catch (_: Exception) {
            // Not an ordered broadcast on this OEM.
        }
    }

    private fun connectProfiles() {
        val bluetoothAdapter = adapter ?: return

        if (!hasConnectPermission() || !isAdapterEnabled()) {
            return
        }

        try {
            if (a2dpProxy == null) {
                bluetoothAdapter.getProfileProxy(
                    appContext,
                    profileListener,
                    BluetoothProfile.A2DP
                )
            }

            if (headsetProxy == null) {
                bluetoothAdapter.getProfileProxy(
                    appContext,
                    profileListener,
                    BluetoothProfile.HEADSET
                )
            }
        } catch (exception: SecurityException) {
            Log.w(TAG, "Unable to open Bluetooth profile proxies.", exception)
        }
    }

    private fun connectPendingIfNeeded() {
        val address = pendingConnectAddress ?: return
        val device = remoteDevice(address) ?: return

        if (isAddressConnected(address)) {
            clearPendingConnect(clearStatus = true)
            publish()
            return
        }

        val bonded = try {
            device.bondState == BluetoothDevice.BOND_BONDED
        } catch (_: SecurityException) {
            false
        }

        if (!bonded) {
            return
        }

        /*
         * Request both audio profiles. Earbuds often need A2DP
         * for music and HFP for full stack settle. Also raise
         * connection policy so the stack is allowed to auto /
         * reconnect, then kick ACL if profile connect is denied.
         */
        val a2dpReady = a2dpProxy != null
        val headsetReady = headsetProxy != null

        if (!a2dpReady || !headsetReady) {
            Log.i(
                TAG,
                "Waiting for profile proxies (a2dp=$a2dpReady headset=$headsetReady)."
            )
            connectProfiles()
        }

        allowProfileConnection(a2dpProxy, device)
        allowProfileConnection(headsetProxy, device)

        val a2dpRequested = connectProfile(a2dpProxy, device)
        val headsetRequested = connectProfile(headsetProxy, device)

        Log.i(
            TAG,
            "Connect requested for $address a2dp=$a2dpRequested headset=$headsetRequested."
        )

        if (!isAddressConnected(address)) {
            if (!aclKickStarted) {
                aclKickStarted = true
                kickAclConnection(device)
            }
        }

        if (isAddressConnected(address)) {
            clearPendingConnect(clearStatus = true)
            publish()
        }
    }

    private fun onConnectionStatePossiblyChanged() {
        val address = pendingConnectAddress

        if (address != null && isAddressConnected(address)) {
            clearPendingConnect(clearStatus = true)
        }

        publish()
    }

    private fun beginPendingConnect(address: String) {
        cancelConnectAttempts()
        pendingConnectAddress = address
        aclKickStarted = false
        statusMessage = appContext.getString(
            R.string.bluetooth_connect_pending
        )
    }

    private fun scheduleConnectAttempts() {
        val token = connectAttemptToken

        mainHandler.postDelayed(
            connectTimeoutRunnable,
            CONNECT_TIMEOUT_MS
        )

        for (delayMs in CONNECT_RETRY_DELAYS_MS) {
            mainHandler.postDelayed(
                {
                    if (
                        !started ||
                        token != connectAttemptToken ||
                        pendingConnectAddress == null
                    ) {
                        return@postDelayed
                    }

                    connectPendingIfNeeded()
                },
                delayMs
            )
        }
    }

    private fun failPendingConnect() {
        if (pendingConnectAddress == null) {
            return
        }

        clearPendingConnect()
        statusMessage = appContext.getString(
            R.string.bluetooth_connect_failed
        )
        publish()
    }

    private fun clearPendingConnect(clearStatus: Boolean = false) {
        cancelConnectAttempts()
        pendingConnectAddress = null
        aclKickStarted = false

        if (clearStatus) {
            statusMessage = null
        }
    }

    private fun cancelConnectAttempts() {
        connectAttemptToken += 1
        mainHandler.removeCallbacks(connectTimeoutRunnable)
    }

    private fun stopDiscoveryInternal() {
        val bluetoothAdapter = adapter ?: return

        discovering = false

        if (!hasScanPermission()) {
            return
        }

        try {
            if (bluetoothAdapter.isDiscovering) {
                bluetoothAdapter.cancelDiscovery()
            }
        } catch (exception: SecurityException) {
            Log.w(TAG, "Unable to cancel discovery.", exception)
        }
    }

    private fun publish() {
        mainHandler.post {
            if (!started) {
                return@post
            }

            listener.onBluetoothStateChanged(buildState())
        }
    }

    private fun buildState(): State {
        val enabled = isAdapterEnabled()

        if (!enabled) {
            return State(
                adapterEnabled = false,
                discovering = false,
                bondedDevices = emptyList(),
                availableDevices = emptyList(),
                connectingAddress = pendingConnectAddress,
                statusMessage = statusMessage
                    ?: appContext.getString(R.string.bluetooth_off)
            )
        }

        return State(
            adapterEnabled = true,
            discovering = discovering,
            bondedDevices = bondedDevices(),
            availableDevices = availableByAddress.values.toList(),
            connectingAddress = pendingConnectAddress,
            statusMessage = statusMessage
        )
    }

    private fun bondedDevices(): List<DeviceItem> {
        if (!hasConnectPermission()) {
            return emptyList()
        }

        val bluetoothAdapter = adapter ?: return emptyList()

        val bonded = try {
            bluetoothAdapter.bondedDevices.orEmpty()
        } catch (exception: SecurityException) {
            Log.w(TAG, "Unable to list bonded devices.", exception)
            return emptyList()
        }

        return bonded
            .filter { isAudioCandidate(it) || isDeviceConnected(it) }
            .mapNotNull { device ->
                val address = safeAddress(device) ?: return@mapNotNull null

                DeviceItem(
                    address = address,
                    name = safeName(device) ?: address,
                    bonded = true,
                    connected = isAddressConnected(address)
                )
            }
            .sortedWith(
                compareByDescending<DeviceItem> { it.connected }
                    .thenBy { it.name.lowercase() }
            )
    }

    private fun isAdapterEnabled(): Boolean {
        val bluetoothAdapter = adapter ?: return false

        if (!hasConnectPermission()) {
            return false
        }

        return try {
            bluetoothAdapter.isEnabled
        } catch (exception: SecurityException) {
            Log.w(TAG, "Unable to read adapter state.", exception)
            false
        }
    }

    private fun isBondedAddress(address: String): Boolean {
        if (!hasConnectPermission()) {
            return false
        }

        val bluetoothAdapter = adapter ?: return false

        return try {
            bluetoothAdapter.bondedDevices.orEmpty().any { device ->
                safeAddress(device) == address
            }
        } catch (_: SecurityException) {
            false
        }
    }

    private fun isAddressConnected(address: String): Boolean {
        return connectedAddresses().contains(address)
    }

    private fun isDeviceConnected(device: BluetoothDevice): Boolean {
        val address = safeAddress(device) ?: return false
        return isAddressConnected(address)
    }

    private fun connectedAddresses(): Set<String> {
        val addresses = linkedSetOf<String>()

        collectConnectedAddresses(a2dpProxy, addresses)
        collectConnectedAddresses(headsetProxy, addresses)

        return addresses
    }

    private fun collectConnectedAddresses(
        proxy: BluetoothProfile?,
        addresses: MutableSet<String>
    ) {
        if (proxy == null) {
            return
        }

        val connected = try {
            proxy.connectedDevices
        } catch (exception: SecurityException) {
            Log.w(TAG, "Unable to list connected devices.", exception)
            return
        }

        for (device in connected) {
            safeAddress(device)?.let { addresses.add(it) }
        }
    }

    private fun isAudioCandidate(device: BluetoothDevice): Boolean {
        /*
         * JBL and other TWS buds often advertise a BLE "-LE"
         * identity alongside classic audio. Pairing the LE-only
         * address bonds successfully but A2DP/HFP can never
         * connect. Keep classic / dual-mode only.
         */
        if (isLeOnlyDevice(device)) {
            return false
        }

        val name = try {
            device.name
        } catch (_: SecurityException) {
            null
        }

        if (name?.contains("-LE", ignoreCase = true) == true) {
            return false
        }

        val bluetoothClass = try {
            device.bluetoothClass
        } catch (_: SecurityException) {
            null
        } ?: return true

        val major = bluetoothClass.majorDeviceClass

        /*
         * AUDIO_VIDEO is the real speaker/headset CoD. Many cheap
         * speakers still ship as UNCATEGORIZED; keep those only when
         * they already passed the usable-name check above. Drop MISC
         * and other majors (phones, computers, etc.).
         */
        return major == BluetoothClass.Device.Major.AUDIO_VIDEO ||
            major == BluetoothClass.Device.Major.UNCATEGORIZED
    }

    private fun isLeOnlyDevice(device: BluetoothDevice): Boolean {
        return try {
            device.type == BluetoothDevice.DEVICE_TYPE_LE
        } catch (_: SecurityException) {
            false
        }
    }

    /*
     * Prefer BR/EDR bonding so dual-mode earbuds expose A2DP.
     * Fall back to the public createBond() if the transport
     * overload is unavailable.
     */
    private fun createBondForAudio(device: BluetoothDevice): Boolean {
        return try {
            val method = device.javaClass.getMethod(
                "createBond",
                Int::class.javaPrimitiveType
            )
            val started = method.invoke(device, TRANSPORT_BREDR) as? Boolean

            if (started == true) {
                Log.i(TAG, "Started BR/EDR bond for ${safeAddress(device)}.")
                true
            } else {
                Log.w(
                    TAG,
                    "BR/EDR createBond returned false; falling back."
                )
                device.createBond()
            }
        } catch (exception: Exception) {
            Log.i(
                TAG,
                "BR/EDR createBond unavailable; using default bond.",
                exception
            )
            device.createBond()
        }
    }

    private fun isUsableDeviceName(name: String): Boolean {
        val trimmed = name.trim()

        if (trimmed.isEmpty()) {
            return false
        }

        /*
         * Reject bare MAC-shaped labels so inquiry hits without a
         * friendly name never appear in the available list.
         */
        return !MAC_ADDRESS_PATTERN.matches(trimmed)
    }

    private fun remoteDevice(address: String): BluetoothDevice? {
        val bluetoothAdapter = adapter ?: return null

        return try {
            bluetoothAdapter.getRemoteDevice(address)
        } catch (exception: IllegalArgumentException) {
            Log.w(TAG, "Invalid Bluetooth address: $address", exception)
            null
        }
    }

    private fun deviceExtra(intent: Intent): BluetoothDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(
                BluetoothDevice.EXTRA_DEVICE,
                BluetoothDevice::class.java
            )
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
    }

    private fun safeAddress(device: BluetoothDevice): String? {
        return try {
            device.address
        } catch (_: SecurityException) {
            null
        }
    }

    private fun safeName(device: BluetoothDevice): String? {
        return try {
            device.name?.takeIf { it.isNotBlank() }
        } catch (_: SecurityException) {
            null
        }
    }

    private fun connectProfile(
        proxy: BluetoothProfile?,
        device: BluetoothDevice
    ): Boolean {
        if (proxy == null) {
            return false
        }

        return invokeProfileBoolean(proxy, "connect", device)
    }

    private fun disconnectProfile(
        proxy: BluetoothProfile?,
        device: BluetoothDevice
    ): Boolean {
        if (proxy == null) {
            return false
        }

        return invokeProfileBoolean(proxy, "disconnect", device)
    }

    private fun allowProfileConnection(
        proxy: BluetoothProfile?,
        device: BluetoothDevice
    ) {
        if (proxy == null) {
            return
        }

        /*
         * PRIORITY_ON / CONNECTION_POLICY_ALLOWED. Without this,
         * Samsung often accepts pair but refuses later profile
         * connect from a third-party app.
         */
        if (!invokeProfileInt(
                proxy,
                "setConnectionPolicy",
                device,
                CONNECTION_POLICY_ALLOWED
            )
        ) {
            invokeProfileInt(
                proxy,
                "setPriority",
                device,
                PRIORITY_ON
            )
        }
    }

    private fun invokeProfileBoolean(
        proxy: BluetoothProfile,
        methodName: String,
        device: BluetoothDevice
    ): Boolean {
        return try {
            val method = resolveProfileMethod(
                proxy,
                methodName,
                BluetoothDevice::class.java
            ) ?: return false

            val result = method.invoke(proxy, device) as? Boolean ?: false

            if (!result) {
                Log.w(
                    TAG,
                    "$methodName returned false for " +
                        "${proxy.javaClass.name}."
                )
            }

            result
        } catch (exception: Exception) {
            Log.w(
                TAG,
                "Unable to invoke $methodName on ${proxy.javaClass.name}.",
                exception
            )
            false
        }
    }

    private fun invokeProfileInt(
        proxy: BluetoothProfile,
        methodName: String,
        device: BluetoothDevice,
        value: Int
    ): Boolean {
        return try {
            val method = resolveProfileMethod(
                proxy,
                methodName,
                BluetoothDevice::class.java,
                Int::class.javaPrimitiveType!!
            ) ?: return false

            val result = method.invoke(proxy, device, value)

            if (result is Boolean) {
                result
            } else {
                true
            }
        } catch (exception: Exception) {
            Log.w(
                TAG,
                "Unable to invoke $methodName on ${proxy.javaClass.name}.",
                exception
            )
            false
        }
    }

    private fun resolveProfileMethod(
        proxy: BluetoothProfile,
        methodName: String,
        vararg parameterTypes: Class<*>
    ): java.lang.reflect.Method? {
        var type: Class<*>? = proxy.javaClass

        while (type != null) {
            try {
                return type.getDeclaredMethod(methodName, *parameterTypes)
                    .apply { isAccessible = true }
            } catch (_: NoSuchMethodException) {
                type = type.superclass
            }
        }

        val fallbackClassName = when (proxy) {
            a2dpProxy -> "android.bluetooth.BluetoothA2dp"
            headsetProxy -> "android.bluetooth.BluetoothHeadset"
            else -> null
        } ?: return null

        return try {
            Class.forName(fallbackClassName)
                .getMethod(methodName, *parameterTypes)
                .apply { isAccessible = true }
        } catch (exception: Exception) {
            Log.w(
                TAG,
                "No $methodName on ${proxy.javaClass.name}.",
                exception
            )
            null
        }
    }

    /*
     * Opening a throwaway RFCOMM socket pages the remote device
     * and brings ACL up. Audio profiles often follow for bonded
     * earbuds when direct profile connect is blocked.
     */
    private fun kickAclConnection(device: BluetoothDevice) {
        Thread(
            {
                val uuids = listOf(
                    UUID.fromString(UUID_A2DP_SINK),
                    UUID.fromString(UUID_HFP_AG),
                    UUID.fromString(UUID_SPP)
                )

                for (uuid in uuids) {
                    if (!started || pendingConnectAddress == null) {
                        return@Thread
                    }

                    var socket: android.bluetooth.BluetoothSocket? = null

                    try {
                        Log.i(TAG, "ACL kick via $uuid")
                        @Suppress("DEPRECATION")
                        socket = device.createRfcommSocketToServiceRecord(uuid)
                        socket.connect()
                        Log.i(TAG, "ACL kick connected via $uuid")
                        break
                    } catch (exception: Exception) {
                        Log.i(
                            TAG,
                            "ACL kick via $uuid failed: ${exception.message}"
                        )
                    } finally {
                        try {
                            socket?.close()
                        } catch (_: Exception) {
                            // Ignore close failures.
                        }
                    }
                }

                mainHandler.post {
                    if (started) {
                        connectPendingIfNeeded()
                        publish()
                    }
                }
            },
            "bt-acl-kick"
        ).start()
    }

    private fun exemptHiddenApis() {
        try {
            val vmRuntime = Class.forName("dalvik.system.VMRuntime")
            val runtime = vmRuntime.getDeclaredMethod("getRuntime").invoke(null)
            val method = vmRuntime.getDeclaredMethod(
                "setHiddenApiExemptions",
                Array<String>::class.java
            )
            method.invoke(runtime, arrayOf("L"))
            Log.i(TAG, "Hidden API exemptions applied.")
        } catch (exception: Exception) {
            Log.w(TAG, "Unable to exempt hidden APIs.", exception)
        }
    }

    private fun removeBond(device: BluetoothDevice): Boolean {
        return try {
            val method = device.javaClass.getMethod("removeBond")
            method.invoke(device) as? Boolean ?: false
        } catch (exception: Exception) {
            Log.w(TAG, "Unable to remove bond.", exception)
            false
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

    private fun hasScanPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        }

        return ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.BLUETOOTH_SCAN
        ) == PackageManager.PERMISSION_GRANTED
    }

    companion object {

        private const val TAG = "AetherPlayer"

        private const val CONNECT_TIMEOUT_MS = 20_000L

        private val CONNECT_RETRY_DELAYS_MS = longArrayOf(
            0L,
            750L,
            1_500L,
            3_000L,
            6_000L,
            10_000L
        )

        private const val PRIORITY_ON = 100
        private const val CONNECTION_POLICY_ALLOWED = 100
        private const val TRANSPORT_BREDR = 1

        private const val UUID_SPP = "00001101-0000-1000-8000-00805F9B34FB"
        private const val UUID_A2DP_SINK = "0000110B-0000-1000-8000-00805F9B34FB"
        private const val UUID_HFP_AG = "0000111F-0000-1000-8000-00805F9B34FB"

        private const val PAIRING_VARIANT_CONSENT = 3
        private const val PAIRING_VARIANT_DISPLAY_PASSKEY = 4
        private const val PAIRING_VARIANT_DISPLAY_PIN = 5
        private const val PAIRING_VARIANT_OOB_CONSENT = 6
        private const val PAIRING_VARIANT_PIN_16_DIGITS = 7

        private val MAC_ADDRESS_PATTERN =
            Regex("(?i)^([0-9A-F]{2}:){5}[0-9A-F]{2}$")

        private const val ACTION_A2DP_CONNECTION_STATE_CHANGED =
            "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED"

        private const val ACTION_HEADSET_CONNECTION_STATE_CHANGED =
            "android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED"
    }
}

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
import android.net.wifi.ScanResult
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiEnterpriseConfig
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat

/*
 * Owns Wi-Fi adapter state, scanning, and connect / forget for
 * the in-app network screen. Device-owner privileges are what
 * still allow WifiConfiguration connect on modern Android, so
 * system Settings is not required under lock task.
 */
@Suppress("DEPRECATION")
class WifiController(
    context: Context,
    private val listener: Listener
) {

    interface Listener {
        fun onWifiStateChanged(state: State)

        fun onPasswordRequired(
            ssid: String,
            submitPassword: (String?) -> Unit
        )
    }

    data class NetworkItem(
        val ssid: String,
        val networkId: Int,
        val saved: Boolean,
        val connected: Boolean,
        val secured: Boolean,
        val removable: Boolean = true
    )

    data class State(
        val adapterEnabled: Boolean,
        val scanning: Boolean,
        val savedNetworks: List<NetworkItem>,
        val availableNetworks: List<NetworkItem>,
        val connectingSsid: String? = null,
        val statusMessage: String? = null
    )

    private enum class Security {
        OPEN,
        WEP,
        PSK,
        SAE,
        ENTERPRISE
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    private val wifiManager =
        appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager?

    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE)
            as ConnectivityManager

    private var started = false
    private var scanning = false
    private var pendingConnectSsid: String? = null
    private var statusMessage: String? = null
    private var networkCallbackRegistered = false

    private val availableBySsid = linkedMapOf<String, NetworkItem>()
    private val securityBySsid = hashMapOf<String, Security>()

    private val scanTimeoutRunnable = Runnable {
        if (scanning) {
            scanning = false
            publish()
        }
    }

    private val connectTimeoutRunnable = Runnable {
        failPendingConnect()
    }

    private val broadcastReceiver = object : BroadcastReceiver() {

        override fun onReceive(
            context: Context,
            intent: Intent
        ) {
            when (intent.action) {
                WifiManager.WIFI_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(
                        WifiManager.EXTRA_WIFI_STATE,
                        WifiManager.WIFI_STATE_UNKNOWN
                    )

                    if (state == WifiManager.WIFI_STATE_DISABLED) {
                        scanning = false
                        availableBySsid.clear()
                        clearPendingConnect()
                    }

                    if (state == WifiManager.WIFI_STATE_ENABLED) {
                        startScanInternal(showFailure = false)
                    }

                    publish()
                }

                WifiManager.SCAN_RESULTS_AVAILABLE_ACTION -> {
                    scanning = false
                    mainHandler.removeCallbacks(scanTimeoutRunnable)
                    ingestScanResults()
                    publish()
                }

                WifiManager.NETWORK_STATE_CHANGED_ACTION,
                WifiManager.SUPPLICANT_STATE_CHANGED_ACTION -> {
                    onConnectionPossiblyChanged()
                }
            }
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {

        override fun onAvailable(network: Network) {
            onConnectionPossiblyChanged()
        }

        override fun onLost(network: Network) {
            onConnectionPossiblyChanged()
        }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities
        ) {
            onConnectionPossiblyChanged()
        }
    }

    fun start() {
        if (started) {
            return
        }

        started = true
        exemptHiddenApis()

        val filter = IntentFilter().apply {
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
            addAction(WifiManager.SUPPLICANT_STATE_CHANGED_ACTION)
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
            Log.w(TAG, "Unable to register network callback.", exception)
        }

        if (isAdapterEnabled()) {
            ingestScanResults()
            startScanInternal(showFailure = false)
        }

        publish()
    }

    fun stop() {
        if (!started) {
            return
        }

        started = false
        clearPendingConnect()
        scanning = false
        mainHandler.removeCallbacks(scanTimeoutRunnable)

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

        availableBySsid.clear()
        securityBySsid.clear()
    }

    fun refresh() {
        if (isAdapterEnabled()) {
            ingestScanResults()
        }

        publish()
    }

    fun setAdapterEnabled(enabled: Boolean): Boolean {
        val manager = wifiManager ?: return false

        if (manager.isWifiEnabled == enabled) {
            return true
        }

        return try {
            val success = manager.setWifiEnabled(enabled)

            if (!success) {
                statusMessage = appContext.getString(
                    R.string.wifi_toggle_failed
                )
                publish()
            }

            success
        } catch (exception: SecurityException) {
            Log.w(TAG, "Unable to toggle Wi-Fi.", exception)
            statusMessage = appContext.getString(R.string.wifi_toggle_failed)
            publish()
            false
        }
    }

    fun startScan(): Boolean {
        return startScanInternal(showFailure = true)
    }

    fun stopScan() {
        scanning = false
        mainHandler.removeCallbacks(scanTimeoutRunnable)
        publish()
    }

    fun connectNetwork(ssid: String): Boolean {
        if (!isAdapterEnabled()) {
            statusMessage = appContext.getString(R.string.wifi_off)
            publish()
            return false
        }

        val saved = savedNetwork(ssid)

        if (saved != null && saved.networkId != INVALID_NETWORK_ID) {
            return enableSavedNetwork(saved.networkId, ssid)
        }

        val security = securityBySsid[ssid] ?: Security.OPEN

        if (security == Security.ENTERPRISE) {
            statusMessage = appContext.getString(R.string.wifi_unsupported)
            publish()
            return false
        }

        if (securityNeedsPassword(security)) {
            beginPendingConnect(ssid)
            publish()
            requestPassword(ssid, security)
            return true
        }

        return connectWithSecurity(ssid, security, password = null)
    }

    fun disconnectNetwork(ssid: String): Boolean {
        val manager = wifiManager ?: return false
        val saved = savedNetwork(ssid)

        if (saved == null || saved.networkId == INVALID_NETWORK_ID) {
            statusMessage = appContext.getString(
                R.string.wifi_disconnect_failed
            )
            publish()
            return false
        }

        if (pendingConnectSsid == ssid) {
            clearPendingConnect()
        }

        return try {
            manager.disableNetwork(saved.networkId)
            manager.disconnect()
            statusMessage = null
            publish()
            true
        } catch (exception: SecurityException) {
            Log.w(TAG, "Unable to disconnect from $ssid.", exception)
            statusMessage = appContext.getString(
                R.string.wifi_disconnect_failed
            )
            publish()
            false
        }
    }

    fun forgetNetwork(ssid: String): Boolean {
        val manager = wifiManager ?: return false
        val matching = configuredNetworks().filter { configuration ->
            sanitizeSsid(configuration.SSID) == ssid
        }

        if (matching.isEmpty()) {
            statusMessage = appContext.getString(R.string.wifi_forget_failed)
            publish()
            return false
        }

        if (matching.any { isCarrierLockedNetwork(it) }) {
            statusMessage = appContext.getString(R.string.wifi_forget_carrier)
            publish()
            return false
        }

        if (pendingConnectSsid == ssid) {
            clearPendingConnect()
        }

        return try {
            if (currentSsid() == ssid) {
                manager.disconnect()
            }

            var removedAny = false

            for (configuration in matching) {
                val networkId = configuration.networkId

                if (networkId == INVALID_NETWORK_ID) {
                    continue
                }

                manager.disableNetwork(networkId)
                forgetViaHiddenApi(manager, networkId)

                if (manager.removeNetwork(networkId)) {
                    removedAny = true
                    Log.i(TAG, "Removed Wi-Fi network $ssid id=$networkId.")
                } else {
                    Log.w(TAG, "removeNetwork returned false for $ssid id=$networkId.")
                }
            }

            manager.saveConfiguration()

            val stillSaved = configuredNetworks().any { configuration ->
                sanitizeSsid(configuration.SSID) == ssid
            }

            if (!removedAny || stillSaved) {
                statusMessage = appContext.getString(
                    if (matching.any { isCarrierLockedNetwork(it) }) {
                        R.string.wifi_forget_carrier
                    } else {
                        R.string.wifi_forget_failed
                    }
                )
                publish()
                return false
            }

            ingestScanResults()
            statusMessage = null
            publish()
            true
        } catch (exception: SecurityException) {
            Log.w(TAG, "Unable to forget $ssid.", exception)
            statusMessage = appContext.getString(R.string.wifi_forget_failed)
            publish()
            false
        }
    }

    private fun startScanInternal(showFailure: Boolean): Boolean {
        val manager = wifiManager ?: return false

        if (!isAdapterEnabled()) {
            if (showFailure) {
                statusMessage = appContext.getString(R.string.wifi_off)
                publish()
            }
            return false
        }

        if (!hasScanPermission()) {
            statusMessage = appContext.getString(
                R.string.wifi_permission_missing
            )
            publish()
            return false
        }

        statusMessage = null
        scanning = true
        mainHandler.removeCallbacks(scanTimeoutRunnable)
        mainHandler.postDelayed(scanTimeoutRunnable, SCAN_TIMEOUT_MS)
        publish()

        return try {
            val startedScan = manager.startScan()

            if (!startedScan) {
                scanning = false
                mainHandler.removeCallbacks(scanTimeoutRunnable)
                ingestScanResults()

                if (showFailure && availableBySsid.isEmpty()) {
                    statusMessage = appContext.getString(
                        R.string.wifi_scan_failed
                    )
                }

                publish()
            }

            startedScan || availableBySsid.isNotEmpty()
        } catch (exception: SecurityException) {
            Log.w(TAG, "Unable to start Wi-Fi scan.", exception)
            scanning = false
            mainHandler.removeCallbacks(scanTimeoutRunnable)
            statusMessage = appContext.getString(R.string.wifi_scan_failed)
            publish()
            false
        }
    }

    private fun ingestScanResults() {
        val manager = wifiManager ?: return

        if (!hasScanPermission()) {
            return
        }

        val results = try {
            manager.scanResults.orEmpty()
        } catch (exception: SecurityException) {
            Log.w(TAG, "Unable to read Wi-Fi scan results.", exception)
            return
        }

        val strongestBySsid = linkedMapOf<String, ScanResult>()

        for (result in results) {
            val ssid = sanitizeSsid(result.SSID) ?: continue

            if (result.capabilities.contains("IBSS")) {
                continue
            }

            val existing = strongestBySsid[ssid]

            if (existing == null || result.level > existing.level) {
                strongestBySsid[ssid] = result
            }
        }

        availableBySsid.clear()
        securityBySsid.clear()

        val savedSsids = configuredNetworks()
            .mapNotNull { sanitizeSsid(it.SSID) }
            .toHashSet()
        val connectedSsid = currentSsid()

        for ((ssid, result) in strongestBySsid) {
            val security = securityFromCapabilities(result.capabilities)
            securityBySsid[ssid] = security

            if (ssid == connectedSsid || savedSsids.contains(ssid)) {
                continue
            }

            availableBySsid[ssid] = NetworkItem(
                ssid = ssid,
                networkId = INVALID_NETWORK_ID,
                saved = false,
                connected = false,
                secured = security != Security.OPEN
            )
        }
    }

    private fun requestPassword(
        ssid: String,
        security: Security
    ) {
        mainHandler.post {
            if (!started || pendingConnectSsid != ssid) {
                return@post
            }

            listener.onPasswordRequired(ssid) { password ->
                if (!started || pendingConnectSsid != ssid) {
                    return@onPasswordRequired
                }

                if (password.isNullOrBlank()) {
                    clearPendingConnect()
                    publish()
                    return@onPasswordRequired
                }

                if (
                    security != Security.WEP &&
                    password.length < MIN_PSK_LENGTH
                ) {
                    statusMessage = appContext.getString(
                        R.string.wifi_password_invalid
                    )
                    publish()
                    requestPassword(ssid, security)
                    return@onPasswordRequired
                }

                connectWithSecurity(ssid, security, password)
            }
        }
    }

    private fun connectWithSecurity(
        ssid: String,
        security: Security,
        password: String?
    ): Boolean {
        val manager = wifiManager ?: return false

        beginPendingConnect(ssid)

        val configuration = WifiConfiguration().apply {
            SSID = quoted(ssid)
            status = WifiConfiguration.Status.ENABLED
            applySecurity(this, security, password)
        }

        return try {
            val existing = configuredNetworks().firstOrNull { config ->
                sanitizeSsid(config.SSID) == ssid
            }

            val networkId = if (existing != null) {
                existing.SSID = configuration.SSID
                existing.preSharedKey = configuration.preSharedKey
                existing.wepKeys = configuration.wepKeys
                existing.wepTxKeyIndex = configuration.wepTxKeyIndex
                existing.allowedKeyManagement.clear()
                existing.allowedKeyManagement =
                    configuration.allowedKeyManagement
                existing.allowedAuthAlgorithms.clear()
                existing.allowedAuthAlgorithms =
                    configuration.allowedAuthAlgorithms
                manager.updateNetwork(existing)
            } else {
                manager.addNetwork(configuration)
            }

            if (networkId == INVALID_NETWORK_ID) {
                clearPendingConnect()
                statusMessage = appContext.getString(
                    R.string.wifi_connect_failed
                )
                publish()
                return false
            }

            enableSavedNetwork(networkId, ssid)
        } catch (exception: SecurityException) {
            Log.w(TAG, "Unable to connect to $ssid.", exception)
            clearPendingConnect()
            statusMessage = appContext.getString(R.string.wifi_connect_failed)
            publish()
            false
        }
    }

    private fun enableSavedNetwork(
        networkId: Int,
        ssid: String
    ): Boolean {
        val manager = wifiManager ?: return false

        beginPendingConnect(ssid)

        return try {
            manager.disconnect()
            val enabled = manager.enableNetwork(networkId, true)
            manager.reconnect()

            if (!enabled) {
                clearPendingConnect()
                statusMessage = appContext.getString(
                    R.string.wifi_connect_failed
                )
                publish()
                return false
            }

            scheduleConnectTimeout()
            publish()
            true
        } catch (exception: SecurityException) {
            Log.w(TAG, "Unable to enable $ssid.", exception)
            clearPendingConnect()
            statusMessage = appContext.getString(R.string.wifi_connect_failed)
            publish()
            false
        }
    }

    private fun applySecurity(
        configuration: WifiConfiguration,
        security: Security,
        password: String?
    ) {
        when (security) {
            Security.OPEN -> {
                configuration.allowedKeyManagement.set(
                    WifiConfiguration.KeyMgmt.NONE
                )
            }

            Security.WEP -> {
                val wepKey = password.orEmpty()
                configuration.wepKeys[0] =
                    if (wepKey.length == 10 || wepKey.length == 26) {
                        wepKey
                    } else {
                        quoted(wepKey)
                    }
                configuration.wepTxKeyIndex = 0
                configuration.allowedKeyManagement.set(
                    WifiConfiguration.KeyMgmt.NONE
                )
                configuration.allowedAuthAlgorithms.set(
                    WifiConfiguration.AuthAlgorithm.OPEN
                )
                configuration.allowedAuthAlgorithms.set(
                    WifiConfiguration.AuthAlgorithm.SHARED
                )
            }

            Security.PSK -> {
                configuration.allowedKeyManagement.set(
                    WifiConfiguration.KeyMgmt.WPA_PSK
                )
                configuration.preSharedKey = quoted(password.orEmpty())
            }

            Security.SAE -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    configuration.allowedKeyManagement.set(
                        WifiConfiguration.KeyMgmt.SAE
                    )
                    configuration.preSharedKey = quoted(password.orEmpty())
                    configuration.allowedProtocols.set(
                        WifiConfiguration.Protocol.RSN
                    )
                } else {
                    configuration.allowedKeyManagement.set(
                        WifiConfiguration.KeyMgmt.WPA_PSK
                    )
                    configuration.preSharedKey = quoted(password.orEmpty())
                }
            }

            Security.ENTERPRISE -> {
                configuration.allowedKeyManagement.set(
                    WifiConfiguration.KeyMgmt.WPA_EAP
                )
            }
        }
    }

    private fun onConnectionPossiblyChanged() {
        val ssid = pendingConnectSsid

        if (ssid != null && currentSsid() == ssid) {
            clearPendingConnect(clearStatus = true)
        }

        ingestScanResults()
        publish()
    }

    private fun beginPendingConnect(ssid: String) {
        cancelConnectTimeout()
        pendingConnectSsid = ssid
        statusMessage = appContext.getString(R.string.wifi_connect_pending)
    }

    private fun scheduleConnectTimeout() {
        mainHandler.removeCallbacks(connectTimeoutRunnable)
        mainHandler.postDelayed(connectTimeoutRunnable, CONNECT_TIMEOUT_MS)
    }

    private fun failPendingConnect() {
        if (pendingConnectSsid == null) {
            return
        }

        clearPendingConnect()
        statusMessage = appContext.getString(R.string.wifi_connect_failed)
        publish()
    }

    private fun clearPendingConnect(clearStatus: Boolean = false) {
        cancelConnectTimeout()
        pendingConnectSsid = null

        if (clearStatus) {
            statusMessage = null
        }
    }

    private fun cancelConnectTimeout() {
        mainHandler.removeCallbacks(connectTimeoutRunnable)
    }

    private fun publish() {
        mainHandler.post {
            if (!started) {
                return@post
            }

            listener.onWifiStateChanged(buildState())
        }
    }

    private fun buildState(): State {
        val enabled = isAdapterEnabled()

        if (!enabled) {
            return State(
                adapterEnabled = false,
                scanning = false,
                savedNetworks = emptyList(),
                availableNetworks = emptyList(),
                connectingSsid = pendingConnectSsid,
                statusMessage = statusMessage
                    ?: appContext.getString(R.string.wifi_off)
            )
        }

        return State(
            adapterEnabled = true,
            scanning = scanning,
            savedNetworks = savedNetworks(),
            availableNetworks = availableBySsid.values.toList(),
            connectingSsid = pendingConnectSsid,
            statusMessage = statusMessage
        )
    }

    private fun savedNetworks(): List<NetworkItem> {
        val connectedSsid = currentSsid()
        val connectedNetworkId = currentNetworkId()
        val items = linkedMapOf<String, NetworkItem>()

        for (configuration in configuredNetworks()) {
            val ssid = sanitizeSsid(configuration.SSID) ?: continue

            items[ssid] = NetworkItem(
                ssid = ssid,
                networkId = configuration.networkId,
                saved = true,
                connected = ssid == connectedSsid,
                secured = configuredNetworkIsSecured(configuration),
                removable = !isCarrierLockedNetwork(configuration)
            )
        }

        if (
            connectedSsid != null &&
            !items.containsKey(connectedSsid)
        ) {
            items[connectedSsid] = NetworkItem(
                ssid = connectedSsid,
                networkId = connectedNetworkId,
                saved = connectedNetworkId != INVALID_NETWORK_ID,
                connected = true,
                secured = securityBySsid[connectedSsid] != Security.OPEN
            )
        }

        return items.values.sortedWith(
            compareByDescending<NetworkItem> { it.connected }
                .thenBy { it.ssid.lowercase() }
        )
    }

    private fun savedNetwork(ssid: String): NetworkItem? {
        return savedNetworks().firstOrNull { it.ssid == ssid }
    }

    private fun configuredNetworks(): List<WifiConfiguration> {
        val manager = wifiManager ?: return emptyList()

        return try {
            manager.configuredNetworks.orEmpty()
        } catch (exception: SecurityException) {
            Log.w(TAG, "Unable to list saved Wi-Fi networks.", exception)
            emptyList()
        }
    }

    private fun configuredNetworkIsSecured(
        configuration: WifiConfiguration
    ): Boolean {
        return !configuration.allowedKeyManagement.get(
            WifiConfiguration.KeyMgmt.NONE
        )
    }

    /*
     * Samsung (and some carriers) preloads SIM/hotspot configs
     * marked vendor-specific. WifiConfigManager refuses to
     * delete those even for the device owner.
     */
    private fun isCarrierLockedNetwork(
        configuration: WifiConfiguration
    ): Boolean {
        if (
            booleanField(configuration, "isVendorSpecificSsid") ||
            booleanField(configuration, "semIsVendorSpecificSsid")
        ) {
            return true
        }

        if (
            !configuration.allowedKeyManagement.get(
                WifiConfiguration.KeyMgmt.WPA_EAP
            ) &&
            !configuration.allowedKeyManagement.get(
                WifiConfiguration.KeyMgmt.IEEE8021X
            )
        ) {
            return false
        }

        val eapMethod = configuration.enterpriseConfig?.eapMethod
            ?: return false

        return eapMethod == WifiEnterpriseConfig.Eap.SIM ||
            eapMethod == WifiEnterpriseConfig.Eap.AKA ||
            eapMethod == WifiEnterpriseConfig.Eap.AKA_PRIME
    }

    private fun booleanField(
        target: Any,
        name: String
    ): Boolean {
        val type = target.javaClass

        try {
            return type.getField(name).getBoolean(target)
        } catch (_: Exception) {
            // Fall through to declared fields.
        }

        return try {
            type.getDeclaredField(name)
                .apply { isAccessible = true }
                .getBoolean(target)
        } catch (_: Exception) {
            false
        }
    }

    private fun forgetViaHiddenApi(
        manager: WifiManager,
        networkId: Int
    ): Boolean {
        return try {
            val listenerClass = Class.forName(
                "android.net.wifi.WifiManager\$ActionListener"
            )
            val method = WifiManager::class.java.getMethod(
                "forget",
                Int::class.javaPrimitiveType,
                listenerClass
            )
            method.invoke(manager, networkId, null)
            true
        } catch (exception: Exception) {
            Log.i(TAG, "Hidden WifiManager.forget() unavailable.")
            false
        }
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
        } catch (exception: Exception) {
            Log.w(TAG, "Unable to exempt hidden APIs.", exception)
        }
    }

    private fun isAdapterEnabled(): Boolean {
        val manager = wifiManager ?: return false

        return try {
            manager.isWifiEnabled
        } catch (exception: SecurityException) {
            Log.w(TAG, "Unable to read Wi-Fi adapter state.", exception)
            false
        }
    }

    private fun currentSsid(): String? {
        return sanitizeSsid(currentWifiInfo()?.ssid)
    }

    private fun currentNetworkId(): Int {
        return currentWifiInfo()?.networkId ?: INVALID_NETWORK_ID
    }

    private fun currentWifiInfo(): WifiInfo? {
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

                if (wifiInfo != null) {
                    return wifiInfo
                }
            }
        }

        val info = wifiManager?.connectionInfo ?: return null

        if (info.networkId == INVALID_NETWORK_ID) {
            return null
        }

        return info
    }

    private fun hasScanPermission(): Boolean {
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

    private fun securityFromCapabilities(capabilities: String): Security {
        val hasPsk = capabilities.contains("PSK")
        val hasSae = capabilities.contains("SAE")
        val hasEap = capabilities.contains("EAP")

        return when {
            hasEap && !hasPsk -> Security.ENTERPRISE
            hasSae && !hasPsk -> Security.SAE
            hasPsk -> Security.PSK
            capabilities.contains("WEP") -> Security.WEP
            else -> Security.OPEN
        }
    }

    private fun securityNeedsPassword(security: Security): Boolean {
        return security == Security.WEP ||
            security == Security.PSK ||
            security == Security.SAE
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

    private fun quoted(value: String): String {
        if (value.startsWith("\"") && value.endsWith("\"")) {
            return value
        }

        return "\"$value\""
    }

    companion object {

        private const val TAG = "AetherPlayer"

        private const val UNKNOWN_SSID = "<unknown ssid>"
        private const val INVALID_NETWORK_ID = -1
        private const val MIN_PSK_LENGTH = 8
        private const val SCAN_TIMEOUT_MS = 12_000L
        private const val CONNECT_TIMEOUT_MS = 20_000L
    }
}

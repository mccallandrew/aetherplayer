package com.mccallandrew.aetherplayer

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.ActivityOptions
import android.app.admin.DevicePolicyManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView

/*
 * The kiosk launcher. This activity is the Lock Task root, so
 * Spotify is started on top of it as an allowlisted package and
 * the Home button is what brings the user back here.
 */
class KioskHomeActivity :
    Activity(),
    SpotifyNowPlayingController.Listener,
    BluetoothDevicesHelper.Listener,
    WifiStatusHelper.Listener {

    private lateinit var devicePolicyManager: DevicePolicyManager
    private lateinit var activityManager: ActivityManager
    private lateinit var adminComponent: ComponentName

    private lateinit var nowPlayingPanel: View
    private lateinit var albumArt: ImageView
    private lateinit var trackTitle: TextView
    private lateinit var trackArtist: TextView
    private lateinit var buttonPrevious: ImageButton
    private lateinit var buttonPlayPause: ImageButton
    private lateinit var buttonNext: ImageButton
    private lateinit var bluetoothTile: View
    private lateinit var bluetoothDevices: TextView
    private lateinit var wifiTile: View
    private lateinit var wifiNetwork: TextView
    private lateinit var statusMessage: TextView

    private var nowPlayingController: SpotifyNowPlayingController? = null
    private var bluetoothDevicesHelper: BluetoothDevicesHelper? = null
    private var wifiStatusHelper: WifiStatusHelper? = null
    private var forwardedToOriginalHome = false
    private var spotifyInstalled = false

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(savedInstanceState)

        devicePolicyManager =
            getSystemService(
                Context.DEVICE_POLICY_SERVICE
            ) as DevicePolicyManager

        activityManager =
            getSystemService(
                Context.ACTIVITY_SERVICE
            ) as ActivityManager

        adminComponent = ComponentName(
            this,
            AetherDeviceAdminReceiver::class.java
        )

        setContentView(R.layout.activity_kiosk_home)

        findViewById<View>(R.id.home_root).background =
            WordCloudDrawable(this)

        nowPlayingPanel = findViewById(R.id.now_playing_panel)
        albumArt = findViewById(R.id.album_art)
        trackTitle = findViewById(R.id.track_title)
        trackArtist = findViewById(R.id.track_artist)
        buttonPrevious = findViewById(R.id.button_previous)
        buttonPlayPause = findViewById(R.id.button_play_pause)
        buttonNext = findViewById(R.id.button_next)
        bluetoothTile = findViewById(R.id.bluetooth_tile)
        bluetoothDevices = findViewById(R.id.bluetooth_devices)
        wifiTile = findViewById(R.id.wifi_tile)
        wifiNetwork = findViewById(R.id.wifi_network)
        statusMessage = findViewById(R.id.status_message)

        nowPlayingPanel.setOnClickListener {
            launchSpotify()
        }

        bluetoothTile.setOnClickListener {
            launchBluetooth()
        }

        wifiTile.setOnClickListener {
            launchWifi()
        }

        buttonPrevious.setOnClickListener {
            nowPlayingController?.skipPrevious()
        }

        buttonPlayPause.setOnClickListener {
            nowPlayingController?.playPause()
        }

        buttonNext.setOnClickListener {
            nowPlayingController?.skipNext()
        }

        handleLaunchIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)

        if (intent == null) {
            return
        }

        setIntent(intent)

        handleLaunchIntent(intent)
    }

    override fun onResume() {
        super.onResume()

        refreshNowPlayingAvailability()
        enterLockTaskIfNeeded()

        if (
            KioskCommandReceiver.isKioskEnabled(this) &&
            devicePolicyManager.isDeviceOwnerApp(packageName)
        ) {
            KioskCommandReceiver.setNotificationListenerAccess(this, true)
        }

        if (spotifyInstalled) {
            startNowPlayingController()
        }

        startBluetoothDevicesHelper()
        startWifiStatusHelper()
    }

    override fun onPause() {
        stopNowPlayingController()
        stopBluetoothDevicesHelper()
        stopWifiStatusHelper()
        super.onPause()
    }

    override fun onNowPlayingChanged(
        state: SpotifyNowPlayingController.NowPlayingState
    ) {
        if (!spotifyInstalled) {
            return
        }

        if (!state.hasSession) {
            albumArt.setImageResource(R.drawable.ic_app_placeholder)
            trackTitle.setText(R.string.now_playing_idle)
            trackArtist.text = ""
            setTransportEnabled(
                playPauseEnabled = false,
                previousEnabled = false,
                nextEnabled = false
            )
            buttonPlayPause.setImageResource(R.drawable.ic_play)
            buttonPlayPause.contentDescription =
                getString(R.string.now_playing_play)
            return
        }

        if (state.artwork != null && !state.artwork.isRecycled) {
            albumArt.setImageBitmap(state.artwork)
        } else {
            albumArt.setImageResource(R.drawable.ic_app_placeholder)
        }

        trackTitle.text = state.title?.takeIf { it.isNotBlank() }
            ?: getString(R.string.now_playing_idle)

        trackArtist.text = state.artist.orEmpty()

        if (state.isPlaying) {
            buttonPlayPause.setImageResource(R.drawable.ic_pause)
            buttonPlayPause.contentDescription =
                getString(R.string.now_playing_pause)
        } else {
            buttonPlayPause.setImageResource(R.drawable.ic_play)
            buttonPlayPause.contentDescription =
                getString(R.string.now_playing_play)
        }

        setTransportEnabled(
            playPauseEnabled = state.canPlayPause,
            previousEnabled = state.canSkipPrevious,
            nextEnabled = state.canSkipNext
        )
    }

    override fun onConnectedDevicesChanged(summary: String) {
        bluetoothDevices.text = summary
    }

    override fun onWifiStatusChanged(summary: String) {
        wifiNetwork.text = summary
    }

    private fun handleLaunchIntent(intent: Intent?) {
        when {
            intent == null -> {
                Log.i(TAG, "Ignoring launch with no intent.")
            }

            intent.action == KioskCommandReceiver.ACTION_EXIT_KIOSK -> {
                exitKiosk()
            }

            intent.action == KioskCommandReceiver.ACTION_START_KIOSK -> {
                KioskCommandReceiver.setKioskEnabled(this, true)
            }

            intent.hasCategory(Intent.CATEGORY_HOME) -> {
                handleHomeIntent()
            }

            else -> {
                Log.i(
                    TAG,
                    "Ignoring unrecognised action: ${intent.action}"
                )
            }
        }
    }

    /*
     * Home intents are how the user returns here from Spotify,
     * so there is nothing to do beyond letting the activity
     * resume. onResume() takes care of entering Lock Task.
     */
    private fun handleHomeIntent() {

        if (!KioskCommandReceiver.isKioskEnabled(this)) {
            Log.i(TAG, "Home intent received while kiosk is not enabled.")

            forwardToOriginalHome()
        }
    }

    /*
     * The kiosk can only be reached by a Home intent while it is
     * the persistent Home app, so if kiosk mode is off the
     * policy is stale. Hand back to the real launcher, but only
     * once per instance so a launcher that cannot be resolved
     * does not bounce the intent back and forth.
     */
    private fun forwardToOriginalHome() {

        if (forwardedToOriginalHome) {
            return
        }

        if (!KioskCommandReceiver.hasOriginalHome(this)) {
            return
        }

        forwardedToOriginalHome = true

        KioskCommandReceiver.restoreOriginalHome(this)
        KioskCommandReceiver.launchOriginalHome(this)
    }

    private fun enterLockTaskIfNeeded() {

        if (!KioskCommandReceiver.isKioskEnabled(this)) {
            return
        }

        if (!devicePolicyManager.isDeviceOwnerApp(packageName)) {
            Log.w(TAG, "Cannot start kiosk: not the Device Owner.")

            showMessage(getString(R.string.status_not_device_owner))

            return
        }

        configureDevice()

        /*
         * Lock Task is already active whenever the user has
         * returned here from Spotify or Settings, because those
         * tasks are part of the same locked set.
         */
        if (
            activityManager.lockTaskModeState !=
            ActivityManager.LOCK_TASK_MODE_NONE
        ) {
            return
        }

        try {
            startLockTask()

            Log.i(TAG, "Entered Lock Task on the kiosk home screen.")

        } catch (exception: IllegalArgumentException) {

            /*
             * Thrown when the allowlist written just above has
             * not taken effect for this task yet.
             */
            Log.e(TAG, "Unable to enter Lock Task.", exception)
        }
    }

    private fun configureDevice() {

        val allowedPackages =
            KioskCommandReceiver.defaultLockTaskPackages(this)

        /*
         * AetherPlayer must stay allowlisted so this activity
         * can be the Lock Task root. Spotify must be
         * allowlisted so it can be started on top of it
         * without leaving Lock Task. Settings / Fast Pair stay
         * off this default list; BluetoothActivity and
         * WifiActivity widen the allowlist only while those
         * screens are open.
         *
         * Both setters below are persisted device-policy
         * writes, so only touch them when the policy that is
         * already in force actually differs.
         */
        KioskCommandReceiver.applyLockTaskPackages(
            this,
            allowedPackages
        )

        if (
            devicePolicyManager.getLockTaskFeatures(adminComponent) !=
            KioskCommandReceiver.LOCK_TASK_FEATURES
        ) {
            devicePolicyManager.setLockTaskFeatures(
                adminComponent,
                KioskCommandReceiver.LOCK_TASK_FEATURES
            )
        }

        grantKioskPermissions()
        KioskCommandReceiver.setNotificationListenerAccess(this, true)
        KioskCommandReceiver.applyPersistentHome(this)
    }

    private fun grantKioskPermissions() {
        if (!devicePolicyManager.isDeviceOwnerApp(packageName)) {
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            grantRuntimePermission(Manifest.permission.BLUETOOTH_CONNECT)
            grantRuntimePermission(Manifest.permission.BLUETOOTH_SCAN)
        }

        grantRuntimePermission(Manifest.permission.ACCESS_FINE_LOCATION)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            grantRuntimePermission(Manifest.permission.NEARBY_WIFI_DEVICES)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                devicePolicyManager.setLocationEnabled(adminComponent, true)
            } catch (exception: SecurityException) {
                Log.w(TAG, "Unable to enable location.", exception)
            }
        }
    }

    private fun grantRuntimePermission(permission: String) {
        try {
            devicePolicyManager.setPermissionGrantState(
                adminComponent,
                packageName,
                permission,
                DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
            )
        } catch (exception: SecurityException) {
            Log.w(
                TAG,
                "Unable to grant $permission.",
                exception
            )
        }
    }

    private fun refreshNowPlayingAvailability() {

        spotifyInstalled = spotifyApplicationInfo() != null

        if (!spotifyInstalled) {
            stopNowPlayingController()

            nowPlayingPanel.isEnabled = false
            nowPlayingPanel.alpha = DISABLED_PANEL_ALPHA

            albumArt.setImageResource(R.drawable.ic_app_placeholder)
            trackTitle.setText(R.string.now_playing_idle)
            trackArtist.text = ""

            setTransportEnabled(
                playPauseEnabled = false,
                previousEnabled = false,
                nextEnabled = false
            )

            showMessage(getString(R.string.status_spotify_missing))

            return
        }

        nowPlayingPanel.isEnabled = true
        nowPlayingPanel.alpha = 1f

        clearMessage()
    }

    private fun startNowPlayingController() {
        if (nowPlayingController != null) {
            return
        }

        val controller = SpotifyNowPlayingController(this, this)
        nowPlayingController = controller
        controller.start()
    }

    private fun stopNowPlayingController() {
        nowPlayingController?.stop()
        nowPlayingController = null
    }

    private fun startBluetoothDevicesHelper() {
        if (bluetoothDevicesHelper != null) {
            bluetoothDevicesHelper?.refresh()
            return
        }

        val helper = BluetoothDevicesHelper(this, this)
        bluetoothDevicesHelper = helper
        helper.start()
    }

    private fun stopBluetoothDevicesHelper() {
        bluetoothDevicesHelper?.stop()
        bluetoothDevicesHelper = null
    }

    private fun startWifiStatusHelper() {
        if (wifiStatusHelper != null) {
            wifiStatusHelper?.refresh()
            return
        }

        val helper = WifiStatusHelper(this, this)
        wifiStatusHelper = helper
        helper.start()
    }

    private fun stopWifiStatusHelper() {
        wifiStatusHelper?.stop()
        wifiStatusHelper = null
    }

    private fun setTransportEnabled(
        playPauseEnabled: Boolean,
        previousEnabled: Boolean,
        nextEnabled: Boolean
    ) {
        buttonPlayPause.isEnabled = playPauseEnabled
        buttonPlayPause.alpha =
            if (playPauseEnabled) 1f else DISABLED_CONTROL_ALPHA

        buttonPrevious.isEnabled = previousEnabled
        buttonPrevious.alpha =
            if (previousEnabled) 1f else DISABLED_CONTROL_ALPHA

        buttonNext.isEnabled = nextEnabled
        buttonNext.alpha =
            if (nextEnabled) 1f else DISABLED_CONTROL_ALPHA
    }

    private fun launchSpotify() {

        val spotifyIntent =
            packageManager.getLaunchIntentForPackage(
                KioskCommandReceiver.SPOTIFY_PACKAGE
            )

        if (spotifyIntent == null) {
            Log.w(TAG, "Spotify has no launcher activity.")

            showMessage(getString(R.string.status_spotify_missing))

            return
        }

        spotifyIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        /*
         * Redundant once this activity holds Lock Task, but it
         * is what locks Spotify down when the kiosk is running
         * without Lock Task, such as before Device Owner is set.
         */
        val options =
            ActivityOptions.makeBasic().apply {
                setLockTaskEnabled(true)
            }

        try {

            startActivity(
                spotifyIntent,
                options.toBundle()
            )

            Log.i(TAG, "Launched Spotify.")

        } catch (exception: SecurityException) {

            Log.e(TAG, "Unable to launch Spotify.", exception)

            showMessage(
                getString(R.string.status_spotify_launch_failed)
            )

        } catch (exception: ActivityNotFoundException) {

            Log.e(TAG, "Unable to launch Spotify.", exception)

            showMessage(
                getString(R.string.status_spotify_launch_failed)
            )
        }
    }

    private fun launchBluetooth() {
        try {
            startActivity(Intent(this, BluetoothActivity::class.java))
            Log.i(TAG, "Launched Bluetooth screen.")
        } catch (exception: ActivityNotFoundException) {
            Log.e(TAG, "Unable to launch Bluetooth screen.", exception)
            showMessage(
                getString(R.string.status_bluetooth_launch_failed)
            )
        }
    }

    private fun launchWifi() {
        try {
            startActivity(Intent(this, WifiActivity::class.java))
            Log.i(TAG, "Launched Wi-Fi screen.")
        } catch (exception: ActivityNotFoundException) {
            Log.e(TAG, "Unable to launch Wi-Fi screen.", exception)
            showMessage(
                getString(R.string.status_wifi_launch_failed)
            )
        }
    }

    private fun exitKiosk() {

        Log.i(TAG, "Exiting kiosk mode.")

        stopNowPlayingController()
        stopBluetoothDevicesHelper()
        stopWifiStatusHelper()

        KioskCommandReceiver.setKioskEnabled(this, false)
        KioskCommandReceiver.setNotificationListenerAccess(this, false)

        try {
            stopLockTask()
        } catch (_: IllegalStateException) {
            // Lock Task was not running.
        }

        KioskCommandReceiver.clearLockTaskPackages(this)
        KioskCommandReceiver.clearLockTaskFeatures(this)
        KioskCommandReceiver.restoreOriginalHome(this)
        KioskCommandReceiver.launchOriginalHome(this)
        finish()
    }

    private fun spotifyApplicationInfo() =
        try {
            packageManager.getApplicationInfo(
                KioskCommandReceiver.SPOTIFY_PACKAGE,
                0
            )
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }

    private fun showMessage(
        message: String
    ) {
        statusMessage.text = message
        statusMessage.visibility = View.VISIBLE
    }

    private fun clearMessage() {
        statusMessage.text = ""
        statusMessage.visibility = View.GONE
    }

    companion object {

        private const val TAG = "AetherPlayer"

        private const val DISABLED_PANEL_ALPHA = 0.4f
        private const val DISABLED_CONTROL_ALPHA = 0.35f
    }
}

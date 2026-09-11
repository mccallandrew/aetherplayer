package com.mccallandrew.aetherplayer

import android.app.Activity
import android.app.ActivityOptions
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var devicePolicyManager: DevicePolicyManager
    private lateinit var adminComponent: ComponentName

    private val handler =
        Handler(Looper.getMainLooper())

    private var spotifyReadyAttempts = 0

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(savedInstanceState)

        devicePolicyManager =
            getSystemService(
                Context.DEVICE_POLICY_SERVICE
            ) as DevicePolicyManager

        adminComponent = ComponentName(
            this,
            AetherDeviceAdminReceiver::class.java
        )

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

    private fun handleLaunchIntent(intent: Intent?) {
        when {
            intent?.action == ACTION_EXIT_KIOSK -> {
                exitKiosk()
            }

            shouldStartKiosk(intent) -> {
                startKiosk()
            }

            else -> {
                showControllerScreen()
            }
        }
    }

    private fun shouldStartKiosk(intent: Intent?): Boolean {
        if (intent == null) {
            return false
        }

        return intent.action == ACTION_START_KIOSK ||
                intent.hasCategory(Intent.CATEGORY_HOME)
    }

    private fun startKiosk() {

        if (!devicePolicyManager.isDeviceOwnerApp(packageName)) {
            showMessage(
                "AetherPlayer is not the Device Owner."
            )

            return
        }

        if (!isSpotifyInstalled()) {
            if (spotifyReadyAttempts < 5) {
                spotifyReadyAttempts++
                handler.postDelayed(
                    { startKiosk() },
                    1000L
                )
                return
            }

            showMessage(
                "Spotify is not installed."
            )

            return
        }

        spotifyReadyAttempts = 0

        configureDevice()

        launchSpotifyIntoLockTask()
    }

    private fun configureDevice() {

        /*
         * AetherPlayer must stay allowlisted so this
         * activity can start Lock Task and later stop it.
         * Spotify must be allowlisted so it can be launched
         * into Lock Task with ActivityOptions.
         */
        devicePolicyManager.setLockTaskPackages(
            adminComponent,
            arrayOf(packageName, SPOTIFY_PACKAGE)
        )

        /*
         * Keep kiosk restrictions, but leave the power-button
         * dialog enabled so the device can still be shut down.
         *
         * Features not listed here stay disabled:
         * Home, Recents, notifications, status info, keyguard.
         */
        devicePolicyManager.setLockTaskFeatures(
            adminComponent,
            DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS
        )

        KioskCommandReceiver.applyPersistentHome(this)

        try {
            devicePolicyManager.setKeyguardDisabled(
                adminComponent,
                true
            )
        } catch (_: SecurityException) {
            // A lock screen PIN/password can block this.
        }
    }

    private fun launchSpotifyIntoLockTask() {

        val spotifyIntent =
            packageManager.getLaunchIntentForPackage(
                SPOTIFY_PACKAGE
            )

        if (spotifyIntent == null) {
            showMessage(
                "Unable to find Spotify launcher activity."
            )

            return
        }

        /*
         * setLockTaskEnabled() does not apply to an
         * already-running activity. CLEAR_TASK forces
         * Spotify to relaunch so Lock Task can attach.
         */
        spotifyIntent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TASK
        )

        val options =
            ActivityOptions.makeBasic().apply {
                setLockTaskEnabled(true)
            }

        try {

            startActivity(
                spotifyIntent,
                options.toBundle()
            )

        } catch (exception: SecurityException) {

            showMessage(
                "Unable to enter Spotify kiosk mode:\n" +
                        exception.message
            )
        }
    }

    private fun exitKiosk() {

        /*
         * stopLockTask() only works for the activity that
         * started Lock Task. Spotify started it, so this
         * call is usually a no-op. The Device Owner must
         * clear the allowlist to actually unlock.
         */
        try {
            stopLockTask()
        } catch (_: IllegalStateException) {
            // This activity is not the locked task.
        }

        KioskCommandReceiver.clearLockTaskPackages(this)
        KioskCommandReceiver.restoreOriginalHome(this)
        KioskCommandReceiver.launchOriginalHome(this)
        finish()
    }

    private fun isSpotifyInstalled(): Boolean {
        return packageManager
            .getLaunchIntentForPackage(
                SPOTIFY_PACKAGE
            ) != null
    }

    private fun showControllerScreen() {

        val status = TextView(this).apply {
            text = buildStatusText()
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }

        setContentView(status)
    }

    private fun showMessage(
        message: String
    ) {
        val status = TextView(this).apply {
            text = message
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }

        setContentView(status)
    }

    private fun buildStatusText(): String {

        val isDeviceOwner =
            devicePolicyManager
                .isDeviceOwnerApp(packageName)

        val spotifyInstalled =
            isSpotifyInstalled()

        val allowedPackages =
            if (isDeviceOwner) {
                devicePolicyManager
                    .getLockTaskPackages(
                        adminComponent
                    )
                    .joinToString("\n")
            } else {
                "N/A"
            }

        val lockTaskFeatures =
            if (isDeviceOwner) {
                devicePolicyManager
                    .getLockTaskFeatures(
                        adminComponent
                    )
            } else {
                -1
            }

        return """
            AetherPlayer
            
            Device Owner: $isDeviceOwner
            
            Spotify installed: $spotifyInstalled
            
            Lock Task packages:
            $allowedPackages
            
            Lock Task features:
            $lockTaskFeatures
        """.trimIndent()
    }

    companion object {

        const val ACTION_START_KIOSK =
            "com.mccallandrew.aetherplayer.START_KIOSK"

        const val ACTION_EXIT_KIOSK =
            "com.mccallandrew.aetherplayer.EXIT_KIOSK"

        private const val SPOTIFY_PACKAGE =
            "com.spotify.music"
    }
}
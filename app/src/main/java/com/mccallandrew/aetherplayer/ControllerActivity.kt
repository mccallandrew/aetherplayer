package com.mccallandrew.aetherplayer

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView

/*
 * Launcher-facing status screen. Kiosk control lives in
 * KioskHomeActivity, which is the only activity registered for
 * Home intents, so that the two cannot end up as separate
 * instances in the Home and standard stacks.
 */
class ControllerActivity : Activity() {

    private lateinit var devicePolicyManager: DevicePolicyManager
    private lateinit var adminComponent: ComponentName

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

        showControllerScreen()
    }

    override fun onResume() {
        super.onResume()

        showControllerScreen()
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

    private fun buildStatusText(): String {

        val isDeviceOwner =
            devicePolicyManager
                .isDeviceOwnerApp(packageName)

        val spotifyInstalled =
            packageManager.getLaunchIntentForPackage(
                KioskCommandReceiver.SPOTIFY_PACKAGE
            ) != null

        val kioskEnabled =
            KioskCommandReceiver.isKioskEnabled(this)

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
            
            Kiosk enabled: $kioskEnabled
            
            Lock Task packages:
            $allowedPackages
            
            Lock Task features:
            $lockTaskFeatures
        """.trimIndent()
    }
}

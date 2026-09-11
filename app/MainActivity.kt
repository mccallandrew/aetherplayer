package app.aetherbinder.aetherplayer

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle

class MainActivity : Activity() {

    private lateinit var devicePolicyManager: DevicePolicyManager
    private lateinit var adminComponent: ComponentName

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        devicePolicyManager =
            getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

        adminComponent = ComponentName(
            this,
            AetherDeviceAdminReceiver::class.java
        )

        configureKiosk()
    }

    private fun configureKiosk() {
        if (!devicePolicyManager.isDeviceOwnerApp(packageName)) {
            return
        }

        devicePolicyManager.setLockTaskPackages(
            adminComponent,
            arrayOf(packageName, "com.spotify.music")
        )

        startLockTask()

        val spotify = packageManager.getLaunchIntentForPackage(
            "com.spotify.music"
        )

        if (spotify != null) {
            startActivity(spotify)
        }
    }
}
package com.mccallandrew.aetherplayer

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

class AetherDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(
        context: Context,
        intent: Intent
    ) {
        Toast.makeText(
            context,
            "AetherPlayer device administration enabled",
            Toast.LENGTH_SHORT
        ).show()
    }

    override fun onDisabled(
        context: Context,
        intent: Intent
    ) {
        Toast.makeText(
            context,
            "AetherPlayer device administration disabled",
            Toast.LENGTH_SHORT
        ).show()
    }

}
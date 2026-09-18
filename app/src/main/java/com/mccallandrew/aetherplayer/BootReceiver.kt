package com.mccallandrew.aetherplayer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(
        context: Context,
        intent: Intent
    ) {
        /*
         * Only BOOT_COMPLETED. Listening for
         * LOCKED_BOOT_COMPLETED as well only started a second
         * kiosk that overlapped the real one.
         */
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) {
            return
        }

        Log.i(TAG, "Boot completed, entering kiosk mode.")

        KioskCommandReceiver.setKioskEnabled(context, true)
        KioskCommandReceiver.setNotificationListenerAccess(context, true)
        KioskCommandReceiver.applyPersistentHome(context)
        KioskCommandReceiver.launchKioskHome(context)
    }

    companion object {

        private const val TAG = "AetherPlayer"
    }
}

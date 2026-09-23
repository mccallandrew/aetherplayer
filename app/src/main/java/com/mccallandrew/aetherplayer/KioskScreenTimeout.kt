package com.mccallandrew.aetherplayer

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.util.Log

/*
 * While kiosk mode is on, the screen turns off and locks after
 * five minutes without touch. The timeout that was in place
 * before the kiosk started is put back on exit.
 *
 * A maximum lock time is required as well as the screen-off
 * setting. Stay-awake-while-plugged-in ignores the screen
 * timeout unless that maximum is set, so a charging phone
 * would otherwise stay on.
 */
class KioskScreenTimeout {

    companion object {

        private const val TAG = "AetherPlayer"

        private const val PREFS_NAME = "kiosk_screen_timeout"
        private const val KEY_APPLIED = "applied"
        private const val KEY_PREVIOUS_TIMEOUT = "previous_timeout"

        private const val KIOSK_TIMEOUT_MS = 5 * 60 * 1000

        fun apply(context: Context) {
            val devicePolicyManager = devicePolicyManager(context) ?: return
            val admin = adminComponent(context)

            rememberPreviousTimeout(context)

            if (currentTimeout(context) != KIOSK_TIMEOUT_MS) {
                try {
                    devicePolicyManager.setSystemSetting(
                        admin,
                        Settings.System.SCREEN_OFF_TIMEOUT,
                        KIOSK_TIMEOUT_MS.toString()
                    )

                    Log.i(TAG, "Set the kiosk screen timeout to 5 minutes.")
                } catch (exception: SecurityException) {
                    Log.w(
                        TAG,
                        "Unable to set the kiosk screen timeout.",
                        exception
                    )
                }
            }

            if (
                devicePolicyManager.getMaximumTimeToLock(admin) !=
                KIOSK_TIMEOUT_MS.toLong()
            ) {
                try {
                    devicePolicyManager.setMaximumTimeToLock(
                        admin,
                        KIOSK_TIMEOUT_MS.toLong()
                    )

                    Log.i(TAG, "Set the kiosk maximum lock time to 5 minutes.")
                } catch (exception: SecurityException) {
                    Log.w(
                        TAG,
                        "Unable to set the kiosk maximum lock time.",
                        exception
                    )
                }
            }
        }

        fun restore(context: Context) {
            if (!isApplied(context)) {
                return
            }

            val devicePolicyManager = devicePolicyManager(context) ?: return
            val admin = adminComponent(context)
            val previous = prefs(context).getInt(
                KEY_PREVIOUS_TIMEOUT,
                KIOSK_TIMEOUT_MS
            )

            try {
                devicePolicyManager.setSystemSetting(
                    admin,
                    Settings.System.SCREEN_OFF_TIMEOUT,
                    previous.toString()
                )

                devicePolicyManager.setMaximumTimeToLock(admin, 0)

                setApplied(context, false)

                Log.i(TAG, "Restored the screen timeout.")
            } catch (exception: SecurityException) {
                Log.w(
                    TAG,
                    "Unable to restore the screen timeout.",
                    exception
                )
            }
        }

        private fun rememberPreviousTimeout(context: Context) {
            if (isApplied(context)) {
                return
            }

            prefs(context).edit()
                .putBoolean(KEY_APPLIED, true)
                .putInt(KEY_PREVIOUS_TIMEOUT, currentTimeout(context))
                .commit()
        }

        private fun currentTimeout(context: Context): Int {
            return Settings.System.getInt(
                context.contentResolver,
                Settings.System.SCREEN_OFF_TIMEOUT,
                KIOSK_TIMEOUT_MS
            )
        }

        private fun isApplied(context: Context): Boolean {
            return prefs(context).getBoolean(KEY_APPLIED, false)
        }

        private fun setApplied(
            context: Context,
            applied: Boolean
        ) {
            prefs(context).edit()
                .putBoolean(KEY_APPLIED, applied)
                .commit()
        }

        private fun adminComponent(context: Context) =
            ComponentName(
                context,
                AetherDeviceAdminReceiver::class.java
            )

        private fun devicePolicyManager(context: Context): DevicePolicyManager? {
            val manager =
                context.getSystemService(Context.DEVICE_POLICY_SERVICE)
                    as DevicePolicyManager

            if (!manager.isDeviceOwnerApp(context.packageName)) {
                return null
            }

            return manager
        }

        private fun prefs(context: Context) =
            context.createDeviceProtectedStorageContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}

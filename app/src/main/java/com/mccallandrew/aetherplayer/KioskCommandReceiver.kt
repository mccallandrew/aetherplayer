package com.mccallandrew.aetherplayer

import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.UserManager
import android.util.Log

class KioskCommandReceiver : BroadcastReceiver() {

    override fun onReceive(
        context: Context,
        intent: Intent
    ) {
        when (intent.action) {

            ACTION_EXIT_KIOSK -> {
                Log.i(TAG, "EXIT_KIOSK received.")

                /*
                 * Clear the enabled flag first. Releasing Lock
                 * Task leaves the system with nothing to resume,
                 * so it falls back to Home, and the kiosk is
                 * still the persistent Home at that point. The
                 * flag is what stops it re-entering the kiosk.
                 */
                setKioskEnabled(context, false)

                setNotificationListenerAccess(context, false)
                clearLockTaskPackages(context)
                clearLockTaskFeatures(context)
                restoreOriginalHome(context)
                launchOriginalHome(context)
            }

            ACTION_START_KIOSK -> {
                Log.i(TAG, "START_KIOSK received.")

                setKioskEnabled(context, true)
                setNotificationListenerAccess(context, true)
                applyPersistentHome(context)
                launchKioskHome(context)
            }
        }
    }

    companion object {

        const val ACTION_START_KIOSK =
            "com.mccallandrew.aetherplayer.START_KIOSK"

        const val ACTION_EXIT_KIOSK =
            "com.mccallandrew.aetherplayer.EXIT_KIOSK"

        const val SPOTIFY_PACKAGE = "com.spotify.music"

        /*
         * HOME is the only way back to the kiosk launcher from
         * Spotify. It routes Home intents to whichever Home app
         * is allowlisted, which is KioskHomeActivity.
         *
         * GLOBAL_ACTIONS keeps the power-button dialog
         * available. KEYGUARD has to stay enabled alongside it:
         * Samsung defers Power off and Restart behind a keyguard
         * unlock, so a suppressed keyguard silently discards
         * them and the device can never be turned off.
         *
         * Features not listed here stay disabled: Recents,
         * notifications and status info.
         */
        const val LOCK_TASK_FEATURES =
            DevicePolicyManager.LOCK_TASK_FEATURE_HOME or
                    DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS or
                    DevicePolicyManager.LOCK_TASK_FEATURE_KEYGUARD

        /*
         * Packages the Bluetooth stack / Fast Pair may launch
         * while pairing. Kept off the default allowlist so the
         * kiosk stays closed; BluetoothActivity adds them only
         * while that screen is open.
         */
        private val BLUETOOTH_PAIRING_PACKAGES = arrayOf(
            "com.android.settings",
            "com.samsung.android.settings",
            "com.android.bluetooth",
            "com.google.android.gms"
        )

        /*
         * Packages the Wi-Fi stack / captive portal may launch
         * while connecting. Kept off the default allowlist so
         * the kiosk stays closed; WifiActivity adds them only
         * while that screen is open.
         */
        private val WIFI_CONNECTION_PACKAGES = arrayOf(
            "com.android.settings",
            "com.samsung.android.settings",
            "com.android.captiveportallogin",
            "com.google.android.captiveportallogin"
        )

        private const val TAG = "AetherPlayer"

        /*
         * Settings ships this as a HOME activity so the system
         * has somewhere to land before a real launcher can run.
         * Its screen is "Phone is starting…". It is not a
         * launcher, and pinning it as the preferred Home makes
         * that screen wait forever for a home that is not itself.
         */
        private const val FALLBACK_HOME_CLASS = "FallbackHome"
        private const val FALLBACK_HOME_PACKAGE = "com.android.settings"

        private const val PREFS_NAME = "kiosk_policy"
        private const val KEY_HOME_PACKAGE = "original_home_package"
        private const val KEY_HOME_CLASS = "original_home_class"
        private const val KEY_KIOSK_ENABLED = "kiosk_enabled"

        private val PREFERRED_HOME_PACKAGES = listOf(
            "com.sec.android.app.launcher"
        )

        fun isKioskEnabled(context: Context): Boolean {
            return prefs(context)
                .getBoolean(KEY_KIOSK_ENABLED, false)
        }

        fun setKioskEnabled(
            context: Context,
            enabled: Boolean
        ) {
            prefs(context).edit()
                .putBoolean(KEY_KIOSK_ENABLED, enabled)
                .commit()
        }

        fun clearLockTaskPackages(context: Context) {
            val devicePolicyManager = devicePolicyManager(context) ?: return

            devicePolicyManager.setLockTaskPackages(
                adminComponent(context),
                emptyArray()
            )
        }

        fun defaultLockTaskPackages(context: Context): Array<String> {
            return arrayOf(
                context.packageName,
                SPOTIFY_PACKAGE
            )
        }

        fun bluetoothLockTaskPackages(context: Context): Array<String> {
            return defaultLockTaskPackages(context) +
                BLUETOOTH_PAIRING_PACKAGES
        }

        fun wifiLockTaskPackages(context: Context): Array<String> {
            return defaultLockTaskPackages(context) +
                WIFI_CONNECTION_PACKAGES
        }

        fun applyLockTaskPackages(
            context: Context,
            packages: Array<String>
        ) {
            val devicePolicyManager = devicePolicyManager(context) ?: return
            val admin = adminComponent(context)

            if (
                devicePolicyManager
                    .getLockTaskPackages(admin)
                    .contentEquals(packages)
            ) {
                return
            }

            devicePolicyManager.setLockTaskPackages(admin, packages)
        }

        fun clearLockTaskFeatures(context: Context) {
            val devicePolicyManager = devicePolicyManager(context) ?: return

            devicePolicyManager.setLockTaskFeatures(
                adminComponent(context),
                DevicePolicyManager.LOCK_TASK_FEATURE_NONE
            )
        }

        fun setNotificationListenerAccess(
            context: Context,
            granted: Boolean
        ) {
            val listener = notificationListenerComponent(context)
            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE)
                    as android.app.NotificationManager

            val currentlyGranted =
                notificationManager.isNotificationListenerAccessGranted(listener)

            if (currentlyGranted == granted) {
                return
            }

            if (grantNotificationListenerViaReflection(
                    notificationManager,
                    listener,
                    granted
                )
            ) {
                Log.i(
                    TAG,
                    "Notification listener access set to $granted."
                )
                return
            }

            Log.w(
                TAG,
                "Unable to set notification listener access. Grant with: " +
                    "adb shell cmd notification allow_listener " +
                    listener.flattenToString()
            )
        }

        private fun grantNotificationListenerViaReflection(
            notificationManager: android.app.NotificationManager,
            listener: ComponentName,
            granted: Boolean
        ): Boolean {
            return try {
                val method =
                    android.app.NotificationManager::class.java.getMethod(
                        "setNotificationListenerAccessGranted",
                        ComponentName::class.java,
                        java.lang.Boolean.TYPE
                    )

                method.invoke(notificationManager, listener, granted)
                true
            } catch (exception: Exception) {
                Log.w(
                    TAG,
                    "NotificationManager reflection grant failed.",
                    exception
                )
                false
            }
        }

        fun isUserUnlocked(context: Context): Boolean {
            val userManager =
                context.getSystemService(Context.USER_SERVICE) as UserManager

            return userManager.isUserUnlocked
        }

        /*
         * BOOT_COMPLETED is what turns the kiosk back on. Home
         * can arrive before that, and handing off in that window
         * is what used to pin FallbackHome in place.
         */
        fun isBootCompleted(): Boolean {
            val value = systemProperty("sys.boot_completed")
                ?: shellProperty("sys.boot_completed")

            if (value == null) {
                Log.w(TAG, "Unable to read boot state; assuming boot has completed.")
                return true
            }

            return value == "1"
        }

        fun applyPersistentHome(context: Context) {
            val devicePolicyManager = devicePolicyManager(context) ?: return
            val admin = adminComponent(context)

            captureOriginalHomeIfNeeded(context)

            /*
             * Rewriting this churns the system Home role and is
             * a persisted package-manager write, so skip it when
             * the kiosk is already the preferred Home.
             */
            if (isPersistentHome(context)) {
                return
            }

            Log.i(TAG, "Making the kiosk the persistent Home app.")

            originalHomeComponent(context)?.let { originalHome ->
                devicePolicyManager.clearPackagePersistentPreferredActivities(
                    admin,
                    originalHome.packageName
                )
            }

            devicePolicyManager.clearPackagePersistentPreferredActivities(
                admin,
                context.packageName
            )

            devicePolicyManager.addPersistentPreferredActivity(
                admin,
                homeIntentFilter(),
                kioskHomeComponent(context)
            )
        }

        fun restoreOriginalHome(context: Context) {
            val devicePolicyManager = devicePolicyManager(context) ?: return
            val admin = adminComponent(context)

            captureOriginalHomeIfNeeded(context)

            val originalHome = originalHomeComponent(context)

            /*
             * Clearing our own preference with nothing to put
             * back resolves Home to FallbackHome, which then
             * never finishes. Wait until a real launcher exists.
             */
            if (originalHome == null) {
                Log.w(TAG, "Not restoring Home; no usable launcher yet.")
                return
            }

            devicePolicyManager.clearPackagePersistentPreferredActivities(
                admin,
                context.packageName
            )

            clearFallbackHomePreference(context)

            Log.i(TAG, "Restoring $originalHome as Home.")

            devicePolicyManager.addPersistentPreferredActivity(
                admin,
                homeIntentFilter(),
                originalHome
            )
        }

        fun hasOriginalHome(context: Context): Boolean {
            return originalHomeComponent(context) != null
        }

        fun launchOriginalHome(context: Context) {
            val originalHome = originalHomeComponent(context)

            /*
             * An implicit Home intent is how the system picks
             * FallbackHome when no real launcher is preferred.
             * Never start that screen from here.
             */
            if (originalHome == null) {
                Log.w(TAG, "Not launching Home; no usable launcher yet.")
                return
            }

            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TASK
                )
                component = originalHome
            }

            context.startActivity(homeIntent)
        }

        fun launchKioskHome(context: Context) {

            val kioskIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            /*
             * Naming the component makes this an ordinary
             * activity launch, which the system files in the
             * standard stack. The Home button only ever targets
             * the home stack, so it would build a second
             * instance there and leave this one orphaned behind
             * it. Left unnamed, the intent resolves through the
             * persistent Home policy applied just before it and
             * lands in the home stack, where the Home button
             * reuses it.
             *
             * The component is still the fallback for a policy
             * write that has not taken hold, where showing the
             * kiosk at all matters more than which stack it is
             * in.
             */
            if (!isPersistentHome(context)) {
                kioskIntent.component = kioskHomeComponent(context)
            }

            context.startActivity(kioskIntent)
        }

        private fun isPersistentHome(context: Context): Boolean {
            val resolved = context.packageManager.resolveActivity(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME),
                PackageManager.MATCH_DEFAULT_ONLY
            ) ?: return false

            return resolved.activityInfo.packageName ==
                    context.packageName
        }

        private fun captureOriginalHomeIfNeeded(context: Context) {
            /*
             * Before unlock, queryIntentActivities only returns
             * direct-boot homes. On this device that set is the
             * kiosk and FallbackHome, so a capture here stores
             * the "Phone is starting" screen as the original
             * launcher.
             */
            if (!isUserUnlocked(context)) {
                return
            }

            val savedHome = savedHomeComponent(context)

            if (savedHome != null && isUsableHome(context, savedHome)) {
                return
            }

            val originalHome = findOriginalHome(context) ?: return

            Log.i(TAG, "Remembering $originalHome as the original Home.")

            prefs(context).edit()
                .putString(KEY_HOME_PACKAGE, originalHome.packageName)
                .putString(KEY_HOME_CLASS, originalHome.className)
                .commit()
        }

        private fun findOriginalHome(context: Context): ComponentName? {
            val homeApps = queryHomeActivities(context)
                .filter { isUsableHome(context, it) }

            PREFERRED_HOME_PACKAGES.forEach { preferredPackage ->
                homeApps.firstOrNull { it.packageName == preferredPackage }?.let {
                    return it
                }
            }

            return homeApps.firstOrNull()
        }

        private fun queryHomeActivities(context: Context): List<ComponentName> {
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
            }

            @Suppress("DEPRECATION")
            return context.packageManager
                .queryIntentActivities(homeIntent, 0)
                .map { ComponentName(it.activityInfo.packageName, it.activityInfo.name) }
        }

        private fun originalHomeComponent(context: Context): ComponentName? {
            val savedHome = savedHomeComponent(context)

            if (savedHome != null && isUsableHome(context, savedHome)) {
                return savedHome
            }

            return findOriginalHome(context)
        }

        private fun savedHomeComponent(context: Context): ComponentName? {
            val prefs = prefs(context)
            val packageName = prefs.getString(KEY_HOME_PACKAGE, null)
            val className = prefs.getString(KEY_HOME_CLASS, null)

            if (packageName.isNullOrEmpty() || className.isNullOrEmpty()) {
                return null
            }

            return ComponentName(packageName, className)
        }

        private fun isUsableHome(
            context: Context,
            component: ComponentName
        ): Boolean {
            if (component.packageName == context.packageName) {
                return false
            }

            if (component.className.contains("ResolverActivity")) {
                return false
            }

            if (isFallbackHome(component)) {
                return false
            }

            return try {
                context.packageManager.getActivityInfo(component, 0)
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }
        }

        private fun isFallbackHome(component: ComponentName): Boolean {
            return component.packageName == FALLBACK_HOME_PACKAGE ||
                component.className.contains(FALLBACK_HOME_CLASS)
        }

        private fun clearFallbackHomePreference(context: Context) {
            val devicePolicyManager = devicePolicyManager(context) ?: return

            devicePolicyManager.clearPackagePersistentPreferredActivities(
                adminComponent(context),
                FALLBACK_HOME_PACKAGE
            )
        }

        private fun systemProperty(name: String): String? {
            return try {
                val systemProperties = Class.forName("android.os.SystemProperties")
                val get = systemProperties.getMethod(
                    "get",
                    String::class.java,
                    String::class.java
                )

                (get.invoke(null, name, "") as String).trim().ifEmpty { null }
            } catch (exception: Exception) {
                Log.w(TAG, "SystemProperties read failed.", exception)
                null
            }
        }

        private fun shellProperty(name: String): String? {
            return try {
                val process = ProcessBuilder("/system/bin/getprop", name)
                    .redirectErrorStream(true)
                    .start()

                val value = process.inputStream.bufferedReader().readText().trim()
                process.waitFor()

                value.ifEmpty { null }
            } catch (exception: Exception) {
                Log.w(TAG, "getprop read failed.", exception)
                null
            }
        }

        private fun homeIntentFilter(): IntentFilter {
            return IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addCategory(Intent.CATEGORY_DEFAULT)
            }
        }

        private fun prefs(context: Context) =
            context.createDeviceProtectedStorageContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        private fun kioskHomeComponent(context: Context) =
            ComponentName(
                context,
                KioskHomeActivity::class.java
            )

        private fun notificationListenerComponent(context: Context) =
            ComponentName(
                context,
                SpotifyNotificationListener::class.java
            )

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
    }
}

package com.mccallandrew.aetherplayer

import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager

class KioskCommandReceiver : BroadcastReceiver() {

    override fun onReceive(
        context: Context,
        intent: Intent
    ) {
        when (intent.action) {

            MainActivity.ACTION_EXIT_KIOSK -> {
                clearLockTaskPackages(context)
                restoreOriginalHome(context)
                launchOriginalHome(context)
            }

            MainActivity.ACTION_START_KIOSK -> {
                launchController(
                    context,
                    MainActivity.ACTION_START_KIOSK
                )
            }
        }
    }

    private fun launchController(
        context: Context,
        action: String
    ) {
        val launchIntent =
            Intent(context, MainActivity::class.java).apply {
                this.action = action

                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TASK
                )
            }

        context.startActivity(launchIntent)
    }

    companion object {

        private const val PREFS_NAME = "kiosk_policy"
        private const val KEY_HOME_PACKAGE = "original_home_package"
        private const val KEY_HOME_CLASS = "original_home_class"

        private val PREFERRED_HOME_PACKAGES = listOf(
            "com.sec.android.app.launcher"
        )

        fun clearLockTaskPackages(context: Context) {
            val devicePolicyManager = devicePolicyManager(context) ?: return

            devicePolicyManager.setLockTaskPackages(
                adminComponent(context),
                emptyArray()
            )
        }

        fun applyPersistentHome(context: Context) {
            val devicePolicyManager = devicePolicyManager(context) ?: return
            val admin = adminComponent(context)

            captureOriginalHomeIfNeeded(context)

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
                ComponentName(context, MainActivity::class.java)
            )
        }

        fun restoreOriginalHome(context: Context) {
            val devicePolicyManager = devicePolicyManager(context) ?: return
            val admin = adminComponent(context)

            devicePolicyManager.clearPackagePersistentPreferredActivities(
                admin,
                context.packageName
            )

            originalHomeComponent(context)?.let { originalHome ->
                devicePolicyManager.addPersistentPreferredActivity(
                    admin,
                    homeIntentFilter(),
                    originalHome
                )
            }

            try {
                devicePolicyManager.setKeyguardDisabled(admin, false)
            } catch (_: SecurityException) {
                // Keyguard may already be in its default state.
            }
        }

        fun launchOriginalHome(context: Context) {
            val originalHome = originalHomeComponent(context)

            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TASK
                )

                if (originalHome != null) {
                    component = originalHome
                }
            }

            context.startActivity(homeIntent)
        }

        private fun captureOriginalHomeIfNeeded(context: Context) {
            val savedHome = savedHomeComponent(context)

            if (savedHome != null && isUsableHome(context, savedHome)) {
                return
            }

            val originalHome = findOriginalHome(context) ?: return

            prefs(context).edit()
                .putString(KEY_HOME_PACKAGE, originalHome.packageName)
                .putString(KEY_HOME_CLASS, originalHome.className)
                .apply()
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

            return try {
                context.packageManager.getActivityInfo(component, 0)
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
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

package com.mccallandrew.aetherplayer

import android.app.WallpaperManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import kotlin.math.min

/*
 * Puts the family crest on the system lock screen while kiosk
 * mode is on. Lock Task keeps the keyguard visible, so this is
 * the lock screen the device actually shows.
 *
 * Only FLAG_LOCK is written. Home wallpaper is left alone, and
 * exiting the kiosk clears the lock wallpaper so the lock screen
 * follows the home wallpaper again.
 *
 * The lock wallpaper does not survive reboot. The applied flag
 * does, so each boot sets the crest again, and once more a few
 * seconds later in case the system restores its own wallpaper
 * after boot.
 */
class KioskLockWallpaper {

    companion object {

        private const val TAG = "AetherPlayer"

        private const val PREFS_NAME = "kiosk_lock_wallpaper"
        private const val KEY_APPLIED = "applied"
        private const val KEY_BOOT_COUNT = "boot_count"
        private const val KEY_WALLPAPER_ID = "wallpaper_id"

        private const val BOOT_RETRY_DELAY_MS = 8000L

        private var retryScheduledForBoot = -1

        fun apply(context: Context) {
            if (!isDeviceOwner(context)) {
                return
            }

            if (alreadyApplied(context)) {
                return
            }

            if (!writeWallpaper(context)) {
                return
            }

            scheduleBootRetry(context)
        }

        fun restore(context: Context) {
            if (!isApplied(context)) {
                return
            }

            try {
                WallpaperManager.getInstance(context)
                    .clear(WallpaperManager.FLAG_LOCK)

                markCleared(context)

                Log.i(TAG, "Restored the lock screen wallpaper.")
            } catch (exception: Exception) {
                Log.w(
                    TAG,
                    "Unable to restore the lock screen wallpaper.",
                    exception
                )
            }
        }

        private fun writeWallpaper(context: Context): Boolean {
            val crest = decodeCrest(context) ?: return false
            var applied = false

            try {
                val wallpaper = composeWallpaper(context, crest)

                try {
                    val wallpaperManager = WallpaperManager.getInstance(context)

                    wallpaperManager.setBitmap(
                        wallpaper,
                        null,
                        false,
                        WallpaperManager.FLAG_LOCK
                    )

                    markApplied(
                        context,
                        wallpaperManager.getWallpaperId(
                            WallpaperManager.FLAG_LOCK
                        )
                    )

                    applied = true

                    Log.i(TAG, "Set the kiosk lock screen wallpaper.")
                } finally {
                    wallpaper.recycle()
                }
            } catch (exception: Exception) {
                Log.w(
                    TAG,
                    "Unable to set the kiosk lock screen wallpaper.",
                    exception
                )
            } finally {
                crest.recycle()
            }

            return applied
        }

        /*
         * Home is up before the system has finished restoring
         * wallpapers. Setting the crest again after that pass
         * is what makes it stick.
         */
        private fun scheduleBootRetry(context: Context) {
            val boot = bootCount(context)

            if (retryScheduledForBoot == boot) {
                return
            }

            retryScheduledForBoot = boot

            val appContext = context.applicationContext

            Handler(Looper.getMainLooper()).postDelayed(
                { rewriteIfStillKiosk(appContext) },
                BOOT_RETRY_DELAY_MS
            )
        }

        private fun rewriteIfStillKiosk(context: Context) {
            if (!KioskCommandReceiver.isKioskEnabled(context)) {
                return
            }

            if (!isDeviceOwner(context)) {
                return
            }

            writeWallpaper(context)
        }

        private fun decodeCrest(context: Context): Bitmap? {
            val crest = context.resources
                .openRawResource(R.raw.kiosk_lock_wallpaper)
                .use { stream ->
                    BitmapFactory.decodeStream(stream)
                }

            if (crest == null) {
                Log.w(TAG, "Unable to decode the kiosk lock screen wallpaper.")
            }

            return crest
        }

        /*
         * The crest is square. A screen-sized black canvas keeps
         * the whole image on screen instead of letting the
         * wallpaper service crop it to the display.
         */
        private fun composeWallpaper(
            context: Context,
            crest: Bitmap
        ): Bitmap {
            val size = displaySize(context)
            val width = size.x.coerceAtLeast(1)
            val height = size.y.coerceAtLeast(1)

            val wallpaper = Bitmap.createBitmap(
                width,
                height,
                Bitmap.Config.ARGB_8888
            )

            val canvas = Canvas(wallpaper)
            canvas.drawColor(crest.getPixel(0, 0))

            val scale = min(
                width.toFloat() / crest.width,
                height.toFloat() / crest.height
            )

            val destWidth = crest.width * scale
            val destHeight = crest.height * scale
            val left = (width - destWidth) / 2f
            val top = (height - destHeight) / 2f

            canvas.drawBitmap(
                crest,
                null,
                RectF(left, top, left + destWidth, top + destHeight),
                Paint(Paint.FILTER_BITMAP_FLAG)
            )

            return wallpaper
        }

        private fun displaySize(context: Context): Point {
            val windowManager =
                context.getSystemService(Context.WINDOW_SERVICE)
                    as WindowManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = windowManager.currentWindowMetrics.bounds

                return Point(bounds.width(), bounds.height())
            }

            val size = Point()

            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealSize(size)

            return size
        }

        private fun isDeviceOwner(context: Context): Boolean {
            val devicePolicyManager =
                context.getSystemService(Context.DEVICE_POLICY_SERVICE)
                    as DevicePolicyManager

            return devicePolicyManager.isDeviceOwnerApp(context.packageName)
        }

        private fun alreadyApplied(context: Context): Boolean {
            val preferences = prefs(context)

            if (!preferences.getBoolean(KEY_APPLIED, false)) {
                return false
            }

            if (preferences.getInt(KEY_BOOT_COUNT, -1) != bootCount(context)) {
                return false
            }

            val appliedId = preferences.getInt(KEY_WALLPAPER_ID, -1)

            if (appliedId == -1) {
                return false
            }

            return WallpaperManager.getInstance(context)
                .getWallpaperId(WallpaperManager.FLAG_LOCK) == appliedId
        }

        private fun isApplied(context: Context): Boolean {
            return prefs(context).getBoolean(KEY_APPLIED, false)
        }

        private fun markApplied(
            context: Context,
            wallpaperId: Int
        ) {
            prefs(context).edit()
                .putBoolean(KEY_APPLIED, true)
                .putInt(KEY_BOOT_COUNT, bootCount(context))
                .putInt(KEY_WALLPAPER_ID, wallpaperId)
                .commit()
        }

        private fun markCleared(context: Context) {
            prefs(context).edit()
                .putBoolean(KEY_APPLIED, false)
                .remove(KEY_BOOT_COUNT)
                .remove(KEY_WALLPAPER_ID)
                .commit()
        }

        private fun bootCount(context: Context): Int {
            return Settings.Global.getInt(
                context.contentResolver,
                Settings.Global.BOOT_COUNT,
                0
            )
        }

        private fun prefs(context: Context) =
            context.createDeviceProtectedStorageContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}

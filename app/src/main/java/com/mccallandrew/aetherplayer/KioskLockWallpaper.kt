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
 */
class KioskLockWallpaper {

    companion object {

        private const val TAG = "AetherPlayer"

        private const val PREFS_NAME = "kiosk_lock_wallpaper"
        private const val KEY_APPLIED = "applied"

        fun apply(context: Context) {
            if (isApplied(context)) {
                return
            }

            if (!isDeviceOwner(context)) {
                return
            }

            val crest = decodeCrest(context) ?: return

            try {
                val wallpaper = composeWallpaper(context, crest)

                try {
                    WallpaperManager.getInstance(context).setBitmap(
                        wallpaper,
                        null,
                        false,
                        WallpaperManager.FLAG_LOCK
                    )

                    setApplied(context, true)

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
        }

        fun restore(context: Context) {
            if (!isApplied(context)) {
                return
            }

            try {
                WallpaperManager.getInstance(context)
                    .clear(WallpaperManager.FLAG_LOCK)

                setApplied(context, false)

                Log.i(TAG, "Restored the lock screen wallpaper.")
            } catch (exception: Exception) {
                Log.w(
                    TAG,
                    "Unable to restore the lock screen wallpaper.",
                    exception
                )
            }
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

        private fun prefs(context: Context) =
            context.createDeviceProtectedStorageContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}

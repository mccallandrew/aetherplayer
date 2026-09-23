package com.mccallandrew.aetherplayer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat

/*
 * Tracks battery level and charging state for the kiosk home gauge.
 */
class BatteryStatusHelper(
    context: Context,
    private val listener: Listener
) {

    interface Listener {
        fun onBatteryStatusChanged(status: BatteryStatus)
    }

    data class BatteryStatus(
        val percent: Int,
        val isCharging: Boolean,
        val isFull: Boolean,
        val chargeTimeRemainingMs: Long
    )

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    private val batteryManager =
        appContext.getSystemService(Context.BATTERY_SERVICE) as BatteryManager?

    private var started = false

    private val broadcastReceiver = object : BroadcastReceiver() {

        override fun onReceive(
            context: Context,
            intent: Intent
        ) {
            publish()
        }
    }

    fun start() {
        if (started) {
            return
        }

        started = true

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_BATTERY_LOW)
            addAction(Intent.ACTION_BATTERY_OKAY)
        }

        ContextCompat.registerReceiver(
            appContext,
            broadcastReceiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED
        )

        publish()
    }

    fun stop() {
        if (!started) {
            return
        }

        started = false

        try {
            appContext.unregisterReceiver(broadcastReceiver)
        } catch (_: IllegalArgumentException) {
            // Already unregistered.
        }
    }

    fun refresh() {
        publish()
    }

    private fun publish() {
        mainHandler.post {
            if (!started) {
                return@post
            }

            listener.onBatteryStatusChanged(readStatus())
        }
    }

    private fun readStatus(): BatteryStatus {
        val sticky = appContext.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )

        val level = sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = sticky?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = sticky?.getIntExtra(
            BatteryManager.EXTRA_STATUS,
            BatteryManager.BATTERY_STATUS_UNKNOWN
        ) ?: BatteryManager.BATTERY_STATUS_UNKNOWN
        val plugged = sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0

        val percent = when {
            level < 0 || scale <= 0 -> 0
            else -> ((level * 100f) / scale).toInt().coerceIn(0, 100)
        }

        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL ||
            plugged != 0

        val isFull = status == BatteryManager.BATTERY_STATUS_FULL ||
            (isCharging && percent >= 100)

        val remainingMs = if (isCharging && !isFull) {
            batteryManager?.computeChargeTimeRemaining() ?: -1L
        } else {
            -1L
        }

        return BatteryStatus(
            percent = percent,
            isCharging = isCharging,
            isFull = isFull,
            chargeTimeRemainingMs = remainingMs
        )
    }
}

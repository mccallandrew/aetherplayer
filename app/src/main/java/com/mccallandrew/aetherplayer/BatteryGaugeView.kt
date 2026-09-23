package com.mccallandrew.aetherplayer

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import kotlin.math.max
import kotlin.math.min

/*
 * Custom battery silhouette with a live fill, charging sweep,
 * and a low-charge outline pulse.
 */
class BatteryGaugeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val greenColor =
        ContextCompat.getColor(context, R.color.aether_green)

    private val orangeColor =
        ContextCompat.getColor(context, R.color.aether_orange)

    private val outlineIdleColor =
        ContextCompat.getColor(context, R.color.aether_text_muted)

    private val chamberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(40, 242, 245, 243)
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.5f)
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    private val terminalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(6f)
    }

    private val sweepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val bodyPath = Path()
    private val fillClip = Path()
    private val bodyRect = RectF()
    private val terminalRect = RectF()
    private val fillRect = RectF()

    private var percent = 0
    private var isCharging = false
    private var isFull = false

    private var sweepProgress = 0f
    private var pulseProgress = 0f

    private var fillShader: LinearGradient? = null
    private var lastFillColor = Color.TRANSPARENT
    private var lastWidth = 0
    private var lastHeight = 0

    private var sweepAnimator: ValueAnimator? = null
    private var pulseAnimator: ValueAnimator? = null

    fun setBatteryState(
        percent: Int,
        isCharging: Boolean,
        isFull: Boolean
    ) {
        this.percent = percent.coerceIn(0, 100)
        this.isCharging = isCharging
        this.isFull = isFull
        fillShader = null
        updateAnimations()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        stopAnimations()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()

        if (w <= 0f || h <= 0f) {
            return
        }

        rebuildGeometry(w, h)

        val fillColor = colorForPercent(percent)
        ensureFillShader(fillColor)

        // Soft outer glow when charging or low.
        if (isCharging && !isFull) {
            glowPaint.color = withAlpha(fillColor, 55)
            canvas.drawPath(bodyPath, glowPaint)
        } else if (percent <= LOW_THRESHOLD && !isCharging) {
            val alpha = (70 + 90 * pulseProgress).toInt()
            glowPaint.color = withAlpha(orangeColor, alpha)
            canvas.drawPath(bodyPath, glowPaint)
        }

        canvas.drawPath(bodyPath, chamberPaint)

        // Level fill from the bottom of the chamber.
        val fillFraction = percent / 100f
        val inset = dp(5f)
        fillRect.set(
            bodyRect.left + inset,
            bodyRect.top + inset,
            bodyRect.right - inset,
            bodyRect.bottom - inset
        )

        val fillHeight = fillRect.height() * fillFraction
        val fillTop = fillRect.bottom - fillHeight

        if (fillHeight > 0f) {
            val save = canvas.save()
            fillClip.reset()
            fillClip.addRoundRect(
                fillRect.left,
                max(fillTop, fillRect.top),
                fillRect.right,
                fillRect.bottom,
                dp(10f),
                dp(10f),
                Path.Direction.CW
            )
            canvas.clipPath(fillClip)

            fillPaint.shader = fillShader
            canvas.drawRect(
                fillRect.left,
                fillTop,
                fillRect.right,
                fillRect.bottom,
                fillPaint
            )

            if (isCharging && !isFull && fillHeight > dp(4f)) {
                drawChargingSweep(canvas, fillTop)
            }

            canvas.restoreToCount(save)
        }

        val outlineColor = when {
            percent <= LOW_THRESHOLD && !isCharging -> {
                val blend = 0.45f + 0.55f * pulseProgress
                lerpColor(outlineIdleColor, orangeColor, blend)
            }
            isCharging -> fillColor
            else -> outlineIdleColor
        }

        outlinePaint.color = outlineColor
        canvas.drawPath(bodyPath, outlinePaint)

        terminalPaint.color = outlineColor
        canvas.drawRoundRect(
            terminalRect,
            dp(3f),
            dp(3f),
            terminalPaint
        )
    }

    private fun drawChargingSweep(
        canvas: Canvas,
        fillTop: Float
    ) {
        val bandWidth = fillRect.width() * 0.35f
        val travel = fillRect.width() + bandWidth
        val left = fillRect.left - bandWidth + travel * sweepProgress

        sweepPaint.shader = LinearGradient(
            left,
            0f,
            left + bandWidth,
            0f,
            intArrayOf(
                Color.TRANSPARENT,
                withAlpha(Color.WHITE, 90),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )

        canvas.drawRect(
            left,
            max(fillTop, fillRect.top),
            left + bandWidth,
            fillRect.bottom,
            sweepPaint
        )
        sweepPaint.shader = null
    }

    private fun rebuildGeometry(
        w: Float,
        h: Float
    ) {
        if (w.toInt() == lastWidth && h.toInt() == lastHeight) {
            return
        }

        lastWidth = w.toInt()
        lastHeight = h.toInt()

        val pad = dp(2f)
        val terminalWidth = w * 0.14f
        val terminalHeight = h * 0.28f
        val bodyRight = w - pad - terminalWidth * 0.55f

        bodyRect.set(
            pad,
            pad,
            bodyRight,
            h - pad
        )

        val terminalCenterY = h / 2f
        terminalRect.set(
            bodyRect.right - dp(1f),
            terminalCenterY - terminalHeight / 2f,
            w - pad,
            terminalCenterY + terminalHeight / 2f
        )

        bodyPath.reset()
        bodyPath.addRoundRect(
            bodyRect,
            dp(14f),
            dp(14f),
            Path.Direction.CW
        )

        fillShader = null
    }

    private fun ensureFillShader(fillColor: Int) {
        if (
            fillShader != null &&
            fillColor == lastFillColor &&
            lastWidth == width &&
            lastHeight == height
        ) {
            return
        }

        lastFillColor = fillColor
        val highlight = lighten(fillColor, 0.35f)
        val deep = darken(fillColor, 0.28f)

        fillShader = LinearGradient(
            0f,
            bodyRect.top,
            0f,
            bodyRect.bottom,
            intArrayOf(highlight, fillColor, deep),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    private fun colorForPercent(percent: Int): Int {
        return when {
            percent > HIGH_THRESHOLD -> greenColor
            percent <= LOW_THRESHOLD -> orangeColor
            else -> {
                val t = (HIGH_THRESHOLD - percent).toFloat() /
                    (HIGH_THRESHOLD - LOW_THRESHOLD).toFloat()
                lerpColor(greenColor, orangeColor, t)
            }
        }
    }

    private fun updateAnimations() {
        val shouldSweep = isCharging && !isFull
        val shouldPulse = percent <= LOW_THRESHOLD && !isCharging

        if (shouldSweep) {
            startSweep()
        } else {
            stopSweep()
        }

        if (shouldPulse) {
            startPulse()
        } else {
            stopPulse()
        }
    }

    private fun startSweep() {
        if (sweepAnimator?.isRunning == true) {
            return
        }

        sweepAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = SWEEP_DURATION_MS
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { animator ->
                sweepProgress = animator.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stopSweep() {
        sweepAnimator?.cancel()
        sweepAnimator = null
        sweepProgress = 0f
    }

    private fun startPulse() {
        if (pulseAnimator?.isRunning == true) {
            return
        }

        pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = PULSE_DURATION_MS
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener { animator ->
                pulseProgress = animator.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stopPulse() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        pulseProgress = 0f
    }

    private fun stopAnimations() {
        stopSweep()
        stopPulse()
    }

    private fun dp(value: Float): Float =
        value * resources.displayMetrics.density

    private fun withAlpha(
        color: Int,
        alpha: Int
    ): Int =
        Color.argb(
            alpha.coerceIn(0, 255),
            Color.red(color),
            Color.green(color),
            Color.blue(color)
        )

    private fun lerpColor(
        from: Int,
        to: Int,
        t: Float
    ): Int {
        val clamped = min(1f, max(0f, t))
        val r = Color.red(from) +
            ((Color.red(to) - Color.red(from)) * clamped).toInt()
        val g = Color.green(from) +
            ((Color.green(to) - Color.green(from)) * clamped).toInt()
        val b = Color.blue(from) +
            ((Color.blue(to) - Color.blue(from)) * clamped).toInt()
        return Color.rgb(r, g, b)
    }

    private fun lighten(
        color: Int,
        amount: Float
    ): Int {
        val r = Color.red(color) +
            ((255 - Color.red(color)) * amount).toInt()
        val g = Color.green(color) +
            ((255 - Color.green(color)) * amount).toInt()
        val b = Color.blue(color) +
            ((255 - Color.blue(color)) * amount).toInt()
        return Color.rgb(
            r.coerceIn(0, 255),
            g.coerceIn(0, 255),
            b.coerceIn(0, 255)
        )
    }

    private fun darken(
        color: Int,
        amount: Float
    ): Int {
        val factor = 1f - amount
        return Color.rgb(
            (Color.red(color) * factor).toInt().coerceIn(0, 255),
            (Color.green(color) * factor).toInt().coerceIn(0, 255),
            (Color.blue(color) * factor).toInt().coerceIn(0, 255)
        )
    }

    companion object {
        private const val HIGH_THRESHOLD = 40
        private const val LOW_THRESHOLD = 15
        private const val SWEEP_DURATION_MS = 2200L
        private const val PULSE_DURATION_MS = 1100L
    }
}

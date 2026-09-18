package com.mccallandrew.aetherplayer

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat

/*
 * Soft family-name word cloud used as the kiosk home background.
 * Placements are fixed so the layout is stable across launches.
 */
class WordCloudDrawable(
    context: Context
) : Drawable() {

    private val backgroundColor =
        ContextCompat.getColor(context, R.color.aether_background)

    private val wordColor =
        ContextCompat.getColor(context, R.color.aether_orange)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private data class Placement(
        val word: String,
        val xFraction: Float,
        val yFraction: Float,
        val sizeFractionOfHeight: Float,
        val rotationDegrees: Float,
        val alpha: Int
    )

    private val placements = listOf(
        Placement("Nana", 0.18f, 0.12f, 0.055f, -18f, 120),
        Placement("Mama", 0.72f, 0.10f, 0.070f, 14f, 95),
        Placement("Daddy", 0.42f, 0.22f, 0.048f, -8f, 75),
        Placement("Oma", 0.88f, 0.28f, 0.060f, 22f, 110),
        Placement("Papa", 0.12f, 0.36f, 0.052f, 12f, 85),
        Placement("Opa", 0.58f, 0.38f, 0.045f, -24f, 70),
        Placement("Essyn", 0.28f, 0.52f, 0.065f, 8f, 100),
        Placement("Waylon", 0.78f, 0.50f, 0.058f, -12f, 90),
        Placement("Mama", 0.08f, 0.68f, 0.042f, -20f, 60),
        Placement("Nana", 0.52f, 0.62f, 0.038f, 16f, 55),
        Placement("Daddy", 0.90f, 0.70f, 0.050f, -6f, 80),
        Placement("Oma", 0.34f, 0.78f, 0.044f, 18f, 65),
        Placement("Papa", 0.68f, 0.82f, 0.056f, -15f, 100),
        Placement("Opa", 0.16f, 0.90f, 0.048f, 10f, 70),
        Placement("Essyn", 0.82f, 0.92f, 0.040f, -22f, 55),
        Placement("Waylon", 0.48f, 0.94f, 0.046f, 6f, 75)
    )

    override fun draw(
        canvas: Canvas
    ) {
        val bounds = bounds
        canvas.drawColor(backgroundColor)

        val width = bounds.width().toFloat()
        val height = bounds.height().toFloat()
        if (width <= 0f || height <= 0f) {
            return
        }

        for (placement in placements) {
            paint.color = wordColor
            paint.alpha = placement.alpha
            paint.textSize = height * placement.sizeFractionOfHeight

            val x = bounds.left + width * placement.xFraction
            val y = bounds.top + height * placement.yFraction

            canvas.save()
            canvas.rotate(placement.rotationDegrees, x, y)
            canvas.drawText(placement.word, x, y, paint)
            canvas.restore()
        }
    }

    override fun setAlpha(
        alpha: Int
    ) {
        // Word alphas are authored per placement; overall alpha is unused.
    }

    override fun setColorFilter(
        colorFilter: ColorFilter?
    ) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}

package com.logcam.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/** Compact live luminance histogram for the camera preview. */
class HistogramView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    @Volatile private var bins = FloatArray(64)
    private val panel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xB90B0D10.toInt() }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x44FFFFFF
        strokeWidth = resources.displayMetrics.density
        style = Paint.Style.STROKE
    }
    private val bars = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xE6FFFFFF.toInt()
        textSize = 9f * resources.displayMetrics.scaledDensity
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x55FFFFFF
        strokeWidth = resources.displayMetrics.density
        style = Paint.Style.STROKE
    }

    fun setBins(values: FloatArray) {
        bins = values.copyOf()
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val d = resources.displayMetrics.density
        val r = RectF(0.5f * d, 0.5f * d, width - 0.5f * d, height - 0.5f * d)
        canvas.drawRoundRect(r, 8f * d, 8f * d, panel)
        canvas.save()
        canvas.clipRect(r)
        val left = 8f * d
        val right = width - 8f * d
        val top = 18f * d
        val bottom = height - 8f * d
        for (i in 1..3) {
            val y = top + (bottom - top) * i / 4f
            canvas.drawLine(left, y, right, y, grid)
        }
        canvas.drawLine(left, top, left, bottom, grid)
        canvas.drawLine((left + right) / 2f, top, (left + right) / 2f, bottom, grid)
        val values = bins
        val step = (right - left) / values.size
        bars.color = Theme.accent
        val path = Path().apply {
            moveTo(left, bottom)
            for (i in values.indices) {
                val x = left + (i + 0.5f) * step
                val y = bottom - values[i].coerceIn(0f, 1f) * (bottom - top)
                lineTo(x, y)
            }
            lineTo(right, bottom)
            close()
        }
        canvas.drawPath(path, bars)
        canvas.restore()
        canvas.drawRoundRect(r, 8f * d, 8f * d, outline)
        canvas.drawText("LUMA", left, 12f * d, text)
        text.textAlign = Paint.Align.RIGHT
        canvas.drawText(if (isShown) "LIVE" else "", right, 12f * d, text)
        text.textAlign = Paint.Align.LEFT
    }
}

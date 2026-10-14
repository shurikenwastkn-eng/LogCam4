package com.logcam.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import java.util.Locale
import kotlin.math.abs

/** Horizon level: a line that tilts with the phone and turns green when it is straight. */
class LevelView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    private var angle = 0f
    private var show = false
    private val d = resources.displayMetrics.density
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#80000000")
        strokeWidth = 4.5f * d
        strokeCap = Paint.Cap.ROUND
    }
    private val main = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * d
        strokeCap = Paint.Cap.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 12f * d
        textAlign = Paint.Align.CENTER
        setShadowLayer(3f * d, 0f, 0f, Color.BLACK)
    }

    /** [deg] is how far the horizon is rotated clockwise on screen. */
    fun set(deg: Float, visible: Boolean) {
        angle = deg; show = visible
        invalidate()
    }

    private fun line(c: Canvas, x1: Float, y1: Float, x2: Float, y2: Float) {
        c.drawLine(x1, y1, x2, y2, outline)
        c.drawLine(x1, y1, x2, y2, main)
    }

    override fun onDraw(c: Canvas) {
        if (!show || width == 0) return
        val level = abs(angle) < 1f
        val col = if (level) Color.parseColor("#4CD964") else Color.WHITE
        main.color = col
        text.color = col
        val cx = width / 2f; val cy = height / 2f
        val len = 74f * d; val gap = 10f * d

        // fixed reference ticks
        line(c, cx - len - 38f * d, cy, cx - len - 16f * d, cy)
        line(c, cx + len + 16f * d, cy, cx + len + 38f * d, cy)

        // the tilting horizon
        c.save()
        c.rotate(angle, cx, cy)
        if (level) {
            line(c, cx - len, cy, cx + len, cy)
        } else {
            line(c, cx - len, cy, cx - gap, cy)
            line(c, cx + gap, cy, cx + len, cy)
        }
        c.restore()

        c.drawText(String.format(Locale.US, "%+.1f°", -angle), cx, cy + 30f * d, text)
    }
}

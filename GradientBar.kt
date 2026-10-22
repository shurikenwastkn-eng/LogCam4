package com.logcam.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/** A smooth gradient slider (used for the hue / saturation / brightness bars of the colour picker). */
class GradientBar @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    var onChange: ((Float) -> Unit)? = null

    private val d = resources.displayMetrics.density
    private var colors = intArrayOf(Color.BLACK, Color.WHITE)
    private var progress = 0f
    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    fun setColors(c: IntArray) { colors = c; invalidate() }

    fun setProgress(p: Float) { progress = p.coerceIn(0f, 1f); invalidate() }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        setMeasuredDimension(resolveSize((200 * d).toInt(), widthSpec), (30 * d).toInt())
    }

    override fun onDraw(c: Canvas) {
        val h = height.toFloat()
        val w = width.toFloat()
        val pad = h / 2f
        val th = 12f * d
        rect.set(pad, (h - th) / 2f, w - pad, (h + th) / 2f)
        track.shader = LinearGradient(rect.left, 0f, rect.right, 0f, colors, null, Shader.TileMode.CLAMP)
        c.drawRoundRect(rect, th / 2f, th / 2f, track)

        val cx = rect.left + progress * rect.width()
        val cy = h / 2f
        knob.shader = null
        knob.color = Color.parseColor("#59000000")
        c.drawCircle(cx, cy + 1.5f * d, 11f * d, knob)
        knob.color = Color.WHITE
        c.drawCircle(cx, cy, 11f * d, knob)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val pad = height / 2f
                val span = (width - 2f * pad).coerceAtLeast(1f)
                progress = ((e.x - pad) / span).coerceIn(0f, 1f)
                invalidate()
                onChange?.invoke(progress)
                return true
            }
            MotionEvent.ACTION_UP -> { performClick(); return true }
        }
        return super.onTouchEvent(e)
    }

    override fun performClick(): Boolean { super.performClick(); return true }
}

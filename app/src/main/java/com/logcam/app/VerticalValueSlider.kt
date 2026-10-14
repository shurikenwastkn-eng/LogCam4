package com.logcam.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.widget.OverScroller
import kotlin.math.abs

/** Compact, precise vertical control for camera settings. */
class VerticalValueSlider @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    var onProgressChanged: ((Int) -> Unit)? = null
    private var maxProgress = 100
    private var currentProgress = 0
    private var valueText: (Int) -> String = { it.toString() }
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val scroller = OverScroller(context)
    private var tracker: VelocityTracker? = null
    private var lastY = 0f
    private var fractionalProgress = 0f
    private var dragging = false
    private val amber = 0xFFFFC23C.toInt()
    private val white = 0xFFF7F7F7.toInt()
    private val muted = 0xFF858B95.toInt()
    private val panel = 0x00101216

    init {
        isClickable = true
        contentDescription = "Vertical camera control"
    }

    fun configure(max: Int, progress: Int, formatter: (Int) -> String) {
        maxProgress = max.coerceAtLeast(1)
        currentProgress = progress.coerceIn(0, maxProgress)
        valueText = formatter
        invalidate()
    }

    private fun setProgress(value: Int) {
        val next = value.coerceIn(0, maxProgress)
        if (next != currentProgress) {
            currentProgress = next
            onProgressChanged?.invoke(next)
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val top = 12f * density
        val bottom = h - 12f * density
        val range = (bottom - top).coerceAtLeast(1f)
        paint.style = Paint.Style.FILL
        paint.color = panel
        canvas.drawRoundRect(0f, 0f, w, h, 10f * density, 10f * density, paint)

        paint.color = 0xFF343941.toInt()
        paint.strokeWidth = 1.4f * density
        canvas.drawLine(cx, top, cx, bottom, paint)

        val centerY = bottom - (currentProgress.toFloat() / maxProgress) * range
        for (offset in -6..6) {
            val y = centerY - offset * 8f * density
            if (y < top || y > bottom) continue
            val major = offset == 0 || offset % 3 == 0
            val len = if (major) 15f else 7f
            paint.color = if (offset == 0) amber else muted
            paint.strokeWidth = if (offset == 0) 2f * density else 1f * density
            canvas.drawLine(cx - len * density / 2f, y, cx + len * density / 2f, y, paint)
        }

        val readoutHeight = 28f * density
        val left = 2f * density
        val right = w - 2f * density
        paint.style = Paint.Style.FILL
        paint.color = 0xFF252017.toInt()
        canvas.drawRoundRect(left, centerY - readoutHeight / 2f, right, centerY + readoutHeight / 2f, 7f * density, 7f * density, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f * density
        paint.color = amber
        canvas.drawRoundRect(left, centerY - readoutHeight / 2f, right, centerY + readoutHeight / 2f, 7f * density, 7f * density, paint)
        paint.style = Paint.Style.FILL
        paint.color = white
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        paint.textSize = 10f * density
        val label = valueText(currentProgress)
        val maxTextWidth = w - 8f * density
        val fitted = if (paint.measureText(label) > maxTextWidth) label.take(7) else label
        canvas.drawText(fitted, cx, centerY + 3.5f * density, paint)

        paint.color = muted
        paint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        paint.textSize = 7f * density
        canvas.drawText("MAX", cx, top - 2f * density, paint)
        canvas.drawText("MIN", cx, bottom + 8f * density, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                fractionalProgress = currentProgress.toFloat()
                tracker?.recycle()
                tracker = VelocityTracker.obtain().also { it.addMovement(event) }
                lastY = event.y
                dragging = true
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(event)
                if (dragging) {
                    val travel = (height - 24f * density).coerceAtLeast(1f)
                    val dy = lastY - event.y
                    fractionalProgress = (fractionalProgress + dy / travel * maxProgress).coerceIn(0f, maxProgress.toFloat())
                    setProgress(fractionalProgress.toInt())
                    lastY = event.y
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                tracker?.addMovement(event)
                tracker?.computeCurrentVelocity(1000)
                val velocity = -(tracker?.yVelocity ?: 0f)
                val travel = (height - 24f * density).coerceAtLeast(1f)
                val progressVelocity = velocity / travel * maxProgress
                tracker?.recycle()
                tracker = null
                dragging = false
                if (abs(progressVelocity) > maxProgress * 0.25f) {
                    scroller.fling(0, currentProgress, 0, progressVelocity.toInt(), 0, 0, 0, maxProgress)
                    postInvalidateOnAnimation()
                }
                parent?.requestDisallowInterceptTouchEvent(false)
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                tracker?.recycle()
                tracker = null
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return true
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            setProgress(scroller.currY)
            fractionalProgress = scroller.currY.toFloat()
            postInvalidateOnAnimation()
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}

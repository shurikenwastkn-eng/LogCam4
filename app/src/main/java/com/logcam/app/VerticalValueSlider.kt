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
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** A compact, camera-style vertical control with a live value readout and inertial drag. */
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
    private var dragging = false
    private var touchSlop = 0f
    private val amber = 0xFFFFC23C.toInt()
    private val white = 0xFFF7F7F7.toInt()
    private val muted = 0xFF9CA3AF.toInt()
    private val panel = 0xE6101216.toInt()

    init {
        isClickable = true
        touchSlop = 8f * density
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
            invalidate()
        } else invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val top = 18f * density
        val bottom = h - 18f * density
        val range = (bottom - top).coerceAtLeast(1f)
        val trackX = cx
        paint.style = Paint.Style.FILL
        paint.color = panel
        canvas.drawRoundRect(0f, 0f, w, h, 16f * density, 16f * density, paint)

        paint.color = 0xFF343941.toInt()
        paint.strokeWidth = 2f * density
        canvas.drawLine(trackX, top, trackX, bottom, paint)

        val centerY = bottom - (currentProgress.toFloat() / maxProgress) * range
        val tickStep = 8
        for (offset in -5..5) {
            val y = centerY - offset * 12f * density
            if (y < top || y > bottom) continue
            val longTick = offset == 0 || offset % 5 == 0
            val len = if (longTick) 21f else 10f
            paint.color = if (offset == 0) amber else muted
            paint.strokeWidth = if (offset == 0) 2.4f * density else 1.2f * density
            canvas.drawLine(trackX - len * density / 2f, y, trackX + len * density / 2f, y, paint)
        }

        paint.style = Paint.Style.FILL
        paint.color = amber
        canvas.drawCircle(trackX, centerY, 5f * density, paint)
        paint.color = 0xFF252017.toInt()
        canvas.drawRoundRect(4f * density, centerY - 18f * density, w - 4f * density, centerY + 18f * density, 9f * density, 9f * density, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f * density
        paint.color = amber
        canvas.drawRoundRect(4f * density, centerY - 18f * density, w - 4f * density, centerY + 18f * density, 9f * density, 9f * density, paint)
        paint.style = Paint.Style.FILL
        paint.color = white
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        paint.textSize = 12f * density
        canvas.drawText(valueText(currentProgress), cx, centerY + 4f * density, paint)

        paint.color = muted
        paint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        paint.textSize = 9f * density
        canvas.drawText("MAX", cx, top - 4f * density, paint)
        canvas.drawText("MIN", cx, bottom + 13f * density, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                tracker?.recycle()
                tracker = VelocityTracker.obtain().also { it.addMovement(event) }
                lastY = event.y
                dragging = true
                parent?.requestDisallowInterceptTouchEvent(true)
                performClick()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(event)
                if (dragging) {
                    val dy = lastY - event.y
                    val travel = (height - 36f * density).coerceAtLeast(1f)
                    val delta = (dy / travel * maxProgress).roundToInt()
                    if (abs(dy) >= touchSlop || delta != 0) {
                        setProgress(currentProgress + delta)
                        lastY = event.y
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                tracker?.addMovement(event)
                tracker?.computeCurrentVelocity(1000)
                val velocity = -(tracker?.yVelocity ?: 0f)
                val travel = (height - 36f * density).coerceAtLeast(1f)
                val progressVelocity = (velocity / travel * maxProgress).roundToInt()
                tracker?.recycle()
                tracker = null
                dragging = false
                if (abs(progressVelocity) > maxProgress / 2) {
                    scroller.fling(0, currentProgress, 0, progressVelocity, 0, 0, 0, maxProgress)
                    postInvalidateOnAnimation()
                }
                performClick()
                parent?.requestDisallowInterceptTouchEvent(false)
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
            postInvalidateOnAnimation()
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}

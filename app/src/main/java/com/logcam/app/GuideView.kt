package com.logcam.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.ceil

/**
 * Preview-only overlay: darkens everything outside a chosen aspect-ratio frame and draws grid lines.
 * Grid types: 0 off, 1 thirds, 2 thirds + diagonals, 3 square grid, 4 square grid + diagonals, 5 centre cross.
 */
class GuideView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    private var ratio = 0f
    private var contentAspect = 16f / 9f
    private var label = ""
    private var gridType = 0
    private val d = resources.displayMetrics.density
    private val dim = Paint().apply { color = Color.parseColor("#B3000000") }
    private val frameLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#FFFFC23C")
        strokeWidth = 2f * d
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFFFC23C")
        textSize = 12f * d
    }
    private val gridShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#66000000")
        strokeWidth = 3f * d
    }
    private val gridLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#B3FFFFFF")
        strokeWidth = 1.2f * d
    }
    private val rect = RectF()

    fun setGuide(r: Float, aspect: Float, name: String) {
        ratio = r; contentAspect = aspect; label = name
        invalidate()
    }

    fun setGrid(type: Int) {
        if (type != gridType) { gridType = type; invalidate() }
    }

    private fun line(c: Canvas, x1: Float, y1: Float, x2: Float, y2: Float) {
        c.drawLine(x1, y1, x2, y2, gridShadow)
        c.drawLine(x1, y1, x2, y2, gridLine)
    }

    override fun onDraw(c: Canvas) {
        if (width == 0 || height == 0) return
        if (ratio <= 0f && gridType == 0) return
        val vw = width.toFloat(); val vh = height.toFloat()

        // area the camera image occupies inside the view
        val cw: Float; val ch: Float
        if (vw / vh > contentAspect) { ch = vh; cw = vh * contentAspect } else { cw = vw; ch = vw / contentAspect }
        val cl = (vw - cw) / 2f; val ct = (vh - ch) / 2f

        if (ratio > 0f) {
            var gw = cw; var gh = cw / ratio
            if (gh > ch) { gh = ch; gw = ch * ratio }
            val gl = cl + (cw - gw) / 2f; val gt = ct + (ch - gh) / 2f
            rect.set(gl, gt, gl + gw, gt + gh)
            c.save()
            c.clipOutRect(rect)
            c.drawRect(0f, 0f, vw, vh, dim)
            c.restore()
            c.drawRect(rect, frameLine)
            if (label.isNotEmpty()) c.drawText(label, rect.left + 8f * d, rect.top + 20f * d, text)
        } else {
            rect.set(cl, ct, cl + cw, ct + ch)
        }
        drawGrid(c)
    }

    private fun drawGrid(c: Canvas) {
        if (gridType == 0) return
        val l = rect.left; val t = rect.top; val r = rect.right; val b = rect.bottom
        val w = rect.width(); val h = rect.height()
        val cx = (l + r) / 2f; val cy = (t + b) / 2f
        when (gridType) {
            1, 2 -> {
                line(c, l + w / 3f, t, l + w / 3f, b); line(c, l + 2f * w / 3f, t, l + 2f * w / 3f, b)
                line(c, l, t + h / 3f, r, t + h / 3f); line(c, l, t + 2f * h / 3f, r, t + 2f * h / 3f)
            }
            3, 4 -> {
                val cell = h / 4f
                val n = ceil(w / 2f / cell).toInt() + 1
                for (k in -n..n) {
                    val x = cx + k * cell
                    if (x > l + 1f && x < r - 1f) line(c, x, t, x, b)
                }
                for (k in -2..2) {
                    val y = cy + k * cell
                    if (y > t + 1f && y < b - 1f) line(c, l, y, r, y)
                }
            }
            5 -> {
                val a = 14f * d
                line(c, cx - a, cy, cx + a, cy); line(c, cx, cy - a, cx, cy + a)
            }
        }
        if (gridType == 2 || gridType == 4) {
            line(c, l, t, r, b)
            line(c, r, t, l, b)
        }
    }
}

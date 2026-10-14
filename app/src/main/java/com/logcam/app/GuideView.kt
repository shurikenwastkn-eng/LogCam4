package com.logcam.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/** Darkens everything outside a chosen aspect-ratio frame inside the preview. Preview only - never recorded. */
class GuideView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    private var ratio = 0f
    private var contentAspect = 16f / 9f
    private var label = ""
    private val d = resources.displayMetrics.density
    private val dim = Paint().apply { color = Color.parseColor("#B3000000") }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#FFFFC23C")
        strokeWidth = 2f * d
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFFFC23C")
        textSize = 12f * d
    }
    private val rect = RectF()

    fun setGuide(r: Float, aspect: Float, name: String) {
        ratio = r; contentAspect = aspect; label = name
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        if (ratio <= 0f || width == 0 || height == 0) return
        val vw = width.toFloat(); val vh = height.toFloat()

        // area the camera image occupies inside the view
        val cw: Float; val ch: Float
        if (vw / vh > contentAspect) { ch = vh; cw = vh * contentAspect } else { cw = vw; ch = vw / contentAspect }
        val cl = (vw - cw) / 2f; val ct = (vh - ch) / 2f

        // largest frame with the chosen ratio that fits inside it
        var gw = cw; var gh = cw / ratio
        if (gh > ch) { gh = ch; gw = ch * ratio }
        val gl = cl + (cw - gw) / 2f; val gt = ct + (ch - gh) / 2f
        rect.set(gl, gt, gl + gw, gt + gh)

        c.save()
        c.clipOutRect(rect)
        c.drawRect(0f, 0f, vw, vh, dim)
        c.restore()
        c.drawRect(rect, line)
        if (label.isNotEmpty()) c.drawText(label, rect.left + 8f * d, rect.top + 20f * d, text)
    }
}
